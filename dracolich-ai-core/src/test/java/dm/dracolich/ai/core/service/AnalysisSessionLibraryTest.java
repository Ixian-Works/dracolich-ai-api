package dm.dracolich.ai.core.service;

import dm.dracolich.ai.client.config.MtgLibraryConfig;
import dm.dracolich.ai.client.mtg.MtgLibraryClient;
import dm.dracolich.ai.datasource.entity.SessionEntity;
import dm.dracolich.ai.datasource.repository.SessionRepository;
import dm.dracolich.ai.dto.SessionDto;
import dm.dracolich.ai.dto.enums.SessionType;
import dm.dracolich.ai.dto.records.DeckCardRequest;
import dm.dracolich.ai.dto.records.SessionCreateRequest;
import dm.dracolich.forge.exception.ResponseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opening an analysis session against a stand-in library on a real local HTTP server, so the number
 * of requests is counted on the wire.
 */
class AnalysisSessionLibraryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private DisposableServer library;
    private final AtomicInteger collectionCalls = new AtomicInteger();
    private final AtomicInteger otherCalls = new AtomicInteger();

    @AfterEach
    void stop() {
        if (library != null) library.disposeNow();
    }

    /** A library that knows every name except those in {@code unknown}. Commanders are white-blue. */
    private void libraryKnowsAllBut(Set<String> unknown) {
        library = HttpServer.create().port(0).route(routes -> routes
                .post("/cards/collection", (request, response) -> {
                    collectionCalls.incrementAndGet();
                    return response.header("Content-Type", "application/json").sendString(
                            request.receive().aggregate().asString(StandardCharsets.UTF_8)
                                    .map(body -> answer(JSON.readTree(body), unknown)));
                })
                .route(r -> true, (request, response) -> {
                    otherCalls.incrementAndGet();
                    return response.status(404).send();
                }))
                .bindNow();
    }

    private static String answer(JsonNode request, Set<String> unknown) {
        ArrayNode cards = JSON.createArrayNode();
        ArrayNode notFound = JSON.createArrayNode();
        for (JsonNode identifier : request.get("identifiers")) {
            String name = identifier.get("name").asString();
            if (unknown.contains(name)) {
                notFound.add(identifier);
                continue;
            }
            ObjectNode card = cards.addObject();
            card.put("id", "id-" + name);
            card.put("name", name);
            card.putObject("default_face").putObject("gameplay_property")
                    .putArray("color_identity").add("W").add("U");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.set("cards", cards);
        payload.set("not_found", notFound);
        ObjectNode envelope = JSON.createObjectNode().put("success", true);
        envelope.set("payload", payload);
        return envelope.toString();
    }

    private AgentServiceImpl service(String baseUrl, Duration responseTimeout) {
        WebClient webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(
                        MtgLibraryConfig.httpClient(Duration.ofSeconds(1), responseTimeout)))
                .build();
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.save(any(SessionEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        return new AgentServiceImpl(repository, new MtgLibraryClient(webClient), mock(ChatClient.class));
    }

    private static SessionCreateRequest deckOf(int cards, String... unknown) {
        List<DeckCardRequest> deck = new ArrayList<>();
        deck.add(new DeckCardRequest("Commander Card", "MAINBOARD", 1, true));
        for (int i = 1; i < cards; i++) deck.add(new DeckCardRequest("Card " + i, "MAINBOARD", 1, false));
        for (String name : unknown) deck.add(new DeckCardRequest(name, "MAINBOARD", 1, false));
        return new SessionCreateRequest("user-1", SessionType.ANALYSIS, "COMMANDER", null, deck);
    }

    @Test
    void aHundredCardDeckIsOneRequestToTheLibrary() {
        libraryKnowsAllBut(Set.of());

        SessionDto session = service("http://localhost:" + library.port(), WAIT)
                .createSession(deckOf(100)).block(WAIT);

        assertThat(collectionCalls).hasValue(1);
        assertThat(otherCalls).hasValue(0);
        assertThat(session.getDeckList()).hasSize(100);
        assertThat(session.getCommanderName()).isEqualTo("Commander Card");
        // The commander's identity comes from the same answer, with no second request.
        assertThat(session.getColorIdentity()).containsExactlyInAnyOrder("W", "U");
    }

    @Test
    void anUnknownCardIsLeftOutAndTheSessionStillOpens() {
        libraryKnowsAllBut(Set.of("Not A Card"));

        SessionDto session = service("http://localhost:" + library.port(), WAIT)
                .createSession(deckOf(3, "Not A Card")).block(WAIT);

        assertThat(session.getDeckList()).extracting("name")
                .containsExactly("Commander Card", "Card 1", "Card 2");
        assertThat(collectionCalls).hasValue(1);
    }

    @Test
    void aLibraryThatNeverAnswersFailsTheSessionWithDmd029() {
        library = HttpServer.create().port(0).handle((request, response) -> Mono.never()).bindNow();

        assertThatThrownBy(() -> service("http://localhost:" + library.port(), Duration.ofMillis(300))
                .createSession(deckOf(3)).block(WAIT))
                .isInstanceOfSatisfying(ResponseException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrors()).extracting("error").containsExactly("DMD029");
                });
    }
}
