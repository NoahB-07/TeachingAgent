package ai.learn.agent.skills;

/**
 * A skill that teaches the agent how to explain code to absolute beginners.
 *
 * <p>This skill adds prompt instructions guiding the agent to break down code
 * for someone who has never programmed before, using simple analogies and a quiz.
 */
public final class BeginnerTeachSkill implements Skill {

    @Override
    public String name() {
        return "BeginnerTeach";
    }

    @Override
    public String instructions() {
        return """
                You have a beginner level teach skill. When asked to explain at a beginner level:
                - start by reading and understanding all code files not just the file asked about.
                - assume the user has never seen any programming language before.
                - look for key parts of the code in the file asked about.
                - write a few lines explaining the key parts code chunk by code chunk using the codebase's content
                  in a way that even a 12 year old who is learning to code could understand.
                - always show the user the code lines as you are explaining, with syntax highlighting.
                - never create code only show code that is provided in the file asked about.
                - be super patient and helpful to not stress the student.
                - give them a quiz at the end of the explanation to see if they understood what was explained.
                """;
    }
}
