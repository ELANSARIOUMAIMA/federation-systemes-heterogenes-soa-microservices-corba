# ADR-003 — Orchestration de la Saga de réservation

- **Statut :** accepté
- **Contexte :** train CORBA et avion SOAP sont intégrés derrière des bridges REST; l'autopartage et le booking sont nos services. Les transactions locales ne peuvent pas rendre atomique un itinéraire fédéré.
- **Décision :** booking-service orchestre les holds puis les confirmations. Il persiste l'état du dossier et de chaque étape avant/après les appels distants. Un échec entraîne une compensation en ordre inverse; une compensation persistée peut être reprise. Les bridges et carshare gardent leurs propres transactions locales.
- **Pourquoi pas la chorégraphie :** l'ordre strict « tous les holds avant le premier confirm », les états visibles et la compensation globale demandent un coordonnateur explicite. Une chorégraphie rendrait les décisions et l'ordre difficiles à auditer.
- **Conséquences :** responsabilités d'orchestration concentrées et observables, au prix d'une dépendance du booking-service aux contrats REST des fournisseurs. Les timeouts ambigus sont persistés et les appels non idempotents ne sont pas relancés à l'aveugle.
- **À revisiter :** évolution vers orchestration durable/commandes idempotentes si le nombre de fournisseurs ou la durée des workflows augmente.
