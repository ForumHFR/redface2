---
title: ADR-014
parent: ADRs
grand_parent: Spécifications
nav_order: 14
permalink: /adr/014-mpstorage-v01-de-facto
---

# ADR-014 — MPStorage : adopter l'enveloppe v0.1 de facto (lecture d'abord, écriture opt-in)

## Statut

Accepté — 2026-06-12 (proposé 2026-06-11 ; révisé à l'acceptation après audit adversarial ; **écriture opt-in livrée 2026-06-19/20, #593/#597**)

Cette ADR formalise les verdicts de l'[exploration du format MPStorage réel](https://github.com/ForumHFR/redface2/issues/6#issuecomment-4673324560) (2026-06-10 : confrontation `MPStorage.user.js` / `DTCloud.user.js` / `multimp.user.js` / doc Wiripse) et des vérifications live du 2026-06-11 (découverte par recherche `cat=prive`, fixtures `mp_storage_search_*`). `models.md` § MPStorage porte la projection canonique de cette décision.

État d'implémentation (à jour clôture Phase 3, #598) :

- **Livré** : décisions 1 à 3 (client lecture) — `DefaultMpStorageRepository` (découverte par **scan de l'inbox**, cf. Addendum ; lecture du `content_form` ; `MpStorageParser` tolérant). NB : `MpStorageDiscoveryParser` (recherche par titre) **a été supprimé** par l'Addendum 2026-06-14 — la découverte par recherche serveur ne marchait pas sur un compte réel.
- **Livré** : décision 5 — seed des positions DT (`MpStorageReadPositionSeeder`) + onglet « DT » câblé (réglage section DT, OFF par défaut).
- **Livré (opt-in OFF)** : décision 4 — **écriture** RMW guardée (#593) + **déclencheur automatique de synchronisation de la position de lecture DT** (#597, mode `writeBackFlagIfPresent` UPDATE-ONLY). L'opt-in est **OFF par défaut** (`KEY_SYNC_PRIVATE_MESSAGES_WRITE_ENABLED ?: false`, `DataStoreUserPreferencesRepository`). Le POST `bdd.php cat=prive` **n'a pas été observé live** : l'implémentation est *fail-closed*.
- **À venir (Phase 4, #6/#577)** : activation de l'opt-in par défaut (suppose l'arbitrage de la clé write-back tranché), cache Room du **contenu** MP, synchronisation bidirectionnelle complète.

## Contexte

L'issue [#6](https://github.com/ForumHFR/redface2/issues/6) (MPStorage, sync cross-plateforme) attendait « MPStorage2 » dans `XaaT/hfr-redkit` — qui est **vide** : la seule spec déployée et interopérable est le format v0.1 de facto, en production depuis ~2019 dans les userscripts (DTCloud pour les drapeaux DT, HFR4K, …). Par ailleurs, `models.md` décrivait un modèle (`MPStorageData/MultiMPFlag(lastReadDate, pinned)`) **incompatible** avec ce format réel — l'implémenter aurait cassé la compatibilité additive avec les userscripts (exigence Q4 de #6, non négociable).

Mécanique du format réel (détail complet dans [le rapport #6](https://github.com/ForumHFR/redface2/issues/6#issuecomment-4673324560)) :

- stockage = premier post d'un MP dédié (sujet = hash fixe `a2bcc09b796b8c6fab77058ff8446c34`, destinataire = compte tiers `MultiMP`) ;
- enveloppe JSON `{ data: [ { version: '0.1', <clés par outil> } ], sourceName, lastUpdate }` — namespacing faible, chaque outil pose ses clés dans l'entrée partagée ;
- lecture = GET du formulaire d'édition du premier post (textarea `content_form`) ; écriture = POST `bdd.php` `cat=prive` en **remplacement intégral** (last-write-wins, pas de verrou) ;
- piège connu de la bibliothèque d'origine : contenu invalide → **reset destructif au défaut** ;
- `mpFlags.list[]` (DTCloud) = **position de reprise de lecture** par conversation DT (`{uri, post, page, href: "t<numreponse>", p}` — sémantique champ par champ dans [le rapport #6](https://github.com/ForumHFR/redface2/issues/6#issuecomment-4673324560)), pas un lu/non-lu (cf. ADR-013/#361 : le lu/non-lu MP est le dot serveur binaire).

Vérifications live 2026-06-11 (GET only, compte XaTriX) :

- le **mécanisme de recherche authentifiée répond** : `forum1.php?recherches=1&cat=prive&search=<hash>&titre=1` renvoie le listing standard (fixture `mp_storage_search_hit.html`, capturée sur un sujet réel du compte — pas un MP storage) ou la page « aucune réponse » (fixture `mp_storage_search_no_results.html`) — alors que la REST API rejette `cat=prive`. La requête réelle envoyée par le client porte les paramètres complets du formulaire HFR : `config=hfr.inc`, `orderSearch=1`, `resSearch=50`, `daterange=2`, `searchtype=1`, plus `jour/mois/annee` sérialisés à la date du jour (le formulaire les envoie toujours, même si `daterange=2` les rend en principe inopérants) — cf. `HfrClient.searchPrivateMessagesBySubject` ;
- le compte de test **n'a pas de MP storage** : « pas de storage » est donc le cas nominal premier du client, pas un cas d'erreur. Corollaire : la découverte d'un **vrai** document storage n'a jamais été observée de bout en bout (cf. Trous de vérification).

## Décision

1. **Geler le contrat sur l'enveloppe v0.1 de facto.** Pas de « MPStorage2 » côté Redface 2 : toute extension passe par de **nouvelles clés additives** dans l'entrée v0.1. Si un MPStorage2 émerge un jour dans `hfr-redkit`, il fera l'objet d'une nouvelle ADR (et le format v0.1 restera lu pour la migration).
2. **Lecture d'abord.** Phase 3 livre un client **lecture seule** dans `:core:data` : découverte (recherche par sujet) → premier post (`numreponse` via la page de conversation) → formulaire d'édition → `content_form` → parsing **tolérant** (clés inconnues ignorées à la projection mais le JSON intégral est conservé dans `MpStorageDocument.rawEnvelope`). Si plusieurs conversations portent le sujet-hash, la découverte retient le **premier résultat** du listing tel qu'ordonné par `orderSearch=1` (`MpStorageDiscoveryParser.parseFirstThreadId`) — acceptable en lecture seule, mais ce choix devra être **re-tranché avant l'étape écriture** (écrire dans le mauvais document forkerait silencieusement le storage).
3. **Jamais de reset destructif.** Un document illisible = échec de lecture explicite surfacé à l'UI ; aucune écriture de « réparation ».
4. **Écriture opt-in — LIVRÉE (OFF par défaut, #593/#597).** Read-modify-write pur immédiatement avant le POST `bdd.php cat=prive`, déclenché à la sortie d'une conversation DT — pas une édition par page vue comme DTCloud ; les clés tierces du `rawEnvelope` survivent au round-trip. Détail livré :
   - **Deux modes** : `writeBackFlag` (manuel, *add-or-update* : crée l'entrée si absente) et `writeBackFlagIfPresent` (#597, **UPDATE-ONLY** : ne réécrit que si la conversation est déjà présente, sinon `SkippedNotPresent` et **aucun POST** — c'est le mode du déclencheur automatique, anti-pollution).
   - **Verify-after-write** : relecture après POST ; restauration **bornée à la corruption réelle** (garde `isJsonEnvelope`), jamais un rollback aveugle.
   - **Cap** : `MAX_CONTENT_FORM_BYTES = 64 KiB` ; dépassement ⇒ `TooLarge`, aucun POST (fail-closed, HFR tronque silencieusement).
   - **Opt-in OFF par défaut** : gardé par le réglage `KEY_SYNC_PRIVATE_MESSAGES_WRITE_ENABLED` ; OFF ⇒ `DisabledByPreference`, aucune requête.
   - **Arbitrage de clé write-back (#597)** : la clé d'identification d'une entrée (threadId du MP vs id de topic DT côté forum) comporte un risque résiduel d'écriture dans la mauvaise entrée. Il est **borné** par le mode UPDATE-ONLY (jamais de création silencieuse) mais **non nul** — à requalifier avant d'activer l'opt-in par défaut (Phase 4, #6/#577). Le contrat `bdd.php cat=prive` reste **non observé live**.
5. **Surface UI** : l'onglet « DT » opt-in (PR #397) consommera `mpFlags` (liste des conversations DT avec position de reprise) ; fusion avec le drapal local ADR-013 étage 1 (local prioritaire, MPStorage = seed + sync).

## Conséquences

- `models.md` § MPStorage remplace les modèles inventés par `MpStorageDocument` / `MpStorageFlagEntry` et référence cette ADR.
- Les ids découverts (mpId, numreponse du premier post) sont cachés **par compte** et purgés au logout (même règle de vie privée que #316) — **implémenté** (cf. Addendum 2026-06-14) : table Room `mp_storage_locations`, et non DataStore comme initialement envisagé, pour aligner la purge sur `mp_read_positions`/`uploaded_images` via `CacheInvalidator`.
- Risques assumés et documentés : lost-update inter-outils (full overwrite sans condition), taille max du post MP inconnue (la `list` DTCloud n'est jamais prunée — la vérification de taille devra précéder toute écriture), dépendance au compte tiers `MultiMP`.
- Trous de vérification restants (l'écriture #593/#597 est livrée *fail-closed* et **OFF par défaut** précisément parce que ces trous ne sont pas tous comblés — à lever avant d'activer l'opt-in par défaut) :
  - effet du GET du formulaire d'édition sur le dot du correspondant (non mesuré par #361) ;
  - contrat `bdd.php cat=prive` en écriture **toujours non capturé live** — le POST est codé selon le contrat de réponse mais jamais exercé contre HFR ;
  - round-trip JSON réel post-écriture, jamais observé de bout en bout sur un vrai document ;
  - arbitrage de la clé write-back (#597, cf. Décision 4) : risque borné par UPDATE-ONLY mais non nul.

  > Note : la découverte par **recherche par titre** (et donc la sensibilité aux paramètres de date `daterange=2` + `jour/mois/annee`) n'est **plus** un trou : l'Addendum 2026-06-14 a remplacé la découverte par un **scan de l'inbox**, supprimant cette voie.

## Addendum — 2026-06-14 (découverte corrigée, cache, seed DT)

Confrontation du **source réel** `MPStorage.user.js` (récupéré depuis le dépôt Wiripse) et vérification live sur un compte possédant un vrai document storage (XaTriX) :

1. **La découverte de la décision 2 était cassée.** La recherche par titre (`forum1.php?recherches=1&…&search=<hash>&titre=1`) renvoie **toujours** « aucune réponse » sur un compte réel : l'index de titres HFR n'indexe pas le hash 32-hex. Le client #406 reportait donc `NotFound` même quand le storage existait. Le source userscript ne fait **aucune recherche serveur** : il **pagine la boîte de réception** (`findStorageMPOnPage` → `forum1.php?cat=prive&page=N`) et matche le **sujet** == hash côté client, puis **cache** `mpId`/`mpRepId` (GM storage) — il ne redécouvre pas à chaque chargement.

   **Correction livrée** : `DefaultMpStorageRepository` découvre désormais par **scan de l'inbox** (réutilise `PrivateMessageListParser`, borné à `MAX_DISCOVERY_PAGES`), `MpStorageDiscoveryParser` et `HfrClient.searchPrivateMessagesBySubject` sont **supprimés**. Le choix « premier résultat » de la décision 2 devient « premier sujet == hash rencontré dans l'ordre inbox » (à re-trancher avant écriture, inchangé).

2. **Cache des ids par compte** (table Room `mp_storage_locations`, purge `CacheInvalidator`) — résout la conséquence « redécouvre à chaque fetch ». Cache périmé (formulaire d'édition introuvable) → purge + rescan.

3. **Décision 5 — application des `mpFlags` livrée en partie** : un seeder (`MpStorageReadPositionSeeder`) projette les positions DT de `mpFlags.list[]` dans le store local `PrivateMessageReadPositionStore` (table `mp_read_positions`), **seed local-prioritaire** (jamais de recul de page). Déclenché une fois par session sur l'écran liste MP, gated par le réglage « section DT ». L'onglet DT dédié reste à câbler ; l'écriture (décision 4) reste différée & opt-in.

Confirmation de format (document réel, 16 clés dans l'entrée v0.1) : `mpFlags.list[].post` = threadId d'une conversation MP de groupe (`cat=prive` dans toutes les `uri`), `page` entier, `p` string. Le piège du **reset destructif** sur contenu invalide est confirmé dans le source (`getStorageData` réécrit le défaut) — non reproduit (ADR-014 décision 3).
