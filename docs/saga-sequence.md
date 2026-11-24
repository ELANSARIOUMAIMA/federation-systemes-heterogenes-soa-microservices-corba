# Diagrammes de séquence — Saga de réservation multimodale

La Saga est orchestrée par `booking-service`. Les fournisseurs sont accessibles via une API REST canonique : `rail-bridge` (CORBA), `air-bridge` (SOAP) et `carshare-service` (REST natif). Les diagrammes suivent les scénarios de `demo-scenario.md` et l'implémentation de `BookingService.java`.

## 1. Cas nominal — réservation confirmée

```mermaid
sequenceDiagram
    actor C as Client
    participant G as API Gateway
    participant B as Booking Service
    participant R as Rail Bridge (CORBA)
    participant A as Air Bridge (SOAP)
    participant S as Carshare Service
    participant P as Pricing Service
    participant D as H2 / Outbox
    participant Q as RabbitMQ
    participant N as Notification Service
    C->>G: POST /api/v1/bookings + Idempotency-Key
    G->>B: Transmettre la demande authentifiée
    B->>D: Vérifier la clé et le hash du payload
    Note over B,D: Nouvelle clé (cas nominal)
    B->>R: GET /v1/offers
    R-->>B: Offre train
    B->>A: GET /v1/offers
    A-->>B: Offre avion
    B->>S: GET /v1/offers
    S-->>B: Offre covoiturage
    B->>P: POST /v1/pricing/quote
    P-->>B: Devis MAD en centimes
    B->>D: Créer réservation PENDING et étapes
    rect rgb(230,240,250)
    Note over B,S: Phase 1 - Hold
    B->>R: POST /v1/holds
    R-->>B: holdId train
    B->>A: POST /v1/holds
    A-->>B: holdId avion
    B->>S: POST /v1/holds
    S-->>B: holdId covoiturage
    B->>D: HELD + événement BookingHeld
    end
    rect rgb(230,245,235)
    Note over B,S: Phase 2 - Confirm avant expiration
    B->>R: POST /v1/holds/{holdId}/confirm
    R-->>B: bookingRef train
    B->>A: POST /v1/holds/{holdId}/confirm
    A-->>B: bookingRef avion
    B->>S: POST /v1/holds/{holdId}/confirm
    S-->>B: bookingRef covoiturage
    B->>D: CONFIRMED + événement BookingConfirmed
    end
    B-->>G: BookingView (CONFIRMED)
    G-->>C: HTTP 200 + bookingId et total
    Note over D,N: Publication asynchrone indépendante de la réponse HTTP
    D-->>B: Événements outbox à publier
    B->>Q: BookingHeld / BookingConfirmed
    Q-->>N: Livraison des événements
    N->>N: Déduplication par eventId et traitement
```

## 2. Cas d'échec — compensation des holds

Scénario de démonstration : TRAIN puis CAR, puis AIR avec 4 places demandées pour une offre de 3 places. Les compensations s'effectuent dans l'ordre inverse.

```mermaid
sequenceDiagram
    actor C as Client
    participant G as API Gateway
    participant B as Booking Service
    participant R as Rail Bridge (CORBA)
    participant S as Carshare Service
    participant A as Air Bridge (SOAP)
    participant D as H2 / Outbox
    participant Q as RabbitMQ
    participant N as Notification Service
    C->>G: POST /api/v1/bookings + Idempotency-Key
    G->>B: Demande de réservation
    Note over B,D: Vérification idempotence, relecture offres et devis
    B->>D: Créer réservation PENDING et étapes
    B->>R: POST /v1/holds (1 place)
    R-->>B: holdId train
    B->>S: POST /v1/holds (1 place)
    S-->>B: holdId covoiturage
    B->>A: POST /v1/holds (4 places)
    A-->>B: HTTP 409 NOT_AVAILABLE
    B->>D: AIR = HOLD_FAILED, réservation COMPENSATING
    B->>S: DELETE /v1/references/{holdId car}
    S-->>B: HTTP 200
    B->>D: CAR = COMPENSATED
    B->>R: DELETE /v1/references/{holdId train}
    R-->>B: HTTP 200
    B->>D: TRAIN = COMPENSATED
    B->>D: CANCELLED + événement BookingCancelled
    B-->>G: BookingView (CANCELLED)
    G-->>C: HTTP 200, state CANCELLED, failureReason
    Note over D,N: Publication asynchrone
    D-->>B: Événement outbox à publier
    B->>Q: BookingCancelled
    Q-->>N: Livraison de l'événement
    N->>N: Déduplication et traitement
```

## 3. Variante — échec pendant la confirmation

```mermaid
sequenceDiagram
    participant B as Booking Service
    participant R as Rail Bridge
    participant A as Air Bridge
    participant S as Carshare Service
    participant D as H2 / Outbox
    Note over B,D: Tous les holds obtenus, état HELD
    B->>R: confirm(holdId train)
    R-->>B: bookingRef train
    B->>A: confirm(holdId avion)
    A-->>B: Échec HTTP
    B->>D: COMPENSATING
    B->>S: cancel(holdId car)
    B->>A: cancel(holdId avion)
    B->>R: cancel(bookingRef train)
    alt Toutes les annulations réussissent
        B->>D: CANCELLED + BookingCancelled
    else Une annulation échoue
        B->>D: COMPENSATION_PENDING
        Note over B,D: Reprise planifiée des compensations
    end
```

## Règles de cohérence

- Les holds expirent après 10 minutes ; l'orchestrateur vérifie une marge de 9 minutes avant la confirmation.
- Une même clé d'idempotence et un payload identique retournent la réservation existante ; un payload différent provoque HTTP 409.
- Les annulations sont idempotentes. Les holds et confirmations ne sont pas rejoués automatiquement ; les lectures et annulations peuvent bénéficier du retry de Resilience4j.
- Un timeout de hold sans référence connue entraîne `UNKNOWN_OUTCOME` et peut nécessiter une réconciliation manuelle ; la réservation reste `COMPENSATION_PENDING`.
- L'outbox assure la publication asynchrone des événements ; la notification déduplique par `eventId`. La cohérence globale est à terme, et non une transaction ACID distribuée.

## Références internes

- [Sémantique Saga](03-semantique-saga.md)
- [Scénario de démonstration](demo-scenario.md)
- [Implémentation P2](P2_IMPLEMENTATION.md)
- `booking-service/src/main/java/ma/mobility/booking/service/BookingService.java`
- `booking-service/src/main/java/ma/mobility/booking/service/BookingPersistence.java`
