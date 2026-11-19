package dm.dracolich.ai.core.tool;

import dm.dracolich.ai.datasource.entity.DeckCardEntity;
import dm.dracolich.ai.datasource.entity.SessionEntity;
import dm.dracolich.ai.dto.CardSuggestionDto;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Legality;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of the model's suggested cards may be returned (Ixian-Works/dracolich-ai-api#14). A suggestion
 * is kept only when the library has the card; it isn't already in the deck as far as the deck allows
 * (a basic land never counts, a deck with a commander holds one of each card, any other deck four);
 * it is legal in the deck's format; and, when the deck has a commander or a colour identity, it is
 * within that identity (a colourless commander allows only colourless cards). A kept
 * suggestion carries the library's id, name, mana cost, type line and image, so the app can draw it
 * and add it without another lookup.
 */
public final class SuggestionCheck {

    /** Why a suggestion was dropped. */
    public enum Dropped { NOT_A_CARD, IN_DECK, NOT_LEGAL, OFF_COLOUR }

    /** The suggestions kept, in the model's order, and how many were dropped for each reason. */
    public record Result(List<CardSuggestionDto> kept, Map<Dropped, List<String>> dropped) {
        public int droppedCount() {
            return dropped.values().stream().mapToInt(List::size).sum();
        }
    }

    private SuggestionCheck() {
    }

    /**
     * Checks {@code suggestions} against the session's deck. {@code found} is the library's answer for
     * each suggested name, aligned with {@code suggestions}, null where it has no such card.
     */
    public static Result check(List<SuggestCardsTool.CardSuggestion> suggestions, List<CardDto> found,
                               SessionEntity session) {
        Map<String, Integer> inDeck = deckCopies(session);
        Format format = format(session.getFormat());
        Set<String> identity = session.getColorIdentity() == null ? Set.of()
                : session.getColorIdentity().stream().map(c -> c.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        boolean hasCommander = notBlank(session.getCommanderName()) || notBlank(session.getCommanderId());
        // A Commander deck with no commander yet has no identity to check against.
        boolean checkColour = hasCommander || !identity.isEmpty();
        int copiesAllowed = hasCommander ? 1 : 4;

        List<CardSuggestionDto> kept = new ArrayList<>();
        Map<Dropped, List<String>> dropped = new EnumMap<>(Dropped.class);
        for (int i = 0; i < suggestions.size(); i++) {
            SuggestCardsTool.CardSuggestion suggestion = suggestions.get(i);
            CardDto card = i < found.size() ? found.get(i) : null;
            String asked = suggestion.cardName() == null ? "" : suggestion.cardName().trim();
            Dropped reason = card == null ? Dropped.NOT_A_CARD
                    : !isBasicLand(card) && inDeck.getOrDefault(lower(card.getName()), 0) >= copiesAllowed ? Dropped.IN_DECK
                    : !legal(card, format) ? Dropped.NOT_LEGAL
                    : checkColour && !withinIdentity(card, identity) ? Dropped.OFF_COLOUR
                    : null;
            if (reason != null) {
                dropped.computeIfAbsent(reason, r -> new ArrayList<>()).add(asked);
                continue;
            }
            if (kept.stream().anyMatch(k -> k.getCardId().equals(card.getId()))) continue; // suggested twice
            kept.add(CardSuggestionDto.builder()
                    .cardId(card.getId())
                    .cardName(card.getName())
                    .manaCost(manaCost(card))
                    .typeLine(card.getDefaultFace() != null ? card.getDefaultFace().getFullType() : null)
                    .imageUri(image(card))
                    .category(suggestion.category())
                    .reason(suggestion.reason())
                    .synergyScore(suggestion.synergyScore())
                    .topic(suggestion.topic())
                    .build());
        }
        return new Result(kept, dropped);
    }

    /** Copies of each card in the deck, by lower-cased name. */
    private static Map<String, Integer> deckCopies(SessionEntity session) {
        Map<String, Integer> copies = new java.util.HashMap<>();
        if (session.getDeckList() == null) return copies;
        for (DeckCardEntity entry : session.getDeckList()) {
            if (entry.getName() == null) continue;
            copies.merge(lower(entry.getName()), entry.getQuantity() != null ? entry.getQuantity() : 1, Integer::sum);
        }
        return copies;
    }

    /** A basic land, of which a deck may hold any number. */
    private static boolean isBasicLand(CardDto card) {
        String type = card.getDefaultFace() != null ? card.getDefaultFace().getFullType() : null;
        if (type == null) return false;
        String left = type.split("—", 2)[0];
        return left.contains("Basic") && left.contains("Land");
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static Format format(String format) {
        if (format == null) return null;
        try {
            return Format.valueOf(format.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Legal or restricted in the format; with no format, or no legality data, it isn't checked. */
    private static boolean legal(CardDto card, Format format) {
        if (format == null || card.getLegalities() == null) return true;
        Legality legality = card.getLegalities().get(format);
        return legality == null || legality == Legality.LEGAL || legality == Legality.RESTRICTED;
    }

    private static boolean withinIdentity(CardDto card, Set<String> identity) {
        Set<Color> colours = card.getDefaultFace() != null && card.getDefaultFace().getGameplayProperty() != null
                ? card.getDefaultFace().getGameplayProperty().getColorIdentity() : null;
        if (colours == null) return true;
        return colours.stream().filter(c -> c != Color.C).allMatch(c -> identity.contains(c.name()));
    }

    private static String manaCost(CardDto card) {
        return card.getDefaultFace() != null && card.getDefaultFace().getGameplayProperty() != null
                ? card.getDefaultFace().getGameplayProperty().getManaCost() : null;
    }

    private static String image(CardDto card) {
        if (card.getDefaultArt() == null || card.getDefaultArt().getImageUris() == null) return null;
        Map<String, String> uris = card.getDefaultArt().getImageUris();
        return uris.getOrDefault("normal", uris.getOrDefault("small", uris.get("large")));
    }

    private static String lower(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
