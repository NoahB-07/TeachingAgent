package ai.learn.agent.skills;

/**
 * A skill that teaches the agent how to perform code reviews.
 *
 * <p>This skill adds prompt instructions guiding the agent to review code
 * for common issues like bugs, style violations, and performance problems.
 * It provides no additional tools — the agent uses existing file-reading tools.
 */
public final class CodeReviewSkill implements Skill {

    @Override
    public String name() {
        return "Code Review";
    }

    @Override
    public String instructions() {
        return """
                You have a Code Review skill. When asked to review code:
                - Read the file first, then analyze it section by section
                - Look for bugs, edge cases, and logic errors
                - Check for style inconsistencies and naming issues
                - Identify performance problems or resource leaks
                - Suggest concrete improvements with code examples
                - Prioritize findings: Critical > Warning > Suggestion
                - Be constructive and explain why each change matters
                """;
    }
}
