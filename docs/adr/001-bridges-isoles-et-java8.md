# ADR 001 — Bridges de protocole isolés, rail-bridge en Java 8

**Statut :** accepté

## Contexte
- Les systèmes legacy (opérateur ferroviaire CORBA, partenaire aérien SOAP) ne doivent pas être modifiés. Leurs contrats (`rail.idl`, `air.xsd`) font foi.
- JacORB 3.9 ne fonctionne qu'avec Java 8 à l'exécution : `javax.rmi.CORBA` a été retiré du JDK à partir de Java 11. Le starter kit cible lui-même Java 8 pour l'opérateur ferroviaire.
- L'énoncé demande Java 17 ou supérieur pour le reste de la plateforme.

## Décision
- **Un bridge par protocole**, chacun étant un service Spring Boot autonome exposant la même API REST canonique (`GET /v1/offers`, `POST /v1/holds`, `POST /v1/holds/{id}/confirm`, `DELETE /v1/references/{ref}`).
- **rail-bridge : Java 8 et Spring Boot 2.7.18** (dernière ligne compatible Java 8), image `eclipse-temurin:8`.
- **air-bridge : Java 17 et Spring Boot 3.2.5** (même version que le partenaire).
- Les contrats sont **copiés sans modification** dans les bridges (`rail.idl`, `air.xsd`). Les classes sont générées au build (compilateur IDL JacORB dans un processus séparé car il appelle `System.exit`, et xjc). Le build Docker ne dépend donc d'aucun service démarré.
- **Client CORBA** : l'IOR est lue à la demande (le bridge démarre même si rail n'est pas prêt) et relue après toute erreur système. Constat : après un redémarrage de rail, la première invocation échoue en `OBJECT_NOT_EXIST` et l'adresse du conteneur peut changer.
- **Reprises** : un seul retry, uniquement sur `search` et `cancel` (idempotents). `hold` et `confirm` ne sont jamais rejoués automatiquement.
- **Erreurs** : `NotAvailable` (CORBA) et SOAP Fault sur hold/confirm deviennent HTTP 409 `{code, reason}` ; opérateur injoignable, HTTP 503 `UPSTREAM_UNAVAILABLE` ; requête invalide, HTTP 400.

## Conséquences
- (+) Un client de la plateforme ne voit jamais ni CORBA ni SOAP : les bridges forment une couche anti-corruption.
- (+) Seul le rail-bridge est contraint à Java 8 ; le reste reste en Java 17.
- (−) Deux versions de Spring Boot à maintenir.
- (−) Le modèle canonique doit rester compilable en Java 8 (pas de `record`, aucune dépendance).

## Alternatives écartées
- Un bridge unique : imposerait Java 8 à tout le code d'intégration.
- Utiliser le CORBA du JDK : supprimé depuis Java 11.
- Générer le client SOAP depuis le WSDL en ligne : le service n'est pas démarré pendant `docker build`.