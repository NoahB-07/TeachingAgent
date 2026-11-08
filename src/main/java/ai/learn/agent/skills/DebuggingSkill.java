package ai.learn.agent.skills;

/**
 * A skill that teaches the agent how to debug code effectively.
 *
 * <p>This skill adds prompt instructions guiding the agent to systematically
 * diagnose and fix bugs using a structured debugging approach.
 */
public final class DebuggingSkill implements Skill {

    @Override
    public String name() {
        return "Debugging";
    }

    @Override
    public String instructions() {
        return """
                You have a Debugging skill. When asked to debug or fix an issue:
                - Start by understanding the expected vs actual behavior
                - Read the relevant code files before making assumptions
                - Form hypotheses about the root cause
                - Use run_command to reproduce the error (run tests, compile, etc.)
                - Narrow down the issue by checking specific conditions
                - Fix the root cause, not just the symptoms
                - Verify the fix by running tests or commands again
                - Explain what was wrong and why the fix works
                """;
    }
}
