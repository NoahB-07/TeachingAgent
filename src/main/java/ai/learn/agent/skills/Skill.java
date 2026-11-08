package ai.learn.agent.skills;

import ai.koog.agents.core.tools.ToolBase;

import java.util.Collections;
import java.util.List;

/**
 * A modular capability that can be added to the agent.
 *
 * <p>A skill contributes:
 * <ul>
 *   <li>A <b>name</b> for identification</li>
 *   <li><b>Prompt instructions</b> appended to the system prompt</li>
 *   <li>Optionally, <b>tools</b> that extend the agent's capabilities</li>
 * </ul>
 *
 * <p>Skills are composed into the agent at session creation time.
 * To add a skill, include it when creating the session's skill list.
 */
public interface Skill {

    /**
     * Human-readable name of the skill (e.g., "Code Review", "Debugging").
     */
    String name();

    /**
     * Instructions appended to the system prompt when this skill is active.
     *
     * <p>Return an empty string if the skill only provides tools with no
     * additional behavioral instructions.
     */
    String instructions();

    /**
     * Tools provided by this skill.
     *
     * <p>Return an empty list if the skill only provides prompt instructions
     * with no additional tools.
     */
    default List<ToolBase<?, ?>> tools() {
        return Collections.emptyList();
    }
}
