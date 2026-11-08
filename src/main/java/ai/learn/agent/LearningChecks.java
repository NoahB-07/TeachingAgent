package ai.learn.agent;

import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonNull;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks explanation trees, walkthroughs and ranges against the learner's files before they are
 * sent to the plugin.
 *
 * <p>The plugin already rejects malformed JSON and bad syntax. What it can't know is whether a
 * range points at the code the model meant, and that is where most mistakes are (miscounted lines
 * and columns). So every range is checked against the file as the IDE has it, and the result
 * echoes the code each node or step actually lands on, which lets the model see its own mistakes.
 */
final class LearningChecks {

    /** Reads a project file's lines (as the IDE sees it); throws if there is no such file. */
    interface FileSource {
        List<String> lines(String path) throws Exception;

        /** Project-relative paths of files with the same name as {@code path}, to suggest when it isn't found. */
        default List<String> suggest(String path) {
            return List.of();
        }
    }

    /** Errors stop the call; warnings and the echo of highlighted code go back with the result. */
    static final class Report {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> echo = new ArrayList<>();
        final Set<String> ids = new LinkedHashSet<>();

        boolean ok() {
            return errors.isEmpty();
        }

        String errorText() {
            return "Nothing was shown. Fix these problems and send the whole JSON again:\n- "
                    + String.join("\n- ", errors);
        }

        /** The plugin's reply plus what the model should verify. */
        String summary(String pluginReply, String echoHeading) {
            StringBuilder sb = new StringBuilder(pluginReply == null || pluginReply.isBlank() ? "Done." : pluginReply);
            if (!echo.isEmpty()) {
                sb.append("\n\n").append(echoHeading).append('\n');
                int shown = Math.min(echo.size(), MAX_ECHO);
                for (int i = 0; i < shown; i++) sb.append("- ").append(echo.get(i)).append('\n');
                if (echo.size() > shown) sb.append("- ... and ").append(echo.size() - shown).append(" more\n");
                sb.append("If any of these isn't the code you meant, fix the range and send it again.");
            }
            if (!warnings.isEmpty()) {
                sb.append("\n\nWarnings:\n- ").append(String.join("\n- ", warnings));
            }
            return sb.toString();
        }
    }

    private static final int MAX_ECHO = 40;
    private static final int MAX_SNIPPET = 90;

    /** Same syntax as the plugin's CodeRange: [path@]line[:col][-line[:col]]. */
    private static final Pattern RANGE = Pattern.compile(
            "(?:(?<path>[^@]+)@)?(?<sl>\\d+)(?::(?<sc>\\d+))?(?:-(?<el>\\d+)(?::(?<ec>\\d+))?)?");
    /** Markdown links: [label](target). */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");

    private static final List<String> NODE_KINDS = List.of("note", "info", "tip", "warning", "question", "example");
    private static final List<String> STEP_KINDS = List.of("line", "call", "return", "error");

    private final FileSource files;
    private final Map<String, List<String>> cache = new HashMap<>();

    LearningChecks(FileSource files) {
        this.files = files;
    }

    // ---------------------------------------------------------------- single highlight

    /** Checks one highlight and the code links in its explanation. */
    Report checkHighlight(String path, String range, String explanation) {
        Report r = new Report();
        String snippet = checkRange(r, "range", range, path);
        if (snippet != null) r.echo.add(path + " " + range + ": " + snippet);
        checkLinks(r, "explanation", explanation, path, Set.of());
        return r;
    }

    // ---------------------------------------------------------------- explanation trees

    /**
     * Checks a tree. {@code loaded} are node ids sent earlier in this session (trees merge by id,
     * so links, {@code root} and {@code next} may point at them).
     */
    Report checkTree(String json, String showId, Set<String> loaded) {
        Report r = new Report();
        JsonObject tree = parseObject(r, json, "The explanation tree");
        if (tree == null) return r;

        String file = string(tree.get("file"));
        JsonObject nodes = tree.get("nodes") instanceof JsonObject o ? o : null;
        if (nodes == null || nodes.isEmpty()) {
            r.errors.add("The tree needs a \"nodes\" object with at least one node.");
            return r;
        }
        Set<String> known = new LinkedHashSet<>(loaded);
        known.addAll(nodes.keySet());
        Set<String> missingLinks = new LinkedHashSet<>();

        for (Map.Entry<String, JsonElement> e : nodes.entrySet()) {
            String id = e.getKey();
            r.ids.add(id);
            if (!(e.getValue() instanceof JsonObject node)) {
                r.errors.add("Node '" + id + "' must be an object.");
                continue;
            }
            String nodeFile = string(node.get("file")) != null ? string(node.get("file")) : file;
            JsonElement range = node.get("range");
            if (range == null || range instanceof JsonNull) {
                r.errors.add("Node '" + id + "' needs a \"range\", e.g. \"12\" or \"12-15\".");
            } else if (!(range instanceof JsonPrimitive p) || !p.isString()) {
                r.errors.add("Node '" + id + "': \"range\" must be a string, e.g. \"" + range + "\" (with quotes).");
            } else {
                String snippet = checkRange(r, "Node '" + id + "'", p.getContent(), nodeFile);
                if (snippet != null) r.echo.add(id + " (" + label(p.getContent(), nodeFile) + "): " + snippet);
            }

            String kind = string(node.get("kind"));
            if (kind != null && !NODE_KINDS.contains(kind)) {
                r.errors.add("Node '" + id + "': unknown kind '" + kind + "' (use " + String.join(", ", NODE_KINDS) + ").");
            }
            if ("question".equals(kind) && node.get("answer") == null) {
                r.warnings.add("Node '" + id + "' is a question without an \"answer\".");
            }
            String next = string(node.get("next"));
            if (next != null && !known.contains(next)) {
                r.errors.add("Node '" + id + "': \"next\" points at '" + next + "', which isn't defined.");
            }
            JsonElement also = node.get("also");
            if (also instanceof JsonArray list) {
                for (JsonElement a : list) {
                    String s = string(a);
                    if (s == null) r.errors.add("Node '" + id + "': every \"also\" entry must be a range string.");
                    else checkRange(r, "Node '" + id + "' also", s, nodeFile);
                }
            }
            missingLinks.addAll(checkLinks(r, "Node '" + id + "' text", string(node.get("text")), nodeFile, known));
            missingLinks.addAll(checkLinks(r, "Node '" + id + "' answer", string(node.get("answer")), nodeFile, known));
            if (node.get("hints") instanceof JsonArray hints) {
                for (JsonElement h : hints) {
                    missingLinks.addAll(checkLinks(r, "Node '" + id + "' hint", string(h), nodeFile, known));
                }
            }
        }

        String root = string(tree.get("root"));
        if (root != null && !known.contains(root)) {
            r.errors.add("\"root\" is '" + root + "', which isn't a node in this tree or one sent earlier.");
        }
        if (showId != null && !showId.isBlank() && !known.contains(showId)) {
            r.errors.add("show_id '" + showId + "' isn't a node in this tree or one sent earlier.");
        }
        if (!missingLinks.isEmpty()) {
            r.warnings.add("These links point at nodes you haven't defined: #" + String.join(", #", missingLinks)
                    + ". They won't open until you send a tree that defines them (trees merge by id).");
        }
        return r;
    }

    // ---------------------------------------------------------------- walkthroughs

    Report checkWalkthrough(String json) {
        Report r = new Report();
        JsonObject w = parseObject(r, json, "The walkthrough");
        if (w == null) return r;
        String file = string(w.get("file"));
        if (!(w.get("steps") instanceof JsonArray steps) || steps.isEmpty()) {
            r.errors.add("The walkthrough needs a \"steps\" array with at least one step.");
            return r;
        }
        for (int i = 0; i < steps.size(); i++) {
            String where = "Step " + (i + 1);
            if (!(steps.get(i) instanceof JsonObject step)) {
                r.errors.add(where + " must be an object.");
                continue;
            }
            String stepFile = string(step.get("file")) != null ? string(step.get("file")) : file;
            if (stepFile == null) {
                r.errors.add(where + ": no \"file\" (set it on the walkthrough or the step).");
                continue;
            }
            JsonElement line = step.get("line");
            String lineText = line instanceof JsonPrimitive p ? p.getContent() : null;
            if (lineText == null || !lineText.matches("\\s*\\d+\\s*(-\\s*\\d+\\s*)?")) {
                r.errors.add(where + " needs a \"line\": a number like 8 or a string like \"8-12\".");
            } else {
                String snippet = checkRange(r, where, lineText.replace(" ", ""), stepFile);
                String kind = string(step.get("kind"));
                if (snippet != null) {
                    r.echo.add(where + " (line " + lineText.trim() + (kind != null ? ", " + kind : "") + "): " + snippet);
                }
            }
            String kind = string(step.get("kind"));
            if (kind != null && !STEP_KINDS.contains(kind)) {
                r.errors.add(where + ": unknown kind '" + kind + "' (use line, call, return or error).");
            }
            JsonElement vars = step.get("vars");
            if (vars != null && !(vars instanceof JsonObject)) {
                r.errors.add(where + ": \"vars\" must be an object of display strings, e.g. {\"i\": \"1\"}.");
            }
            if (step.get("highlight") instanceof JsonArray hl) {
                for (JsonElement h : hl) checkRange(r, where + " highlight", string(h), stepFile);
            }
            if (step.get("replace") instanceof JsonObject rep) {
                for (String key : rep.keySet()) checkRange(r, where + " replace", key, stepFile);
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- ranges

    /**
     * Checks a range against its file. Returns the code it covers (shortened), or null after
     * adding an error.
     */
    String checkRange(Report r, String where, String range, String defaultFile) {
        Matcher m = range == null ? null : RANGE.matcher(range.trim());
        if (m == null || !m.matches()) {
            r.errors.add(where + ": invalid range '" + range + "' (use \"12\", \"12-15\", \"12:5-15:20\" or \"File.java@3-5\").");
            return null;
        }
        String file = m.group("path") != null ? m.group("path").trim() : defaultFile;
        if (file == null || file.isBlank()) {
            r.errors.add(where + ": no file for range '" + range + "' (set \"file\").");
            return null;
        }
        List<String> lines;
        try {
            lines = lines(file);
        } catch (Exception e) {
            r.errors.add(where + ": no file at '" + file + "'. " + suggestion(file));
            return null;
        }
        int sl = Integer.parseInt(m.group("sl"));
        int el = m.group("el") != null ? Integer.parseInt(m.group("el")) : sl;
        Integer sc = m.group("sc") != null ? Integer.parseInt(m.group("sc")) : null;
        Integer ec = m.group("ec") != null ? Integer.parseInt(m.group("ec")) : null;
        int n = lines.size();
        if (sl < 1 || el > n || el < sl) {
            r.errors.add(where + ": range '" + range + "' is outside " + file + ", which has " + n + " lines.");
            return null;
        }
        String first = lines.get(sl - 1);
        String last = lines.get(el - 1);
        if (sc != null && (sc < 1 || sc > first.length() + 1)) {
            r.errors.add(where + ": column " + sc + " is past the end of line " + sl + " (" + first.length()
                    + " characters): \"" + shorten(first) + "\". Column 1 is the first character, indentation included.");
            return null;
        }
        if (ec != null && (ec < 1 || ec > last.length())) {
            r.errors.add(where + ": column " + ec + " is past the end of line " + el + " (" + last.length()
                    + " characters): \"" + shorten(last) + "\". The end column is inclusive.");
            return null;
        }
        if (sl == el && sc != null && ec != null && ec < sc) {
            r.errors.add(where + ": range '" + range + "' ends before it starts.");
            return null;
        }
        if (sl == el && sc != null && ec != null) {
            return "`" + shorten(first.substring(sc - 1, ec)) + "`";
        }
        if (sc != null && ec == null && sl == el) {
            return "`" + shorten(first.substring(sc - 1)) + "`";
        }
        String head = "`" + shorten(first.strip()) + "`";
        if (sl == el) return head;
        return head + " ... `" + shorten(last.strip()) + "` (" + (el - sl + 1) + " lines)";
    }

    /** Checks the code links in some Markdown; returns the #ids it links to that aren't known. */
    private Set<String> checkLinks(Report r, String where, String markdown, String file, Set<String> known) {
        Set<String> missing = new LinkedHashSet<>();
        if (markdown == null) return missing;
        Matcher m = LINK.matcher(markdown);
        while (m.find()) {
            String target = m.group(2);
            if (target.startsWith("#")) {
                String id = target.substring(1);
                if (!known.contains(id)) missing.add(id);
            } else if (!target.contains("://") && !target.startsWith("mailto:")) {
                checkRange(r, where + " link [" + m.group(1) + "]", target, file);
            }
        }
        return missing;
    }

    // ---------------------------------------------------------------- helpers

    /** "Did you mean ..." for a missing file, so the model can fix the path instead of giving up. */
    private String suggestion(String file) {
        List<String> found;
        try {
            found = files.suggest(file);
        } catch (RuntimeException e) {
            found = List.of();
        }
        if (found.isEmpty()) {
            return "Paths are relative to the project root (e.g. \"src/main/java/pkg/Foo.java\"). "
                    + "Use find_files to look it up.";
        }
        if (found.size() == 1) {
            return "Did you mean \"" + found.get(0) + "\"? Paths are relative to the project root.";
        }
        return "Files with that name: \"" + String.join("\", \"", found.subList(0, Math.min(found.size(), 5)))
                + "\". Paths are relative to the project root.";
    }

    private List<String> lines(String file) throws Exception {
        List<String> cached = cache.get(file);
        if (cached == null) {
            cached = files.lines(file);
            cache.put(file, cached);
        }
        return cached;
    }

    /** Splits text into lines the way the editor numbers them. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r\n|\r|\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // a trailing newline doesn't start a line you can point at
        }
        return lines;
    }

    private static JsonObject parseObject(Report r, String json, String what) {
        if (json == null || json.isBlank()) {
            r.errors.add(what + " is empty.");
            return null;
        }
        try {
            JsonElement parsed = Json.Default.parseToJsonElement(json);
            if (parsed instanceof JsonObject o) return o;
            r.errors.add(what + " must be a JSON object ({...}).");
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            r.errors.add(what + " is not valid JSON: " + msg
                    + " (use double quotes, no trailing commas or comments, and \\\" / \\n inside strings).");
        }
        return null;
    }

    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getContent() : null;
    }

    private static String label(String range, String file) {
        return range.contains("@") || file == null ? range : file + " " + range;
    }

    private static String shorten(String s) {
        return s.length() <= MAX_SNIPPET ? s : s.substring(0, MAX_SNIPPET - 3) + "...";
    }
}
