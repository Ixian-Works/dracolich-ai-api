package dm.dracolich.ai.core.config;

import com.anthropic.models.messages.OutputConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Anthropic settings this service reads, under {@code spring.ai.anthropic}.
 *
 * <p>The model id has no default anywhere in the code: each environment names its
 * model, and a missing value stops the service at startup.</p>
 *
 * <p>{@code temperature}, {@code topP} and {@code topK} are bound only so that
 * {@link #validate()} can refuse them. Current Claude models reject sampling
 * parameters with a 400, so a leftover setting would break every call.</p>
 *
 * <p>{@code baseUrl} is optional and normally unset, which means Anthropic's own
 * API. A test, or a deployment behind a gateway, points it elsewhere.</p>
 */
@ConfigurationProperties(prefix = "spring.ai.anthropic")
public record AnthropicProperties(String apiKey, String baseUrl, Chat chat) {

    private static final Set<String> EFFORT_LEVELS = Set.of("low", "medium", "high", "xhigh", "max");

    public record Chat(String model, Integer maxTokens, String effort,
                       Double temperature, Double topP, Integer topK) {
    }

    /**
     * Checks the settings and reports every problem at once.
     *
     * @throws IllegalStateException naming each setting that is missing or not allowed
     */
    public void validate() {
        List<String> problems = new ArrayList<>();

        if (isBlank(apiKey)) {
            problems.add("spring.ai.anthropic.api-key is not set (ANTHROPIC_API_KEY)");
        }
        if (chat == null || isBlank(chat.model())) {
            problems.add("spring.ai.anthropic.chat.model is not set (ANTHROPIC_MODEL). "
                    + "It has no default: each environment names its model");
        }
        if (chat != null) {
            if (chat.maxTokens() == null || chat.maxTokens() <= 0) {
                problems.add("spring.ai.anthropic.chat.max-tokens must be a positive number");
            }
            if (isBlank(chat.effort()) || !EFFORT_LEVELS.contains(chat.effort().toLowerCase(Locale.ROOT))) {
                problems.add("spring.ai.anthropic.chat.effort must be one of low, medium, high, xhigh, max");
            }
            if (chat.temperature() != null) {
                problems.add(samplingProblem("temperature"));
            }
            if (chat.topP() != null) {
                problems.add(samplingProblem("top-p"));
            }
            if (chat.topK() != null) {
                problems.add(samplingProblem("top-k"));
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Anthropic configuration is not usable: " + String.join("; ", problems));
        }
    }

    /** The effort level as the SDK's type. Call {@link #validate()} first. */
    public OutputConfig.Effort effortLevel() {
        return OutputConfig.Effort.of(chat.effort().toLowerCase(Locale.ROOT));
    }

    private static String samplingProblem(String name) {
        return "spring.ai.anthropic.chat." + name + " is set, and current Claude models reject sampling "
                + "parameters. Remove it";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
