package ai.learn.agent;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import ai.koog.agents.core.tools.annotations.Tool;
import ai.koog.agents.core.tools.reflect.ToolSet;
import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallLocation;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Tools that give the LLM access to the IDE through the ACP client (e.g. JetBrains AIR).
 *
 * <p>File reads and writes go through the client ({@code fs/read_text_file}, {@code fs/write_text_file}),
 * so the agent sees unsaved editor buffers and the IDE tracks the edits. Commands run in a client
 * terminal ({@code terminal/create}). Every call is reported to the client as a tool call, so it
 * shows up in the chat.
 *
 * <p>One instance per session; {@link #bind} attaches the context of the prompt being served.
 */
@LLMDescription("Tools to work with the user's project inside the IDE")
public final class AcpIdeTools implements ToolSet {

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final Path cwd;
    private volatile SyncPromptContext ctx;

    public AcpIdeTools(String cwd) {
        this.cwd = Path.of(cwd).toAbsolutePath().normalize();
    }

    /** Attaches the context of the current {@code session/prompt} turn. */
    void bind(SyncPromptContext ctx) {
        this.ctx = ctx;
    }

    // ---------------------------------------------------------------- tools

    @Tool(customName = "read_file")
    @LLMDescription("Read a text file from the project, as the IDE sees it (including unsaved edits). "
            + "Paths may be absolute or relative to the project root.")
    public String readFile(@LLMDescription("Path of the file to read") String path) {
        Path file = resolve(path);
        return report("Read " + display(file), ToolKind.READ, file, () -> {
            NegotiatedCapabilities caps = caps();
            if (caps != null && caps.supportsReadTextFile()) {
                return ctx.readFile(file.toString());
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        });
    }

    @Tool(customName = "read_file_with_line_numbers")
    @LLMDescription("Read a text file with 1-based line numbers in front of each line (\"12| code\"). "
            + "Use it before choosing a line or column range to highlight or step through. "
            + "The numbers and the \"| \" separator are not part of the file.")
    public String readFileWithLineNumbers(@LLMDescription("Path of the file to read") String path) {
        Path file = resolve(path);
        return report("Read " + display(file), ToolKind.READ, file, () -> {
            NegotiatedCapabilities caps = caps();
            String text = caps != null && caps.supportsReadTextFile()
                    ? ctx.readFile(file.toString())
                    : Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n|\r|\n", -1);
            int count = text.endsWith("\n") || text.endsWith("\r") ? lines.length - 1 : lines.length;
            int width = String.valueOf(Math.max(count, 1)).length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append(String.format("%" + width + "d| ", i + 1)).append(lines[i]).append('\n');
            }
            return sb.isEmpty() ? "(empty file)" : sb.toString();
        });
    }

    @Tool(customName = "write_file")
    @LLMDescription("Create or overwrite a text file in the project with the given full content. "
            + "Read the file first when changing an existing one, and always send the complete new content.")
    public String writeFile(
            @LLMDescription("Path of the file to write") String path,
            @LLMDescription("The complete new content of the file") String content) {
        Path file = resolve(path);
        return report("Write " + display(file), ToolKind.EDIT, file, () -> {
            NegotiatedCapabilities caps = caps();
            if (caps != null && caps.supportsWriteTextFile()) {
                ctx.writeFile(file.toString(), content);
            } else {
                if (!ctx.askPermission("Write " + file, ToolKind.EDIT)) {
                    return "The user declined writing " + file;
                }
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
            return "Wrote " + content.length() + " characters to " + file;
        });
    }

    @Tool(customName = "list_directory")
    @LLMDescription("List the files and folders in a project directory. Use \".\" for the project root.")
    public String listDirectory(@LLMDescription("Path of the directory to list") String path) {
        Path dir = resolve(path);
        return report("List " + display(dir), ToolKind.READ, dir, () -> {
            try (Stream<Path> entries = Files.list(dir)) {
                List<String> names = entries
                        .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : ""))
                        .sorted()
                        .toList();
                return names.isEmpty() ? "(empty directory)" : String.join("\n", names);
            }
        });
    }

    @Tool(customName = "run_command")
    @LLMDescription("Run a shell command in the project root using the IDE's terminal and return its output "
            + "and exit code. Use it to build, run tests, or inspect the project (e.g. git status).")
    public String runCommand(@LLMDescription("The shell command line to run") String command) {
        return report("Run: " + command, ToolKind.EXECUTE, null, () -> {
            NegotiatedCapabilities caps = caps();
            if (caps == null || !caps.supportsTerminal()) {
                return "The IDE did not offer a terminal to this agent, so commands cannot be run.";
            }
            Command cmd = (WINDOWS ? Command.of("cmd.exe", "/c", command) : Command.of("sh", "-c", command))
                    .cwd(cwd.toString())
                    .outputByteLimit(MAX_OUTPUT_CHARS);
            CommandResult result = ctx.execute(cmd);
            return "Exit code: " + result.exitCode()
                    + (result.truncated() ? " (output truncated)" : "")
                    + "\n" + result.output();
        });
    }

    // ---------------------------------------------------------------- MCP tool calls

    /** Names of the tools defined in this class (so MCP tools with the same name are skipped). */
    static final Set<String> OWN_TOOL_NAMES = Set.of("read_file", "read_file_with_line_numbers",
            "write_file", "list_directory", "run_command");

    /** Shows a call to a tool of an MCP server (e.g. the IDE's) in the chat, as it starts. */
    void mcpToolStarted(String toolCallId, String toolName, String argsJson) {
        SyncPromptContext c = ctx;
        if (c == null) return;
        c.sendSessionUpdate(new ToolCall(null, toolCallId, "IDE: " + toolName + " " + shorten(argsJson, 120),
                null, ToolKind.OTHER, ToolCallStatus.IN_PROGRESS, null, null, null, null, null));
    }

    /** Marks a call started by {@link #mcpToolStarted} as finished. */
    void mcpToolFinished(String toolCallId, boolean success, String detail) {
        SyncPromptContext c = ctx;
        if (c == null) return;
        var content = success ? null : List.<ToolCallContent>of(
                new ToolCallContentBlock("content", new TextContent(truncate(detail))));
        c.sendSessionUpdate(new ToolCallUpdateNotification(null, toolCallId, null, null, null,
                success ? ToolCallStatus.COMPLETED : ToolCallStatus.FAILED, content, null, null, null, null));
    }

    private static String shorten(String s, int max) {
        if (s == null || s.equals("{}")) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ---------------------------------------------------------------- helpers

    interface Action {
        String run() throws Exception;
    }

    /** Runs a tool action, reporting it to the client as a tool call (pending → completed/failed). */
    String report(String title, ToolKind kind, Path location, Action action) {
        SyncPromptContext c = ctx;
        String id = "tool-" + UUID.randomUUID();
        List<ToolCallLocation> locations = location == null ? null
                : List.of(new ToolCallLocation(location.toString(), null, null));
        c.sendSessionUpdate(new ToolCall(null, id, title, null, kind, ToolCallStatus.IN_PROGRESS,
                null, locations, null, null, null));
        String result;
        ToolCallStatus status;
        try {
            result = action.run();
            status = ToolCallStatus.COMPLETED;
        } catch (Exception e) {
            result = "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            status = ToolCallStatus.FAILED;
        }
        result = truncate(result);
        // Show command output in the chat; for file reads the location is enough.
        var content = kind == ToolKind.EXECUTE || status == ToolCallStatus.FAILED
                ? List.<ToolCallContent>of(
                        new ToolCallContentBlock("content", new TextContent(result)))
                : null;
        c.sendSessionUpdate(new ToolCallUpdateNotification(null, id, null, null, null, status,
                content, null, null, null, null));
        return result;
    }

    private NegotiatedCapabilities caps() {
        return ctx.getClientCapabilities();
    }

    private Path resolve(String path) {
        Path p = Path.of(path == null || path.isBlank() ? "." : path);
        return (p.isAbsolute() ? p : cwd.resolve(p)).normalize();
    }

    private String display(Path p) {
        return p.startsWith(cwd) && !p.equals(cwd) ? cwd.relativize(p).toString() : p.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_OUTPUT_CHARS ? s
                : s.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated, " + s.length() + " characters total)";
    }
}
