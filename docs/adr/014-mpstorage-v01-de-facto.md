---
title: ADR-014
parent: ADRs
grand_parent: Spécifications
nav_order: 14
permalink: /adr/014-mpstorage-v01-de-facto
---

# ADR-014 — MPStorage : adopter l'enveloppe v0.1 de facto (lecture d'abord, écriture différée)

## Statut

Proposé — 2026-06-11

Cette ADR formalise les verdicts de l'[exploration du format MPStorage réel](https://github.com/ForumHFR/redface2/issues/6#issuecomment-4673324560) (2026-06-10 : confrontation `MPStorage.user.js` / `DTCloud.user.js` / `multimp.user.js` / doc Wiripse) et des vérifications live du 2026-06-11 (découverte par recherche `cat=prive`, fixtures `mp_storage_search_*`). Conformément à la règle « pas de décision implicite », **rien n'est acté tant que le statut n'est pas passé à « Accepté »** ; `models.md` § MPStorage porte la projection de cette proposition.

## Contexte

L'issue [#6](https://github.com/ForumHFR/redface2/issues/6) (MPStorage, sync cross-plateforme) attendait « MPStorage2 » dans `XaaT/hfr-redkit` — qui est **vide** : la seule spec déployée et interopérable est le format v0.1 de facto, en production depuis ~2019 dans les userscripts (DTCloud pour les drapeaux DT, HFR4K, …). Par ailleurs, `models.md` décrivait un modèle (`MPStorageData/MultiMPFlag(lastReadDate, pinned)`) **incompatible** avec ce format réel — l'implémenter aurait cassé la compatibilité additive avec les userscripts (exigence Q4 de #6, non négociable).

Mécanique du format réel (détail complet dans [le rapport #6](https://github.com/ForumHFR/redface2/issues/6#issuecomment-4673324560)) :

- stockage = premier post d'un MP dédié (sujet = hash fixe `a2bcc09b796b8c6fab77058ff8446c34`, destinataire = compte tiers `MultiMP`) ;
- enveloppe JSON `{ data: [ { version: '0.1', <clés par outil> } ], sourceName, lastUpdate }` — namespacing faible, chaque outil pose ses clés dans l'entrée partagée ;
- lecture = GET du formulaire d'édition du premier post (textarea `content_form`) ; écriture = POST `bdd.php` `cat=prive` en **remplacement intégral** (last-write-wins, pas de verrou) ;
- piège connu de la bibliothèque d'origine : contenu invalide → **reset destructif au défaut** ;
- `mpFlags.list[]` (DTCloud) = **position de reprise de lecture** par conversation DT (`{uri, post, page, href: "t<numreponse>", p}`), pas un lu/non-lu (cf. ADR-013/#361 : le lu/non-lu MP est le dot serveur binaire).

Vérifications live 2026-06-11 (GET only, compte XaTriX) :

- la **découverte par recherche authentifiée** marche : `forum1.php?recherches=1&cat=prive&search=<hash>&titre=1` répond le listing standard (fixture `mp_storage_search_hit.html`, capturée sur un sujet réel du compte) ou la page « aucune réponse » (fixture `mp_storage_search_no_results.html`) — alors que la REST API rejette `cat=prive` ;
- le compte de test **n'a pas de MP storage** : « pas de storage » est donc le cas nominal premier du client, pas un cas d'erreur.

## Décision

1. **Geler le contrat sur l'enveloppe v0.1 de facto.** Pas de « MPStorage2 » côté Redface 2 : toute extension passe par de **nouvelles clés additives** dans l'entrée v0.1. Si un MPStorage2 émerge un jour dans `hfr-redkit`, il fera l'objet d'une nouvelle ADR (et le format v0.1 restera lu pour la migration).
2. **Lecture d'abord.** Phase 3 livre un client **lecture seule** dans `:core:data` : découverte (recherche par sujet) → premier post (`numreponse` via la page de conversation) → formulaire d'édition → `content_form` → parsing **tolérant** (clés inconnues ignorées à la projection mais le JSON intégral est conservé dans `MpStorageDocument.rawEnvelope`).
3. **Jamais de reset destructif.** Un document illisible = échec de lecture explicite surfacé à l'UI ; aucune écriture de « réparation ».
4. **Écriture différée et opt-in** (hors scope de cette ADR au-delà du principe) : read-modify-write immédiatement avant le POST `bdd.php`, déclenchée à la sortie d'une conversation DT — pas une édition par page vue comme DTCloud ; les clés tierces du `rawEnvelope` survivent au round-trip.
5. **Surface UI** : l'onglet « DT » opt-in (PR #397) consommera `mpFlags` (liste des conversations DT avec position de reprise) ; fusion avec le drapal local ADR-013 étage 1 (local prioritaire, MPStorage = seed + sync).

## Conséquences

- `models.md` § MPStorage remplace les modèles inventés par `MpStorageDocument` / `MpStorageFlagEntry` (cette PR).
- Les ids découverts (mpId, numreponse du premier post) seront cachés en DataStore **par compte** et purgés au logout (même règle de vie privée que #316).
- Risques assumés et documentés : lost-update inter-outils (full overwrite sans condition), taille max du post MP inconnue (la `list` DTCloud n'est jamais prunée — la vérification de taille devra précéder toute écriture), dépendance au compte tiers `MultiMP`.
- Trous de vérification restants avant l'étape écriture : effet du GET du formulaire d'édition sur le dot du correspondant (non mesuré par #361), contrat `bdd.php cat=prive` (non capturé), round-trip JSON réel (le compte de test n'a pas encore de MP storage).
