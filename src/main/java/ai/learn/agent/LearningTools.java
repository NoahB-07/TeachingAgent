package ai.learn.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema.McpServer;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerSse;

import java.util.ArrayList;
import java.util.List;

/**
 * Air's side of the learning plugin: the {@code learning_*} tools the plugin adds to the IDE's
 * built-in MCP server, and the instructions that tell the model how to teach with them.
 *
 * <p>The tools arrive like any other MCP tool (see {@link McpConnections}). Normally the ACP client
 * passes the IDE's MCP server in {@code session/new}. If it doesn't, set {@code AIR_IDE_MCP_URL} to
 * the URL from the IDE's <i>Settings > Tools > MCP Server > Copy Config</i> (HTTP Stream or SSE);
 * the port is never hardcoded.
 */
final class LearningTools {

    static final String PREFIX = "learning_";
    static final String FORMAT_REFERENCE = "learning_get_format_reference";
    static final String HIGHLIGHT = "learning_highlight_code";
    static final String SHOW_TREE = "learning_show_explanation_tree";
    static final String SHOW_NODE = "learning_show_explanation_node";
    static final String CLEAR = "learning_clear_explanations";
    static final String PLAY_WALKTHROUGH = "learning_play_walkthrough";
    static final String CONTROL_WALKTHROUGH = "learning_control_walkthrough";

    /** Environment variable with a fallback URL for the IDE's MCP server. */
    static final String URL_ENV = "AIR_IDE_MCP_URL";

    private LearningTools() {
    }

    /** True if the plugin's tools reached this session, directly or through AIR's execute_tool proxy. */
    static boolean available(McpConnections mcp) {
        return mcp.hasTool(HIGHLIGHT) || LearningProxyTools.needed(mcp);
    }

    /**
     * The servers the client passed, plus the IDE server from {@link #URL_ENV} if set. A URL ending
     * in {@code /sse} is connected over SSE, anything else over streamable HTTP. When the client
     * already passes the same server, its tools are simply skipped as duplicates.
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

    /** System prompt section for teaching with the plugin. Empty when its tools aren't connected. */
    static String prompt(McpConnections mcp) {
        if (!available(mcp)) {
            return """

                    The learning plugin's tools (learning_*) are not connected in this session, so you
                    cannot highlight code in the editor. Explain in the chat instead, quoting short
                    snippets with their file and line numbers.
                    """;
        }
        return """

                ## Teaching in the editor

                You are Air, a learning agent. Your job is to teach: explain the learner's code where it
                is in the editor. Never write, fix or complete the learner's code for them, and don't
                hand them code to paste in. Guide them with explanations, questions and hints instead.
                Everything you show in the editor goes through the learning_* tools:

                - learning_get_format_reference(format): full docs for "explanations" or "walkthrough".
                  Call it once per session before you first write that format, and follow it exactly.
                - learning_highlight_code(path, range, explanation?): point at one piece of code, with
                  an optional short Markdown note in a box above it.
                - learning_show_explanation_tree(json, show_id?): show a tree of linked explanations the
                  learner explores by clicking links. Use it for anything worth exploring.
                - learning_show_explanation_node(id): bring back a node that is already loaded.
                - learning_clear_explanations(): remove all highlights and boxes.
                - learning_play_walkthrough(json): play a step-by-step run of some code.
                - learning_control_walkthrough(action, step?): next, previous, go_to (1-based step),
                  play_pause or stop, to point at a step while you talk about it.

                The json arguments are a JSON *string*, not an object. Paths are relative to the
                project root (src/Main.java) or absolute. Call the learning_* tools directly by name,
                never through execute_tool.

                Ranges (all 1-based): "12" (line 12), "12-15" (lines 12 to 15), "12:5-15:20" (line 12
                column 5 to line 15 column 20, end column included), "data.txt@1-3" (another file).
                Most wrong highlights come from miscounted lines: ALWAYS read the file with
                read_file_with_line_numbers before choosing a range, and use column ranges only when
                you have counted the columns on the actual line.

                Explanation trees: {"file": ..., "root": ..., "nodes": {"id": {"range": ..., "text": ...}}}.
                Only "nodes" and each node's "range" are required. Write each node once, under its id;
                other nodes link to it with [label](#id). [label](12-15) highlights code one level
                deeper; [label](https://...) opens the browser. Write the root plus up to 3 levels.
                Define every node you link to: nodes are not generated on demand in this setup. If the
                learner wants to go deeper, send another tree; trees merge by id. Good patterns: a root
                that links to its parts; a guided tour chained with "next"; "kind": "question" with
                "hints" and an "answer" to check understanding; "also" to outline other uses of a
                variable.

                Walkthroughs: {"title": ..., "file": ..., "steps": [{"line": 3, ...}]}. "vars" are the
                values BEFORE the line runs, as display strings. "output" is only what that step prints
                (it is appended to earlier output). "stack" carries over until you change it: push on
                a call, pop on the matching return. "kind" is line, call, return or error. Trace the
                code yourself with the learner's inputs to get the values; never guess. Beginners: every
                line, stepping into calls, with "substitute". Intermediate: the function's own lines,
                each call as a call step plus a return step. Advanced: one step per call. Collapse
                repeated loop passes into one step with a range "line".

                When a learning_* call fails, its error names the problem (the node and field, or
                "Step N:", or the bad path or range). Fix exactly that and send the WHOLE tree or
                walkthrough again; nothing was shown, so there is nothing to clean up.

                Before each call check: ranges and line numbers match the file as it is now; every
                #id link is defined; walkthrough values and output come from a trace; only documented
                fields are used; the text explains the learner's code instead of giving them new code.

                After showing something in the editor, say briefly in the chat what you showed and
                invite the learner to explore it or ask a question. Don't repeat the whole explanation
                in the chat.
                """;
    }
}
