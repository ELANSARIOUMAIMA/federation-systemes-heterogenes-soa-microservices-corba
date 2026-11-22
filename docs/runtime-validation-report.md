# Rapport de validation d’exécution P2

**Généré :** 2026-11-22T00:24:41Z
**Cible :** dépôt `federation-systemes-heterogenes-soa-microservices-corba`
**Branche :** `feat/p2-business-saga`

## Résumé

| Étape | Résultat | Code | Preuve |
|---|---|---:|---|
| Configuration Compose | PASS | 0 | `docker compose config -q` |
| Images P2 | PASS | 0 | `docker compose build carshare-service pricing-service booking-service notification-service api-gateway` |
| Démarrage | PASS | 0 | `docker compose up -d --no-deps ...`; RabbitMQ `healthy`, API P2 accessibles |
| Maven racine Java 17 | PASS | 0 | Les 9 modules compilés; 14 tests, 0 échec, 0 erreur, 0 ignoré |
| Parcours HTTP bout en bout | PASS | 0 | `docs/requests/verify-p2.ps1`, exécuté à nouveau pendant l’audit; six groupes de scénarios |
| Bridges P1 | PASS | 0 | Les offres rail/air restent disponibles; conteneurs P1 non recréés |

**Verdict global : PASS.**

## Environnement et stratégie

- Docker : disponible; images et services P2 démarrés avec Docker Compose.
- Java : tests exécutés avec `maven:3.9-eclipse-temurin-17`; images de service basées sur Java 17.
- Le réacteur complet (`mvn -B -ntp test`) compile canonical-model, les deux bridges P1 et les cinq modules P2. Les bridges P1 n’ont pas de tests Maven déclarés.
- Node.js : disponible; Playwright non applicable, la plateforme est une API REST sans interface HTML.
- Les tests Maven utilisent H2 en mémoire et des dépendances simulées là où indiqué par les tests. Le parcours HTTP séparé exerce les vrais conteneurs H2 persistés, RabbitMQ, Gateway et bridges P1.
- Aucun test E2E hérité n’a été trouvé.

## Parcours vérifiés

1. Recherche agrégée avec offres TRAIN et CAR.
2. Réservation nominale via Gateway; statut `CONFIRMED`, total attendu de 179000 centimes MAD, lecture persistée et rejeu idempotent.
3. Même clé d’idempotence avec un contenu différent : HTTP 409.
4. Refus du second hold : compensation du premier, état final `CANCELLED`.
5. Hold/confirmation/annulation Carshare; répétition du DELETE sans double restitution du stock.
6. Vérification que les bridges P1 rail et air répondent toujours.

Les tests automatisés couvrent aussi la concurrence sur une clé Booking, le retry de compensation, les règles de tarification, l’authentification Gateway et la déduplication d’événements.

## Incident résolu pendant la validation

Au premier démarrage, RabbitMQ n’a pas pu lire `/var/lib/rabbitmq/.erlang.cookie` (`EACCES`) car le fichier était créé root-only. Le Compose a été corrigé pour lancer RabbitMQ avec son utilisateur `rabbitmq` et conserver ses données dans `rabbitmq-data`. Après recréation des seuls conteneurs P2, RabbitMQ est devenu `healthy`, les événements HELD/CONFIRMED/CANCELLED ont été consommés et les six groupes de scénarios HTTP ont réussi.

## Limites restantes

- Le parcours HTTP vérifie l’échec d’un hold et sa compensation. Les erreurs réseau de résultats ambigus restent à réconcilier manuellement, conformément à la limitation des bridges P1.
- H2 et Basic Auth sont des choix de démonstration, pas de production.
- Pas d’E2E navigateur : aucun rendu HTML dans l’application.

## Preuves

- Rapports Surefire : `*/target/surefire-reports/`.
- Commande exécutée : `docker run --rm -v mobility-p2-maven-cache:/root/.m2 -v "${repo}:/workspace" -w /workspace maven:3.9-eclipse-temurin-17 mvn -B -ntp test` (code 0).
- Scénarios HTTP : [`requests/verify-p2.ps1`](./requests/verify-p2.ps1).
