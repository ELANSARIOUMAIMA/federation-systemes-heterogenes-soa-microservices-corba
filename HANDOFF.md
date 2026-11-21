# HANDOFF P1 vers P2

## Ce qui marche (testé à la main)
- `canonical-model` : Offer, HoldRequest, HoldResponse, ConfirmResponse, ApiError (Java 8, sans dépendance).
- `rail-bridge` (CORBA vers REST, Java 8, port hôte 8082) et `air-bridge` (SOAP vers REST, Java 17, port hôte 8083) : search, hold, confirm, cancel.
- cancel rejoué ou sur référence inconnue : 200. Hold impossible : 409 `{code, reason}`. Date invalide : 400.
- Hold de 4 places sur `AIR:AIR-CMN-MAD-1800` (3 places) : 409.
- Le rail-bridge survit à un `docker compose restart rail` (IOR relue, un retry).
- `docker compose up --build` construit et démarre rail, air et les deux bridges.

## Contrat à respecter pour le carshare-service
Même API que les bridges (voir `docs/bridges.md`), offerId préfixé `CAR:`, cancel idempotent qui accepte holdId ou bookingRef, hold qui expire après 10 minutes, erreurs `{code, reason}` en 409.

## Ce qui est bancal ou manquant
- Aucun test automatisé (tests manuels via `docs/requests/bridges.http`).
- Aucun timeout configuré côté SOAP (seul CORBA a des timeouts) ; pas de circuit breaker (prévu en phase P3, Resilience4j).
- Pas de healthcheck dans le compose : les services démarrent dans le désordre (le rail-bridge relit l'IOR à la demande, donc pas de problème côté rail).
- Pas d'authentification sur les bridges (ils ne sont appelés que depuis le réseau Docker).
- Le rail-bridge est en Spring Boot 2.7 / Java 8 (voir ADR 001) ; ne pas lui ajouter de dépendances Java 17.

## Commandes
    docker compose up --build -d          # tout démarrer
    docker compose logs rail-bridge -f    # logs d'un service
    docker compose down                   # tout arrêter

Ports hôte : rail 1050 (IIOP), air 8081 (WSDL : `/ws/air.wsdl`), rail-bridge 8082, air-bridge 8083.
Docker : `http://rail-bridge:8080`, `http://air-bridge:8080`.

## Pièges
- Dates de démo : 2026-11-15 (et un trajet le 16), sinon la recherche renvoie une liste vide.
- `rail-operator-corba/` et `air-partner-soap/` ne doivent jamais être modifiés.
- Un `confirm` après 10 minutes renvoie 409 : confirmer juste après les holds.