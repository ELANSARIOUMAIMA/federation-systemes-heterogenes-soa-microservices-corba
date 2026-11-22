# ADR-005 — API Gateway/BFF plutôt qu'un nouvel ESB

- **Statut :** accepté
- **Contexte :** les bridges P1 traduisent déjà CORBA et SOAP vers le modèle canonique REST. Les clients ont besoin d'un point d'entrée de recherche fédérée et de réservation, pas d'une nouvelle couche de transformation de protocoles.
- **Décision :** `api-gateway` assure l'authentification de démonstration, l'agrégation parallèle et le proxy de booking. Les traductions partenaires restent isolées dans `rail-bridge` et `air-bridge`; l'orchestration reste au `booking-service`.
- **Conséquences :** BFF léger et API cliente simple; les recherches peuvent être partielles, signalées via `X-Partial-Results`. La Gateway ne remplace ni les bridges ni un ESB de médiation avancée.
- **Sécurité :** Basic uniquement pour la démo réseau local; identifiants obligatoires par environnement. Une mise en production requiert TLS et une fédération d'identité.
