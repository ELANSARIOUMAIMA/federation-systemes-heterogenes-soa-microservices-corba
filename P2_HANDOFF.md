# Remise P2 — fédération multimodale

## Services et ports

| Service | Port hôte | Fonction |
|---|---:|---|
| api-gateway | 8080 | Basic auth, recherche parallèle, proxy réservation |
| rail-bridge (P1) | 8082 | Adaptateur CORBA REST, inchangé |
| air-bridge (P1) | 8083 | Adaptateur SOAP REST, inchangé |
| carshare-service | 8084 | Offres/stock, holds de 10 min, confirmations et annulations |
| pricing-service | 8085 | Devis MAD en centimes |
| booking-service | 8086 | Saga orchestrée, idempotence, compensation, outbox |
| RabbitMQ | 5672 / 15672 | Événements / console de démo |
| notification-service | interne | Consommation idempotente et logs |

## Build, tests et démonstration

Utiliser Java 17 dans les images et ne pas modifier le JDK local. À la racine PowerShell, après avoir laissé les services P1 actifs, le lancement P2 ci-dessous ne redémarre pas les bridges :

```powershell
$env:RABBITMQ_USERNAME = "mobility-demo"
$env:RABBITMQ_PASSWORD = [guid]::NewGuid().ToString("N")
$env:GATEWAY_USERNAME = "demo"
$env:GATEWAY_PASSWORD = [guid]::NewGuid().ToString("N")
$env:COMPOSE_PARALLEL_LIMIT = "1"
docker compose build carshare-service pricing-service booking-service notification-service api-gateway
docker compose up -d --no-deps rabbitmq carshare-service pricing-service booking-service notification-service api-gateway
docker compose ps
.\docs\requests\verify-p2.ps1
docker compose logs notification-service
docker compose logs rail-bridge air-bridge
```

Conserver ces mêmes identifiants pour les redémarrages tant que les volumes persistent : RabbitMQ ne remplace pas automatiquement son utilisateur lorsque `RABBITMQ_PASSWORD` change.

Arrêt sans effacer les volumes : `docker compose down`. Ne jamais utiliser `down -v` si les données de démo doivent survivre.

Build/tests Java 17 :

```powershell
$repo = (Get-Location).Path
docker run --rm -v mobility-p2-maven-cache:/root/.m2 -v "${repo}:/workspace" -w /workspace maven:3.9-eclipse-temurin-17 `
  mvn -B -ntp -pl carshare-service,pricing-service,booking-service,notification-service,api-gateway -am test
```

Scénarios automatisés : `CarshareFlowTest` (contrat, cycle, erreurs, expiration, concurrence), `PricingQuoteTest` (règles et validation), `BookingSagaTest` (nominal, échec hold, échec confirm, idempotence/conflit, concurrence, retry compensation), `NotificationProcessorTest` (déduplication), `GatewaySecurityAndSearchTest` (Basic et dégradation partielle).

Résultats réellement exécutés : [`docs/runtime-validation-report.md`](docs/runtime-validation-report.md).

## Contrats de réservation

`POST http://localhost:8080/api/v1/bookings` demande `Idempotency-Key` et contient des segments `{mode,offerId,origin,destination,date,seats}`. La valeur de prix provient de la relecture de l'offre côté bridge, puis est envoyée au pricing-service. État de réservation consultable avec `GET /api/v1/bookings/{bookingId}`.

Le HTTP API complet et les cas d'échec sont dans `docs/P2_IMPLEMENTATION.md`, `docs/requests/p2.http`; les appels P1 restent dans `docs/requests/bridges.http`.

## Points restant à connaître

- Événementiel en livraison au moins une fois avec outbox et déduplication `eventId`.
- Hold expiré : 10 minutes; fenêtre d'orchestration visée : moins de 9 minutes.
- Si le hold distant est créé mais qu'un timeout empêche la réception de son `holdId`, l'étape est `UNKNOWN_OUTCOME` et demande réconciliation manuelle : P1 ne propose pas de lookup par identifiant de requête.
- Stockage de démonstration H2 et Basic auth ne sont pas destinés à la production. RabbitMQ utilise aussi le volume persistant `rabbitmq-data`.
- Observabilité distribuée, Jaeger et Resilience4j restent des éléments P3.

## Pour P3

Propager une clé d'opération idempotente dans les bridges/opérateurs afin de résoudre les réponses réseau ambiguës; remplacer H2 par une base opérationnelle; ajouter TLS/OIDC, instrumentation de traces et circuit breaker autour des fournisseurs.
