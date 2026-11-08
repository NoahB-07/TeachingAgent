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

import java.util.List;
import java.util.Scanner;

/**
 * Simple CLI-based teaching agent for quick local testing.
 *
 * <p>This is a standalone console application that uses Koog agents with OpenAI.
 * It is not ACP-aware — use {@link AcpTeachingAgent} for IDE integration.
 *
 * <p>Requires the {@code OPENAI_API_KEY} environment variable to be set.
 */
public class TeachingAgent {

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("Please set the OPENAI_API_KEY environment variable.");
            return;
        }

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
                .systemPrompt("You are a patient teaching assistant. Explain concepts step by step, "
                        + "use examples, and ask clarifying questions when needed.")
                .graphStrategy(AIAgentSimpleStrategies.singleRunStrategy())
                .install(ChatMemory.Feature, config -> {})
                .build();

        String sessionId = "teaching-session-1";

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Teaching Agent ready! (Type 'exit' to quit)");
            while (true) {
                System.out.print("You: ");
                String input = scanner.nextLine();
                if ("exit".equalsIgnoreCase(input.trim())) {
                    break;
                }

                String response = agent.run(input, sessionId);
                System.out.println("Agent: " + response + "\n");
            }
        }
    }
}
