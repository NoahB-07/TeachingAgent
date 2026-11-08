package ai.learn.agent;

import ai.koog.agents.chatMemory.feature.ChatMemory;
import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.features.eventHandler.feature.EventHandler;
import ai.koog.http.client.ktor.KtorKoogHttpClient;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor;
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
    private static String systemPrompt(String cwd, McpConnections mcp) {
        boolean editor = LearningTools.available(mcp);
        return basePrompt(cwd, editor) + mcpPrompt(mcp) + LearningTools.prompt(mcp) + reactInstructions(editor);
    }

    private static String mcpPrompt(McpConnections mcp) {
        if (mcp.connected().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""

                The IDE also offers these MCP tools. Use them only for IDE features your own tools
                don't cover (inspections, symbols, run configurations, the debugger). Never use them
                to show explanations: the learning_* tools do that.
                """);
        for (McpConnections.Connected server : mcp.connected()) {
            sb.append("- ").append(server.name()).append(": ")
                    .append(String.join(", ", server.toolNames())).append('\n');
        }
        return sb.toString();
    }

    private static String basePrompt(String cwd, boolean editor) {
        String how = editor
                ? """
                You teach by showing the learner their own code in their editor, with explanations
                they can click through and step-by-step walkthroughs. Whenever they ask about code,
                that is how you answer: they never need to ask you to "show" it or name a tool.
                The chat is only for short replies around what you show."""
                : """
                The editor tools are not connected in this session, so explain in the chat: quote
                short snippets of the learner's code with their file and line numbers.""";
        return """
                You are Air, a patient programming teacher running inside the learner's JetBrains IDE
                (an ACP agent connected through JetBrains AIR). You help the learner understand their
                own code. You never write, fix or complete it for them.

                %s

                The learner's project is open in the IDE. Project root: %s
                Operating system: %s

                Tools for finding and reading the project's code:
                - search_code: the lines that contain some text (a method, class, variable, string).
                  Start here to find the code a question is about.
                - find_files: a file's path from its name or a glob (e.g. "Main.java").
                - list_directory: the files and folders in a directory ("." for the project root).
                - read_file_with_line_numbers: a file with 1-based line numbers. Use it before
                  pointing at any lines.
                - read_file: a file's text as the IDE sees it, including unsaved edits.
                - write_file: create or overwrite a file, only inside the .teaching folder at the
                  project root (never the learner's own code).
                - run_command: run a shell command in the project root, if the IDE allows it.

                Always find and read the code before explaining it. Never guess, invent file names,
                describe code you haven't read, or ask the learner to paste code that is in the
                project.
                """.formatted(how, cwd, System.getProperty("os.name"));
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
    private static String reactInstructions(boolean editor) {
        String typical = editor
                ? """

                A typical answer: search_code / find_files to find the code, read_file_with_line_numbers
                to read it, then one learning_* call to show it (a tree, a walkthrough or a highlight,
                as "Choosing how to answer" says), then a 1-3 sentence chat reply.
                """
                : "";
        return typical + """

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

    private static final LLModel MODEL = new LLModel(
            LLMProvider.OpenAI,
            "gpt-4o",
            List.of(
                    LLMCapability.Completion.INSTANCE,
                    LLMCapability.Tools.INSTANCE,
                    LLMCapability.OpenAIEndpoint.Completions.INSTANCE
            )
    );

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
        final List<Turn> history = new ArrayList<>();
        volatile String title;
        volatile Instant updatedAt = Instant.now();
        volatile CompletableFuture<String> running;

        Session(String id, String cwd, AcpIdeTools tools, McpConnections mcp, LearningTools learning,
                AIAgent<String, String> agent) {
            this.id = id;
            this.learning = learning;
            this.cwd = cwd;
            this.tools = tools;
            this.mcp = mcp;
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
        LearningTools learning = new LearningTools(tools, mcp);
        AIAgent<String, String> agent;
        try {
            agent = createKoogAgent(request.cwd(), tools, mcp, learning);
        } catch (RuntimeException e) {
            mcp.close();
            throw e;
        }
        sessions.put(sessionId, new Session(sessionId, request.cwd(), tools, mcp, learning, agent));
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
        // Say in the message itself how to answer (tree, walkthrough, highlight...): GPT-4o follows
        // that far more reliably than the system prompt. The chat history keeps the learner's words.
        String modelInput = LearningTools.available(session.mcp)
                ? userText + TeachingIntent.classify(userText).directive()
                : userText;
        CompletableFuture<String> run = CompletableFuture.supplyAsync(
                () -> session.agent.run(modelInput, session.id));
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
            session.agent = createKoogAgent(session.cwd, session.tools, session.mcp, session.learning);
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
                                                    LearningTools learning) {
        var registryBuilder = ToolRegistry.builder().tools(tools).tools(mcp.tools());
        if (LearningTools.available(mcp)) {
            // Our learning_* tools check their input and call the plugin (directly or via AIR's execute_tool).
            registryBuilder.tools(learning);
        }
        ToolRegistry registry = registryBuilder.build();
        return AIAgent.builder()
                .promptExecutor(executor())
                .llmModel(MODEL)
                .systemPrompt(systemPrompt(cwd, mcp))
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
    private static final String CHAT_CODE_NUDGE = """
            Your answer explains code in the chat with code blocks, but you haven't shown anything in \
            the learner's editor. If this is about code in their project, show it there instead (an \
            explanation tree, a walkthrough or a highlight, as "Choosing how to answer" says, \
            after finding and reading the code), then reply in 1-3 sentences without code blocks. If \
            it really isn't about their project's code, give your answer again in a few sentences \
            without code blocks.""";

    /**
     * ReAct agent loop: Thought → Action → Observation cycle.
     *
     * <p>The LLM is prompted to reason (Thought), call tools (Action), and receive results
     * (Observation). After each tool execution, the results are fed back with an Observation
     * prompt so the LLM can reason about what to do next. This continues until the LLM provides
     * a final answer without calling more tools.
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
        boolean showedInEditor = false;
        boolean nudgedChatCode = false;
        // The intent the message was tagged with (see TeachingIntent), if any.
        int marker = input.indexOf(TeachingIntent.MARKER);
        TeachingIntent intent = learningTools && marker >= 0
                ? TeachingIntent.classify(input.substring(0, marker)) : TeachingIntent.CHAT;
        boolean remindedEditor = false;
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
                if (intent.needsEditor() && !showedInEditor && !remindedEditor) {
                    // The learner asked about code, but nothing was shown in the editor.
                    remindedEditor = true;
                    reply = ctx.requestLLM(intent.reminder());
                    continue;
                }
                if (learningTools && !showedInEditor && !nudgedChatCode && answer.contains("```")) {
                    // It explained code in the chat (code blocks) without showing anything in the editor.
                    nudgedChatCode = true;
                    reply = ctx.requestLLM(CHAT_CODE_NUDGE);
                    continue;
                }
                return answer;
            }
            if (calls.stream().anyMatch(c -> c.getTool().startsWith("learning_"))) {
                showedInEditor = true;
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

    private MultiLLMPromptExecutor executor() {
        MultiLLMPromptExecutor executor = promptExecutor;
        if (executor == null) {
            synchronized (this) {
                executor = promptExecutor;
                if (executor == null) {
                    String apiKey = System.getenv("OPENAI_API_KEY");
                    if (apiKey == null || apiKey.isBlank()) {
                        // Reported to the client as the error of session/new.
                        throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
                                "OPENAI_API_KEY environment variable is not set for the agent process.");
                    }
                    var openAIClient = new OpenAILLMClient(apiKey, new KtorKoogHttpClient.Factory());
                    executor = new MultiLLMPromptExecutor(openAIClient);
                    promptExecutor = executor;
                }
            }
        }
        return executor;
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
