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

---

# HANDOFF P2 vers P3

## Ce qui fonctionne

- `carshare-service` : recherche des offres, holds de 10 minutes, confirmation et annulation.
- `pricing-service` : calcul des devis en MAD (centimes).
- `booking-service` : orchestration Saga, compensation, idempotence et outbox.
- `notification-service` : consommation des événements RabbitMQ avec déduplication.
- `api-gateway` : authentification Basic Auth, recherche multimodale et réservation.

Les services P2 sont intégrés avec les bridges P1 (CORBA et SOAP).

## Validation

- Compilation Maven des 9 modules.
- 14 tests automatisés réussis.
- Scénarios de réservation nominale, compensation et idempotence validés.
- Vérification HTTP via `docs/requests/verify-p2.ps1`.

Résultats détaillés : `docs/runtime-validation-report.md`.

## Commandes

Depuis PowerShell, à la racine du projet, avec les services P1 déjà démarrés :

```powershell
$env:RABBITMQ_USERNAME = "mobility-demo"
$env:RABBITMQ_PASSWORD = "<mot-de-passe-stable>"
$env:GATEWAY_USERNAME = "demo"
$env:GATEWAY_PASSWORD = "<mot-de-passe-demo>"
$env:COMPOSE_PARALLEL_LIMIT = "1"

docker compose build carshare-service pricing-service booking-service notification-service api-gateway
docker compose up -d --no-deps rabbitmq carshare-service pricing-service booking-service notification-service api-gateway
docker compose ps
.\docs\requests\verify-p2.ps1
```

Conserver les identifiants RabbitMQ lors des redémarrages avec volumes persistants.

## Limites connues

- Stockage H2 et Basic Auth réservés à la démonstration.
- En cas de timeout après un hold distant, un état `UNKNOWN_OUTCOME` peut nécessiter une réconciliation manuelle.
- RabbitMQ fonctionne avec une livraison au moins une fois, compensée par une déduplication des événements.

## Travail attendu de P3

- Ajouter l'observabilité distribuée et Jaeger.
- Intégrer Resilience4j et les circuit breakers.
- Vérifier les scénarios de résilience et l'intégration globale.
- Préserver la compatibilité avec les contrats REST des bridges et les endpoints de réservation.

## Documentation

- `docs/P2_IMPLEMENTATION.md`
- `docs/requests/p2.http`
- `docs/requests/verify-p2.ps1`
- `docs/runtime-validation-report.md`
- ADR-003, ADR-004 et ADR-005.