package dm.dracolich.ai.core.service;

import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import dm.dracolich.forge.exception.ResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatFailuresTest {

    @Test
    void aRefusalHasItsOwnCode() {
        ResponseException failure = ChatFailures.refusal();

        assertThat(failure.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(codeOf(failure)).isEqualTo("DMD037");
    }

    @Test
    void aRateLimitHasItsOwnCode() {
        ResponseException failure = ChatFailures.from(mock(RateLimitException.class));

        assertThat(failure.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(codeOf(failure)).isEqualTo("DMD038");
    }

    @Test
    void aRateLimitIsFoundWhenItArrivesWrapped() {
        Throwable wrapped = new RuntimeException("stream failed",
                new IllegalStateException(mock(RateLimitException.class)));

        assertThat(codeOf(ChatFailures.from(wrapped))).isEqualTo("DMD038");
    }

    @Test
    void anOverloadedApiHasItsOwnCode() {
        InternalServerException overloaded = mock(InternalServerException.class);
        when(overloaded.statusCode()).thenReturn(529);

        ResponseException failure = ChatFailures.from(overloaded);

        assertThat(failure.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(codeOf(failure)).isEqualTo("DMD039");
    }

    @Test
    void anyOtherServerErrorStaysAChatStreamingError() {
        InternalServerException serverError = mock(InternalServerException.class);
        when(serverError.statusCode()).thenReturn(500);

        ResponseException failure = ChatFailures.from(serverError);

        assertThat(failure.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(codeOf(failure)).isEqualTo("DMD028");
    }

    @Test
    void anErrorThatIsNotFromTheApiStaysAChatStreamingError() {
        ResponseException failure = ChatFailures.from(new IllegalStateException("boom"));

        assertThat(codeOf(failure)).isEqualTo("DMD028");
        assertThat(failure.getMessage()).contains("boom");
    }

    @Test
    void anErrorAlreadyMappedIsReturnedAsItIs() {
        ResponseException refusal = ChatFailures.refusal();

        assertThat(ChatFailures.from(refusal)).isSameAs(refusal);
    }

    private static String codeOf(ResponseException failure) {
        assertThat(failure.getErrors()).hasSize(1);
        return failure.getErrors().get(0).getError();
    }
}
