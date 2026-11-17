package dm.dracolich.ai.core.service;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import dm.dracolich.ai.dto.error.ErrorCodes;
import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.exception.ResponseException;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Turns what can go wrong in a chat call into the service's own errors, so a caller
 * can tell "the model declined" and "try again shortly" from a fault in this service.
 */
final class ChatFailures {

    /** Anthropic's status for "the API is temporarily overloaded". */
    private static final int OVERLOADED = 529;

    private ChatFailures() {
    }

    /** The model ended its turn with {@code stop_reason: "refusal"}. */
    static ResponseException refusal() {
        return new ResponseException(
                ErrorCodes.DMD037.getMessage(),
                List.of(new ApiError(ErrorCodes.DMD037)),
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * Maps an error from the chat stream. A rate limit and an overloaded API get
     * their own codes because both pass on their own and are worth retrying;
     * anything else stays a chat streaming error.
     */
    static ResponseException from(Throwable error) {
        if (error instanceof ResponseException alreadyMapped) {
            return alreadyMapped;
        }

        AnthropicServiceException apiError = findApiError(error);
        if (apiError instanceof RateLimitException) {
            return new ResponseException(
                    ErrorCodes.DMD038.getMessage(),
                    List.of(new ApiError(ErrorCodes.DMD038)),
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        if (apiError != null && apiError.statusCode() == OVERLOADED) {
            return new ResponseException(
                    ErrorCodes.DMD039.getMessage(),
                    List.of(new ApiError(ErrorCodes.DMD039)),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        return new ResponseException(
                ErrorCodes.DMD028.format(error.getMessage()),
                List.of(new ApiError(ErrorCodes.DMD028)),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** The SDK's error may arrive wrapped, so look down the cause chain for it. */
    private static AnthropicServiceException findApiError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof AnthropicServiceException apiError) {
                return apiError;
            }
            current = current.getCause();
        }
        return null;
    }
}
