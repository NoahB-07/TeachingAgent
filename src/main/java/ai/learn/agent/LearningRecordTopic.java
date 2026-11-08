package ai.learn.agent;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import ai.koog.agents.core.tools.annotations.Tool;
import ai.koog.agents.core.tools.reflect.ToolSet;

import java.util.List;

/**
 * Tools that let the LLM manage the teaching memory: record taught topics and check what is already known.
 *
 * <p>This is the "LLM-driven" half of the hybrid approach: after explaining a concept,
 * the LLM calls the record tools so the agent remembers it in future sessions.
 * The LLM can also check what the user already knows before teaching, to avoid re-teaching.
 * The other half is auto-logging in {@link LearningTools} after every explanation tree or walkthrough.
 */
@LLMDescription("Learning memory tools: record what you have taught and check what the user already knows.")
public final class LearningRecordTopic implements ToolSet {

    private final TeachingProfile profile;

    public LearningRecordTopic(TeachingProfile profile) {
        this.profile = profile;
    }

    @Tool(customName = "learning_record_topic")
    @LLMDescription("Record that the learner has been taught a topic. "
            + "Call this after explaining a concept so you remember next time. "
            + "The topic label should be short (e.g. \"for-loops\", \"recursion\"). "
            + "The title is a human-readable description (e.g. \"For loops in Java\"). "
            + "The file is the project-relative path that was explained, or empty if no specific file.")
    public String recordTopic(
            @LLMDescription("Short label, e.g. \"for-loops\", \"recursion\"") String topic,
            @LLMDescription("Human-readable title, e.g. \"For loops in Java\"") String title,
            @LLMDescription("Project-relative file path that was explained, or \"\" if none") String file) {
        String resolvedFile = (file != null && !file.isBlank()) ? file : null;
        profile.addTopic(topic, title, resolvedFile, List.of());
        return "Recorded topic: " + topic + (title != null && !title.equals(topic) ? " (" + title + ")" : "");
    }

    @Tool(customName = "learning_record_topics")
    @LLMDescription("Record multiple topics at once after a complex explanation. "
            + "Use this when you have taught several related concepts in one session.")
    public String recordTopics(
            @LLMDescription("Comma-separated short labels, e.g. \"for-loops,variables,conditionals\"") String topics,
            @LLMDescription("Project-relative file path that was explained, or \"\" if none") String file) {
        String resolvedFile = (file != null && !file.isBlank()) ? file : null;
        String[] parts = topics.split(",");
        int count = 0;
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty()) {
                profile.addTopic(t, t, resolvedFile, List.of());
                count++;
            }
        }
        return "Recorded " + count + " topic" + (count == 1 ? "" : "s") + ": " + topics;
    }

    @Tool(customName = "learning_check_known_topics")
    @LLMDescription("Check what the user has already been taught. "
            + "Call this BEFORE teaching a new topic to avoid re-teaching something the user already knows. "
            + "Returns a list of topics the user already knows, or says they know nothing yet. "
            + "If the user already knows the topic you plan to teach, skip it and move on to what they don't know.")
    public String checkKnownTopics() {
        return profile.knownTopicsSummary();
    }

    @Tool(customName = "learning_is_topic_known")
    @LLMDescription("Check whether a specific topic has already been taught to the user. "
            + "Returns true if the user already knows this topic, false otherwise. "
            + "Use this to quickly check before teaching a specific concept. "
            + "The topic parameter should be a short label like \"for-loops\", \"recursion\", \"variables\".")
    public String isTopicKnown(
            @LLMDescription("Short topic label to check, e.g. \"for-loops\", \"recursion\", \"variables\"") String topic) {
        boolean known = profile.isKnownTopic(topic);
        if (known) {
            return "Yes, the user already knows about: " + topic + ". Do NOT re-teach this topic unless explicitly asked.";
        } else {
            return "No, the user has not been taught: " + topic + ". You are free to teach this topic.";
        }
    }

    @Tool(customName = "learning_is_file_explained")
    @LLMDescription("Check whether a specific file has already been explained to the user. "
            + "Returns true if the file was already explained, false otherwise. "
            + "Use this to avoid re-explaining the same file unless the user asks.")
    public String isFileExplained(
            @LLMDescription("Project-relative file path to check, e.g. \"src/Main.java\"") String file) {
        boolean discussed = profile.isFileDiscussed(file);
        if (discussed) {
            return "Yes, this file was already explained: " + file + ". Avoid re-explaining it unless asked.";
        } else {
            return "No, this file has not been explained yet: " + file + ". You are free to explain it.";
        }
    }
}
