package ai.learn.agent.skills;

/**
 * A skill that teaches the agent how to explain code to experienced engineers.
 *
 * <p>This skill adds prompt instructions guiding the agent to explain code
 * for a professional developer who is unfamiliar with the current codebase.
 */
public final class EngineerTeachSkill implements Skill {

    @Override
    public String name() {
        return "EngineerTeach";
    }

    @Override
    public String instructions() {
        return """
                You have an engineer level teach skill. When asked to explain at an engineer level:
                - start by reading and understanding all code files not just the file asked about.
                - assume the user is a professional software developer but doesn't know anything about the current codebase.
                - look for key parts of the code in the file asked about.
                - write a few lines explaining the key parts code chunk by code chunk using the codebase's content.
                - always show the user the code lines as you are explaining, with syntax highlighting.
                - never create code only show code that is provided in the file asked about.
                - be very direct just explain what is happening in context to the whole codebase not what everything is doing.
                """;
    }
}