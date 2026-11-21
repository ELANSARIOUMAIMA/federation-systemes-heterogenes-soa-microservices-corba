# ADR 002 — Modèle canonique comme couche anti-corruption

**Statut :** accepté

## Contexte
Trois mondes coexistent : CORBA (struct `Trip`), SOAP (type `Flight`) et JSON (autopartage, services internes). Leurs représentations diffèrent par les noms, les types et la gestion des erreurs.

## Décision
- Un module Maven partagé `canonical-model`, **Java 8, sans dépendance**, contenant `Offer`, `TransportMode`, `HoldRequest`, `HoldResponse`, `ConfirmResponse` et `ApiError`.
- `Offer` : `offerId` (préfixé `RAIL:`, `AIR:` ou `CAR:`), `mode`, `origin`, `destination`, `departure`, `arrival`, `priceMinor` (centimes de MAD), `currency` (MAD), `seatsAvailable`.
- Le **préfixe de `offerId`** permet à l'orchestrateur de router vers le bon bridge sans jamais voir le protocole sous-jacent.
- Les **dates restent des chaînes ISO-8601** : les deux contrats fournis les exposent ainsi (le XSD fourni utilise `xsd:string`, bien que l'énoncé évoque `xsd:dateTime`).
- `DELETE /v1/references/{ref}` accepte un `holdId` ou un `bookingRef` et répond toujours 200 : c'est la sémantique idempotente des deux legacy.
- `ApiError {code, reason}` avec `NOT_AVAILABLE` (409), `UPSTREAM_UNAVAILABLE` (503) et `INVALID_REQUEST` (400).
- L'API est versionnée (`/v1`).

## Conséquences
- (+) Le contrat REST est identique pour les bridges et pour le carshare-service, ce qui rend la saga homogène.
- (+) Aucun type legacy ne sort des bridges.
- (−) Les champs sont publics et sans validation intégrée (simples objets de transfert) ; la validation est faite dans les contrôleurs.
- (−) Toute évolution doit rester compatible : ajouter des champs optionnels, ne pas renommer.