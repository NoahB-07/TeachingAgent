package ai.learn.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent record of what the learner has already been taught.
 *
 * <p>Stored as JSON in {@code .teaching/profile.json} inside the project root.
 * Topics older than one week are automatically expired on load and on save.
 *
 * <p>Two recording modes (hybrid approach):
 * <ul>
 *   <li><b>Auto-log</b> — after every {@code showExplanationTree} or {@code playWalkthrough},
 *       the agent extracts node titles and file paths and appends them automatically.</li>
 *   <li><b>LLM-driven</b> — the LLM can call {@code learning_record_topic} to explicitly
 *       label a topic after teaching it.</li>
 * </ul>
 */
public final class TeachingProfile {

    /** Topics taught more than this many days ago are expired on load/save. */
    static final long EXPIRE_DAYS = 7;

    private static final String PROFILE_FILE = ".teaching/profile.json";

    /** A single teaching record. */
    public static final class Topic {
        /** Short label, e.g. "for-loops", "recursion". */
        public String topic;
        /** Human-readable title, e.g. "For loops in Java". */
        public String title;
        /** Project-relative file path that was explained. */
        public String file;
        /** ISO-8601 timestamp when the topic was taught. */
        public String taughtAt;
        /** Node ids from the explanation tree, if any. */
        public List<String> nodes;

        Topic(String topic, String title, String file, String taughtAt, List<String> nodes) {
            this.topic = topic;
            this.title = title;
            this.file = file;
            this.taughtAt = taughtAt;
            this.nodes = nodes;
        }
    }

    /** All taught topics (may contain expired entries until next save). */
    private final List<Topic> topics = new ArrayList<>();
    /** Flat set of high-level concept labels. */
    private final Set<String> concepts = new java.util.LinkedHashSet<>();
    /** Files that have been explained. */
    private final Set<String> filesDiscussed = new java.util.LinkedHashSet<>();

    private final Path profilePath;

    TeachingProfile(Path projectRoot) {
        this.profilePath = projectRoot.resolve(PROFILE_FILE);
    }

    // ---------------------------------------------------------------- load / save

    /** Load the profile from disk, expiring stale entries. Returns a new empty profile if the file doesn't exist. */
    static TeachingProfile load(Path projectRoot) {
        TeachingProfile profile = new TeachingProfile(projectRoot);
        if (!Files.exists(profile.profilePath)) {
            return profile;
        }
        try {
            String json = Files.readString(profile.profilePath, StandardCharsets.UTF_8);
            profile.deserialize(json);
            profile.expireStale();
        } catch (IOException e) {
            System.err.println("[teaching-profile] Failed to load profile: " + e.getMessage());
        }
        return profile;
    }

    void save() {
        expireStale();
        try {
            Path parent = profilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(profilePath, serialize(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[teaching-profile] Failed to save profile: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- mutation

    /** Add a topic record (auto-log or explicit). */
    public void addTopic(String topic, String title, String file, List<String> nodes) {
        topics.add(new Topic(topic, title, file, Instant.now().toString(),
                nodes != null ? new ArrayList<>(nodes) : List.of()));
        if (topic != null && !topic.isBlank()) {
            concepts.add(topic);
        }
        if (file != null && !file.isBlank()) {
            filesDiscussed.add(file);
        }
    }

    /** Add an explicit concept label (LLM-driven). */
    public void addConcept(String concept, String file) {
        if (concept != null && !concept.isBlank()) {
            concepts.add(concept);
        }
        if (file != null && !file.isBlank()) {
            filesDiscussed.add(file);
        }
        topics.add(new Topic(concept, concept, file, Instant.now().toString(), List.of()));
    }

    // ---------------------------------------------------------------- lookup

    /**
     * Check whether a topic label is already known (taught recently).
     * Compares case-insensitively and checks both topic labels and concept labels.
     */
    public boolean isKnownTopic(String topic) {
        if (topic == null || topic.isBlank()) return false;
        String lower = topic.toLowerCase(java.util.Locale.ROOT);
        for (Topic t : topics) {
            if (t.topic != null && t.topic.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                return true;
            }
        }
        for (String c : concepts) {
            if (c.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a file has already been explained.
     */
    public boolean isFileDiscussed(String file) {
        if (file == null || file.isBlank()) return false;
        return filesDiscussed.contains(file);
    }

    /**
     * Returns a summary of known topics for the LLM to check before teaching.
     * Returns an empty string if nothing is known.
     */
    public String knownTopicsSummary() {
        if (topics.isEmpty()) {
            return "The user has not been taught any topics yet. You are free to teach anything.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("The user already knows about:\n");
        for (Topic t : topics) {
            String when = relativeTime(t.taughtAt);
            sb.append("- ").append(t.title != null ? t.title : t.topic);
            if (t.file != null && !t.file.isBlank()) {
                sb.append(" (in ").append(t.file).append(")");
            }
            sb.append(" — taught ").append(when);
            if (t.nodes != null && !t.nodes.isEmpty()) {
                sb.append(", covered: ").append(String.join(", ", t.nodes));
            }
            sb.append('\n');
        }
        if (!filesDiscussed.isEmpty()) {
            sb.append("\nFiles already explained: ").append(String.join(", ", filesDiscussed)).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- expiration

    /** Remove topics older than {@link #EXPIRE_DAYS}. */
    private void expireStale() {
        Instant cutoff = Instant.now().minus(EXPIRE_DAYS, ChronoUnit.DAYS);
        topics.removeIf(t -> {
            try {
                return Instant.parse(t.taughtAt).isBefore(cutoff);
            } catch (Exception e) {
                return false; // keep unparseable dates
            }
        });
        // Rebuild concepts/files from remaining topics
        concepts.clear();
        filesDiscussed.clear();
        for (Topic t : topics) {
            if (t.topic != null && !t.topic.isBlank()) {
                concepts.add(t.topic);
            }
            if (t.file != null && !t.file.isBlank()) {
                filesDiscussed.add(t.file);
            }
        }
    }

    // ---------------------------------------------------------------- system prompt injection

    /**
     * Returns a snippet for the system prompt describing what the user already knows.
     * Returns an empty string if the profile is empty.
     */
    public String systemPromptSnippet() {
        if (topics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("The user has previously learned about these topics (from recent sessions). ");
        sb.append("Do NOT re-teach these topics unless the user explicitly asks for a refresher. ");
        sb.append("If the user asks about something you have already taught, briefly acknowledge it ");
        sb.append("and move on to what they don't know yet. Build on what they already know.\n");
        for (Topic t : topics) {
            String when = relativeTime(t.taughtAt);
            sb.append("- ").append(t.title != null ? t.title : t.topic);
            if (t.file != null && !t.file.isBlank()) {
                sb.append(" (in ").append(t.file).append(")");
            }
            sb.append(" — ").append(when);
            if (t.nodes != null && !t.nodes.isEmpty()) {
                sb.append(", covered: ").append(String.join(", ", t.nodes));
            }
            sb.append('\n');
        }
        if (!filesDiscussed.isEmpty()) {
            sb.append("\nFiles already explained: ").append(String.join(", ", filesDiscussed)).append('\n');
        }
        sb.append("\nBefore teaching a new topic, use learning_check_known_topics to verify ");
        sb.append("the user does not already know it. Skip re-teaching already-known material.\n");
        return sb.toString();
    }

    private static String relativeTime(String iso) {
        try {
            Instant instant = Instant.parse(iso);
            long days = ChronoUnit.DAYS.between(instant, Instant.now());
            if (days == 0) return "today";
            if (days == 1) return "yesterday";
            return days + " days ago";
        } catch (Exception e) {
            return iso;
        }
    }

    // ---------------------------------------------------------------- JSON serialization (manual, no external library)

    private void deserialize(String json) {
        // Simple manual JSON parsing for our known structure
        topics.clear();
        concepts.clear();
        filesDiscussed.clear();

        JsonObject root = parseObject(json.trim());
        JsonArray topicArray = root.getArray("topics");
        if (topicArray != null) {
            for (JsonObject tObj : topicArray.objects()) {
                String topic = tObj.getString("topic");
                String title = tObj.getString("title");
                String file = tObj.getString("file");
                String taughtAt = tObj.getString("taughtAt");
                List<String> nodes = tObj.getStringList("nodes");
                topics.add(new Topic(topic, title, file, taughtAt, nodes));
            }
        }
        JsonArray conceptArray = root.getArray("concepts");
        if (conceptArray != null) {
            for (String c : conceptArray.strings()) {
                concepts.add(c);
            }
        }
        JsonArray fileArray = root.getArray("filesDiscussed");
        if (fileArray != null) {
            for (String f : fileArray.strings()) {
                filesDiscussed.add(f);
            }
        }
    }

    private String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // topics
        sb.append("  \"topics\": [");
        for (int i = 0; i < topics.size(); i++) {
            Topic t = topics.get(i);
            if (i > 0) sb.append(",");
            sb.append('\n').append("    {");
            sb.append("\"topic\":\"").append(escape(t.topic)).append("\",");
            sb.append("\"title\":\"").append(escape(t.title != null ? t.title : "")).append("\",");
            sb.append("\"file\":\"").append(escape(t.file != null ? t.file : "")).append("\",");
            sb.append("\"taughtAt\":\"").append(t.taughtAt).append("\"");
            if (t.nodes != null && !t.nodes.isEmpty()) {
                sb.append(",\"nodes\":[");
                for (int j = 0; j < t.nodes.size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append("\"").append(escape(t.nodes.get(j))).append("\"");
                }
                sb.append("]");
            }
            sb.append("}");
        }
        sb.append("\n  ],\n");

        // concepts
        sb.append("  \"concepts\": [");
        int i = 0;
        for (String c : concepts) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(c)).append("\"");
            i++;
        }
        sb.append("],\n");

        // filesDiscussed
        sb.append("  \"filesDiscussed\": [");
        i = 0;
        for (String f : filesDiscussed) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(f)).append("\"");
            i++;
        }
        sb.append("]\n");

        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ---------------------------------------------------------------- minimal JSON parser

    /** Bare-bones JSON parser for our profile format. Handles nested objects and arrays of primitives. */
    private static abstract class JsonElement {}

    private static class JsonPrimitive extends JsonElement {
        final String value;
        JsonPrimitive(String value) { this.value = value; }
    }

    private static class JsonObject extends JsonElement {
        private final Map<String, JsonElement> map = new LinkedHashMap<>();

        JsonObject(Map<String, JsonElement> map) { this.map.putAll(map); }

        String getString(String key) {
            JsonElement e = map.get(key);
            if (e instanceof JsonPrimitive) return ((JsonPrimitive) e).value;
            return null;
        }

        JsonArray getArray(String key) {
            JsonElement e = map.get(key);
            if (e instanceof JsonArray) return (JsonArray) e;
            return null;
        }

        List<String> getStringList(String key) {
            JsonArray a = getArray(key);
            if (a == null) return List.of();
            return a.strings();
        }

        /** Returns all JsonObject elements inside any JsonArray values in this object. */
        List<JsonObject> objects() {
            List<JsonObject> result = new ArrayList<>();
            for (JsonElement e : map.values()) {
                if (e instanceof JsonArray) {
                    JsonArray arr = (JsonArray) e;
                    for (JsonElement item : arr.elements) {
                        if (item instanceof JsonObject) {
                            result.add((JsonObject) item);
                        }
                    }
                }
            }
            return result;
        }
    }

    private static class JsonArray extends JsonElement {
        final List<JsonElement> elements = new ArrayList<>();

        JsonArray(List<JsonElement> elements) { this.elements.addAll(elements); }

        List<String> strings() {
            List<String> result = new ArrayList<>();
            for (JsonElement e : elements) {
                if (e instanceof JsonPrimitive) {
                    String v = ((JsonPrimitive) e).value;
                    if (v != null) result.add(v);
                }
            }
            return result;
        }

        List<JsonObject> objects() {
            List<JsonObject> result = new ArrayList<>();
            for (JsonElement e : elements) {
                if (e instanceof JsonObject) {
                    result.add((JsonObject) e);
                }
            }
            return result;
        }
    }

    /**
     * Parse a JSON string into a JsonObject. Minimal parser: handles objects, arrays, strings, numbers, booleans, null.
     */
    private static JsonObject parseObject(String json) {
        int[] pos = {0};
        json = json.trim();
        JsonElement elem = parseValue(json, pos);
        if (!(elem instanceof JsonObject)) {
            throw new IllegalArgumentException("Expected JSON object at top level");
        }
        return (JsonObject) elem;
    }

    private static JsonElement parseValue(String s, int[] p) {
        skipWhitespace(s, p);
        if (p[0] >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = s.charAt(p[0]);
        if (c == '{') return parseObj(s, p);
        if (c == '[') return parseArr(s, p);
        if (c == '"') return parseStr(s, p);
        if (c == 't' || c == 'f') return parseBool(s, p);
        if (c == 'n') return parseNull(s, p);
        return parseNum(s, p);
    }

    private static JsonObject parseObj(String s, int[] p) {
        p[0]++; // skip '{'
        Map<String, JsonElement> map = new LinkedHashMap<>();
        skipWhitespace(s, p);
        if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return new JsonObject(map); }
        while (true) {
            skipWhitespace(s, p);
            JsonElement keyElem = parseValue(s, p);
            if (!(keyElem instanceof JsonPrimitive)) throw new IllegalArgumentException("Object key must be string");
            String key = ((JsonPrimitive) keyElem).value;
            skipWhitespace(s, p);
            if (p[0] >= s.length() || s.charAt(p[0]) != ':') throw new IllegalArgumentException("Expected ':'");
            p[0]++;
            JsonElement value = parseValue(s, p);
            map.put(key, value);
            skipWhitespace(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
            break;
        }
        if (p[0] >= s.length() || s.charAt(p[0]) != '}') throw new IllegalArgumentException("Expected '}'");
        p[0]++;
        return new JsonObject(map);
    }

    private static JsonArray parseArr(String s, int[] p) {
        p[0]++; // skip '['
        List<JsonElement> list = new ArrayList<>();
        skipWhitespace(s, p);
        if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return new JsonArray(list); }
        while (true) {
            list.add(parseValue(s, p));
            skipWhitespace(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
            break;
        }
        if (p[0] >= s.length() || s.charAt(p[0]) != ']') throw new IllegalArgumentException("Expected ']'");
        p[0]++;
        return new JsonArray(list);
    }

    private static JsonPrimitive parseStr(String s, int[] p) {
        p[0]++; // skip opening '"'
        StringBuilder sb = new StringBuilder();
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (c == '"') { p[0]++; return new JsonPrimitive(sb.toString()); }
            if (c == '\\') {
                p[0]++;
                if (p[0] >= s.length()) break;
                char esc = s.charAt(p[0]);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(esc); break;
                }
            } else {
                sb.append(c);
            }
            p[0]++;
        }
        return new JsonPrimitive(sb.toString());
    }

    private static JsonPrimitive parseNum(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                p[0]++;
            } else break;
        }
        return new JsonPrimitive(s.substring(start, p[0]));
    }

    private static JsonPrimitive parseBool(String s, int[] p) {
        if (s.startsWith("true", p[0])) { p[0] += 4; return new JsonPrimitive("true"); }
        if (s.startsWith("false", p[0])) { p[0] += 5; return new JsonPrimitive("false"); }
        throw new IllegalArgumentException("Invalid boolean");
    }

    private static JsonPrimitive parseNull(String s, int[] p) {
        if (s.startsWith("null", p[0])) { p[0] += 4; return new JsonPrimitive(null); }
        throw new IllegalArgumentException("Invalid null");
    }

    private static void skipWhitespace(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }
}
