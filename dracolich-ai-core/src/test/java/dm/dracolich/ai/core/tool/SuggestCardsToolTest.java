package dm.dracolich.ai.core.tool;

import dm.dracolich.ai.client.mtg.MtgLibraryClient;
import dm.dracolich.ai.datasource.entity.DeckCardEntity;
import dm.dracolich.ai.datasource.entity.SessionEntity;
import dm.dracolich.ai.datasource.repository.SessionRepository;
import dm.dracolich.ai.dto.CardSuggestionDto;
import dm.dracolich.ai.dto.enums.SessionType;
import dm.dracolich.mtgLibrary.dto.ArtPropertyDto;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.CardFaceDto;
import dm.dracolich.mtgLibrary.dto.GameplayPropertyDto;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Legality;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The suggest tool called the way the model calls it, against a stubbed library: only suggestions
 * the library has, that fit the deck, are kept, each with the library's id and display fields.
 */
class SuggestCardsToolTest {

    private static final String SESSION = "session-1";

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final MtgLibraryClient library = mock(MtgLibraryClient.class);
    private final SuggestCardsTool tool = new SuggestCardsTool(sessions, library);
    private final Map<String, CardDto> catalogue = new HashMap<>();
    private SessionEntity session;

    @BeforeEach
    void setUp() {
        session = SessionEntity.builder()
                .id(SESSION).sessionType(SessionType.ANALYSIS).format("COMMANDER")
                .commanderName("Krenko, Mob Boss").colorIdentity(Set.of("R"))
                .deckList(new ArrayList<>(List.of(
                        DeckCardEntity.builder().name("Krenko, Mob Boss").quantity(1).build(),
                        DeckCardEntity.builder().name("Goblin Lackey").quantity(1).build())))
                .build();
        when(sessions.findById(SESSION)).thenAnswer(i -> Mono.just(session));
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(library.resolveCards(anyList())).thenAnswer(i -> {
            List<CardDto> answer = new ArrayList<>();
            for (CardIdentifier identifier : i.<List<CardIdentifier>>getArgument(0)) {
                answer.add(catalogue.get(identifier.name().toLowerCase()));
            }
            return Mono.just(answer);
        });

        add(card("id-impact", "Impact Tremors", "{1}{R}", "Enchantment", Legality.LEGAL, Color.R));
        add(card("id-counterspell", "Counterspell", "{U}{U}", "Instant", Legality.LEGAL, Color.U));
        add(card("id-lackey", "Goblin Lackey", "{R}", "Creature — Goblin", Legality.LEGAL, Color.R));
        add(card("id-recruiter", "Goblin Recruiter", "{1}{R}", "Creature — Goblin", Legality.BANNED, Color.R));
        add(card("id-sol", "Sol Ring", "{1}", "Artifact", Legality.LEGAL));
        add(card("id-mountain", "Mountain", "", "Basic Land — Mountain", Legality.LEGAL));
    }

    private void add(CardDto card) {
        catalogue.put(card.getName().toLowerCase(), card);
    }

    private static CardDto card(String id, String name, String cost, String type, Legality commander, Color... identity) {
        GameplayPropertyDto gameplay = new GameplayPropertyDto();
        gameplay.setManaCost(cost);
        gameplay.setColorIdentity(Set.of(identity));
        CardFaceDto face = new CardFaceDto();
        face.setFullType(type);
        face.setGameplayProperty(gameplay);
        ArtPropertyDto art = new ArtPropertyDto();
        art.setImageUris(Map.of("normal", "https://img.test/" + id + ".jpg"));
        CardDto dto = new CardDto();
        dto.setId(id);
        dto.setName(name);
        dto.setDefaultFace(face);
        dto.setDefaultArt(art);
        Map<Format, Legality> legalities = new EnumMap<>(Format.class);
        legalities.put(Format.COMMANDER, commander);
        dto.setLegalities(legalities);
        return dto;
    }

    private static String suggestion(String name) {
        return "{\"cardName\":\"" + name + "\",\"category\":\"Win Condition\",\"reason\":\"Fits the plan.\",\"synergyScore\":0.9}";
    }

    private List<CardSuggestionDto> saved() {
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessions).save(captor.capture());
        return captor.getValue().getCardSuggestions();
    }

    @Test
    void aMadeUpCardAndAnOffColourCardAreDroppedAndOnlyTheValidOneIsReturned() {
        String answer = tool.suggestCards(SESSION, "[" + suggestion("Goblin Overlord Supreme") + ","
                + suggestion("Counterspell") + "," + suggestion("Impact Tremors") + "]");

        List<CardSuggestionDto> kept = saved();
        assertThat(kept).extracting(CardSuggestionDto::getCardName).containsExactly("Impact Tremors");
        CardSuggestionDto impact = kept.getFirst();
        assertThat(impact.getCardId()).isEqualTo("id-impact");
        assertThat(impact.getManaCost()).isEqualTo("{1}{R}");
        assertThat(impact.getTypeLine()).isEqualTo("Enchantment");
        assertThat(impact.getImageUri()).isEqualTo("https://img.test/id-impact.jpg");
        assertThat(impact.getCategory()).isEqualTo("Win Condition");
        assertThat(answer).contains("Suggested 1 cards: Impact Tremors.")
                .contains("Not cards in the library, dropped: Goblin Overlord Supreme.")
                .contains("Outside the commander's colour identity, dropped: Counterspell.");
        verify(library, times(1)).resolveCards(anyList()); // one call for every name
    }

    @Test
    void cardsAlreadyInTheDeckAndCardsNotLegalAreDropped() {
        String answer = tool.suggestCards(SESSION, "[" + suggestion("Goblin Lackey") + ","
                + suggestion("Goblin Recruiter") + "," + suggestion("Sol Ring") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardId).containsExactly("id-sol");
        assertThat(answer).contains("Already in the deck, dropped: Goblin Lackey.")
                .contains("Not legal in this format, dropped: Goblin Recruiter.");
    }

    @Test
    void whenNothingIsLeftTheListIsEmptyAndTheModelIsToldToSaySo() {
        String answer = tool.suggestCards(SESSION, "[" + suggestion("Not A Card") + "," + suggestion("Counterspell") + "]");

        assertThat(saved()).isEmpty();
        assertThat(answer).startsWith("None of these cards can be suggested for this deck.");
    }

    @Test
    void theLibrarysNameIsKeptAndASecondMentionIsOne() {
        tool.suggestCards(SESSION, "[" + suggestion("impact tremors") + "," + suggestion("Impact Tremors") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardName).containsExactly("Impact Tremors");
    }

    @Test
    void outsideCommanderNoColourIdentityCheck() {
        session.setFormat("MODERN");
        session.setCommanderName(null);
        session.setColorIdentity(Set.of());
        catalogue.get("counterspell").getLegalities().put(Format.MODERN, Legality.LEGAL);

        tool.suggestCards(SESSION, "[" + suggestion("Counterspell") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardName).containsExactly("Counterspell");
    }

    @Test
    void aCommanderDeckWithNoCommanderYetKeepsColouredCards() {
        session.setCommanderName(null);
        session.setColorIdentity(Set.of());

        tool.suggestCards(SESSION, "[" + suggestion("Counterspell") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardName).containsExactly("Counterspell");
    }

    @Test
    void aColourlessCommanderAllowsOnlyColourlessCards() {
        session.setCommanderName("Karn, Silver Golem");
        session.setColorIdentity(Set.of());

        tool.suggestCards(SESSION, "[" + suggestion("Impact Tremors") + "," + suggestion("Sol Ring") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardName).containsExactly("Sol Ring");
    }

    @Test
    void aBasicLandAlreadyInTheDeckIsNeverDropped() {
        session.getDeckList().add(DeckCardEntity.builder().name("Mountain").quantity(30).build());

        tool.suggestCards(SESSION, "[" + suggestion("Mountain") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardName).containsExactly("Mountain");
    }

    @Test
    void withoutACommanderACardIsDroppedOnlyAtFourCopies() {
        session.setFormat("MODERN");
        session.setCommanderName(null);
        session.setColorIdentity(Set.of());
        catalogue.get("impact tremors").getLegalities().put(Format.MODERN, Legality.LEGAL);
        catalogue.get("sol ring").getLegalities().put(Format.MODERN, Legality.LEGAL);
        session.getDeckList().add(DeckCardEntity.builder().name("Impact Tremors").quantity(2).build());
        session.getDeckList().add(DeckCardEntity.builder().name("Sol Ring").quantity(4).build());

        String answer = tool.suggestCards(SESSION, "[" + suggestion("Impact Tremors") + "," + suggestion("Sol Ring") + "]");

        assertThat(saved()).extracting(CardSuggestionDto::getCardName).containsExactly("Impact Tremors");
        assertThat(answer).contains("Already in the deck, dropped: Sol Ring.");
    }
}
