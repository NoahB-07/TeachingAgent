# Teaching Agent

An ACP (Agent Client Protocol) teaching assistant that runs inside JetBrains IDEs. It helps learners understand code by searching, reading, and explaining project files using interactive learning tools.

## Prerequisites

- **Java 17+** (for building the agent)
- **LLM API key** for your chosen provider (OpenAI, Anthropic, Google, etc.)
- **JetBrains IDE** with AI Assistant / Junie support (IntelliJ IDEA, PyCharm, WebStorm, etc.)

## Choosing an AI Backend

The agent supports multiple LLM providers. Configure them using environment variables in `acp.json`:

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_PROVIDER` | Provider name: `openai`, `anthropic`, `google`, etc. | `openai` |
| `LLM_MODEL` | Model name (e.g. `gpt-4o`, `claude-sonnet-4-20250514`) | `gpt-4o` |
| `LLM_API_KEY` | API key (generic, works for any provider) | *(required)* |

You can also use provider-specific key variables: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, or `GOOGLE_API_KEY`.

### Example: Anthropic Claude

```json
"env": {
  "LLM_PROVIDER": "anthropic",
  "LLM_MODEL": "claude-sonnet-4-20250514",
  "ANTHROPIC_API_KEY": "your-anthropic-api-key-here"
}
```

### Example: OpenAI GPT-4o (default)

```json
"env": {
  "OPENAI_API_KEY": "your-openai-api-key-here"
}
```

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
        "/absolute/path/to/build/libs/TeachingAgent.jar"
      ],
      "env": {
        "LLM_PROVIDER": "openai",
        "LLM_MODEL": "gpt-4o",
        "LLM_API_KEY": "your-api-key-here"
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
| `env` | Environment variables for the agent process. Set `LLM_API_KEY` (or a provider-specific key like `OPENAI_API_KEY`). Optionally set `LLM_PROVIDER` and `LLM_MODEL` to change the backend. |
| `use_idea_mcp` | Set to `true` to connect the IDE's built-in MCP server, giving the agent access to IDE tools (code search, file reading, etc.). |

### Important notes

- **Use absolute paths** for the `command` and JAR path. The IDE runs with a restricted `PATH` and may not resolve relative paths.
- **Replace** `your-api-key-here` with your actual API key. See the "Choosing an AI Backend" section above for provider-specific examples.
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
- Check that an API key is set (`LLM_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, etc.).
- If using a non-OpenAI provider, verify `LLM_PROVIDER` is set correctly.
- Ensure the JAR was built successfully (`./gradlew build`).
- Look for error messages in the IDE's event log or run the JAR manually: `java -jar build/libs/test-all.jar` to see startup errors.

**Agent can't read project files:**
- Make sure `"use_idea_mcp": true` is set in `default_mcp_settings`.
- Verify the IDE's MCP server is enabled in Settings → Tools → AI Assistant.
