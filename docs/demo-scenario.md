# Scénario de démonstration — Soutenance (P1 + P2 + P3)

Ce scénario rejoue l'intégralité du parcours attendu lors de la soutenance :
démarrage en une commande, recherche agrégée 3 modes, Saga nominale tracée de bout en bout dans Jaeger,
Saga en échec compensée, idempotence, et ouverture/reprise du circuit breaker.

## Scripts automatisés

- **Windows PowerShell** :
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\docs\requests\demo-p3.ps1
  ```
- **Linux / macOS / Git Bash** :
  ```bash
  ./docs/requests/demo-p3.sh
  ```
- **Requêtes interactives (IntelliJ / VS Code REST Client)** : [`docs/requests/p3.http`](requests/p3.http)

---

## Déroulé pas à pas (démonstration en direct)

### Étape 0 — Démarrage à froid et interfaces d'observation

```bash
docker compose up --build -d
docker compose ps
```

Ouvrir dans le navigateur :
- **Jaeger UI** : <http://localhost:16686>
- **RabbitMQ Management** : <http://localhost:15672> (`mobility` / `mobility-demo`)
- **Santé & circuit breakers** :
  - <http://localhost:8080/actuator/health> (`api-gateway`)
  - <http://localhost:8082/actuator/health> (`rail-bridge`)
  - <http://localhost:8083/actuator/health> (`air-bridge`)
  - <http://localhost:8086/actuator/health> (`booking-service`)

### Étape 1 — Recherche multimodale agrégée (CORBA + SOAP + REST)

```bash
curl -u demo:demo-pass "http://localhost:8080/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15"
```

Attendu : HTTP `200`, liste canonique triée contenant des offres `TRAIN` (`RAIL:...`, issues de CORBA) et `CAR` (`CAR:...`, issues de `carshare-service`), sans jamais exposer de détail CORBA/SOAP.

### Étape 2 — Réservation multimodale nominale (TRAIN + AIR + CAR) + trace Jaeger

```bash
curl -u demo:demo-pass -X POST http://localhost:8080/api/v1/bookings \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-nominal-01" \
  -d '{
    "segments": [
      {"mode":"TRAIN","offerId":"RAIL:RAIL-CAS-RAB-0730","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1},
      {"mode":"AIR","offerId":"AIR:AIR-RBA-ORY-1200","origin":"Rabat","destination":"Paris","date":"2026-11-15","seats":1},
      {"mode":"CAR","offerId":"CAR:CAR-CAS-RAB-1000","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1}
    ]
  }'
```

Attendu :
- HTTP `200`, `state: "CONFIRMED"`, `totalMinor: 183475` MAD (sous-total 192 500 + frais 600 − remise multimodale 5 % = 9 625).
- **Dans Jaeger (`http://localhost:16686`)** : sélectionner `api-gateway` → `POST /api/v1/bookings`. Une **unique trace** traverse les **7 services**, incluant :
  - `rail-bridge` : `CORBA RailBooking/search`, `CORBA RailBooking/hold`, `CORBA RailBooking/confirm` ;
  - `air-bridge` : `SOAP AirPartner/searchFlights`, `SOAP AirPartner/holdFlight`, `SOAP AirPartner/confirmFlight` ;
  - `carshare-service`, `pricing-service` ;
  - `booking-service` : `outbox.publish BookingHeld` & `BookingConfirmed` → `notification-service`.
- **Logs corrélés** : copier le `traceID` depuis Jaeger et lancer :
  ```bash
  docker compose logs api-gateway booking-service pricing-service rail-bridge air-bridge carshare-service notification-service | grep <TRACE_ID>
  ```

### Étape 3 — Idempotence (rejeu identique + conflit de payload)

1. Rejouer exactement la même requête avec `Idempotency-Key: demo-nominal-01` → retourne le **même `bookingId`**, sans aucun nouvel appel aux opérateurs.
2. Envoyer la même clé `demo-nominal-01` avec `seats: 2` → retourne HTTP **`409 NOT_AVAILABLE`** (protection SHA-256).

### Étape 4 — Échec sur le vol `AIR-CMN-MAD-1800` (4 places > 3) et compensation Saga

```bash
curl -u demo:demo-pass -X POST http://localhost:8080/api/v1/bookings \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-fail-01" \
  -d '{
    "segments": [
      {"mode":"TRAIN","offerId":"RAIL:RAIL-CAS-RAB-0730","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1},
      {"mode":"CAR","offerId":"CAR:CAR-CAS-RAB-1000","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1},
      {"mode":"AIR","offerId":"AIR:AIR-CMN-MAD-1800","origin":"Casablanca","destination":"Madrid","date":"2026-11-15","seats":4}
    ]
  }'
```

Attendu :
- `state: "CANCELLED"`, `failureReason: "hold HTTP 409"`.
- Étapes `TRAIN` et `CAR` : `state: "COMPENSATED"` ; étape `AIR` : `state: "HOLD_FAILED"`.
- Vérifier les logs des systèmes legacy et de notification :
  ```bash
  docker compose logs --tail 20 rail air notification-service
  ```
  `rail` affiche `[RailBooking] hold(...)` suivi de `[RailBooking] cancel(...) -> maintien libéré`, et `notification-service` journalise `BookingCancelled`.
- Vérifier que ce refus métier `409` **n'a pas ouvert** le circuit breaker de `air-bridge` (`/actuator/health` → `state: "CLOSED"`).

### Étape 5 — Résilience : panne de `rail`, circuit breaker `OPEN`, mode dégradé rapide, puis reprise

```bash
docker compose stop rail
```

1. Lancer 3–4 recherches `GET /api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15` :
   - Les premiers appels échouent vite (budget CORBA réduit avec `jacorb.retries=0`) et ouvrent le circuit breaker.
   - Dès l'ouverture, la passerelle répond en **quelques millisecondes** avec HTTP `200`, les offres `CAR` disponibles, `X-Partial-Results: rail` et `X-Degraded-Reason: rail:CIRCUIT_OPEN`.
2. Montrer l'état `OPEN` sur `http://localhost:8082/actuator/health` (`railCorbaCircuitBreaker.details.state = "OPEN"`) et `http://localhost:8080/actuator/health` (`gatewayCircuitBreakers.details.rail.state = "OPEN"`).
3. Relancer l'opérateur ferroviaire :
   ```bash
   docker compose start rail
   ```
   Attendre ~15 s (durée `waitDurationInOpenState`) : le circuit passe en `HALF_OPEN` puis `CLOSED`, le `rail-bridge` relit automatiquement le nouvel IOR et les offres `TRAIN` réapparaissent.
