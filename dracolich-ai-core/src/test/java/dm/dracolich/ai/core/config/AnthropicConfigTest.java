package dm.dracolich.ai.core.config;

import com.anthropic.models.messages.OutputConfig;
import dm.dracolich.ai.client.mtg.MtgLibraryClient;
import dm.dracolich.ai.core.tool.CardSearchTool;
import dm.dracolich.ai.core.tool.ReportIssuesTool;
import dm.dracolich.ai.core.tool.SuggestCardsTool;
import dm.dracolich.ai.datasource.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Builds the chat client the way the service does, from configuration, without
 * calling Anthropic.
 */
class AnthropicConfigTest {

    /** Any id will do: the service must take it from configuration and nowhere else. */
    private static final String CONFIGURED_MODEL = "model-id-from-configuration";

    private final ApplicationContextRunner base = new ApplicationContextRunner()
            .withUserConfiguration(AnthropicConfig.class)
            .withBean(CardSearchTool.class, () -> new CardSearchTool(mock(MtgLibraryClient.class)))
            .withBean(SuggestCardsTool.class, () -> new SuggestCardsTool(mock(SessionRepository.class), mock(dm.dracolich.ai.client.mtg.MtgLibraryClient.class)))
            .withBean(ReportIssuesTool.class, () -> new ReportIssuesTool(mock(SessionRepository.class)))
            .withPropertyValues(
                    "spring.ai.anthropic.api-key=test-key",
                    "spring.ai.anthropic.chat.max-tokens=8192",
                    "spring.ai.anthropic.chat.effort=low");

    private final ApplicationContextRunner configured = base
            .withPropertyValues("spring.ai.anthropic.chat.model=" + CONFIGURED_MODEL);

    @Test
    void buildsTheChatClientFromConfiguration() {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AnthropicChatModel.class);
            assertThat(context).hasBean("agentChatClient");
            assertThat(context.getBean("agentChatClient")).isInstanceOf(ChatClient.class);

            AnthropicChatOptions options = context.getBean(AnthropicChatOptions.class);
            assertThat(options.getModel()).isEqualTo(CONFIGURED_MODEL);
            assertThat(options.getMaxTokens()).isEqualTo(8192);
            assertThat(options.getOutputConfig().effort()).contains(OutputConfig.Effort.LOW);
        });
    }

    @Test
    void sendsNoSamplingParameter() {
        configured.run(context -> {
            AnthropicChatOptions options = context.getBean(AnthropicChatOptions.class);
            assertThat(options.getTemperature()).isNull();
            assertThat(options.getTopP()).isNull();
            assertThat(options.getTopK()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"temperature=0.7", "top-p=0.9", "top-k=40"})
    void refusesToStartWhenASamplingParameterIsSet(String setting) {
        String name = setting.substring(0, setting.indexOf('='));

        configured.withPropertyValues("spring.ai.anthropic.chat." + setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.ai.anthropic.chat." + name);
        });
    }

    @Test
    void refusesToStartWithoutAModel() {
        base.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("spring.ai.anthropic.chat.model is not set");
        });
    }

    @Test
    void refusesAnEffortLevelThatDoesNotExist() {
        configured.withPropertyValues("spring.ai.anthropic.chat.effort=extreme").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("spring.ai.anthropic.chat.effort");
        });
    }

    @Test
    void acceptsEveryEffortLevelInAnyCase() {
        for (String effort : new String[] {"low", "medium", "high", "xhigh", "max", "LOW"}) {
            configured.withPropertyValues("spring.ai.anthropic.chat.effort=" + effort)
                    .run(context -> assertThat(context).as("effort %s", effort).hasNotFailed());
        }
    }
}
