# Teaching Agent

An ACP (Agent Client Protocol) teaching assistant that runs inside JetBrains IDEs. It helps learners understand code by searching, reading, and explaining project files using interactive learning tools.

## Prerequisites

- **Java 17+** (for building the agent)
- **OpenAI API key** (set as the `OPENAI_API_KEY` environment variable)
- **JetBrains IDE** with AI Assistant / Junie support (IntelliJ IDEA, PyCharm, WebStorm, etc.)

## Building

Build the agent JAR with Gradle:

```bash
./gradlew build
```

The resulting JAR is located at `build/libs/` and includes all dependencies (fat JAR).

## Setting Up the ACP Configuration

The IDE discovers ACP agents through a configuration file at `~/.jetbrains/acp.json`. Follow these steps to create and configure it:

### 1. Create the configuration file

You can create it manually or let the IDE generate it:

**Option A — From the IDE (recommended):**
1. Open the **AI Chat** tool window in your JetBrains IDE.
2. Click the **⚙️ (Settings)** button in the upper-right corner.
3. Select **Add Custom Agent**.
4. This creates `~/.jetbrains/acp.json` and opens it for editing.

**Option B — Manually:**
1. Create the directory `~/.jetbrains/` if it doesn't exist.
2. Create a file named `acp.json` inside it.

### 2. Add the Teaching Agent entry

Populate `acp.json` with the following structure:

```json
{
  "default_mcp_settings": {
     "use_custom_mcp": true,
     "use_idea_mcp": true
  },
  "agent_servers": {
    "Teaching Agent": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/build/libs/test-all.jar"
      ],
      "env": {
        "OPENAI_API_KEY": "your-openai-api-key-here"
      }
    }
  }
}
```

### 3. Configure each field

| Field | Description |
|-------|-------------|
| `command` | The executable to launch the agent. Use `"java"` to run the JAR. |
| `args` | Arguments passed to the command. Include `-jar` and the **absolute path** to the built JAR. |
| `env` | Environment variables for the agent process. At minimum, set `OPENAI_API_KEY`. |
| `use_idea_mcp` | Set to `true` to connect the IDE's built-in MCP server, giving the agent access to IDE tools (code search, file reading, etc.). |

### Important notes

- **Use absolute paths** for the `command` and JAR path. The IDE runs with a restricted `PATH` and may not resolve relative paths.
- **Replace** `your-openai-api-key-here` with your actual OpenAI API key, or rely on the key being set in your shell environment.
- After saving `acp.json`, the agent should appear in AI Chat immediately. If it doesn't, restart the IDE.

## Using the Agent

1. Open the **AI Chat** tool window in your IDE.
2. Select **Teaching Agent** from the agent list.
3. Ask a question about your code — for example:
   - "How does the `AcpTeachingAgent` class work?"
   - "Explain the ReAct loop in this project"
   - "What do the learning tools do?"

The agent will search your project, read the relevant code, and explain it using interactive learning tools that highlight code directly in your editor.

## Project Structure

```
src/main/java/ai/learn/agent/
├── AcpTeachingAgent.java   # Main ACP agent entry point
├── AcpIdeTools.java        # IDE integration tools (read files, search code, etc.)
├── LearningTools.java      # Interactive learning tools (highlights, walkthroughs)
├── McpConnections.java     # MCP server connection management
└── skills/                 # Teaching skill implementations
    ├── Skill.java
    ├── BeginnerTeachSkill.java
    ├── StudentTeachSkill.java
    ├── EngineerTeachSkill.java
    ├── CodeReviewSkill.java
    └── DebuggingSkill.java
```

## Troubleshooting

**Agent doesn't appear in AI Chat:**
- Verify `acp.json` is valid JSON (check for trailing commas or missing quotes).
- Confirm the JAR path is absolute and the file exists.
- Restart the IDE.

**Agent fails to start:**
- Check that `OPENAI_API_KEY` is set and valid.
- Ensure the JAR was built successfully (`./gradlew build`).
- Look for error messages in the IDE's event log or run the JAR manually: `java -jar build/libs/test-all.jar` to see startup errors.

**Agent can't read project files:**
- Make sure `"use_idea_mcp": true` is set in `default_mcp_settings`.
- Verify the IDE's MCP server is enabled in Settings → Tools → AI Assistant.
