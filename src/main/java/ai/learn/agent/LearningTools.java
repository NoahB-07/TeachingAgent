package ai.learn.agent;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import ai.koog.agents.core.tools.annotations.Tool;
import ai.koog.agents.core.tools.reflect.ToolSet;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServer;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerSse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tools that let the LLM teach in the learner's editor, through the JetBrains learning plugin.
 *
 * <p>The plugin adds {@code learning_*} tools to the IDE's MCP server. These are the agent's own
 * versions of them, with the same names and arguments. Each one:
 * <ol>
 *   <li>checks its input against the learner's files ({@link LearningChecks}), so a miscounted
 *       range is caught before anything is shown;</li>
 *   <li>calls the plugin, either directly (when the IDE's MCP server lists the plugin's tools) or
 *       through JetBrains AIR's {@code execute_tool} proxy, which takes a command line
 *       {@code toolName --param 'value'} and so needs careful quoting;</li>
 *   <li>is reported to the client as a tool call, like {@link AcpIdeTools}, and returns the
 *       plugin's reply plus the code each range landed on, so the LLM can check itself.</li>
 * </ol>
 *
 * <p>One instance per session. The IDE's MCP server normally comes from the ACP client's
 * {@code session/new}; set {@code AIR_IDE_MCP_URL} to the URL from the IDE's
 * <i>Settings > Tools > MCP Server > Copy Config</i> to connect to it directly instead.
 */
@LLMDescription("Tools to show the learner their code in the editor: explanation trees, walkthroughs and highlights. "
        + "Use them for every question about code.")
public final class LearningTools implements ToolSet {

    static final String FORMAT_REFERENCE = "learning_get_format_reference";
    static final String HIGHLIGHT = "learning_highlight_code";
    static final String SHOW_TREE = "learning_show_explanation_tree";
    static final String SHOW_NODE = "learning_show_explanation_node";
    static final String CLEAR = "learning_clear_explanations";
    static final String PLAY_WALKTHROUGH = "learning_play_walkthrough";
    static final String CONTROL_WALKTHROUGH = "learning_control_walkthrough";

    /** Names of the tools defined in this class (the plugin's tools of the same name are wrapped, not exposed). */
    static final Set<String> OWN_TOOL_NAMES = Set.of(FORMAT_REFERENCE, HIGHLIGHT, SHOW_TREE, SHOW_NODE,
            CLEAR, PLAY_WALKTHROUGH, CONTROL_WALKTHROUGH);

    /** JetBrains AIR's single tool that runs any IDE MCP tool from a command line. */
    static final String PROXY = "execute_tool";

    /** Environment variable with a URL for the IDE's MCP server. */
    static final String URL_ENV = "AIR_IDE_MCP_URL";

    private static final Set<String> ACTIONS = Set.of("next", "previous", "go_to", "play_pause", "stop");

    private final AcpIdeTools ide;
    private final McpConnections mcp;
    private final TeachingProfile profile;
    /** Node ids loaded into the plugin this session (trees merge by id). */
    private final Set<String> loadedIds = ConcurrentHashMap.newKeySet();
    /** Steps in the walkthrough last played, or 0. */
    private volatile int walkthroughSteps;

    LearningTools(AcpIdeTools ide, McpConnections mcp, TeachingProfile profile) {
        this.ide = ide;
        this.mcp = mcp;
        this.profile = profile;
    }

    /** True if the plugin can be reached in this session, directly or through AIR's proxy. */
    static boolean available(McpConnections mcp) {
        return mcp.canCall(HIGHLIGHT) || mcp.canCall(PROXY);
    }

    // ---------------------------------------------------------------- tools

    @Tool(customName = FORMAT_REFERENCE)
    @LLMDescription("Returns the plugin's full reference for a JSON format: every field, ranges, links, "
            + "colours and levels of detail. Read it once per session before your first tree or walkthrough.")
    public String getFormatReference(
            @LLMDescription("Which format: \"explanations\" or \"walkthrough\"") String format) {
        return report("Format reference: " + format, null, () -> send(FORMAT_REFERENCE, args("format", format)));
    }

    @Tool(customName = HIGHLIGHT)
    @LLMDescription("Use when the learner asks WHERE something is (\"where is X?\", \"which line...?\", \"show me "
            + "the loop\"). Highlights a range of code in their editor, opening the file if needed, with an "
            + "optional one-sentence Markdown note in a box above it. Replaces any explanation shown. "
            + "To explain code, use learning_show_explanation_tree instead. "
            + "Ranges are 1-based: \"12\", \"12-15\" or \"12:5-15:20\" (end column inclusive). "
            + "The result shows the code that was highlighted: check it is what you meant.")
    public String highlightCode(
            @LLMDescription("File path, relative to the project root (e.g. \"src/Main.java\") or absolute") String path,
            @LLMDescription("Code to highlight: \"12\", \"12-15\" or \"12:5-15:20\"") String range,
            @LLMDescription("Markdown shown above the highlight, or \"\" to only highlight") String explanation) {
        return report("Highlight " + path + " " + range, ide.resolve(path), () -> {
            LearningChecks.Report check = checks().checkHighlight(path, range, explanation);
            if (!check.ok()) throw new IllegalArgumentException(check.errorText());
            Map<String, Object> args = args("path", path, "range", range);
            if (!blank(explanation)) args.put("explanation", explanation);
            return check.summary(send(HIGHLIGHT, args), "Highlighted:");
        });
    }

    @Tool(customName = SHOW_TREE)
    @LLMDescription("The default way to answer any request to explain, describe or help understand code (a file, "
            + "class, method, block, or how a feature works), and to quiz the learner or answer follow-ups. "
            + "Shows an explanation tree in their editor: the code is highlighted with a Markdown box above it, "
            + "and the learner clicks links to go deeper. Find and read the code first. "
            + "Nodes merge by id with nodes sent earlier. Shape: {\"file\": \"src/Main.java\", \"root\": \"main\", "
            + "\"nodes\": {\"main\": {\"range\": \"3-13\", \"text\": \"It [loops](#loop).\"}, \"loop\": {\"range\": \"8-12\", "
            + "\"text\": \"...\"}}}. The result lists the code each node highlights: check it.")
    public String showExplanationTree(
            @LLMDescription("The explanation tree as a JSON string") String json,
            @LLMDescription("Id of the node to show instead of the root, or \"\" for the root") String show_id) {
        return report("Show explanation tree", null, () -> {
            LearningChecks.Report check = checks().checkTree(json, show_id, loadedIds);
            if (!check.ok()) throw new IllegalArgumentException(check.errorText());
            Map<String, Object> args = args("json", json);
            if (!blank(show_id)) args.put("show_id", show_id);
            String reply = send(SHOW_TREE, args);
            loadedIds.addAll(check.ids);
            // Auto-log: extract file and node ids from the tree for the teaching profile.
            autoLogTree(json, check.ids);
            return check.summary(reply, "Nodes and the code they highlight:");
        });
    }

    @Tool(customName = SHOW_NODE)
    @LLMDescription("Shows a node that is already loaded as the start of a new chain, replacing what is shown. "
            + "Use it to bring the learner back to a node or start a guided tour from a step.")
    public String showExplanationNode(@LLMDescription("Id of a loaded node") String id) {
        return report("Show node " + id, null, () -> {
            String reply = send(SHOW_NODE, args("id", id));
            if (!loadedIds.contains(id)) {
                reply += "\n\n(Note: '" + id + "' wasn't sent in this chat, so check it is the node you meant.)";
            }
            return reply;
        });
    }

    @Tool(customName = CLEAR)
    @LLMDescription("Removes every explanation highlight and box from the editors. Loaded nodes stay loaded.")
    public String clearExplanations() {
        return report("Clear explanations", null, () -> send(CLEAR, args()));
    }

    @Tool(customName = PLAY_WALKTHROUGH)
    @LLMDescription("Use when the learner asks what happens when code RUNS: \"walk me through\", \"step through\", "
            + "\"trace\", \"what does it print?\", \"what is x after...?\". Plays a step-by-step run of the code in "
            + "their editor, with variables, call stack and output, replacing any playing. Trace the code yourself "
            + "first to get real values. "
            + "Shape: {\"title\": \"Running main()\", \"file\": \"Main.java\", \"steps\": [{\"line\": 3, \"kind\": \"call\", "
            + "\"note\": \"...\", \"stack\": [\"main()\"]}, {\"line\": 8, \"vars\": {\"i\": \"1\"}, \"substitute\": [\"i\"]}]}. "
            + "\"vars\" are values BEFORE the line runs; \"output\" is only what that step prints. "
            + "The result lists the code each step highlights: check it.")
    public String playWalkthrough(@LLMDescription("The walkthrough as a JSON string") String json) {
        return report("Play walkthrough", null, () -> {
            LearningChecks.Report check = checks().checkWalkthrough(json);
            if (!check.ok()) throw new IllegalArgumentException(check.errorText());
            String reply = send(PLAY_WALKTHROUGH, args("json", json));
            walkthroughSteps = check.echo.size();
            // Auto-log: extract file and title from the walkthrough.
            autoLogWalkthrough(json);
            return check.summary(reply, "Steps and the code they highlight:");
        });
    }

    @Tool(customName = CONTROL_WALKTHROUGH)
    @LLMDescription("Controls the walkthrough that is playing: next, previous, go_to (with step), "
            + "play_pause (toggles auto-play) or stop (closes it). Use it to point at a step while you talk about it.")
    public String controlWalkthrough(
            @LLMDescription("One of: next, previous, go_to, play_pause, stop") String action,
            @LLMDescription("For go_to: the 1-based step number; otherwise 0") int step) {
        String a = action == null ? "" : action.trim().toLowerCase();
        return report("Walkthrough: " + a + ("go_to".equals(a) ? " " + step : ""), null, () -> {
            if (!ACTIONS.contains(a)) {
                throw new IllegalArgumentException("Unknown action '" + action + "'. Use next, previous, go_to, play_pause or stop.");
            }
            Map<String, Object> args = args("action", a);
            if ("go_to".equals(a)) {
                int count = walkthroughSteps;
                if (step < 1 || (count > 0 && step > count)) {
                    throw new IllegalArgumentException("go_to needs a step from 1"
                            + (count > 0 ? " to " + count : "") + ", not " + step + ".");
                }
                args.put("step", step);
            }
            return send(CONTROL_WALKTHROUGH, args);
        });
    }

    // ---------------------------------------------------------------- calling the plugin

    /** Calls a plugin tool directly if the IDE lists it, else through AIR's proxy. */
    private String send(String tool, Map<String, Object> args) throws Exception {
        McpConnections.CallResult result;
        if (mcp.canCall(tool)) {
            result = mcp.call(tool, args);
        } else if (mcp.canCall(PROXY)) {
            result = mcp.call(PROXY, Map.of("command", command(tool, args)));
        } else {
            throw new IllegalStateException("The learning plugin isn't connected in this session.");
        }
        if (result.error()) {
            String text = result.text().isBlank() ? "The IDE rejected the call." : result.text();
            if (text.toLowerCase().contains("unknown tool") || text.toLowerCase().contains("not found")) {
                text += "\n(The IDE may not expose the learning plugin's tools: check they are enabled in "
                        + "Settings > Tools > MCP Server.)";
            }
            throw new IllegalArgumentException(text);
        }
        return result.text();
    }

    /**
     * The proxy's command line: {@code tool --name value ...}. Strings are single-quoted with
     * {@code \'} for apostrophes, the only escape its tokenizer accepts there (newlines and other
     * backslashes pass through as they are). In JSON, apostrophes can only occur inside strings, so
     * they become the JSON escape {@code \u0027} instead and the value has no quotes to escape.
     */
    static String command(String tool, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder(tool);
        args.forEach((name, value) -> {
            sb.append(" --").append(name).append(' ');
            if (value instanceof Number n) {
                sb.append(n);
            } else if ("json".equals(name)) {
                sb.append('\'').append(String.valueOf(value).replace("'", "\\u0027")).append('\'');
            } else {
                sb.append('\'').append(String.valueOf(value).replace("'", "\\'")).append('\'');
            }
        });
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private LearningChecks checks() {
        // A fresh cache per call, so each check sees the files as they are now.
        return new LearningChecks(new LearningChecks.FileSource() {
            @Override
            public List<String> lines(String path) throws Exception {
                return LearningChecks.splitLines(ide.readText(ide.resolve(path)));
            }

            @Override
            public List<String> suggest(String path) {
                try {
                    return ide.findByName(Path.of(path.replace('\\', '/')).getFileName().toString(), 5);
                } catch (Exception e) {
                    return List.of();
                }
            }
        });
    }

    private String report(String title, Path location, AcpIdeTools.Action action) {
        return ide.report("Learning: " + title, ToolKind.OTHER, location, () -> {
            try {
                return action.run();
            } catch (Exception e) {
                // Goes to the IDE's log (idea.log, "[acp.teaching-agent] stderr"), to diagnose failed calls.
                System.err.println("[learning] " + title + " failed: " + e.getMessage());
                throw e;
            }
        });
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---------------------------------------------------------------- auto-logging to teaching profile

    /** Extract file and node ids from an explanation tree JSON and record them in the profile. */
    private void autoLogTree(String json, Set<String> nodeIds) {
        if (profile == null) return;
        try {
            String file = extractJsonString(json, "file");
            String root = extractJsonString(json, "root");
            // Use the root node id or the first node id as the topic label.
            String topic = root;
            if (topic == null || topic.isBlank()) {
                if (!nodeIds.isEmpty()) {
                    topic = nodeIds.iterator().next();
                }
            }
            if (topic != null && !topic.isBlank()) {
                profile.addTopic(topic, topic, file, new ArrayList<>(nodeIds));
            }
        } catch (Exception e) {
            // Silently ignore — auto-log is best-effort.
            System.err.println("[learning] Auto-log tree failed: " + e.getMessage());
        }
    }

    /** Extract each walkthrough step and record it as a separate topic in the profile. */
    private void autoLogWalkthrough(String json) {
        if (profile == null) return;
        try {
            String file = extractJsonString(json, "file");
            String title = extractJsonString(json, "title");

            // Find the "steps" array and extract each step object individually
            List<String> stepBlocks = extractStepBlocks(json);
            int stepIndex = 0;
            for (String stepJson : stepBlocks) {
                stepIndex++;
                // Extract fields from this step
                String stepFile = extractJsonString(stepJson, "file");
                if (stepFile == null || stepFile.isBlank()) {
                    stepFile = file;
                }

                String stepKind = extractJsonString(stepJson, "kind");
                String stepTitle = extractJsonString(stepJson, "title");
                String stepNote = extractJsonString(stepJson, "note");
                String stepLine = extractJsonPrimitive(stepJson, "line");
                String stepOutput = extractJsonString(stepJson, "output");
                List<String> stepVars = extractVarsKeys(stepJson);

                // Build the topic label: what this step actually taught
                // Priority: stepTitle > stepNote > kind + line
                String topicLabel;
                if (stepTitle != null && !stepTitle.isBlank()) {
                    topicLabel = stepTitle;
                } else if (stepNote != null && !stepNote.isBlank()) {
                    topicLabel = stepNote;
                } else if (stepKind != null && !stepKind.isBlank()) {
                    topicLabel = stepKind;
                } else {
                    topicLabel = "step";
                }

                // Build the title for the profile entry
                String profileTitle;
                if (title != null && !title.isBlank()) {
                    profileTitle = title + " (step " + stepIndex + ")";
                } else {
                    profileTitle = "Walkthrough step " + stepIndex;
                }

                // Collect nodes: exact details about what this step covered
                List<String> nodes = new ArrayList<>();
                if (stepLine != null && !stepLine.isBlank()) {
                    nodes.add("line:" + stepLine);
                }
                if (stepKind != null && !stepKind.isBlank()) {
                    nodes.add("kind:" + stepKind);
                }
                if (stepNote != null && !stepNote.isBlank()) {
                    String note = stepNote.length() > 80 ? stepNote.substring(0, 80) : stepNote;
                    nodes.add("note:" + note);
                }
                for (String v : stepVars) {
                    nodes.add("variable:" + v);
                }
                if (stepOutput != null && !stepOutput.isBlank()) {
                    String output = stepOutput.replace("\n", "\\n").replace("\r", "");
                    if (output.length() > 80) output = output.substring(0, 80);
                    nodes.add("output:" + output);
                }

                profile.addTopic(topicLabel, profileTitle, stepFile, nodes);
            }
        } catch (Exception e) {
            System.err.println("[learning] Auto-log walkthrough failed: " + e.getMessage());
        }
    }

    /** Extract individual step object blocks from the "steps" array in walkthrough JSON. */
    private static List<String> extractStepBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        // Find the "steps" array
        String stepsSearch = "\"steps\"\\s*:\\s*\\[";
        int stepsIdx = json.indexOf(stepsSearch);
        if (stepsIdx == -1) return blocks;

        // Find the opening bracket of the array
        int arrayStart = stepsIdx + stepsSearch.length() - 1; // position of '['
        int depth = 0;
        int i = arrayStart;
        int arrayEnd = -1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
            }
            i++;
        }
        if (arrayEnd <= arrayStart) return blocks;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        // Now extract each object within the array
        int objStart = 0;
        while (objStart < arrayContent.length()) {
            // Skip whitespace and commas
            int pos = objStart;
            while (pos < arrayContent.length() && (arrayContent.charAt(pos) == ' ' || arrayContent.charAt(pos) == '\n' || arrayContent.charAt(pos) == '\r' || arrayContent.charAt(pos) == '\t' || arrayContent.charAt(pos) == ',')) {
                pos++;
            }
            if (pos >= arrayContent.length() || arrayContent.charAt(pos) != '{') break;

            // Find the matching closing brace
            int braceStart = pos;
            int braceDepth = 0;
            int braceEnd = -1;
            int j = braceStart;
            while (j < arrayContent.length()) {
                char c = arrayContent.charAt(j);
                if (c == '{') braceDepth++;
                else if (c == '}') {
                    braceDepth--;
                    if (braceDepth == 0) {
                        braceEnd = j;
                        break;
                    }
                } else if (c == '"') {
                    // Skip string literals to avoid counting braces inside strings
                    j++;
                    while (j < arrayContent.length()) {
                        char sc = arrayContent.charAt(j);
                        if (sc == '\\') j++;
                        else if (sc == '"') break;
                        j++;
                    }
                }
                j++;
            }
            if (braceEnd > braceStart) {
                blocks.add(arrayContent.substring(braceStart, braceEnd + 1));
                objStart = braceEnd + 1;
            } else {
                break;
            }
        }
        return blocks;
    }

    /** Extract variable names from the "vars" object in a single step JSON block. */
    private static List<String> extractVarsKeys(String stepJson) {
        List<String> vars = new ArrayList<>();
        String search = "\"vars\"\\s*:\\s*{";
        int idx = stepJson.indexOf(search);
        if (idx == -1) return vars;

        int braceStart = idx + search.length() - 1;
        int depth = 0;
        int i = braceStart;
        int braceEnd = -1;
        while (i < stepJson.length()) {
            char c = stepJson.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    braceEnd = i;
                    break;
                }
            }
            i++;
        }
        if (braceEnd > braceStart) {
            String varsBlock = stepJson.substring(braceStart + 1, braceEnd);
            int keyIdx = 0;
            while ((keyIdx = varsBlock.indexOf("\"", keyIdx)) != -1) {
                int keyStart = keyIdx + 1;
                int keyEnd = keyStart;
                while (keyEnd < varsBlock.length()) {
                    char c = varsBlock.charAt(keyEnd);
                    if (c == '"') break;
                    if (c == '\\') keyEnd++;
                    keyEnd++;
                }
                if (keyEnd < varsBlock.length()) {
                    String key = varsBlock.substring(keyStart, keyEnd);
                    int afterKey = keyEnd + 1;
                    while (afterKey < varsBlock.length() && (varsBlock.charAt(afterKey) == ' ' || varsBlock.charAt(afterKey) == '\t')) {
                        afterKey++;
                    }
                    if (afterKey < varsBlock.length() && varsBlock.charAt(afterKey) == ':') {
                        vars.add(key);
                    }
                    keyIdx = keyEnd + 1;
                } else {
                    break;
                }
            }
        }
        return vars;
    }

    /** Extract a primitive value (number or string) for a given key from JSON. */
    private static String extractJsonPrimitive(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int pos = idx + pattern.length();
        // Skip whitespace
        while (pos < json.length() && (json.charAt(pos) == ' ' || json.charAt(pos) == '\t' || json.charAt(pos) == '\n' || json.charAt(pos) == '\r')) {
            pos++;
        }
        if (pos >= json.length()) return null;
        char c = json.charAt(pos);
        if (c == '"') {
            // String value
            int start = pos + 1;
            int end = start;
            while (end < json.length()) {
                char ch = json.charAt(end);
                if (ch == '"') break;
                if (ch == '\\') end++;
                end++;
            }
            if (end >= json.length()) return null;
            String value = json.substring(start, end);
            return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        } else {
            // Number or other primitive
            int start = pos;
            int end = pos;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']' && json.charAt(end) != ' ' && json.charAt(end) != '\n' && json.charAt(end) != '\r' && json.charAt(end) != '\t') {
                end++;
            }
            return json.substring(start, end);
        }
    }

    /**
     * Extract a string value for a given key from a JSON string using simple text search.
     * This is a lightweight alternative to full JSON parsing for auto-logging.
     */
    private static String extractJsonString(String json, String key) {
        // Look for "key":"value" pattern
        String pattern = "\"" + key + "\"\\s*:\\s*\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"') break;
            if (c == '\\') end++; // skip escaped character
            end++;
        }
        if (end >= json.length()) return null;
        String value = json.substring(start, end);
        // Unescape JSON strings
        return value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    // ---------------------------------------------------------------- configuration and prompt

    /**
     * The servers the client passed, plus the IDE server from {@link #URL_ENV} if set. A URL ending
     * in {@code /sse} is connected over SSE, anything else over streamable HTTP.
     */
    static List<McpServer> withConfiguredServer(List<McpServer> servers) {
        List<McpServer> all = new ArrayList<>(servers == null ? List.of() : servers);
        String url = System.getenv(URL_ENV);
        if (url != null && !url.isBlank()) {
            url = url.strip();
            boolean sse = url.replaceAll("/+$", "").endsWith("/sse");
            all.add(sse ? new McpServerSse("jetbrains-ide", url, List.of())
                    : new McpServerHttp("jetbrains-ide", url, List.of()));
        }
        return all;
    }

    /** System prompt section for teaching with the plugin. */
    static String prompt(McpConnections mcp) {
        if (!available(mcp)) {
            return ""; // the base prompt already says to explain in the chat
        }
        return "\n" + resource("teaching-guide.md");
    }

    private static String resource(String name) {
        try (InputStream in = LearningTools.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
