package dm.dracolich.ai.client.mtg;

import dm.dracolich.mtgLibrary.dto.records.CardCollectionRecord;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRequest;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import java.util.ArrayList;
import java.util.List;
import dm.dracolich.forge.response.DmdResponse;
import dm.dracolich.mtgLibrary.dto.*;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.dto.records.PageRecord;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MtgLibraryClient {

    private final WebClient webClient;

    public MtgLibraryClient(WebClient mtgLibraryWebClient) {
        this.webClient = mtgLibraryWebClient;
    }

    /**
     * Looks up many cards through the library's collection endpoint, at most
     * {@link CardCollectionRequest#MAX_IDENTIFIERS} per call. Returns a list aligned with
     * {@code identifiers}: the card for each one, or null where the library found none.
     */
    public Mono<List<CardDto>> resolveCards(List<CardIdentifier> identifiers) {
        if (identifiers.isEmpty()) {
            return Mono.just(List.of());
        }
        int max = CardCollectionRequest.MAX_IDENTIFIERS;
        List<List<CardIdentifier>> chunks = new ArrayList<>();
        for (int from = 0; from < identifiers.size(); from += max) {
            chunks.add(identifiers.subList(from, Math.min(from + max, identifiers.size())));
        }
        return Flux.fromIterable(chunks)
                .concatMap(chunk -> webClient.post()
                        .uri("/cards/collection")
                        .bodyValue(new CardCollectionRequest(chunk))
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardCollectionRecord>>() {})
                        .map(response -> align(chunk, response.getPayload())))
                .collectList()
                .map(parts -> {
                    List<CardDto> all = new ArrayList<>(identifiers.size());
                    parts.forEach(all::addAll);
                    return all;
                });
    }

    /**
     * Pairs each identifier with its card. The library answers with the cards found and the
     * identifiers not found, both in request order, so walking the request recovers the pairs.
     */
    static List<CardDto> align(List<CardIdentifier> request, CardCollectionRecord record) {
        List<CardDto> cards = record != null && record.cards() != null ? record.cards() : List.of();
        List<CardIdentifier> notFound = record != null && record.notFound() != null ? record.notFound() : List.of();
        List<CardDto> aligned = new ArrayList<>(request.size());
        int nextCard = 0;
        int nextMissing = 0;
        for (CardIdentifier identifier : request) {
            if (nextMissing < notFound.size() && notFound.get(nextMissing).equals(identifier)) {
                aligned.add(null);
                nextMissing++;
            } else {
                aligned.add(nextCard < cards.size() ? cards.get(nextCard++) : null);
            }
        }
        return aligned;
    }

    public Mono<CardDto> fetchCardById(String id) {
        return webClient.get()
                .uri("/cards/{id}", id)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<CardDto> fetchCardByName(String name) {
        return webClient.get()
                .uri(builder -> builder.path("/cards").queryParam("name", name).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<PageRecord> searchCards(String name, CardSearchRecord searchRecord, int page, int size) {
        return webClient.post()
                .uri(builder -> {
                    builder.path("/cards/search")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (name != null && !name.isBlank()) {
                        builder.queryParam("name", name);
                    }
                    return builder.build();
                })
                .bodyValue(searchRecord)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<PageRecord>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<CardDto> fetchRandomCard(CardSearchRecord searchRecord) {
        return webClient.post()
                .uri("/cards/random")
                .bodyValue(searchRecord)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<GameFormatDto> fetchFormatByCode(String code) {
        return webClient.get()
                .uri("/formats/")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<java.util.List<GameFormatDto>>>() {})
                .flatMapMany(resp -> Flux.fromIterable(resp.getPayload()))
                .filter(f -> f.getCode().equalsIgnoreCase(code))
                .next();
    }

    public Mono<CollectionSetDto> fetchSetById(String id) {
        return webClient.get()
                .uri("/sets/{id}", id)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CollectionSetDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<CollectionSetDto> fetchSetByCode(String code) {
        return webClient.get()
                .uri(builder -> builder.path("/sets").queryParam("code", code).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CollectionSetDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<ArtPropertyDto> fetchArtPropertyByCardId(String cardId) {
        return webClient.get()
                .uri(builder -> builder.path("/art-properties").queryParam("card_id", cardId).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<ArtPropertyDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<RulingDto> fetchRulingById(String id) {
        return webClient.get()
                .uri("/rulings/{id}", id)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<RulingDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<SymbolDto> fetchSymbolBySymbol(String symbol) {
        return webClient.get()
                .uri(builder -> builder.path("/symbols").queryParam("symbol", symbol).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<SymbolDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<SubtypeDto> fetchSubtypeByCode(String code) {
        return webClient.get()
                .uri(builder -> builder.path("/subtypes").queryParam("code", code).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<SubtypeDto>>() {})
                .map(DmdResponse::getPayload);
    }

    public Mono<CardLayoutDto> fetchLayoutByCode(String code) {
        return webClient.get()
                .uri(builder -> builder.path("/layouts").queryParam("code", code).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardLayoutDto>>() {})
                .map(DmdResponse::getPayload);
    }
}
