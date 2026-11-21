# Bridges rail et air : API REST canonique

| Service | Protocole amont | Port hôte | Réseau Docker |
|---|---|---|---|
| rail-bridge | CORBA / IIOP (JacORB, Java 8) | 8082 | `http://rail-bridge:8080` |
| air-bridge | SOAP (Spring WS, Java 17) | 8083 | `http://air-bridge:8080` |

## Démarrage

    docker compose up --build -d

Le rail-bridge lit l'IOR dans `./ior/RailBooking.ior` (volume partagé en lecture seule) et la relit après une erreur. Il n'a pas besoin que rail soit prêt au démarrage.

## Contrat (identique pour les deux bridges)

| Méthode et chemin | Corps | Réponse |
|---|---|---|
| `GET /v1/offers?origin=&destination=&date=AAAA-MM-JJ` | | liste d'`Offer` |
| `POST /v1/holds` | `{"offerId":"RAIL:...","seats":1}` | `{"holdId":"..."}` |
| `POST /v1/holds/{holdId}/confirm` | | `{"bookingRef":"..."}` |
| `DELETE /v1/references/{ref}` | | 200, idempotent (holdId ou bookingRef) |

Erreurs : 409 `NOT_AVAILABLE` (plus de place, trajet ou vol inconnu, maintien expiré), 503 `UPSTREAM_UNAVAILABLE` (opérateur injoignable), 400 `INVALID_REQUEST`. Corps : `{"code":"...","reason":"..."}`.

## Données de démo (date 2026-11-15)
- Train : `RAIL:RAIL-CAS-RAB-0730` et `RAIL:RAIL-CAS-RAB-0900` (Casablanca vers Rabat, 80,00 MAD, 120 places)
- Avion : `AIR:AIR-RBA-ORY-1200` (Rabat vers Paris, 1 800,00 MAD, 40 places)
- Avion : `AIR:AIR-CMN-MAD-1800` (3 places seulement, pour provoquer l'échec et la compensation)

Un hold expire au bout de 10 minutes : un `confirm` ultérieur renvoie 409.

Requêtes rejouables : `docs/requests/bridges.http` (client HTTP d'IntelliJ).