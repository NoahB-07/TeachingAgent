package ai.learn.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What kind of answer a learner's message needs, decided in code rather than left to the model.
 *
 * <p>The system prompt asks the model to show code in the editor, but GPT-4o tends to reply with a
 * prose summary unless the message itself says to use the editor. So each message is classified
 * here and gets a short directive attached to the text the model sees ({@link #directive}), which
 * names the tool to finish with. The agent loop then checks that a learning tool was actually used
 * ({@link #MARKER}).
 */
enum TeachingIntent {

    /** Explain, describe, "how does X work": an explanation tree. */
    EXPLAIN(LearningTools.SHOW_TREE, """
            show it in the learner's editor as an explanation tree the learner explores by clicking. \
            The root node says in 1-3 sentences what the code is for, with inline links on the words \
            that name each of its 2-5 parts, e.g. "It [reads the file](#read), then [counts the \
            lines](#count)." Each part's node explains that part and links on, the same way, to the \
            ideas it uses (a method it calls, a variable, a concept), 2-3 levels deep. Every node is \
            reached by a link in the text; don't chain nodes with "next" (that is only for a guided \
            tour the learner asked for)"""),
    /** "Walk me through", "what does it print": a walkthrough. */
    WALKTHROUGH(LearningTools.PLAY_WALKTHROUGH, """
            show it in the learner's editor as a step-by-step walkthrough, with variables, call stack \
            and output from a real trace of the code"""),
    /** "Where is X", "which line": a highlight. */
    LOCATE(LearningTools.HIGHLIGHT, """
            highlight that code in the learner's editor with a one-sentence note"""),
    /** "Quiz me": a tree of question nodes. */
    QUIZ(LearningTools.SHOW_TREE, """
            show a quiz in the learner's editor: an explanation tree whose root introduces the code \
            in a sentence or two and links inline to 2-4 "kind": "question" nodes on the relevant \
            code (e.g. "Try [what the loop prints](#q1) and [when it stops](#q2)."), each with 1-3 \
            hints and an answer; hints and answers can link to nodes that explain the idea"""),
    /** Greetings, thanks, "what can you do": a chat reply. */
    CHAT(null, null);

    /** Starts every directive; the agent loop looks for it to know the editor must be used. */
    static final String MARKER = "[Air instruction, not written by the learner:";

    private final String tool;
    private final String what;

    TeachingIntent(String tool, String what) {
        this.tool = tool;
        this.what = what;
    }

    private static final Pattern CHAT_ONLY = Pattern.compile(
            "^(hi|hello|hey|yo|thanks|thank you|thx|cheers|ok|okay|cool|great|nice|bye|good (morning|afternoon|evening))\\b[\\s!.,?]*$"
                    + "|\\b(what can you do|who are you|what are you|how do i use you|what do you do)\\b");
    private static final Pattern QUIZ_WORDS = Pattern.compile(
            "\\bquiz\\b|\\btest (me|my)\\b|\\bcheck (that )?(i|my) (understand|understanding|know)\\b|\\bask me (some )?questions\\b");
    private static final Pattern WALKTHROUGH_WORDS = Pattern.compile(
            "\\bwalk (me )?through\\b|\\bwalkthrough\\b|\\bstep through\\b|\\bstep[- ]by[- ]step run\\b|\\btrace\\b"
                    + "|\\bline by line\\b|\\bwhat happens when\\b|\\bwhat (does|would|will) .{0,80}\\b(print|output|return)\\b"
                    + "|\\bwhat(?:'s| is) the (value|output)\\b|\\bwhen (it|this|the program|the code) runs\\b"
                    + "|\\brun through\\b|\\bin what order\\b");
    private static final Pattern LOCATE_WORDS = Pattern.compile(
            "^where\\b|\\bwhere (is|are|do|does|did)\\b|\\bwhich (line|lines|file|files|method|class)\\b"
                    + "|\\bshow me where\\b|\\bpoint (me )?(to|at)\\b|\\bfind (me )?the\\b");

    /** Classifies a learner's message. Anything that isn't small talk is about code. */
    static TeachingIntent classify(String message) {
        String m = message == null ? "" : message.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (m.isEmpty() || CHAT_ONLY.matcher(m).find()) return CHAT;
        if (QUIZ_WORDS.matcher(m).find()) return QUIZ;
        if (WALKTHROUGH_WORDS.matcher(m).find()) return WALKTHROUGH;
        if (LOCATE_WORDS.matcher(m).find()) return LOCATE;
        return EXPLAIN;
    }

    /** True if this kind of answer has to end with something shown in the editor. */
    boolean needsEditor() {
        return tool != null;
    }

    /** The text appended to the learner's message, or "" for small talk. */
    String directive() {
        if (!needsEditor()) return "";
        return "\n\n" + MARKER + " answer this by showing it in the editor. First find the code it is about "
                + "(search_code / find_files) and read it with read_file_with_line_numbers. Then " + what
                + ", by calling " + tool + ". Then reply in the chat in 1-3 sentences saying what you showed "
                + "and where: no summary of the code, no code blocks, no JSON. Only if this turns out not to "
                + "be about code in this project, answer briefly in the chat instead.]";
    }

    /** What to tell the model when it answered without showing anything. */
    String reminder() {
        return "You answered without showing anything in the learner's editor, so they saw only text. "
                + "Do it now: find and read the relevant code, then " + what + " by calling " + tool
                + ". Then reply in 1-3 sentences about what you showed. If the question really isn't about "
                + "code in this project, repeat your answer briefly.";
    }
}
