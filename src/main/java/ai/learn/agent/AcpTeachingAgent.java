package ai.learn.agent;

import ai.koog.agents.chatMemory.feature.ChatMemory;
import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.AIAgentSimpleStrategies;
import ai.koog.http.client.ktor.KtorKoogHttpClient;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor;
import ai.koog.prompt.llm.LLMCapability;
import ai.koog.prompt.llm.LLMProvider;
import ai.koog.prompt.llm.LLModel;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;

import java.util.List;
import java.util.UUID;

/**
 * ACP-compatible entry point for the TeachingAgent.
 * This class wraps the Koog AIAgent so it can be connected to JetBrains AIR via ACP.
 */
@AcpAgent(name = "teaching-agent", version = "1.0")
public class AcpTeachingAgent {

    @NewSession
    NewSessionResponse newSession() {
        return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
    }

    @Prompt
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
        // Get API key from environment variable
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            ctx.sendMessage("Error: OPENAI_API_KEY environment variable is not set.");
            return PromptResponse.endTurn();
        }

        // Set up the Koog agent
        var httpClientFactory = new KtorKoogHttpClient.Factory();
        var openAIClient = new OpenAILLMClient(apiKey, httpClientFactory);
        var promptExecutor = new MultiLLMPromptExecutor(openAIClient);
        LLModel model = new LLModel(
                LLMProvider.OpenAI,
                "gpt-4o",
                List.of(
                        LLMCapability.Completion.INSTANCE,
                        LLMCapability.OpenAIEndpoint.Completions.INSTANCE
                )
        );

        AIAgent<String, String> agent = AIAgent.builder()
                .promptExecutor(promptExecutor)
                .llmModel(model)
                .systemPrompt("You are a patient teaching assistant. Explain concepts step by step, use examples, and ask clarifying questions when needed.")
                .graphStrategy(AIAgentSimpleStrategies.singleRunStrategy())
                .install(ChatMemory.Feature, config -> {})
                .build();

        // Run the agent and stream the response back via ACP
        try {
            String response = agent.run(req.text(), "acp-session-" + UUID.randomUUID());
            ctx.sendMessage(response);
        } catch (Exception e) {
            ctx.sendMessage("Error: " + e.getMessage());
        }

        return PromptResponse.endTurn();
    }

    /**
     * Main entry point — starts the ACP agent over STDIO transport.
     * This is what AIR launches to connect to your agent.
     */
    public static void main(String[] args) {
        AcpAgentSupport.create(new AcpTeachingAgent())
                .transport(new StdioAcpAgentTransport())
                .run();
    }
}
