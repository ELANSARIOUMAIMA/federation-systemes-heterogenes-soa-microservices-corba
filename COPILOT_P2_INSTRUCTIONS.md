# Mission GitHub Copilot Agent — Finaliser P2 : Business Microservices & Saga

## Contexte et objectif

Projet ENSIAS IAE « Fédération de systèmes hétérogènes : SOA, microservices et CORBA ». Plateforme de réservation multimodale train / avion / covoiturage. Je suis la personne P2, responsable des microservices métier, de la Saga orchestrée, des événements et de l'API Gateway. Je veux une **implémentation réellement fonctionnelle, compilable, testée et documentée** dans ce dépôt, pas seulement des exemples de code ou un plan.

Travaille directement dans le workspace VS Code, en mode Agent, en inspectant les fichiers existants avant toute modification. Pose une question seulement si un choix bloquant ne peut pas être résolu en inspectant le dépôt. Explique brièvement tes actions en français.

## État actuel vérifié

- Branche Git : `feat/p2-business-saga`. Ne change pas de branche sans raison et ne pousse pas sur `main`.
- Maven parent : `ma.mobility:mobility-platform:1.0.0-SNAPSHOT`, packaging `pom`.
- Modules existants : `canonical-model`, `air-bridge`, `rail-bridge`.
- Java canonique compilé en Java 8, compatible avec les services Java 17.
- Docker Compose a construit et démarré `rail`, `air`, `rail-bridge`, `air-bridge`.
- `GET http://localhost:8082/v1/offers?origin=Casablanca&destination=Rabat&date=2026-11-15` fonctionne et retourne deux offres TRAIN.
- Java local Windows = 26 ; Maven local = 3.9.16 ; Docker disponible. Pour les builds de services P2, privilégier Java 17 via Docker / Maven toolchain adaptée. Ne pas modifier le JDK système.
- Les répertoires P2 n'existaient pas lors de la dernière inspection. S'ils existent maintenant, **inspecte et réutilise** ce qui a été créé ; ne supprime aucun travail existant.

## Règles de protection du travail existant

1. Ne modifie pas `rail-operator-corba/` ni `air-partner-soap/` : ce sont les opérateurs partenaires fournis.
2. Préserve le comportement et les API de `rail-bridge/`, `air-bridge/` et `canonical-model/`. Si un ajustement est indispensable, explique-le, minimise-le et vérifie les tests de régression.
3. Conserve les quatre services existants dans `docker-compose.yml`, le réseau `mobilite-net`, les ports exposés et les données de démonstration.
4. Ne lance pas de commandes destructrices (`docker compose down -v`, `docker system prune -a`, `git reset --hard`, suppression de volumes) sans mon autorisation.
5. Ne commite pas automatiquement sans me présenter le diff et les résultats des tests. N'envoie rien sur GitHub sans mon accord.
6. Ne déclare jamais un test « réussi » si tu ne l'as pas réellement exécuté et observé.

## Contrat canonique existant — À RÉUTILISER, pas à dupliquer

Package : `ma.mobility.canonical`.

- `Offer` : champs publics `String offerId`, `TransportMode mode`, `String origin`, `String destination`, `String departure`, `String arrival`, `long priceMinor`, `String currency` (défaut `MAD`), `int seatsAvailable`.
- `TransportMode` : enum `TRAIN`, `AIR`, `CAR`.
- `HoldRequest` : champs publics `String offerId`, `int seats` ; constructeur sans argument implicite.
- `HoldResponse` : champ public `String holdId` ; constructeurs vide et `(String holdId)`.
- `ConfirmResponse` : champ public `String bookingRef` ; constructeurs vide et `(String bookingRef)`.
- `ApiError` : champs publics `String code`, `String reason` ; constructeurs vide et `(String code, String reason)` ; constantes `NOT_AVAILABLE` (409), `UPSTREAM_UNAVAILABLE` (503), `INVALID_REQUEST` (400).

**Contrat REST uniforme** à préserver pour train/air/car :

- `GET /v1/offers?origin=...&destination=...&date=YYYY-MM-DD` → `Offer[]`.
- `POST /v1/holds` avec `{ "offerId": "CAR:...", "seats": 2 }` → `{ "holdId": "..." }`.
- `POST /v1/holds/{id}/confirm` → `{ "bookingRef": "..." }`.
- `DELETE /v1/references/{ref}` où `ref` est un `holdId` ou `bookingRef` → HTTP 200, idempotent même si déjà annulé / référence absente.
- Indisponibilité / hold expiré → HTTP 409 `{ "code":"NOT_AVAILABLE", "reason":"..." }` ; requête invalide → 400 ; dépendance inaccessible → 503 quand pertinent.
- Préfixes d'offres : `RAIL:`, `AIR:`, `CAR:`.
- Dates de fixtures historiques `2026-11-15` / `2026-11-16` : les conserver pour la démonstration, sans rejeter automatiquement ces dates comme « passées ».

Inspecte `HANDOFF.md`, `docs/bridges.md`, `docs/requests/bridges.http`, le `docker-compose.yml`, les Dockerfiles et les classes des bridges pour confirmer les conventions exactes (ports, chemins, réponses et mécanismes de cancel) avant de coder.

## Livrables obligatoires P2

### A. `carshare-service` (Spring Boot Java 17, base de données indépendante)

- Nouveau module Maven enfant du POM parent, dépendant de `canonical-model` ; packaging JAR exécutable.
- API REST identique aux bridges, avec offres `CAR:` au format `Offer`.
- Base de données propre (H2 persistante pour démo ou PostgreSQL si déjà disponible et pertinent), tables d'offres et réservations.
- Seed de trajets de démonstration (notamment Casablanca → Rabat, 2026-11-15), idempotent au redémarrage.
- Réservation temporaire `HELD` 10 minutes ; confirmation `CONFIRMED` ; annulation `CANCELLED` ; expiration `EXPIRED`.
- Stock atomique : ne jamais sur-réserver. Verrous transactionnels ou mise à jour conditionnelle atomique ; tests de concurrence.
- Annulation idempotente par `holdId` ou `bookingRef` ; restitution des sièges **une seule fois**.
- Nettoyage des holds expirés, y compris au redémarrage ; confirmation après expiration → 409 et libération persistée, sans rollback accidentel.
- Validation des entrées et gestion d'erreurs conforme à `ApiError`.

### B. `pricing-service` (Spring Boot Java 17, base de données indépendante)

- Calcul des montants en `priceMinor` (centimes MAD), jamais de `double` pour l'argent.
- Règles de frais et remises multimodales stockées dans sa propre base ; paramètres documentés et modifiables.
- Endpoint interne explicite, par exemple `POST /v1/pricing/quote`, avec segments, quantité de sièges, sous-total, frais, remise, total et devise.
- Définir un contrat JSON stable et partagé avec `booking-service` ; vérifier arrondis, montants négatifs, devises et calcul de remise.
- Les règles exactes de tarification ne sont pas imposées par le guide : choisir une règle simple, documenter le choix et la tester.

### C. `booking-service` : orchestration Saga (Spring Boot Java 17)

- Base indépendante avec au minimum `trip_booking` (UUID, état, `idempotency_key`, dates, prix) et `saga_step` (ordre, mode, `offerId`, sièges, `holdId`, `bookingRef`, état, erreurs).
- `POST /v1/bookings` : demande de réservation multimodale avec plusieurs segments ; utiliser `Idempotency-Key` HTTP obligatoire. Définir et documenter précisément le JSON.
- `GET /v1/bookings/{id}` : état du dossier et étapes.
- États persistés : `PENDING`, `HELD`, `CONFIRMED`, `COMPENSATING`, `CANCELLED`, `COMPENSATION_PENDING` (en cas d'échec de compensation).
- Routage par préfixe `RAIL:` vers `http://rail-bridge:8080`, `AIR:` vers `http://air-bridge:8080`, `CAR:` vers `http://carshare-service:8080` en réseau Docker ; URLs configurables par environnement pour tests locaux.
- **Phase 1 — Holds** : réserver tous les segments. Si un hold échoue, annuler les holds déjà acquis, en ordre inverse, et terminer en `CANCELLED` ou `COMPENSATION_PENDING` si une compensation échoue.
- **Phase 2 — Confirmations** : seulement après réussite de tous les holds, confirmer chaque segment. Si une confirmation échoue, annuler les `bookingRef` déjà confirmés ET les holds restants ; persister l'état de chaque compensation.
- Respecter expiration de 10 minutes et ne pas garder une transaction DB ouverte pendant les appels réseau. Persister les étapes avant/après les appels et gérer explicitement les résultats incertains (timeout après opération distante) ; ne pas supposer qu'une opération distante n'a pas eu lieu.
- Idempotence : la même clé avec le même payload retourne le même dossier sans doubler les holds ; même clé avec payload différent → 409 ; protéger aussi les requêtes concurrentes par contrainte DB unique.
- Stratégie de reprise : mécanisme de retry pour `COMPENSATION_PENDING` avec bornes et traçabilité. Pas de retry aveugle sur les opérations non idempotentes.
- Calcul du prix via `pricing-service`, sauvegarde du devis et de ses composants.
- Événements RabbitMQ `BookingHeld`, `BookingConfirmed`, `BookingCancelled` avec `bookingId`, timestamp, état et informations utiles. Éviter la perte d'événements si possible (outbox transactionnelle ou mécanisme fiable expliqué).
- Les appels vers les bridges doivent suivre les réponses réelles de P1. Vérifier le code et les tests des bridges.

### D. `notification-service` + RabbitMQ

- Ajouter RabbitMQ à Compose avec healthcheck, credentials de démo via variables d'environnement, réseau interne ; management UI facultative sur un port libre.
- Émettre les trois types d'événements depuis `booking-service`.
- Consommer les événements dans `notification-service`, journaliser clairement l'événement, le `bookingId`, le statut ; gérer redélivrance/idempotence du consommateur.
- Fournir une procédure de vérification des événements (logs ou UI RabbitMQ).

### E. `api-gateway` / BFF

- `GET /api/v1/search?origin=...&destination=...&date=...` : requêtes en parallèle vers train/air/car, agrégation `Offer[]`, tri cohérent, gestion d'une source indisponible (dégradation partielle documentée).
- `POST /api/v1/bookings` et `GET /api/v1/bookings/{id}` : proxy vers booking-service, propagation de `Idempotency-Key`, des statuts HTTP et des erreurs.
- Authentification simple de démonstration (par exemple HTTP Basic via Spring Security), documentée avec identifiants de démo configurables et non codés en dur.
- Port hôte 8080 si disponible ; ports internes 8080. Ne pas casser les ports 8082/8083 des bridges.

### F. Docker Compose, Maven et intégration

- Ajouter les nouveaux modules au `pom.xml` parent en respectant `canonical-model` Java 8 et les autres modules.
- Chaque nouveau service possède un Dockerfile multi-stage Maven + Java 17 ; contexte Docker correctement défini pour construire la dépendance `canonical-model` et le parent POM.
- Étendre `docker-compose.yml` sans écraser les services P1, préserver `mobilite-net` ; volumes persistants pour les bases ; dépendances et healthchecks quand pertinents.
- Ports hôte proposés (à vérifier contre les ports existants) : gateway 8080, carshare 8084, pricing 8085, booking 8086, notification sans port public ; RabbitMQ 5672/15672 si libres.
- `docker compose up --build -d` doit fonctionner, avec possibilité de construire un service à la fois si la RAM manque. Pour limiter la concurrence : `$env:COMPOSE_PARALLEL_LIMIT='1'` dans PowerShell.
- Ne pas exiger que je remplace Java 26 local ; builds Java 17 dans Docker.

### G. Tests et scénarios de validation

Créer des tests automatisés (JUnit / Spring Boot Test, MockWebServer ou WireMock si nécessaire) et un fichier de requêtes `docs/requests/p2.http` ou script PowerShell exécutable.

1. Recherche carshare retourne des `Offer` au format canonique.
2. Hold de 2 places → `holdId`, capacité décrémentée ; confirm → `bookingRef` ; cancel par `bookingRef` → places restaurées ; deuxième cancel → HTTP 200 sans double restitution.
3. Hold de trop de sièges → 409 `NOT_AVAILABLE` ; entrée invalide → 400.
4. Hold expiré → confirm 409 et capacité libérée ; vérifier aussi après redémarrage.
5. Saga nominale multimodale → `CONFIRMED`, tous les segments confirmés, prix correct.
6. Saga échec de hold : utiliser l'offre `AIR:AIR-CMN-MAD-1800` avec **4 sièges** (3 disponibles selon les fixtures) ; vérifier compensation de tous les holds antérieurs et dossier `CANCELLED`. Si le trajet est incompatible avec un autre segment, expliquer qu'il s'agit d'un test de panne métier simulé, pas d'un itinéraire géographique valide.
7. Saga échec en phase de confirmation : simuler un 409/503 après au moins une confirmation réussie ; vérifier annulation des références confirmées et des holds restants.
8. Idempotence : répéter le même `Idempotency-Key` + même payload → même `bookingId`, pas de réservation supplémentaire ; même clé + payload différent → 409.
9. Test de concurrence sur le stock et la clé d'idempotence.
10. Vérifier `BookingHeld`, `BookingConfirmed`, `BookingCancelled` dans RabbitMQ et les logs du consommateur.
11. Vérifier la recherche agrégée et les appels de réservation via le Gateway avec authentification.
12. Vérifier que les deux bridges P1 continuent à fonctionner.

Important : la phase de holds précède toutes les confirmations. Le scénario « train confirmé puis échec du hold avion » contredit cet ordre : ne pas modifier la Saga pour reproduire cette formulation ; séparer les tests d'échec de hold et d'échec de confirmation.

## Documentation et remise P2

Produire :

- `docs/P2_IMPLEMENTATION.md` : architecture réelle, endpoints, contrats JSON, modèles de données, flux Saga nominal et compensation, idempotence, TTL, RabbitMQ, variables d'environnement, commandes Windows PowerShell.
- `docs/adr/ADR-003-saga-orchestration.md` : orchestration vs chorégraphie.
- `docs/adr/ADR-004-saga-idempotence-expiration.md` : états, compensation, idempotence, 10 min.
- `docs/adr/ADR-005-gateway-vs-esb.md` : justification Gateway/BFF.
- `docs/requests/p2.http` : scénarios HTTP exécutables.
- `P2_HANDOFF.md` : résumé des fichiers, services, ports, commandes de build et test, scénarios validés, limites connues, indications d'intégration P3.
- Diagrammes Mermaid dans la documentation si utiles.
- Ne pas affirmer que l'observabilité Jaeger/Resilience4j complète (périmètre P3) est terminée si elle ne l'est pas ; préparer seulement les points d'intégration nécessaires.

## Ordre d'exécution attendu de l'agent

1. **Audit** : `git status`, arbre du dépôt, POM parent et modules, Compose, documentation handoff, API réelles ; identifier conflits et ports.
2. **Plan court** : donner la liste des fichiers à créer/modifier, puis agir sans attendre une approbation pour les changements non destructifs.
3. **Implémentation** : carshare → pricing → booking Saga → RabbitMQ/notification → Gateway → Compose → documentation.
4. **Validation incrémentale** : compiler/tester chaque module et corriger les erreurs immédiatement ; utiliser Docker Java 17 si le JDK local 26 pose problème.
5. **Validation finale** : démarrer Compose, exécuter les scénarios réels, vérifier les logs et les API des bridges.
6. **Bilan vérifiable** : tableau `Fonctionnalité | Fichier(s) | Test exécuté | Résultat` ; commandes exactes ; erreurs restantes ; fichiers modifiés (`git diff --stat`, `git status`).
7. **Stop** : ne pas faire de commit ni de push automatiquement ; me demander mon accord après avoir présenté le bilan.

## Critères de réussite

La mission est terminée uniquement si les services P2 sont implémentés, les contrats REST respectés, les scénarios nominal/compensation/idempotence testés, le déploiement Docker fonctionne et les livrables P2 sont documentés. Si un obstacle empêche une vérification, le signaler clairement avec la commande et l'erreur exactes, sans présenter le travail comme terminé.
