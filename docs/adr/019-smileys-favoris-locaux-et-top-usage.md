---
title: ADR-019
parent: ADRs
grand_parent: Spécifications
nav_order: 19
permalink: /adr/019-smileys-favoris-locaux-et-top-usage
---

# ADR-019 — Smileys : favoris locaux et Top d'usage

## Statut

Accepté — 2026-10-29, décision du mainteneur pour [#1154](https://github.com/ForumHFR/redface2/issues/1154).

## Contexte

Les favoris du compte HFR sont déjà lus depuis le formulaire de rédaction. Les favoris locaux
et le Top d'usage sont deux collections distinctes, conservées sur l'appareil. Le mainteneur
demande leurs moteurs avant leur affichage ; C2 livre les favoris locaux, C1 livrera le Top.
Leur future synchronisation ne dispose pas encore d'un contrat smileys.

## Décision

- **Deux fichiers DataStore Preferences dédiés**, distincts de `user_preferences`, avec une seule
  instance singleton par fichier. Chaque magasin est isolé par compte : propriétaire explicite
  capturé par l'appelant, pseudo normalisé par `lowercase()` comme les super favoris, sans trim.
  Un propriétaire blanc est refusé. Aucune adoption de données anonymes.
- Les collections **survivent aux déconnexions** et changements de compte ; l'effacement passe
  par une API explicite `clear(owner)`. Aucun contenu n'est transféré entre propriétaires.
- **Favoris locaux** : document JSON versionné par propriétaire, exportable sans identifiant
  de compte. Il contient la liste ordonnée des favoris (`token`, `imageUrl` facultative,
  `addedAt`), les tombstones (`token`, `removedAt`) et `orderUpdatedAt` ; dates en epoch millis.
  Le jeton exact est l'identité, espaces et variantes compris. Un jeton vide ou blanc est refusé
  sans écriture (`INVALID_TOKEN`), y compris au retrait et au déplacement (ancre non nulle comprise).
  Maximum **256 favoris actifs** et **256 Kio UTF-8 par document**, tombstones comprises ;
  dépassement explicitement refusé, sauf au retrait selon la règle d'élagage ci-dessous.
  Ajout/retrait idempotents, déplacement transactionnel avant un jeton (null = fin).
  Un ré-ajout retire la tombstone et donne une nouvelle date d'ajout en fin de liste.
- Les mutations relisent et écrivent dans une même transaction DataStore. Document corrompu ou de
  version inconnue : observation vide, export et écritures refusés explicitement, document
  conservé, y compris lors d'un `clear`. Les autres propriétaires restent utilisables si le
  fichier Preferences est lisible. Aucun remplacement automatique du fichier en cas de corruption.
- Les tombstones sont conservées, sauf **sous pression de taille lors d'un retrait** : si le document
  résultant dépasse 256 Kio, les tombstones les plus anciennes (`removedAt` croissant) sont élaguées
  jusqu'à respecter la borne. Le retrait ne peut ainsi pas être bloqué par la limite documentaire ;
  les autres favoris, leur ordre et leurs métadonnées sont conservés. Sans dépassement, aucun élagage.
  La collecte générale reste liée à la future synchronisation.
  L'export est seulement son point d'accroche : aucun moteur de fusion, CRDT, identifiant
  d'appareil, worker ou enveloppe MPStorage smileys. Le client MPStorage v0.1 existe déjà
  ([ADR-014]({{ site.baseurl }}/adr/014-mpstorage-v01-de-facto)) ; aucune dépendance à MPStorage2.
- **Top d'usage (C1, à venir)** : compter uniquement après un envoi reconnu réussi, sur le
  contenu effectivement envoyé ; en édition, seulement le delta positif d'occurrences par
  jeton. Exclure citations et blocs de code ; aucun comptage si les smileys sont désactivés.
  Suivre au plus **512 entrées**, exposer un **Top 24**, trié par compteur décroissant, puis
  dernière utilisation décroissante, puis jeton. Évincer les moins utilisées puis les plus
  anciennes. Conserver uniquement **jeton, compteur et date**, sans contenu du message ni
  identifiant de conversation. Une panne du magasin local ne change jamais un envoi HFR
  réussi en échec ; une interruption du processus peut perdre un comptage local.

## Conséquences

- C2 livre le moteur des favoris et ses tests, sans sélecteur, écran ou ViewModel supplémentaire.
  L'affichage viendra séparément ; aucune nouvelle valeur d'`EditorSmileySource` avant un consommateur.
- L'utilisateur ne dispose pas encore d'un accès à ces collections ni de leur effacement dans l'UI.
- Les dates et tombstones préparent un export fidèle, sans définir une résolution des conflits
  entre appareils. Le Top suit une fréquence d'utilisation et reste une donnée locale personnelle.

## Alternatives considérées

- Favoris HFR seuls : ne couvrent pas une collection locale indépendante du serveur.
- Clés dans `user_preferences` : mélangent collections métier et préférences générales.
- Room, CRDT ou protocole de synchronisation immédiat : complexité sans besoin prouvé dans C2.
- Comptage à l'insertion : compte les essais abandonnés et manque les jetons saisis manuellement.
