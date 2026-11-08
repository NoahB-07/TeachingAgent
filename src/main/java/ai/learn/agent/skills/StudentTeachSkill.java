package ai.learn.agent.skills;

/**
 * A skill that teaches the agent how to explain code to students.
 *
 * <p>This skill adds prompt instructions guiding the agent to explain code
 * for someone who knows the language syntax but needs help understanding the codebase.
 */
public final class StudentTeachSkill implements Skill {

    @Override
    public String name() {
        return "StudentTeach";
    }

    @Override
    public String instructions() {
        return """
                You have a Student level teach skill. When asked to explain at a Student level:
                - start by reading and understanding all code files not just the file asked about.
                - only assume the user knows the language and its syntax but nothing else.
                - look for key parts of the code in the file asked about.
                - write a few lines explaining the key parts code chunk by code chunk using the codebase's content.
                - always show the user the code lines as you are explaining, with syntax highlighting.
                - never create code only show code that is provided in the file asked about.
                """;
    }
}