package ai.learn.agent;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import ai.koog.agents.core.tools.annotations.Tool;
import ai.koog.agents.core.tools.reflect.ToolSet;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;

import java.util.Map;

/**
 * The learning plugin's tools, reached through JetBrains AIR's {@code execute_tool} proxy.
 *
 * <p>AIR doesn't pass the IDE's MCP tools to the agent one by one: it passes one
 * {@code execute_tool(command)} tool that takes a shell-style command line
 * ({@code toolName --param 'value' ...}). Getting the quoting right for a whole JSON tree by hand is
 * error-prone for the model, so these typed tools (with the plugin's own names) build the command
 * and call the proxy. They are only registered when the plugin's tools aren't connected directly.
 */
@LLMDescription("Tools to show explanations, highlights and walkthroughs in the learner's editor")
public final class LearningProxyTools implements ToolSet {

    static final String PROXY = "execute_tool";

    private final McpConnections mcp;
    private final AcpIdeTools ide;

    LearningProxyTools(McpConnections mcp, AcpIdeTools ide) {
        this.mcp = mcp;
        this.ide = ide;
    }

    /** True when the learning tools are only reachable through AIR's proxy. */
    static boolean needed(McpConnections mcp) {
        return mcp.hasTool(PROXY) && !mcp.hasTool(LearningTools.HIGHLIGHT);
    }

    // ---------------------------------------------------------------- tools

    @Tool(customName = LearningTools.FORMAT_REFERENCE)
    @LLMDescription("Returns the full reference for a JSON format used by the other learning_* tools. "
            + "Read it once per session before writing your first explanation tree or walkthrough.")
    public String getFormatReference(
            @LLMDescription("Which format: \"explanations\" or \"walkthrough\"") String format) {
        return run("Format reference: " + format, command(LearningTools.FORMAT_REFERENCE, "format", text(format)));
    }

    @Tool(customName = LearningTools.HIGHLIGHT)
    @LLMDescription("Highlights a range of code in the editor, opening the file if needed, and optionally "
            + "shows a Markdown explanation in a box above it. Replaces any explanation shown. "
            + "Ranges are 1-based: \"12\", \"12-15\" or \"12:5-15:20\" (end column inclusive).")
    public String highlightCode(
            @LLMDescription("File path, relative to the project root (e.g. \"src/Main.java\") or absolute") String path,
            @LLMDescription("Code to highlight: \"12\", \"12-15\" or \"12:5-15:20\"") String range,
            @LLMDescription("Markdown shown above the highlight, or \"\" to only highlight") String explanation) {
        String cmd = command(LearningTools.HIGHLIGHT, "path", text(path), "range", text(range));
        if (explanation != null && !explanation.isBlank()) {
            cmd += " --explanation " + text(explanation);
        }
        return run("Highlight " + path + " " + range, cmd);
    }

    @Tool(customName = LearningTools.SHOW_TREE)
    @LLMDescription("Loads an explanation tree into the editor and shows its root node (or show_id). "
            + "Nodes merge by id with nodes loaded earlier. On invalid JSON the error names the node and "
            + "field at fault: fix it and send the whole tree again.")
    public String showExplanationTree(
            @LLMDescription("The explanation tree as a JSON string") String json,
            @LLMDescription("Id of a node to show instead of the root, or \"\" for the root") String show_id) {
        String cmd = command(LearningTools.SHOW_TREE, "json", jsonValue(json));
        if (show_id != null && !show_id.isBlank()) {
            cmd += " --show_id " + text(show_id);
        }
        return run("Show explanation tree", cmd);
    }

    @Tool(customName = LearningTools.SHOW_NODE)
    @LLMDescription("Shows a node that is already loaded as the start of a new chain, replacing what is shown.")
    public String showExplanationNode(@LLMDescription("Id of a loaded node") String id) {
        return run("Show node " + id, command(LearningTools.SHOW_NODE, "id", text(id)));
    }

    @Tool(customName = LearningTools.CLEAR)
    @LLMDescription("Removes every explanation highlight and box from the editors. Loaded nodes stay loaded.")
    public String clearExplanations() {
        return run("Clear explanations", LearningTools.CLEAR);
    }

    @Tool(customName = LearningTools.PLAY_WALKTHROUGH)
    @LLMDescription("Plays a step-by-step walkthrough of a run of some code in the editor, replacing any "
            + "walkthrough playing. On invalid JSON the error starts with \"Step N:\": fix that step and "
            + "send the whole walkthrough again.")
    public String playWalkthrough(@LLMDescription("The walkthrough as a JSON string") String json) {
        return run("Play walkthrough", command(LearningTools.PLAY_WALKTHROUGH, "json", jsonValue(json)));
    }

    @Tool(customName = LearningTools.CONTROL_WALKTHROUGH)
    @LLMDescription("Controls the walkthrough that is playing: next, previous, go_to (with step), "
            + "play_pause (toggles auto-play) or stop (closes it).")
    public String controlWalkthrough(
            @LLMDescription("One of: next, previous, go_to, play_pause, stop") String action,
            @LLMDescription("For go_to: the 1-based step number; otherwise 0") int step) {
        String cmd = command(LearningTools.CONTROL_WALKTHROUGH, "action", text(action));
        if ("go_to".equalsIgnoreCase(action == null ? "" : action.trim())) {
            cmd += " --step " + step;
        }
        return run("Walkthrough: " + action, cmd);
    }

    // ---------------------------------------------------------------- command building

    /** {@code tool --k1 v1 --k2 v2}; values must already be quoted. */
    static String command(String tool, String... keyValues) {
        StringBuilder sb = new StringBuilder(tool);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            sb.append(" --").append(keyValues[i]).append(' ').append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    /**
     * A plain value as one token: single-quoted, with {@code \'} for apostrophes (the only escape
     * the proxy's tokenizer accepts inside single quotes; newlines and other backslashes pass through).
     */
    static String text(String value) {
        return "'" + (value == null ? "" : value).replace("'", "\\'") + "'";
    }

    /**
     * A JSON value as one token. Apostrophes can only occur inside JSON strings, so they are
     * written as the JSON escape {@code \u0027}: the value then has no single quotes at all, and the
     * plugin's JSON parser turns them back into apostrophes.
     */
    static String jsonValue(String json) {
        return "'" + (json == null ? "" : json).replace("'", "\\u0027") + "'";
    }

    private String run(String title, String command) {
        return ide.report("Learning: " + title, ToolKind.OTHER, null, () -> {
            McpConnections.CallResult result = mcp.call(PROXY, Map.of("command", command));
            if (result.error()) {
                throw new IllegalArgumentException(result.text().isBlank() ? "The IDE rejected the call." : result.text());
            }
            return result.text().isBlank() ? "Done." : result.text();
        });
    }
}
