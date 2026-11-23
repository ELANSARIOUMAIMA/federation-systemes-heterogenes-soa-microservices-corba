# ADR-006 — Observabilité distribuée (OpenTelemetry + Jaeger) et résilience (Resilience4j)

- **Statut :** accepté
- **Date :** 2026-11-22
- **Phase :** P3 (Robustesse et restitution)

## Contexte

La plateforme fédère trois paradigmes incompatibles (CORBA/IIOP en Java 8, SOAP/XML en Java 17, REST + RabbitMQ en Spring Boot 3.3) sans droit de modification des deux systèmes legacy (`rail-operator-corba/`, `air-partner-soap/`). Deux problèmes majeurs subsistaient à l'issue de P2 :

1. **Opacité des parcours distribués** : aucune trace distribuée ni corrélation de logs entre les 7 microservices et les deux protocoles legacy ; de plus, la publication asynchrone via la table *transactional outbox* (`booking_outbox`) coupe naturellement le contexte HTTP synchrone.
2. **Vulnérabilité aux pannes amont** : un arrêt de l'opérateur `rail` faisait attendre la recherche Gateway **8,2 s** (et les appels CORBA jusqu'à **14 s** à cause des 5 retries internes par défaut de JacORB) ; le client SOAP d'`air-bridge` n'avait aucun timeout ; et aucun circuit breaker ne protégeait les bridges, l'orchestrateur Saga ou la passerelle.

## Décisions

### 1. Traçabilité distribuée : agent Java OpenTelemetry + Jaeger + spans manuels

- **Agent Java `opentelemetry-javaagent` 2.10.0** embarqué dans les 7 images Docker (compatible **Java 8** pour `rail-bridge` comme **Java 17** pour les autres services) et export OTLP HTTP vers **Jaeger all-in-one 1.62.0** (`http://jaeger:4318`, UI sur `:16686`).
- **Spans manuels `CLIENT` dans les bridges** via `opentelemetry-api` (no-op sans agent) :
  - `rail-bridge` : `CORBA RailBooking/<search|hold|confirm|cancel>` (`rpc.system=corba`, `network.protocol.name=iiop`), car l'agent OTel n'instrumente pas IIOP/JacORB ;
  - `air-bridge` : `SOAP AirPartner/<searchFlights|holdFlight|confirmFlight|cancelFlight>` (`rpc.system=soap`), rendant l'opération métier SOAP lisible au-dessus du transport HTTP.
- **Propagation de trace à travers le *transactional outbox*** : `BookingPersistence.transition()` capture le header W3C `traceparent` dans `booking_outbox.traceparent` au sein de la transaction JPA ; `OutboxPublisher` restaure ce contexte parent avant de publier sur RabbitMQ et l'injecte dans les headers AMQP, ce qui rattache `notification-service` à **la même trace Jaeger** que la requête HTTP initiale.
- **Logs corrélés** : `logging.pattern.level=%5p [trace_id=%X{trace_id:-} span_id=%X{span_id:-}]` sur les 7 services (MDC peuplé automatiquement par l'agent OTel).
- **Sondes de santé silencieuses** : les `healthcheck` Docker utilisent une vérification TCP (`exec 3<>/dev/tcp/127.0.0.1/8080`) plutôt que `curl /actuator/health` afin de ne pas polluer Jaeger de traces synthétiques toutes les 10 s.

### 2. Résilience : Resilience4j, budget de timeouts cohérent et retries strictement idempotents

- **Isolation de versions Java 8 vs Java 17** : `rail-bridge` (Java 8 / Spring Boot 2.7) utilise `resilience4j-circuitbreaker` **1.7.1** ; les services Java 17 (`air-bridge`, `booking-service`, `api-gateway`) utilisent Resilience4j **2.2.0** (ligne 2.x incompatible Java 8).
- **Désactivation des retries internes JacORB (`jacorb.retries=0`)** : par défaut JacORB relance 5 fois chaque connexion TCP échouée (5 × 2 s + 5 × 500 ms ≈ 14 s par appel), ce qui contournait les timeouts du bridge et pouvait même rejouer tacitement une tentative côté ORB. Désactiver `jacorb.retries` redonne le contrôle exclusif au bridge.
- **Budget de timeouts hiérarchisé** (de l'aval vers l'amont) :
  - `rail-bridge` (JacORB) : connect **1 s**, reply **2,5 s** (budget hold/confirm = 3,5 s ; search 2 tentatives = 2 s) ;
  - `air-bridge` (Spring WS `HttpUrlConnectionMessageSender`) : connect **2 s**, read **3 s** (budget hold/confirm = 5 s) ;
  - `api-gateway` (recherche parallèle) : délai par source **3,5 s** (`CompletableFuture.orTimeout`), proxy booking **20 s** ;
  - `booking-service` (`RestTemplate`) : connect **2 s**, read **6 s** (> 5 s des bridges, évitant les faux `UNKNOWN_OUTCOME`).
- **Retry uniquement sur les opérations idempotentes** : `GET /v1/offers` (`search`) et `DELETE /v1/references/{ref}` (`cancel`) sont rejoués au plus 1 fois sur erreur technique transitoire. `POST /v1/holds` et `POST /v1/holds/{id}/confirm` ne sont **jamais** rejoués automatiquement.
- **Circuit breakers distinguant métier et panne** : seuls les échecs techniques (timeout, erreur réseau, HTTP 5xx, `SystemException` CORBA) alimentent le taux d'échec. Un refus métier `409 NOT_AVAILABLE` (ex. vol complet) prouve que l'opérateur fonctionne et **n'ouvre jamais le circuit**. Un circuit ouvert renvoie HTTP `503` avec le code canonique `CIRCUIT_OPEN`, interprété par la Saga comme un échec certain (`HOLD_FAILED`, compensation immédiate, pas de `UNKNOWN_OUTCOME`) et par la passerelle comme une source dégradée immédiate (`X-Partial-Results`, `X-Degraded-Reason: <src>:CIRCUIT_OPEN`).
- **Diagnostic Actuator** : `/actuator/health` (ouvert sans authentification sur la passerelle) expose l'état détaillé (`CLOSED` / `OPEN` / `HALF_OPEN`, taux d'échec, appels rejetés) de chaque circuit breaker.

## Compromis et limites assumés

- **Échantillonnage `always_on` (100 %)** et stockage Jaeger en mémoire : idéal pour la démonstration pédagogique ; en production, on basculerait vers un échantillonnage probabiliste (ex. 5–10 %) ou *tail-based sampling* et un backend persistant (Elasticsearch/Tempo).
- **Systèmes legacy non instrumentés côté serveur** : conformément à la contrainte d'intangibilité de `rail-operator-corba/` et `air-partner-soap/`, la trace s'arrête aux spans `CLIENT` des bridges (et se recoupe avec les horodatages des logs des deux conteneurs legacy).
- **Double niveau de circuit breaker** (bridges + passerelle/booking) : protège à la fois contre une panne du partenaire legacy (`rail`/`air`) et contre une panne du bridge lui-même, au prix d'une configuration coordonnée des fenêtres (3 appels min. sur bridges et gateway, 15 s d'ouverture).
