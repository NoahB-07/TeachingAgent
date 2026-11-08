package ai.learn.agent;

import ai.koog.agents.chatMemory.feature.ChatMemory;
import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.features.eventHandler.feature.EventHandler;
import ai.koog.http.client.ktor.KtorKoogHttpClient;
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor;
import ai.koog.prompt.executor.llms.all.SimplePromptExecutors;
import ai.koog.prompt.llm.LLMCapability;
import ai.koog.prompt.llm.LLMProvider;
import ai.koog.prompt.llm.LLModel;
import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SessionId;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResourceLink;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.UserMessageChunk;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP-compatible entry point for the TeachingAgent.
 *
 * <p>Implements the ACP methods:
 * <ul>
 *   <li>{@code initialize} – derived by the SDK from the annotations on this class</li>
 *   <li>{@code session/new} – creates a session with its own Koog agent and chat memory</li>
 *   <li>{@code session/prompt} – runs the Koog agent and streams the answer back</li>
 *   <li>{@code session/cancel} – stops the running turn</li>
 *   <li>{@code session/load} – replays a session's conversation (while this process is alive)</li>
 *   <li>{@code session/list} – lists the sessions this process knows</li>
 *   <li>{@code session/close} – frees a session's state</li>
 * </ul>
 *
 * <p>Note: stdout is the ACP channel, so nothing in this process may print to it.
 */
@AcpAgent(name = "teaching-agent", title = "Teaching Agent", version = "1.0", mcpHttp = true, mcpSse = true)
public class AcpTeachingAgent {

    /** Built per session, so the model knows where it runs and which project it works on. */
    private static String systemPrompt(String cwd, McpConnections mcp, TeachingProfile profile) {
        String snippet = profile != null ? profile.systemPromptSnippet() : "";
        return basePrompt(cwd) + snippet + mcpPrompt(mcp) + LearningTools.prompt(mcp) + reactInstructions();
    }

    private static String mcpPrompt(McpConnections mcp) {
        if (mcp.connected().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""

                You are also connected to these MCP servers, provided by the IDE. Their tools give you
                IDE features (such as code search, inspections, symbols, run configurations); prefer them
                over the basic tools when they fit the task:
                """);
        for (McpConnections.Connected server : mcp.connected()) {
            sb.append("- ").append(server.name()).append(": ")
                    .append(String.join(", ", server.toolNames())).append('\n');
        }
        return sb.toString();
    }

    private static String basePrompt(String cwd) {
        return """
                You are a patient teaching assistant, running as an ACP (Agent Client Protocol) agent
                inside the user's JetBrains IDE (connected through JetBrains AIR).

                The user has a project open in the IDE. Project root: %s
                Operating system: %s

                You have tools that act on that project through the IDE:
                - list_directory: list files and folders (use "." for the project root)
                - find_files: find files by name or glob (e.g. "Main.java") and get their paths
                - search_code: find the lines in the project that contain some text (a method,
                  class, tool name or string), to locate the code a question is about
                - read_file: read a file as the IDE sees it, including unsaved edits
                - read_file_with_line_numbers: the same, with 1-based line numbers (use it before
                  choosing ranges to highlight)
                - write_file: create or overwrite a file (the IDE shows the change to the user)
                - run_command: run a shell command in the project root in the IDE's terminal

                When the user asks about "this project", "my code", a file, or how something in the
                project works, find the code (search_code, find_files) and read it with your tools
                instead of guessing or asking them to paste it. Never invent file names or describe
                code you haven't read. Read a file before changing it.
                Only write files if the file path is in the .teaching folder in the root of the project.

                **Always use your learning tools when teaching.** Your primary job is to show the learner
                their code in their editor, not to explain in chat text. For every question about code,
                programming concepts, or how something works, you MUST use one of these learning tools:
                - `learning_show_explanation_tree`: The default for explaining anything (functions, loops, variables, etc.)
                - `learning_play_walkthrough`: For tracing code execution step by step
                - `learning_highlight_code`: For pointing at a specific piece of code
                
                Do NOT just explain in chat text. The learner needs to see their code highlighted in the editor.
                Use examples from the user's own code where it helps, and ask clarifying questions when needed.

                When explaining a function or method, always explain the **whole function** first:
                what it does overall, what parameters it takes, what it returns, and how it works at a high level.
                Then let the learner explore the individual parts (loops, conditionals, calculations) by clicking links.
                Never just explain line-by-line; start with the big picture, then go into the details.

                **Always highlight the entire block of code, not just one line.** When you create an
                explanation tree, use **multi-line ranges** for every node that covers a logical unit:
                - A function: use the range from the signature to the closing brace, e.g. "10-25".
                - A loop: use the range covering the header and body, e.g. "8-12".
                - An if/else: use the range covering all branches, e.g. "15-22".
                The learner should see the whole block of code you're explaining.
                Only use a single-line range when pointing at one specific expression or variable.

                **The same rule applies to walkthroughs.** When creating a walkthrough, each step's
                `line` field should cover the entire logical unit being executed:
                - Entering a function: use the full function range, e.g. "3-13" for the whole main() method.
                - A loop step: use the full loop range (header + body), e.g. "8-12".
                - Collapsed loop iterations: use the loop's range.
                Only use a single-line `line` when pointing at one specific expression, variable, or print statement.
                """.formatted(cwd, System.getProperty("os.name"));
    }

    // ---------------------------------------------------------------- ReAct reasoning

    /**
     * Appends the ReAct reasoning instructions to the system prompt.
     *
     * <p>The ReAct pattern guides the agent to reason before acting:
     * <ol>
     *   <li><b>Thought</b> — reason about what to do next</li>
     *   <li><b>Action</b> — call a tool</li>
     *   <li><b>Observation</b> — receive the tool result</li>
     * </ol>
     * This cycle repeats until the agent has enough information to answer.
     */
    private static String reactInstructions() {
        return """

                Work in a reason-act-observe cycle:
                1. Think about what you know, what you still need, and which tool gets it.
                2. Act by actually calling that tool (a real function call). Never write a tool call,
                   "Action:" lines, or a tool's JSON argument in your message text instead: text is
                   shown to the learner as-is and does nothing.
                3. Read the tool's result and decide the next step.

                Repeat until you have what you need. Your final message to the learner is plain prose:
                no "Thought:" / "Action:" labels, and no explanation-tree or walkthrough JSON (send
                those with the learning tools).

                Key principles:
                - Break complex problems into smaller steps
                - Verify assumptions by reading files or running commands
                - If a tool fails, analyze the error and try a different approach
                - Keep your thoughts concise and focused on the next step
                """;
    }

    /**
     * Resolves the LLM model from environment variables.
     *
     * <p>Supported environment variables:
     * <ul>
     *   <li>{@code LLM_PROVIDER} — provider identifier (e.g. "openai", "anthropic", "google").
     *   Defaults to "openai" if not set.</li>
     *   <li>{@code LLM_MODEL} — model name (e.g. "gpt-4o", "claude-sonnet-4-20250514").
     *   Defaults to "gpt-4o" if not set.</li>
     * </ul>
     *
     * <p>For the API key, use the provider-specific variable (e.g. {@code OPENAI_API_KEY},
     * {@code ANTHROPIC_API_KEY}, {@code GOOGLE_API_KEY}) or the generic {@code LLM_API_KEY}.
     */
    private static LLMProvider resolveProvider(String name) {
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("openai")) return LLMProvider.OpenAI;
        if (normalized.equals("anthropic")) return LLMProvider.Anthropic;
        if (normalized.equals("google")) return LLMProvider.Google;
        if (normalized.equals("deepseek")) return LLMProvider.DeepSeek;
        if (normalized.equals("openrouter")) return LLMProvider.OpenRouter;
        if (normalized.equals("ollama")) return LLMProvider.Ollama;
        return null;
    }

    private static LLModel resolveModel() {
        String providerName = System.getenv("LLM_PROVIDER");
        if (providerName == null || providerName.isBlank()) {
            providerName = "openai";
        }
        LLMProvider provider = resolveProvider(providerName);
        if (provider == null) {
            throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
                    "Unknown LLM provider: " + providerName
                    + ". Set LLM_PROVIDER to a valid provider (e.g. openai, anthropic, google).");
        }
        String modelName = System.getenv("LLM_MODEL");
        if (modelName == null || modelName.isBlank()) {
            modelName = "gpt-4o";
        }
        return new LLModel(
                provider,
                modelName,
                List.of(
                        LLMCapability.Completion.INSTANCE,
                        LLMCapability.Tools.INSTANCE,
                        LLMCapability.OpenAIEndpoint.Completions.INSTANCE
                )
        );
    }

    private static final LLModel MODEL = resolveModel();

    /** One conversational turn, kept so {@code session/load} can replay the conversation. */
    private record Turn(boolean fromUser, String text) {
    }

    /** Per-session state. Each session gets its own Koog agent, so its chat memory is isolated. */
    private static final class Session {
        final String id;
        final String cwd;
        volatile AIAgent<String, String> agent;
        final AcpIdeTools tools;
        final McpConnections mcp;
        final LearningTools learning;
        final TeachingProfile profile;
        final List<Turn> history = new ArrayList<>();
        volatile String title;
        volatile Instant updatedAt = Instant.now();
        volatile CompletableFuture<String> running;

        Session(String id, String cwd, AcpIdeTools tools, McpConnections mcp, LearningTools learning,
                TeachingProfile profile, AIAgent<String, String> agent) {
            this.id = id;
            this.learning = learning;
            this.cwd = cwd;
            this.tools = tools;
            this.mcp = mcp;
            this.profile = profile;
            this.agent = agent;
        }

        synchronized void addTurn(Turn turn) {
            history.add(turn);
            updatedAt = Instant.now();
            if (title == null && turn.fromUser()) {
                String t = turn.text().strip().replaceAll("\\s+", " ");
                title = t.length() > 60 ? t.substring(0, 57) + "..." : t;
            }
        }

        synchronized List<Turn> snapshot() {
            return List.copyOf(history);
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Shared, lazily created LLM executor (needs the API key). */
    private volatile MultiLLMPromptExecutor promptExecutor;

    // ---------------------------------------------------------------- session/new

    @NewSession
    NewSessionResponse newSession(NewSessionRequest request) {
        String sessionId = UUID.randomUUID().toString();
        AcpIdeTools tools = new AcpIdeTools(request.cwd());
        // Connect to the MCP servers the client passed (with AIR + use_idea_mcp: the IDE's own server).
        // AIR_IDE_MCP_URL adds the IDE's server when the client doesn't pass it (for the learning_* tools).
        McpConnections mcp = McpConnections.connect(
                LearningTools.withConfiguredServer(request.mcpServers()), reservedToolNames());
        // Load persistent teaching profile so the model knows what the user already learned.
        TeachingProfile profile = TeachingProfile.load(Path.of(request.cwd()));
        LearningTools learning = new LearningTools(tools, mcp, profile);
        AIAgent<String, String> agent;
        try {
            agent = createKoogAgent(request.cwd(), tools, mcp, learning, profile);
        } catch (RuntimeException e) {
            mcp.close();
            throw e;
        }
        sessions.put(sessionId, new Session(sessionId, request.cwd(), tools, mcp, learning, profile, agent));
        return new NewSessionResponse(sessionId, null, null); // no modes, no config options
    }

    // ---------------------------------------------------------------- session/prompt

    @Prompt
    PromptResponse prompt(PromptRequest request, SyncPromptContext ctx) {
        Session session = requireSession(request.sessionId());

        String userText = promptText(request.prompt());
        if (userText.isBlank()) {
            ctx.sendMessage("I didn't receive any text. What would you like to learn about?");
            return PromptResponse.endTurn();
        }
        session.addTurn(new Turn(true, userText));
        session.tools.bind(ctx); // tool calls in this turn go through this prompt's client context

        // Run the (blocking) Koog agent on another thread so a cancel can end the turn promptly.
        // The ACP session id doubles as the Koog session id, which keys ChatMemory.
        CompletableFuture<String> run = CompletableFuture.supplyAsync(
                () -> session.agent.run(userText, session.id));
        session.running = run;
        ctx.onCancel(() -> run.cancel(true));

        try {
            String answer = run.join();
            if (ctx.isCancelled()) {
                return PromptResponse.cancelled();
            }
            session.addTurn(new Turn(false, answer));
            ctx.sendMessage(answer);
            return PromptResponse.endTurn();
        } catch (CancellationException e) {
            return PromptResponse.cancelled();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = "Error: " + cause.getMessage();
            // A failed turn can leave the LLM history in a state every later request is rejected for
            // (e.g. a tool call without its result). Start the model's memory afresh so the session
            // stays usable; the chat itself stays visible in the IDE.
            session.agent = createKoogAgent(session.cwd, session.tools, session.mcp, session.learning, session.profile);
            msg += "\n\n(I reset my conversation memory after this error, so I may not remember "
                    + "earlier messages in this chat.)";
            session.addTurn(new Turn(false, msg));
            ctx.sendMessage(msg);
            return PromptResponse.endTurn();
        } finally {
            session.running = null;
        }
    }

    // ---------------------------------------------------------------- session/cancel

    @Cancel
    void cancel(@SessionId String sessionId) {
        // The prompt's onCancel hook already stops the turn; this also covers any stray work.
        Session session = sessions.get(sessionId);
        if (session != null) {
            CompletableFuture<String> run = session.running;
            if (run != null) {
                run.cancel(true);
            }
        }
    }

    // ---------------------------------------------------------------- session/load

    @LoadSession
    LoadSessionResponse loadSession(LoadSessionRequest request, AcpSyncAgent client) {
        Session session = requireSession(request.sessionId());
        for (Turn turn : session.snapshot()) {
            TextContent text = new TextContent(turn.text());
            client.sendSessionUpdate(session.id,
                    turn.fromUser() ? new UserMessageChunk(text) : new AgentMessageChunk(text));
        }
        return new LoadSessionResponse(null, null);
    }

    // ---------------------------------------------------------------- session/list

    @ListSessions
    ListSessionsResponse listSessions(ListSessionsRequest request) {
        List<SessionInfo> infos = sessions.values().stream()
                .filter(s -> request.cwd() == null || request.cwd().equals(s.cwd))
                .sorted(Comparator.comparing((Session s) -> s.updatedAt).reversed())
                .map(s -> new SessionInfo(s.id, s.cwd, s.title, s.updatedAt.toString(), null, null))
                .toList();
        return new ListSessionsResponse(infos);
    }

    // ---------------------------------------------------------------- session/close

    @CloseSession
    CloseSessionResponse closeSession(@SessionId String sessionId) {
        // The SDK has already cancelled any running prompt before calling this.
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.profile.save();
            session.mcp.close();
        }
        return new CloseSessionResponse(null);
    }

    // ---------------------------------------------------------------- helpers

    private Session requireSession(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new AcpProtocolException(AcpErrorCodes.RESOURCE_NOT_FOUND,
                    "Unknown session: " + sessionId);
        }
        return session;
    }

    /** Joins all text blocks, and mentions any resource links the client attached. */
    private static String promptText(List<ContentBlock> blocks) {
        if (blocks == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextContent text) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(text.text());
            } else if (block instanceof ResourceLink link) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append("[Referenced resource: ").append(link.name()).append(" (").append(link.uri()).append(")]");
            }
        }
        return sb.toString();
    }

    /** MCP tools with these names are not given to the LLM: the agent's own tools cover them. */
    private static java.util.Set<String> reservedToolNames() {
        java.util.Set<String> names = new java.util.HashSet<>(AcpIdeTools.OWN_TOOL_NAMES);
        names.addAll(LearningTools.OWN_TOOL_NAMES);
        return names;
    }

    private AIAgent<String, String> createKoogAgent(String cwd, AcpIdeTools tools, McpConnections mcp,
                                                    LearningTools learning, TeachingProfile profile) {
        var registryBuilder = ToolRegistry.builder().tools(tools).tools(mcp.tools());
        if (LearningTools.available(mcp)) {
            // Our learning_* tools check their input and call the plugin (directly or via AIR's execute_tool).
            registryBuilder.tools(learning);
        }
        // Add the learning_record_topic tool so the LLM can explicitly record taught topics.
        registryBuilder.tools(new LearningRecordTopic(profile));
        ToolRegistry registry = registryBuilder.build();
        return AIAgent.builder()
                .promptExecutor(executor())
                .llmModel(MODEL)
                .systemPrompt(systemPrompt(cwd, mcp, profile))
                .toolRegistry(registry)
                .functionalStrategy("teaching", (AIAgentFunctionalContext ctx, String input) ->
                        runWithTools(ctx, input, LearningTools.available(mcp)))
                // Our own tools report themselves; show the MCP servers' tool calls in the chat too.
                .install(EventHandler.Feature, events -> {
                    events.onToolCallStarting(e -> {
                        if (mcp.hasTool(e.getToolName())) {
                            tools.mcpToolStarted(callId(e.getToolCallId(), e.getToolName()),
                                    e.getToolName(), String.valueOf(e.getToolArgs()));
                        }
                    });
                    events.onToolCallCompleted(e -> {
                        if (mcp.hasTool(e.getToolName())) {
                            // An MCP error (e.g. the learning plugin rejecting a tree) still "completes"
                            // in Koog; the model sees the message, and the chat should show it failed.
                            String result = String.valueOf(e.getToolResult());
                            boolean failed = result.replace(" ", "").contains("\"isError\":true");
                            tools.mcpToolFinished(callId(e.getToolCallId(), e.getToolName()), !failed,
                                    failed ? result : null);
                        }
                    });
                    events.onToolCallFailed(e -> {
                        if (mcp.hasTool(e.getToolName())) {
                            tools.mcpToolFinished(callId(e.getToolCallId(), e.getToolName()), false,
                                    e.getMessage());
                        }
                    });
                })
                .install(ChatMemory.Feature, config -> {}) // in-memory history per agent
                .build();
    }

    /** Upper bound on LLM ↔ tool round trips in one turn. */
    private static final int MAX_TOOL_STEPS = 25;

    /** An explanation tree ("nodes": {...}) or walkthrough ("steps": [...]) written as message text. */
    private static final java.util.regex.Pattern PASTED_LEARNING_JSON =
            java.util.regex.Pattern.compile("\"(nodes\"\\s*:\\s*\\{|steps\"\\s*:\\s*\\[)");
    private static final int MAX_NUDGES = 2;
    private static final String PASTED_JSON_NUDGE = """
            Your message contains explanation-tree or walkthrough JSON as text. The learner only sees \
            it as text in the chat: nothing is shown in their editor. Don't paste it. Instead:
            1. Make sure every "file" is a real project file you have read (use search_code or \
            find_files to find the code this is about, then read_file_with_line_numbers).
            2. Send it with learning_show_explanation_tree or learning_play_walkthrough.
            3. Then reply with a short sentence about what you showed, without the JSON.""";

    /** Nudge to force the LLM to use a learning tool after researching code. */
    private static final String LEARNING_TOOL_NUDGE = """
            You have researched the code, but you have not shown it to the learner yet. \
            You MUST use a learning tool to show the code in the learner's editor before answering. \
            The learner cannot learn from text alone — they need to see their code highlighted. \
            Use learning_show_explanation_tree to explain what the code does, \
            learning_play_walkthrough to trace execution, or learning_highlight_code to point at a specific part. \
            Call one of these tools now, then reply with a brief summary of what you showed.""";

    /** Keywords that suggest the user is asking about code or programming concepts. */
    private static final java.util.regex.Pattern CODE_QUESTION_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\b(file|files|class|method|function|code|line|lines|project|folder|directory|import|package|" +
                    "variable|loop|if|for|while|return|exception|error|bug|fix|debug|test|run|build|compile|" +
                    "explain|how.*work|what.*do|where.*is|show.*me|read|search|find|look|code.*review|" +
                    "\\.java|\\.kt|\\.py|\\.js|\\.ts|\\.c|\\.cpp|\\.h|\\.go|\\.rs|\\.rb|" +
                    "recursion|inheritance|polymorphism|encapsulation|abstraction|interface|abstract|" +
                    "array|list|map|set|queue|stack|tree|graph|algorithm|data.*structure|" +
                    "thread|concurrent|async|await|promise|callback|event|listener|" +
                    "sql|database|query|table|index|transaction|" +
                    "http|api|endpoint|request|response|json|xml|websocket|" +
                    "design.*pattern|singleton|factory|observer|strategy|decorator|" +
                    "memory|garbage.*collection|heap|stack|pointer|reference|" +
                    "sort|search|binary.*search|merge|quick|bubble|insertion|" +
                    "lambda|closure|stream|filter|map.*reduce|reduce|" +
                    "try|catch|throw|throws|finally|assert|" +
                    "static|final|private|public|protected|default|abstract|interface|" +
                    "constructor|destructor|override|overload|polymorphic|" +
                    "what is|what are|how to|how do|can you explain|help me understand|" +
                    "teach me|learn|understand|concept|programming|language|syntax|" +
                    "difference between|vs|versus|compared to)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Checks if the user's input appears to be about code.
     */
    private static boolean isCodeRelated(String input) {
        return CODE_QUESTION_PATTERN.matcher(input).find();
    }

    /**
     * Checks if any of the tool calls are learning tools.
     */
    private static boolean containsLearningToolCall(List<MessagePart.Tool.Call> calls) {
        for (MessagePart.Tool.Call call : calls) {
            if (LearningTools.OWN_TOOL_NAMES.contains(call.getTool())) {
                return true;
            }
        }
        return false;
    }

    /**
     * ReAct agent loop with two-phase learning tool enforcement: Thought → Action → Observation cycle.
     *
     * <p>The LLM is prompted to reason (Thought), call tools (Action), and receive results
     * (Observation). After each tool execution, the results are fed back with an Observation
     * prompt so the LLM can reason about what to do next. This continues until the LLM provides
     * a final answer without calling more tools.
     *
     * <p>Two-phase approach for learning tools:
     * <ol>
     *   <li><b>Phase 1 (Research)</b>: The LLM freely uses any tools (search_code, read_file, etc.) to
     *       find and understand the code.</li>
     *   <li><b>Phase 2 (Enforce Learning Tool)</b>: After research completes (no more tool calls), if the
     *       question was code-related and no learning tool was used, the LLM is forced to call a learning
     *       tool before answering.</li>
     * </ol>
     *
     * <p>This replaces Koog's {@code singleRunStrategy}, which ends the run as soon as a reply contains
     * text — even when that reply also calls a tool (GPT-4o often says "Let me search…" and calls a
     * tool in one message). That left an unanswered tool call in the chat memory, and OpenAI then
     * rejects every later request in the session with "An assistant message with 'tool_calls' must be
     * followed by tool messages".
     */
    private static String runWithTools(AIAgentFunctionalContext ctx, String input, boolean learningTools) {
        Message.Assistant reply = ctx.requestLLM(input);
        int nudges = 0;
        boolean learningToolUsed = false;

        // Phase 1: Research loop — let the LLM freely use any tools to find and understand the code.
        for (int step = 0; step < MAX_TOOL_STEPS; step++) {
            List<MessagePart.Tool.Call> calls = toolCalls(reply);
            if (calls.isEmpty()) {
                // No tool calls — the LLM has produced a final answer (possibly with reasoning text).
                String answer = text(reply);
                if (learningTools && nudges < MAX_NUDGES && PASTED_LEARNING_JSON.matcher(answer).find()) {
                    // It wrote a tree or walkthrough into the chat instead of sending it to the editor.
                    nudges++;
                    reply = ctx.requestLLM(PASTED_JSON_NUDGE);
                    continue;
                }
                // Phase 2: If learning tools are available, the question is code-related, and no learning
                // tool was used during research, force the LLM to use a learning tool before answering.
                if (learningTools && !learningToolUsed && isCodeRelated(input)) {
                    reply = ctx.requestLLMOnlyCallingTools(LEARNING_TOOL_NUDGE);
                    // Execute the forced learning tool call.
                    List<MessagePart.Tool.Call> forcedCalls = toolCalls(reply);
                    if (!forcedCalls.isEmpty()) {
                        List<ai.koog.agents.core.environment.ReceivedToolResult> results =
                                ctx.executeTools(forcedCalls, false);
                        reply = ctx.sendToolResults(results);
                        // Now get the final answer after showing the learning tool.
                        return text(ctx.requestLLMWithoutTools(
                                "You have shown the code using a learning tool. Now reply with a short " +
                                "sentence about what you showed and where, without repeating the explanation."));
                    }
                }
                return answer;
            }
            // Track if any learning tool was called during this step.
            if (containsLearningToolCall(calls)) {
                learningToolUsed = true;
            }
            // Execute all tool calls from this Action step — returns ReceivedToolResult list
            List<ai.koog.agents.core.environment.ReceivedToolResult> results =
                    ctx.executeTools(calls, false);
            // Send the tool results (the Observation) to the LLM; its reply is the next Thought/Action.
            // Every tool call in that reply must be answered before any other message is sent, or
            // OpenAI rejects the request ("tool_calls must be followed by tool messages"), so the
            // loop goes straight back to executing them.
            reply = ctx.sendToolResults(results);
        }
        // Out of steps: answer the pending calls one last time, then insist on a plain answer.
        List<MessagePart.Tool.Call> pending = toolCalls(reply);
        if (!pending.isEmpty()) {
            ctx.sendToolResults(ctx.executeTools(pending, false));
        }
        return text(ctx.requestLLMWithoutTools(
                "You have used the maximum number of tool steps. Answer now with what you have found."));
    }

    private static List<MessagePart.Tool.Call> toolCalls(Message.Assistant reply) {
        return reply.getParts().stream()
                .filter(MessagePart.Tool.Call.class::isInstance)
                .map(MessagePart.Tool.Call.class::cast)
                .toList();
    }

    private static String text(Message.Assistant reply) {
        return reply.getParts().stream()
                .filter(MessagePart.Text.class::isInstance)
                .map(p -> ((MessagePart.Text) p).getText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String callId(String toolCallId, String toolName) {
        return "mcp-" + (toolCallId != null ? toolCallId : toolName);
    }

    /**
     * Resolves the API key from environment variables.
     *
     * <p>Tries the following variables in order:
     * <ol>
     *   <li>{@code LLM_API_KEY} — generic key for any provider</li>
     *   <li>{@code OPENAI_API_KEY} — OpenAI-specific key</li>
     *   <li>{@code ANTHROPIC_API_KEY} — Anthropic-specific key</li>
     *   <li>{@code GOOGLE_API_KEY} — Google-specific key</li>
     * </ol>
     */
    private static String resolveApiKey() {
        String key = System.getenv("LLM_API_KEY");
        if (key != null && !key.isBlank()) return key;
        key = System.getenv("OPENAI_API_KEY");
        if (key != null && !key.isBlank()) return key;
        key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) return key;
        return System.getenv("GOOGLE_API_KEY");
    }

    private MultiLLMPromptExecutor executor() {
        MultiLLMPromptExecutor executor = promptExecutor;
        if (executor == null) {
            synchronized (this) {
                executor = promptExecutor;
                if (executor == null) {
                    String apiKey = resolveApiKey();
                    if (apiKey == null || apiKey.isBlank()) {
                        // Reported to the client as the error of session/new.
                        throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
                                "LLM API key is not set. Set one of: LLM_API_KEY, OPENAI_API_KEY, " +
                                "ANTHROPIC_API_KEY, or GOOGLE_API_KEY environment variables.");
                    }
                    String providerName = System.getenv("LLM_PROVIDER");
                    if (providerName == null || providerName.isBlank()) {
                        providerName = "openai";
                    }
                    executor = createExecutorForProvider(providerName, apiKey);
                    promptExecutor = executor;
                }
            }
        }
        return executor;
    }

    /**
     * Creates a {@link MultiLLMPromptExecutor} for the configured provider.
     */
    private static MultiLLMPromptExecutor createExecutorForProvider(String providerName, String apiKey) {
        String normalized = providerName.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "openai":
                return SimplePromptExecutors.simpleOpenAIExecutor(apiKey, new KtorKoogHttpClient.Factory());
            case "anthropic":
                return SimplePromptExecutors.simpleAnthropicExecutor(apiKey, new KtorKoogHttpClient.Factory());
            case "google":
                return SimplePromptExecutors.simpleGoogleAIExecutor(apiKey, new KtorKoogHttpClient.Factory());
            case "deepseek":
                return SimplePromptExecutors.simpleOpenAIExecutor(apiKey, new KtorKoogHttpClient.Factory());
            case "openrouter":
                return SimplePromptExecutors.simpleOpenRouterExecutor(apiKey, new KtorKoogHttpClient.Factory());
            case "ollama":
                return SimplePromptExecutors.simpleOllamaAIExecutor(apiKey, new KtorKoogHttpClient.Factory());
            default:
                return SimplePromptExecutors.simpleOpenAIExecutor(apiKey, new KtorKoogHttpClient.Factory());
        }
    }

    /**
     * Main entry point — starts the ACP agent over STDIO transport.
     * This is what the client (e.g. JetBrains AIR) launches to connect to the agent.
     */
    public static void main(String[] args) {
        // stdout carries the ACP JSON-RPC stream: any stray print would corrupt it.
        // kotlin-logging (used by Koog) prints a startup banner to stdout by default.
        KotlinLoggingConfiguration.INSTANCE.setLogStartupMessage(false);

        // The transport captures the real stdout here; afterwards, route System.out to stderr
        // so that library printlns can never interleave with protocol messages.
        StdioAcpAgentTransport transport = new StdioAcpAgentTransport();
        System.setOut(System.err);

        AcpAgentSupport.create(new AcpTeachingAgent())
                .transport(transport)
                .build()
                .run();
    }
}
