#!/usr/bin/env bash
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
RAIL_BRIDGE="${RAIL_BRIDGE:-http://localhost:8082}"
AIR_BRIDGE="${AIR_BRIDGE:-http://localhost:8083}"
JAEGER="${JAEGER:-http://localhost:16686}"
USER_AUTH="${GATEWAY_USERNAME:-demo}:${GATEWAY_PASSWORD:-demo-pass}"
RUN_ID="$(date +%s)"

echo "=== [1/6] Sante & circuit breakers initiaux ==="
curl -fsS "$RAIL_BRIDGE/actuator/health" | grep -q '"state":"CLOSED"'
curl -fsS "$AIR_BRIDGE/actuator/health"  | grep -q '"state":"CLOSED"'
echo "  OK: rail-bridge=CLOSED, air-bridge=CLOSED"

echo "=== [2/6] Recherche multimodale agregee ==="
SEARCH="$(curl -fsS -u "$USER_AUTH" "$GATEWAY/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15")"
echo "$SEARCH" | grep -q '"mode":"TRAIN"'
echo "$SEARCH" | grep -q '"mode":"CAR"'
echo "  OK: offres TRAIN et CAR retournees"

echo "=== [3/6] Saga nominale 3 modes (TRAIN + AIR + CAR) + Idempotence ==="
NOM_BODY='{"segments":[{"mode":"TRAIN","offerId":"RAIL:RAIL-CAS-RAB-0730","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1},{"mode":"AIR","offerId":"AIR:AIR-RBA-ORY-1200","origin":"Rabat","destination":"Paris","date":"2026-11-15","seats":1},{"mode":"CAR","offerId":"CAR:CAR-CAS-RAB-1000","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1}]}'
NOM_RES="$(curl -fsS -u "$USER_AUTH" -X POST "$GATEWAY/api/v1/bookings" -H "Content-Type: application/json" -H "Idempotency-Key: sh-ok-$RUN_ID" -d "$NOM_BODY")"
echo "$NOM_RES" | grep -q '"state":"CONFIRMED"'
REPLAY_RES="$(curl -fsS -u "$USER_AUTH" -X POST "$GATEWAY/api/v1/bookings" -H "Content-Type: application/json" -H "Idempotency-Key: sh-ok-$RUN_ID" -d "$NOM_BODY")"
[ "$NOM_RES" = "$REPLAY_RES" ]
CONFLICT_CODE="$(curl -s -o /dev/null -w '%{http_code}' -u "$USER_AUTH" -X POST "$GATEWAY/api/v1/bookings" -H "Content-Type: application/json" -H "Idempotency-Key: sh-ok-$RUN_ID" -d '{"segments":[{"mode":"TRAIN","offerId":"RAIL:RAIL-CAS-RAB-0730","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":2}]}')"
[ "$CONFLICT_CODE" = "409" ]
echo "  OK: CONFIRMED, rejeu idempotent, conflit -> 409"

echo "=== [4/6] Saga en echec compense (AIR-CMN-MAD-1800 x 4 > 3) ==="
FAIL_BODY='{"segments":[{"mode":"TRAIN","offerId":"RAIL:RAIL-CAS-RAB-0730","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1},{"mode":"CAR","offerId":"CAR:CAR-CAS-RAB-1000","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":1},{"mode":"AIR","offerId":"AIR:AIR-CMN-MAD-1800","origin":"Casablanca","destination":"Madrid","date":"2026-11-15","seats":4}]}'
FAIL_RES="$(curl -fsS -u "$USER_AUTH" -X POST "$GATEWAY/api/v1/bookings" -H "Content-Type: application/json" -H "Idempotency-Key: sh-fail-$RUN_ID" -d "$FAIL_BODY")"
echo "$FAIL_RES" | grep -q '"state":"CANCELLED"'
echo "$FAIL_RES" | grep -q '"state":"COMPENSATED"'
curl -fsS "$AIR_BRIDGE/actuator/health" | grep -q '"state":"CLOSED"'
echo "  OK: CANCELLED avec compensation TRAIN+CAR (circuit air-bridge reste CLOSED)"

echo "=== [5/6] Verification Jaeger ==="
sleep 6
TRACES="$(curl -fsS "$JAEGER/api/traces?service=api-gateway&operation=POST%20/api/v1/bookings&limit=10")"
echo "$TRACES" | grep -q 'CORBA RailBooking/hold'
echo "$TRACES" | grep -q 'SOAP AirPartner/holdFlight'
echo "  OK: spans CORBA + SOAP presents dans Jaeger ($JAEGER)"

echo "=== [6/6] Resilience : arret de rail -> circuit OPEN -> reprise ==="
trap 'docker compose start rail >/dev/null 2>&1 || true' EXIT
docker compose stop rail >/dev/null
for i in 1 2 3 4; do
  curl -sS -D /tmp/gw_hdr -o /dev/null -u "$USER_AUTH" "$GATEWAY/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15"
  echo "  Recherche #$i : $(grep -i 'X-Degraded-Reason\|X-Partial-Results' /tmp/gw_hdr | tr '\r\n' ' ')"
done
docker compose start rail >/dev/null
trap - EXIT
echo "  Attente 17 s pour reprise HALF_OPEN -> CLOSED..."
sleep 17
curl -fsS -u "$USER_AUTH" "$GATEWAY/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" >/dev/null
RECOVERED="$(curl -fsS -u "$USER_AUTH" "$GATEWAY/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15")"
echo "$RECOVERED" | grep -q '"mode":"TRAIN"'
echo "  OK: offres TRAIN revenues apres redemarrage"

echo "=== DEMO P3 COMPLETE REUSSIE ==="
