package dm.dracolich.ai.web.controller;

import dm.dracolich.ai.core.service.AgentService;
import dm.dracolich.forge.controller.ControllerAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Since forge 8.1.3 a request Spring rejects keeps its own status and DMD043, instead of a 500. */
class BadRequestTest {

    private final AgentService service = mock(AgentService.class);
    private final WebTestClient client = WebTestClient.bindToController(new SessionController(service))
            .controllerAdvice(new ControllerAdvice())
            .build();

    @Test
    void aMalformedSessionBodyIs400WithDmd043() {
        client.post().uri("/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ this is not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].error").isEqualTo("DMD043");

        verifyNoInteractions(service);
    }

    @Test
    void aPageThatIsNotANumberIs400WithDmd043() {
        client.get().uri("/agent/session?user_id=u&page=first")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].error").isEqualTo("DMD043");
    }
}
