package dm.dracolich.ai.core.config;

import dm.dracolich.ai.core.tool.CardSearchTool;
import dm.dracolich.ai.core.tool.ReportIssuesTool;
import dm.dracolich.ai.core.tool.SuggestCardsTool;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicConfig {

    /**
     * The options every chat call uses.
     *
     * <p>No sampling parameter is set: current Claude models reject them. Thinking is
     * left to the model's default, which is adaptive, and {@code effort} is the one
     * control over how much it thinks and writes. Thinking counts toward
     * {@code maxTokens}.</p>
     */
    @Bean
    public AnthropicChatOptions anthropicChatOptions(AnthropicProperties properties) {
        properties.validate();
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .apiKey(properties.apiKey())
                .model(properties.chat().model())
                .maxTokens(properties.chat().maxTokens())
                .effort(properties.effortLevel());
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            options.baseUrl(properties.baseUrl());
        }
        return options.build();
    }

    @Bean
    public AnthropicChatModel anthropicChatModel(AnthropicChatOptions options) {
        return AnthropicChatModel.builder()
                .options(options)
                .build();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(AnthropicChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * Singleton ChatClient with tools registered once at startup.
     * AgentService should inject this — NOT the Builder.
     * Re-registering tools per-call on a shared Builder accumulates duplicates
     * and crashes Spring AI with "Multiple tools with the same name".
     *
     * <p>Stats (mana curve, color pie, lands, etc.) are pre-computed by deck-builder-api
     * and injected into the user prompt — no stats tool is registered, which saves
     * ~70 mtg-library-api round trips per analyze call.</p>
     */
    @Bean
    public ChatClient agentChatClient(ChatClient.Builder builder,
                                      CardSearchTool cardSearchTool,
                                      SuggestCardsTool suggestCardsTool,
                                      ReportIssuesTool reportIssuesTool) {
        return builder
                .defaultTools(cardSearchTool, suggestCardsTool, reportIssuesTool)
                .build();
    }
}
