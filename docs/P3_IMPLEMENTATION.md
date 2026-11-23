# Journal d'implémentation P3 — Robustesse, observabilité et restitution

> **Branche :** `feat/p3-resilience-observability` (partie de `main` @ `handoff-2`)
> **Objectif :** Ce document recense **l'intégralité** des modifications, choix techniques, problèmes rencontrés et mesures vérifiées durant la phase P3, afin de fournir toute la matière factuelle nécessaire à la rédaction du rapport (8–12 pages) et à la soutenance.

---

## 1. Audit de départ (état à l'issue de P2)

Vérification complète exécutée avant toute modification P3 (22/11/2026) :

| Vérification | Résultat avant P3 |
|---|---|
| `mvn -B -ntp test` (9 modules, `maven:3.9-eclipse-temurin-17`) | BUILD SUCCESS, 14/14 tests verts |
| `docker compose config -q` **sans variables d'environnement** | **Échec** : `GATEWAY_USERNAME/PASSWORD` et `RABBITMQ_USERNAME/PASSWORD` obligatoires (`:?`) sans défaut |
| `docker compose up --build -d` avec variables | 10 conteneurs démarrés (`notification-service` émet une erreur `Connection refused` initiale avant reconnexion 5 s plus tard) |
| `docs/requests/verify-p2.ps1` | 6/6 groupes de scénarios PASS |
| Saga 3 modes (`TRAIN + AIR + CAR`) nominale | `CONFIRMED`, total `183475` centimes MAD |
| Saga 3 modes avec `AIR-CMN-MAD-1800` × 4 | `CANCELLED`, `TRAIN` et `CAR` `COMPENSATED` |
| `docker stop rail` puis `GET /api/v1/search` | `200` + `X-Partial-Results: rail`, mais **8 176 ms** de latence systématique (aucun circuit breaker) |
| `rail-operator-corba/` et `air-partner-soap/` | 100 % intacts (aucun diff depuis le starter kit) |

**Écarts identifiés à combler en P3 :**
1. Démarrage à froid non « one-command » (`docker compose up` bloquait sans variables).
2. Aucune traçabilité distribuée (ni agent OpenTelemetry, ni Jaeger, ni corrélation de logs MDC).
3. Aucun circuit breaker ; aucun timeout sur le client SOAP (`air-bridge`) ; timeout CORBA (`10 s`) supérieur au read timeout de `booking-service` (`5 s`), exposant les holds lents à des faux `UNKNOWN_OUTCOME`.
4. Retries internes cachés dans JacORB (`jacorb.retries=5` par défaut) provoquant **~14 s par appel CORBA en échec** (découvert en test live, voir §4.2).
5. `booking-service` partageait un seul thread de scheduling entre outbox, reprise de compensation et récupération ; reprise de compensation bornée à 20 × 2 s = 40 s.
6. Aucun test automatisé sur `rail-bridge` ni `air-bridge` ; pas de test d'intégration HTTP réel pour la compensation multi-modes ; `README.md` obsolète (pointait encore vers le starter kit et un fichier `P2_HANDOFF.md` inexistant).

---

## 2. Modèle canonique et contrats préservés

| Fichier modifié | Changement | Justification |
|---|---|---|
| `canonical-model/src/main/java/ma/mobility/canonical/ApiError.java` | Ajout de la constante `CIRCUIT_OPEN = "CIRCUIT_OPEN"` | Distinguer un échec réseau incertain (`UPSTREAM_UNAVAILABLE`) d'un rejet local par circuit ouvert (`CIRCUIT_OPEN`, opérateur non appelé → aucun effet de bord). Compatibilité totale avec P1/P2. |

---

## 3. Observabilité distribuée (OpenTelemetry + Jaeger + MDC)

### 3.1 Architecture retenue

- **Collecteur & UI :** `jaegertracing/all-in-one:1.62.0` ajouté dans `docker-compose.yml` avec `COLLECTOR_OTLP_ENABLED=true`, ports `16686` (UI), `4317` (OTLP gRPC) et `4318` (OTLP HTTP).
- **Agent Java :** `opentelemetry-javaagent` **2.10.0** téléchargé depuis Maven Central dans la couche runtime des **7 Dockerfiles** (`/opt/otel/opentelemetry-javaagent.jar`) et activé via un bloc YAML partagé (`x-otel-env`) dans `docker-compose.yml` (`JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"`, `OTEL_TRACES_SAMPLER=always_on`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318`).
- **Compatibilité Java 8 + Java 17 :** l'agent 2.10.0 et `opentelemetry-api` 1.44.1 fonctionnent à la fois sur **Java 8** (`rail-bridge` / Spring Boot 2.7) et **Java 17** (tous les autres services / Spring Boot 3.x). Sans agent (en tests unitaires), `GlobalOpenTelemetry` est un *no-op* sans impact.
- **Respect strict de l'intangibilité legacy :** `rail-operator-corba/` et `air-partner-soap/` n'ont pas été touchés.

### 3.2 Spans manuels sur les protocoles legacy (CORBA et SOAP)

L'agent Java OpenTelemetry instrumente automatiquement HTTP, Spring MVC, `RestTemplate`, JDBC/H2, Hibernate et RabbitMQ, mais **n'instrumente pas CORBA/IIOP (JacORB)** et ne nomme pas les opérations métier SOAP. Nous avons donc ajouté des spans `SpanKind.CLIENT` manuels :

| Service | Fichier | Nom de span généré | Attributs clés |
|---|---|---|---|
| `rail-bridge` | `RailCorbaClient.java` | `CORBA RailBooking/<search\|hold\|confirm\|cancel>` | `rpc.system=corba`, `rpc.service=mobility::rail::RailBooking`, `network.protocol.name=iiop`, `rail.idempotent`, `mobility.hold_id` / `booking_ref`, `resilience.circuit_breaker` |
| `air-bridge` | `AirSoapClient.java` | `SOAP AirPartner/<searchFlights\|holdFlight\|confirmFlight\|cancelFlight>` | `rpc.system=soap`, `rpc.service=AirPartner`, `air.idempotent`, `mobility.hold_id` / `booking_ref`, `resilience.circuit_breaker` |

### 3.3 Continuité de trace à travers le *Transactional Outbox* → RabbitMQ

**Point dur d'architecture :** dans `booking-service`, la Saga écrit ses événements dans la table `booking_outbox`, puis un `@Scheduled` (`OutboxPublisher`) les publie vers RabbitMQ 0 à 2 s plus tard dans un autre thread. Par défaut, cela coupe la trace en deux traces indépendantes.

**Solution implémentée :**
1. `OutboxEventEntity.java` : ajout du champ nullable `traceparent` (colonne ajoutée automatiquement par Hibernate `ddl-auto=update`).
2. `BookingPersistence.java` : capture du header W3C `traceparent` courant via `GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(...)` dans la même transaction que la transition Saga.
3. `OutboxPublisher.java` : restauration du `Context` parent depuis `event.traceparent`, création d'un span `outbox.publish <eventType>` (`SpanKind.PRODUCER`) rattaché à ce parent, puis injection du contexte W3C dans les headers AMQP du message RabbitMQ.
4. `notification-service` reçoit automatiquement le `traceparent` dans les headers AMQP et crée ses spans de traitement dans **la même trace Jaeger**.

### 3.4 Corrélation des logs (MDC) et sondes silencieuses

- Pattern Logback ajouté dans les `application.properties` des **7 services** :
  ```properties
  logging.pattern.level=%5p [trace_id=%X{trace_id:-} span_id=%X{span_id:-}]
  ```
- Ajout de logs métiers concis (`INFO` / `WARN`) dans `GatewayController`, `BookingService`, `OutboxPublisher`, `PricingController`, `CarshareController`, `RailCorbaClient` et `AirSoapClient`.
- **Sondes Docker sans pollution Jaeger :** les `healthcheck` des conteneurs HTTP utilisent une ouverture TCP Bash (`exec 3<>/dev/tcp/127.0.0.1/8080`) au lieu de `curl /actuator/health` afin de ne pas injecter des centaines de traces synthétiques dans Jaeger.

### 3.5 Preuve mesurée en live (Jaeger + logs)

Sur une réservation nominale 3 modes (`POST /api/v1/bookings`, `TRAIN + AIR + CAR`, `totalMinor=183475`, état `CONFIRMED`) :
- **Trace unique vérifiée dans Jaeger :** `af6a07680a29f92e264a5039ddb62408` — **197 spans répartis sur les 7 services** (`api-gateway`, `booking-service`, `pricing-service`, `rail-bridge`, `air-bridge`, `carshare-service`, `notification-service`), incluant `CORBA RailBooking/search|hold|confirm`, `SOAP AirPartner/searchFlights|holdFlight|confirmFlight`, `outbox.publish BookingHeld|BookingConfirmed` et la consommation `notification-service`.
- **Logs corrélés :** `docker compose logs ... | grep af6a07680a29f92e264a5039ddb62408` affiche les lignes corrélées des **7 services** avec exactement le même `trace_id`.

---

## 4. Résilience (Resilience4j, timeouts, retries et circuit breakers)

### 4.1 Choix des versions et isolation Java 8 / Java 17

| Module | JDK / Spring Boot | Librairies Resilience4j ajoutées |
|---|---|---|
| `rail-bridge` | Java 8 / Boot 2.7.18 | `resilience4j-circuitbreaker:1.7.1` *(dernière ligne compatible Java 8)* |
| `air-bridge` | Java 17 / Boot 3.2.5 | `resilience4j-circuitbreaker:2.2.0`, `resilience4j-retry:2.2.0` |
| `booking-service` | Java 17 / Boot 3.3.13 | `resilience4j-circuitbreaker:2.2.0`, `resilience4j-retry:2.2.0` |
| `api-gateway` | Java 17 / Boot 3.3.13 | `resilience4j-circuitbreaker:2.2.0`, `resilience4j-retry:2.2.0` |

### 4.2 Point dur résolu en live : retries internes cachés de JacORB (`jacorb.retries`)

Lors du premier test de panne (`docker compose stop rail`), nous avons constaté dans les logs de `rail-bridge` que chaque invocation CORBA bloquait **14 secondes** malgré `connect_timeout=2000ms` :

```text
22:30:17.197 ClientIIOPConnection : Retrying to connect to 172.18.0.4:1050 (x5, intervalle 500 ms)
22:30:29.286 RailCorbaClient      : Échec CORBA search (TRANSIENT), tentative 1/2 : Retries exceeded
```

**Cause :** par défaut, JacORB applique `jacorb.retries=5` et `jacorb.retry_interval=500ms` (6 tentatives TCP × ~2,3 s ≈ 14 s par appel), ce qui dépassait les timeouts amont et empêchait le circuit breaker d'être sollicité à temps.
**Correctif :** ajout de `jacorb.retries=0` et `jacorb.retry_interval=100` dans `RailCorbaClient.java` (les reprises restent exclusivement sous le contrôle explicite du bridge et uniquement sur les opérations idempotentes `search` et `cancel`).

### 4.3 Budget de timeouts hiérarchisé

| Couche | Fichier | Configuration | Justification |
|---|---|---|---|
| `rail-bridge` (JacORB) | `RailCorbaClient.java`, `application.properties` | `connect=1000ms`, `reply=2500ms`, `jacorb.retries=0` | `search` (2 tentatives) ≤ 2 s < 3,5 s gateway ; `hold`/`confirm` (1 tentative) ≤ 3,5 s < 6 s booking |
| `air-bridge` (Spring WS) | `SoapClientConfig.java`, `application.properties` | `HttpUrlConnectionMessageSender` `connect=2000ms`, `read=3000ms` | Corrige l'absence totale de timeout SOAP en P1 (budget 5 s < 6 s booking) |
| `booking-service` (`RestTemplate`) | `BookingHttpConfig.java` | `connect=2000ms`, `read=6000ms` | Strictement supérieur au budget des bridges (5 s) : évite de classer un timeout bridge propre en `UNKNOWN_OUTCOME` |
| `api-gateway` | `GatewayHttpConfig.java`, `GatewayController.java` | Recherche parallèle : `.orTimeout(3500ms)` par source ; proxy booking : `read=20s` | Recherche rapide en mode dégradé sans jamais couper une Saga 3 modes + compensation |

### 4.4 Politique stricte d'idempotence et circuit breakers

- **Retries strictement limités aux opérations idempotentes** : `GET /v1/offers` (`search`) et `DELETE /v1/references/{ref}` (`cancel`) ont au plus 2 tentatives sur panne technique transitoire (jamais sur `409` ni sur `CIRCUIT_OPEN`). `POST /v1/holds` et `POST /v1/holds/{id}/confirm` ne sont **jamais** rejoués automatiquement.
- **Distinction panne technique vs refus métier :** un `409 NOT_AVAILABLE` (ex. `AIR-CMN-MAD-1800` complet) prouve que l'opérateur répond et **n'alimente pas** le taux d'échec du circuit breaker. Seuls les `SystemException` CORBA, erreurs SOAP réseau, HTTP `5xx`, `ResourceAccessException` et `TimeoutException` (corrigé dans `GatewayController.isUpstreamFailure`) sont comptabilisés.
- **Sémantique Saga sur `CIRCUIT_OPEN` :** quand un bridge ou le circuit breaker local renvoie `CIRCUIT_OPEN` pendant un `hold`, l'opérateur n'a pas été appelé ; `BookingService` classe l'étape en `HOLD_FAILED` (pas en `UNKNOWN_OUTCOME`) et compense immédiatement les étapes précédentes.
- **Scheduling isolé dans `booking-service` :** `spring.task.scheduling.pool.size=3`, scan de compensation séparé (`booking.compensation.scan-ms=5000`, `max-attempts=60` soit 5 minutes de reprise automatique) pour que l'outbox ne soit jamais bloqué par une reprise de compensation.
- **Visibilité Actuator :** ajout de `spring-boot-starter-actuator` sur les 6 services HTTP et indicateurs dédiés (`RailCircuitHealthIndicator`, `AirCircuitHealthIndicator`, `BookingCircuitHealthIndicator`, `GatewayCircuitHealthIndicator`) exposant `state`, `failureRate`, `bufferedCalls`, `failedCalls`, `notPermittedCalls` sur `/actuator/health` (route `/actuator/health/**` autorisée sans auth sur la passerelle).

### 4.5 Résultats mesurés sur la panne simulée (`docker compose stop rail`)

| Mesure | Avant P3 | Après P3 |
|---|---|---|
| Appel #1 (rail arrêté) | 8 176 ms | 3 577 ms (`X-Partial-Results: rail`, `X-Degraded-Reason: rail:TIMEOUT`) |
| Appels #2, #3, #4 (circuit `OPEN`) | 8 176 ms à chaque appel | **79 à 81 ms** (`X-Partial-Results: rail`, `X-Degraded-Reason: rail:CIRCUIT_OPEN`) — **~100× plus rapide** |
| `/actuator/health` Gateway pendant la panne | *(inexistant)* | `gatewayCircuitBreakers.status: DEGRADED`, `rail.state: OPEN`, `notPermittedCalls: 3` |
| Reprise après `docker compose start rail` (+17 s) | Relecture IOR manuelle/lente | Transition `HALF_OPEN` → `CLOSED` automatique, offres `TRAIN` de retour en **125 ms** |

---

## 5. Démarrage à froid en une commande et fiabilisation Compose

Modifications dans `docker-compose.yml` (et ajout de `.env.example`, `.env` ajouté au `.gitignore`) :
- **Valeurs par défaut sûres pour la démo :** `RABBITMQ_USERNAME:-mobility`, `RABBITMQ_PASSWORD:-mobility-demo`, `GATEWAY_USERNAME:-demo`, `GATEWAY_PASSWORD:-demo-pass`. `docker compose config -q` et `docker compose up --build -d` réussissent sur un clone vierge **sans aucune variable d'environnement**.
- **Correction de la course au démarrage RabbitMQ :** passage du healthcheck de `rabbitmq-diagnostics -q ping` à `rabbitmq-diagnostics -q check_port_connectivity`, supprimant complètement la stacktrace `Connection refused` au démarrage de `notification-service`.
- **Ordonnancement par `service_healthy` :** `booking-service` attend la disponibilité des bridges, de `carshare-service`, `pricing-service` et `rabbitmq` ; `api-gateway` attend `booking-service` et les sources de recherche.

---

## 6. Tests automatisés et validation complète

Passage de **14 tests (P2)** à **26 tests automatisés** (0 échec, 0 erreur, 0 ignoré) dans le réacteur Maven (`mvn -B -ntp test`) :

| Module | Classe de test | Nb | Couverture ajoutée en P3 |
|---|---|---:|---|
| `air-bridge` *(nouveau)* | `AirSoapClientResilienceTest` | 4 | Fault métier `409` n'ouvre pas le circuit ; retry `search` mais jamais `hold` ; ouverture circuit + fail-fast `CIRCUIT_OPEN` ; hold nominal |
| `rail-bridge` *(nouveau)* | `RailCorbaClientResilienceTest` | 4 | Stub `RailBookingOperations` compatible JDK 8 et JDK 17 ; `NotAvailable` n'ouvre pas le circuit ; retry `search`/`cancel` mais jamais `hold` ; ouverture circuit + fail-fast ; idempotence `cancel` |
| `booking-service` *(nouveau)* | `BookingCompensationIntegrationTest` | 3 | **Test d'intégration HTTP réel** (`HttpServer` embarqué + `RestTemplate` + Resilience4j + JPA/H2) : échec `confirm` compensant les 3 modes en ordre inverse avec retry transparent d'un `DELETE` 503 ; `503 CIRCUIT_OPEN` compensé sans `UNKNOWN_OUTCOME` ; ouverture circuit breaker `booking-service` |
| `booking-service` | `BookingSagaTest` | 5 | Non-régression Saga, concurrence `Idempotency-Key`, reprise de compensation |
| `api-gateway` *(étendu)* | `GatewaySecurityAndSearchTest` | 2 | Auth Basic + `/actuator/health` ouvert + ouverture du circuit breaker `gateway-rail` et mode dégradé `rail:CIRCUIT_OPEN` sans rappel |
| `carshare-service` | `CarshareFlowTest` | 4 | Non-régression hold/confirm/cancel/expiration |
| `pricing-service` | `PricingQuoteTest` | 2 | Non-régression tarification et remise multimodale |
| `notification-service` | `NotificationProcessorTest` | 2 | Non-régression consommation et déduplication |
| **TOTAL** | **8 classes de test** | **26** | **100 % PASS** |

---

## 7. Livrables de documentation ajoutés / mis à jour en P3

- [`README.md`](../README.md) : README d'exécution complet (diagramme Mermaid, démarrage 1 commande, tableau des 11 services/ports, démo, tests, index des ADR).
- [`docs/adr/ADR-006-observabilite-resilience.md`](adr/ADR-006-observabilite-resilience.md) : ADR justifiant OpenTelemetry/Jaeger, spans manuels CORBA/SOAP, propagation outbox et Resilience4j (Java 8 vs 17, `jacorb.retries=0`, timeouts).
- [`docs/demo-scenario.md`](demo-scenario.md) : scénario détaillé pas à pas pour la soutenance.
- [`docs/requests/demo-p3.ps1`](requests/demo-p3.ps1) et [`docs/requests/demo-p3.sh`](requests/demo-p3.sh) : scripts de démonstration automatisés (Windows + Linux/macOS) vérifiant recherche, Saga 3 modes, idempotence, compensation, traces Jaeger et bascule/reprise circuit breaker.
- [`docs/requests/p3.http`](requests/p3.http) : collection de requêtes HTTP rejouables (IntelliJ / VS Code).
- [`HANDOFF.md`](../HANDOFF.md) : section finale P3 ajoutée.
- [`.env.example`](../.env.example) : modèle optionnel des variables Compose.
