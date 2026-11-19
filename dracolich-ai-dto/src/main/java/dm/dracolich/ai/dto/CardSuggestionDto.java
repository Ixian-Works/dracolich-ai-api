package dm.dracolich.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardSuggestionDto {
    /** The library's id for the card: every suggestion returned is a card the library has. */
    @JsonProperty("card_id")
    private String cardId;
    /** The library's name for the card, which may differ from what the model wrote in case or spelling of a face. */
    @JsonProperty("card_name")
    private String cardName;
    @JsonProperty("mana_cost")
    private String manaCost;
    @JsonProperty("type_line")
    private String typeLine;
    /** The card's image, so the app can draw the suggestion without another lookup. */
    @JsonProperty("image_uri")
    private String imageUri;
    private String reason;
    private String category;
    @JsonProperty("synergy_score")
    private Double synergyScore;
    /**
     * Optional reference to an issue topic this suggestion addresses (e.g. "removal_count",
     * "card_draw"). Set during ANALYSIS sessions; null for BUILD sessions or when the AI
     * doesn't link the suggestion to a specific finding.
     */
    private String topic;
}
