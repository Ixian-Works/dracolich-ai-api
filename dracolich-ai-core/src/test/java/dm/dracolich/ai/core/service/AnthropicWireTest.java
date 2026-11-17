package dm.dracolich.ai.core.service;

import com.sun.net.httpserver.HttpServer;
import dm.dracolich.ai.client.mtg.MtgLibraryClient;
import dm.dracolich.ai.core.config.AnthropicConfig;
import dm.dracolich.ai.core.tool.CardSearchTool;
import dm.dracolich.ai.core.tool.ReportIssuesTool;
import dm.dracolich.ai.core.tool.SuggestCardsTool;
import dm.dracolich.ai.datasource.entity.SessionEntity;
import dm.dracolich.ai.datasource.repository.SessionRepository;
import dm.dracolich.ai.dto.enums.SessionType;
import dm.dracolich.forge.exception.ResponseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sends a real request through Spring AI and the Anthropic SDK to a local stand-in
 * for the Messages API, and checks what goes over the wire and how the reply is read.
 * No call leaves the machine.
 */
class AnthropicWireTest {

    private static final String CONFIGURED_MODEL = "model-id-from-configuration";
    private static final String SESSION_ID = "session-1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer anthropic;
    /** What the stand-in answers, one reply per request, in order. */
    private final Deque<String> replies = new ConcurrentLinkedDeque<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private SessionRepository sessionRepository;

    @BeforeEach
    void startStandIn() throws IOException {
        anthropic = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        anthropic.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String next = replies.poll();
            byte[] reply = (next != null ? next : textReply("end_turn")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        anthropic.start();
        sessionRepository = sessionRepository();
    }

    @AfterEach
    void stopStandIn() {
        anthropic.stop(0);
    }

    @Test
    void theRequestCarriesTheConfiguredModelAndEffortAndNoSamplingParameter() {
        List<String> chunks = chat("What is missing?");

        assertThat(chunks).containsExactly("Add two ", "ramp spells.");
        assertThat(requestPath.get()).isEqualTo("/v1/messages");
        assertThat(apiKeyHeader.get()).isEqualTo("test-key");

        assertThat(requestBodies).hasSize(1);
        JsonNode request = JSON.readTree(requestBodies.get(0));
        assertThat(request.get("model").asString()).isEqualTo(CONFIGURED_MODEL);
        assertThat(request.get("max_tokens").asInt()).isEqualTo(8192);
        assertThat(request.get("stream").asBoolean()).isTrue();
        assertThat(request.get("output_config").get("effort").asString()).isEqualTo("low");

        assertThat(request.has("temperature")).as("temperature").isFalse();
        assertThat(request.has("top_p")).as("top_p").isFalse();
        assertThat(request.has("top_k")).as("top_k").isFalse();
        // Thinking is left to the model's default. Turning it off is an error on current models.
        assertThat(request.has("thinking")).as("thinking").isFalse();
        // The model chooses its tools. Forcing one is an error on current models.
        assertThat(request.has("tool_choice")).as("tool_choice").isFalse();

        List<String> toolNames = new ArrayList<>();
        request.get("tools").forEach(tool -> toolNames.add(tool.get("name").asString()));
        assertThat(toolNames).containsExactlyInAnyOrder("searchCards", "suggestCards", "reportIssues");
    }

    @Test
    void aRefusalFromTheApiBecomesTheRefusalError() {
        replies.add(textReply("refusal"));

        assertThatThrownBy(() -> chat("anything"))
                .isInstanceOf(ResponseException.class)
                .satisfies(error -> assertThat(((ResponseException) error).getErrors().get(0).getError())
                        .isEqualTo("DMD037"));
    }

    @Test
    void aToolCallIsRunAndItsResultGoesBackToTheModel() {
        String issues = """
                [{"topic":"mana_curve","severity":"WARNING","reason":"Too many expensive spells"}]""";
        replies.add(toolCallReply("toolu_test", "reportIssues",
                JSON.createObjectNode().put("sessionId", SESSION_ID).put("issuesJson", issues)));
        replies.add(textReply("end_turn"));

        List<String> chunks = chat("Analyse this deck");

        assertThat(chunks).containsExactly("Add two ", "ramp spells.");

        // The tool ran: the finding was saved on the session.
        ArgumentCaptor<SessionEntity> saved = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, timeout(2000).atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .anySatisfy(session -> assertThat(session.getAnalysisIssues())
                        .singleElement()
                        .satisfies(issue -> assertThat(issue.getTopic()).isEqualTo("mana_curve")));

        // The model was called again, with the tool's result in the conversation.
        assertThat(requestBodies).hasSize(2);
        JsonNode followUp = JSON.readTree(requestBodies.get(1));
        List<JsonNode> toolResults = new ArrayList<>();
        followUp.get("messages").forEach(message -> {
            if (message.get("content").isArray()) {
                message.get("content").forEach(block -> {
                    if ("tool_result".equals(block.path("type").asString(""))) {
                        toolResults.add(block);
                    }
                });
            }
        });
        assertThat(toolResults).singleElement().satisfies(result -> {
            assertThat(result.get("tool_use_id").asString()).isEqualTo("toolu_test");
            assertThat(result.toString()).contains("Reported 1 issue(s): mana_curve");
        });
    }

    private List<String> chat(String message) {
        AtomicReference<List<String>> chunks = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        new ApplicationContextRunner()
                .withUserConfiguration(AnthropicConfig.class)
                .withBean(CardSearchTool.class, () -> new CardSearchTool(mock(MtgLibraryClient.class)))
                .withBean(SuggestCardsTool.class, () -> new SuggestCardsTool(sessionRepository))
                .withBean(ReportIssuesTool.class, () -> new ReportIssuesTool(sessionRepository))
                .withPropertyValues(
                        "spring.ai.anthropic.api-key=test-key",
                        "spring.ai.anthropic.base-url=http://127.0.0.1:" + anthropic.getAddress().getPort(),
                        "spring.ai.anthropic.chat.model=" + CONFIGURED_MODEL,
                        "spring.ai.anthropic.chat.max-tokens=8192",
                        "spring.ai.anthropic.chat.effort=low")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AgentServiceImpl service = new AgentServiceImpl(sessionRepository,
                            mock(MtgLibraryClient.class), context.getBean("agentChatClient", ChatClient.class));
                    try {
                        chunks.set(service.chat(SESSION_ID, message).collectList().block(Duration.ofSeconds(20)));
                    } catch (RuntimeException e) {
                        failure.set(e);
                    }
                });

        if (failure.get() instanceof RuntimeException e) {
            throw e;
        }
        return chunks.get();
    }

    private static SessionRepository sessionRepository() {
        SessionRepository repository = mock(SessionRepository.class);
        SessionEntity session = SessionEntity.builder()
                .id(SESSION_ID)
                .sessionType(SessionType.ANALYSIS)
                .format("COMMANDER")
                .chatHistory(new ArrayList<>())
                .build();
        when(repository.findById(SESSION_ID)).thenReturn(Mono.just(session));
        when(repository.save(any(SessionEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        return repository;
    }

    /** A Messages API stream that asks for one tool call and stops with {@code tool_use}. */
    private static String toolCallReply(String id, String toolName, JsonNode input) {
        String start = JSON.createObjectNode()
                .put("type", "content_block_start")
                .put("index", 0)
                .set("content_block", JSON.createObjectNode()
                        .put("type", "tool_use")
                        .put("id", id)
                        .put("name", toolName)
                        .set("input", JSON.createObjectNode()))
                .toString();
        String delta = JSON.createObjectNode()
                .put("type", "content_block_delta")
                .put("index", 0)
                .set("delta", JSON.createObjectNode()
                        .put("type", "input_json_delta")
                        .put("partial_json", input.toString()))
                .toString();
        return event("message_start", MESSAGE_START)
                + event("content_block_start", start)
                + event("content_block_delta", delta)
                + event("content_block_stop", """
                {"type":"content_block_stop","index":0}""")
                + event("message_delta", """
                {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},\
                "usage":{"output_tokens":30}}""")
                + event("message_stop", """
                {"type":"message_stop"}""");
    }

    private static final String MESSAGE_START = """
            {"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant",\
            "model":"model-id-from-configuration","content":[],"stop_reason":null,"stop_sequence":null,\
            "usage":{"input_tokens":120,"output_tokens":0}}}""";

    /** A Messages API stream with one text block in two pieces, ending with the given stop reason. */
    private static String textReply(String stopReason) {
        return event("message_start", MESSAGE_START)
                + event("content_block_start", """
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
                + event("content_block_delta", """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Add two "}}""")
                + event("content_block_delta", """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ramp spells."}}""")
                + event("content_block_stop", """
                {"type":"content_block_stop","index":0}""")
                + event("message_delta", """
                {"type":"message_delta","delta":{"stop_reason":"%s","stop_sequence":null},\
                "usage":{"output_tokens":9}}""".formatted(stopReason))
                + event("message_stop", """
                {"type":"message_stop"}""");
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }
}
