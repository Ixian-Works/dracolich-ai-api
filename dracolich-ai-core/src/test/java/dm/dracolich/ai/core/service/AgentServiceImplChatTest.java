package dm.dracolich.ai.core.service;

import dm.dracolich.ai.client.mtg.MtgLibraryClient;
import dm.dracolich.ai.datasource.entity.SessionEntity;
import dm.dracolich.ai.datasource.repository.SessionRepository;
import dm.dracolich.ai.dto.enums.MessageRole;
import dm.dracolich.ai.dto.enums.SessionType;
import dm.dracolich.forge.exception.ResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplChatTest {

    private static final String SESSION_ID = "session-1";

    private SessionRepository sessionRepository;
    private ChatClient chatClient;
    private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        service = new AgentServiceImpl(sessionRepository, mock(MtgLibraryClient.class), chatClient);

        SessionEntity session = SessionEntity.builder()
                .id(SESSION_ID)
                .sessionType(SessionType.ANALYSIS)
                .format("COMMANDER")
                .chatHistory(new ArrayList<>())
                .build();
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Mono.just(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    @Test
    void streamsTheTextAndKeepsTheTurn() {
        modelAnswers(text("Add two "), text("ramp spells."), finished("end_turn"));

        List<String> chunks = service.chat(SESSION_ID, "What is missing?").collectList().block();

        assertThat(chunks).containsExactly("Add two ", "ramp spells.");

        ArgumentCaptor<SessionEntity> saved = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, timeout(2000)).save(saved.capture());
        assertThat(saved.getValue().getChatHistory()).hasSize(2);
        assertThat(saved.getValue().getChatHistory().get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(saved.getValue().getChatHistory().get(1).getContent()).isEqualTo("Add two ramp spells.");
    }

    @Test
    void aRefusalEndsTheStreamWithItsOwnErrorAndIsNotKept() throws InterruptedException {
        modelAnswers(text("I can"), finished("refusal"));

        assertThatThrownBy(() -> service.chat(SESSION_ID, "anything").collectList().block())
                .isInstanceOf(ResponseException.class)
                .satisfies(error -> assertThat(((ResponseException) error).getErrors().get(0).getError())
                        .isEqualTo("DMD037"));

        Thread.sleep(200); // a save, if there were one, runs on another thread
        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void aFailureFromTheModelBecomesAChatStreamingError() {
        when(chatClient.prompt().messages(anyList()).stream().chatResponse())
                .thenReturn(Flux.error(new IllegalStateException("connection reset")));

        assertThatThrownBy(() -> service.chat(SESSION_ID, "anything").collectList().block())
                .isInstanceOf(ResponseException.class)
                .satisfies(error -> assertThat(((ResponseException) error).getErrors().get(0).getError())
                        .isEqualTo("DMD028"));
    }

    private void modelAnswers(ChatResponse... responses) {
        when(chatClient.prompt().messages(anyList()).stream().chatResponse()).thenReturn(Flux.just(responses));
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** What Spring AI emits for Anthropic's final event: no text, and the stop reason. */
    private static ChatResponse finished(String stopReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(stopReason).build())));
    }
}
