package dm.dracolich.ai.core.tool;

import dm.dracolich.ai.client.mtg.MtgLibraryClient;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import dm.dracolich.ai.datasource.entity.SessionEntity;
import dm.dracolich.ai.datasource.repository.SessionRepository;
import dm.dracolich.ai.dto.CardSuggestionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SuggestCardsTool {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SessionRepository sessionRepository;
    private final MtgLibraryClient library;

    @Tool(description = "Formally suggest cards for the user's deck. Call this whenever you recommend cards. " +
            "Pass a JSON array where each element has: cardName, category (Ramp, Removal, Card Draw, Win Condition, Creature, Land, etc.), " +
            "reason (1 sentence), synergyScore (0.0 to 1.0), and optionally topic (must match a topic key " +
            "from a prior reportIssues call, like \"removal_count\" or \"card_draw\", to link the suggestion to " +
            "a specific finding). Omit topic for non-analysis sessions or if the suggestion isn't tied to a finding. " +
            "Do NOT list these cards again in your text response.")
    public String suggestCards(
            @ToolParam(description = "The session ID") String sessionId,
            @ToolParam(description = "JSON array of suggestions, e.g. [{\"cardName\":\"Counterspell\",\"category\":\"Interaction\",\"reason\":\"Cheap counter for opposing threats\",\"synergyScore\":0.9,\"topic\":\"removal_count\"}]") String suggestionsJson
    ) {
        List<CardSuggestion> suggestions;
        try {
            suggestions = MAPPER.readValue(suggestionsJson, new TypeReference<List<CardSuggestion>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse suggestions JSON: {}", e.getMessage());
            return "Error: invalid suggestions format. Send a JSON array of objects with cardName, category, reason, synergyScore.";
        }

        try {
            SessionEntity session = sessionRepository.findById(sessionId).toFuture().join();
            if (session == null) {
                return "Session not found: " + sessionId;
            }

            // Every suggested name through the library in one call, then the deck's checks.
            List<CardDto> found = library.resolveCards(suggestions.stream()
                            .map(s -> CardIdentifier.byName(s.cardName() == null ? "" : s.cardName().trim()))
                            .toList())
                    .toFuture().join();
            SuggestionCheck.Result result = SuggestionCheck.check(suggestions, found, session);
            log.info("Suggestions for session {}: {} asked, {} kept, {} dropped {}.", sessionId,
                    suggestions.size(), result.kept().size(), result.droppedCount(), counts(result));

            session.setCardSuggestions(result.kept());
            sessionRepository.save(session).toFuture().join();

            return answer(result);
        } catch (Exception e) {
            log.error("Failed to check or save card suggestions for session {}: {}", sessionId, e.getMessage());
            return "Failed to save suggestions. Tell the player no suggestions could be checked this time.";
        }
    }

    /** What the model is told: what was kept, what was dropped and why, and what to say when nothing was kept. */
    static String answer(SuggestionCheck.Result result) {
        StringBuilder text = new StringBuilder();
        if (result.kept().isEmpty()) {
            text.append("None of these cards can be suggested for this deck. Tell the player in one sentence that ")
                    .append("you found no suggestions that fit it, and do not name the dropped cards as suggestions.");
        } else {
            text.append("Suggested ").append(result.kept().size()).append(" cards: ")
                    .append(result.kept().stream().map(CardSuggestionDto::getCardName).collect(Collectors.joining(", ")))
                    .append('.');
        }
        result.dropped().forEach((reason, names) -> text.append(' ')
                .append(switch (reason) {
                    case NOT_A_CARD -> "Not cards in the library, dropped: ";
                    case IN_DECK -> "Already in the deck, dropped: ";
                    case NOT_LEGAL -> "Not legal in this format, dropped: ";
                    case OFF_COLOUR -> "Outside the commander's colour identity, dropped: ";
                })
                .append(String.join(", ", names)).append('.'));
        return text.toString();
    }

    private static String counts(SuggestionCheck.Result result) {
        return result.dropped().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().size())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    public record CardSuggestion(
            String cardName,
            String category,
            String reason,
            Double synergyScore,
            String topic
    ) {}
}
