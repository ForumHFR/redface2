---
title: ADR-013
parent: ADRs
grand_parent: Spécifications
nav_order: 13
permalink: /adr/013-mp-lecture-cache-prefetch
---

# ADR-013 — Lecture MP : partage topic↔MP, cache à trois étages, prefetch borné

## Statut

Proposé — 2026-06-10

Cette ADR formalise les arbitrages rendus dans [#351](https://github.com/ForumHFR/redface2/issues/351) ([analyse code](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989) + [addendum cache](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671)) et [#361](https://github.com/ForumHFR/redface2/issues/361) ([investigation live du contrat serveur lu/non-lu](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132), 2026-06-09). Elle n'invente aucun verdict : chaque assertion factuelle sur HFR renvoie au commentaire d'issue qui l'a vérifiée. Conformément à la règle « pas de décision implicite », **rien n'est acté tant que le statut n'est pas passé à « Accepté »** : les pages canoniques (`architecture.md`, `protocol-hfr.md`) portent une note « proposition en cours » et seront mises à jour à l'acceptation.

## Contexte

### Le constat (#351)

Retour bêta v94 (0.6.0) : le swipe de pages ne marche pas dans les MP. Plus largement, la vue conversation (`PrivateMessageThreadScreen`) ne reprend aucun des gestes de lecture du topic (swipe [#282](https://github.com/ForumHFR/redface2/issues/282), ascenseur [#300](https://github.com/ForumHFR/redface2/issues/300), pull-to-refresh [#335](https://github.com/ForumHFR/redface2/issues/335), cluster bas de page [#283](https://github.com/ForumHFR/redface2/issues/283)), et `MessageCard` duplique une version allégée de `TopicPostCard`.

L'[analyse code sur `dev`](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989) a montré que le frein réel n'est pas la visibilité `internal` des composants topic, mais la divergence des **modèles de pagination** :

- **topic = route-driven** : le commit du swipe remplace la `TopicRoute` courante (nouvelle entrée nav, nouveau ViewModel, nouvelle composition). Toute la machinerie de `feature/topic/.../TopicSwipe.kt` repose sur cette hypothèse — le latch `committed` n'est jamais réarmé explicitement, il est détruit avec la composition au changement de route ;
- **MP = in-place** : `PrivateMessageThreadViewModel.selectPage(page)` recharge la page dans le **même** ViewModel, même composition, même entrée nav.

Porter `Modifier.topicPageSwipe` tel quel sur les MP produirait donc un écran gelé après le premier swipe (latch jamais détruit). Par ailleurs, le ressenti instantané du swipe topic repose sur le cache Room et le prefetch anonyme — tous deux absents côté MP : `cat=prive` exige l'authentification (403 anonyme, [#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)), et la décision vie privée d'origine ([#316](https://github.com/ForumHFR/redface2/issues/316) : routes opaques, pas de persistance) excluait tout cache MP. Cette décision « pas de cache MP » a été **explicitement rouverte** par XaaT le 2026-06-09 ([addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671)).

### Le contrat serveur mesuré (#361)

Le prefetch MP butait sur un contrat serveur jamais mesuré (le comportement topic — GET authentifié = drapeau déplacé — était *supposé* s'appliquer aux MP). L'[investigation live #361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132) (2026-06-09, compte XaTriX, sandbox = conversation existante déjà lue, état final restauré à l'identique) a établi :

- **Q1** — un GET authentifié de **n'importe quelle page** d'une conversation `cat=prive` efface le non-lu de **toute** la conversation ; le GET de la liste (`forum1.php?cat=prive`) est inerte ;
- **Q2** — « marquer comme non lu » = `GET /user/nonlu.php?...&cat=prive&post=<threadId>...` **sans `hash_check`** ; granularité **binaire, conversation entière** (le paramètre `page` n'encode aucune position) ;
- **Q3** — il n'existe **aucune position de lecture serveur** pour les MP : pas de drapal en `cat=prive` (zéro `new=1`, zéro `numreponse` non nul, colonne drapeau vide), l'état serveur se réduit au **dot binaire** par conversation ;
- **MultiMP** — l'état de lecture est visible des autres participants (span « Ce message n'a pas été lu par : <pseudos> ») : accusé de lecture de fait ;
- **compensation** — la boucle `nonlu` → lecture est **sans perte** précisément parce que l'état est binaire (pas de position à perdre).

C'est le cas « (b) binaire » anticipé par #361, mais avec une observation clé qui change le verdict prefetch : l'ouverture d'une conversation consomme déjà tout l'état observable.

## Décision proposée

> Les formulations au présent ci-dessous décrivent l'état cible **si cette ADR est acceptée**. Rien n'est effectif ni acté tant que le statut n'est pas passé à « Accepté » par le maintainer.

### 1. Partage topic↔MP à deux niveaux dans `:core:ui`

- Les **fonctions pures du swipe** (`swipeTargetPage`, `swipeCommitDirection`, `swipeCommitDistancePx`, `swipeFollowOffset`, `swipeArmed`, `swipeEdgeHintAlpha` — aujourd'hui `internal` dans `feature/topic/.../TopicSwipe.kt`, testées unitairement, sans dépendance topic) déménagent vers `:core:ui`. Elles portent l'intégralité du « ressenti » (seuils distance/vélocité, overpull, hint d'armement) et garantissent un geste identique sur les deux écrans.
- **`TopicScrollbar`** (paramétrique sur `LazyListState`, callbacks internes au composant, zéro référence à un type topic — [vérifié #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)) déménage vers `:core:ui` comme composant générique, dans le sous-package `components/` déjà prévu par [architecture.md]({{ site.baseurl }}/specs/architecture). **Pas de nouveau module.**
- La **machinerie gestuelle nav-driven** (`Modifier.topicPageSwipe` : `pointerInput` + latch + slide-out, intrinsèquement couplée à la destruction de composition du modèle route-driven) **reste dans `:feature:topic`**.
- Les MP reçoivent une implémentation **minimale in-place** réutilisant les mêmes fonctions pures : commit → `selectPage()`, latch réarmé localement en fin de chargement, sans slide-out ([décision XaaT, #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)).

### 2. Cache MP à trois étages

1. **Position de lecture locale par conversation** (« drapal local », esprit MPStorage) : retenue inconditionnellement. Ce n'est pas un nice-to-have : c'est la **seule** option possible, puisqu'il n'existe aucune position de lecture serveur pour les MP ([#361 Q3](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)). Format à aligner sur MPStorage2 ([#6](https://github.com/ForumHFR/redface2/issues/6)) pour la sync future. Corrige au passage la restauration post process-death (la route reste figée sur la page d'ouverture, [bug relevé en #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)), mieux que `SavedStateHandle` (survit aux sessions).
2. **Cache RAM de session** : retenu. Purgé à la déconnexion et au process death, rien sur disque. Donne les retours de page instantanés et permet de garder le contenu à l'écran pendant les chargements.
3. **Cache Room du contenu** : **opt-in explicite uniquement** — toggle dans les réglages, **défaut OFF**, purge à la déconnexion. ([Décisions XaaT 2026-06-09, addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671).)

Cette politique précise [#316](https://github.com/ForumHFR/redface2/issues/316) sans l'annuler : les routes MP restent opaques (`threadId`, `page`), aucune métadonnée privée dans le back stack, et rien de persistant par défaut.

### 3. Prefetch : exception bornée à l'invariant « prefetch anonyme »

L'invariant général « les requêtes de prefetch ne sont jamais authentifiées » ([protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié)) **reste en vigueur partout ailleurs**. Pour les MP, où le prefetch anonyme est impossible (`cat=prive` exige l'auth), une exception **bornée** est définie :

- **Autorisé : prefetch authentifié intra-conversation ouverte** (le cas du swipe — page N+1 de la conversation que l'utilisateur lit). **Pas d'effet supplémentaire dans le cas nominal** : le GET d'ouverture a déjà effacé le dot binaire de toute la conversation, et en MultiMP l'utilisateur est déjà sorti de la liste « pas lu par » ([#361, verdict 1](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)). Pas de compensation nécessaire. Hors cas nominal, une race **documentée et assumée** : un message arrivant entre la lecture de N et le prefetch de N+1 verrait son dot effacé (et, en MultiMP, le read-receipt mis à jour) sans avoir été affiché — effet observable mais jugé bénin, l'utilisateur est précisément dans cette conversation.
- **Interdit : prefetch depuis la liste** (conversations non ouvertes, dot non-lu) : il effacerait un non-lu jamais vu par l'utilisateur **et** le retirerait de la liste « pas lu par » des autres participants en MultiMP (read-receipt). La compensation `nonlu.php` serait sans perte, mais les deux requêtes ne sont pas atomiques : un crash entre les deux corromprait un état visible des autres clients ([#361, verdict 2](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)). Interdit en v1, réévaluable.

Conséquence d'implémentation : la règle Konsist « toute fonction `prefetch*` passe `useAuth = false` » devra distinguer explicitement le call-site MP intra-conversation (nommage dédié ou exemption documentée) — à traiter dans la PR qui introduira ce prefetch, pas silencieusement.

### 4. Critère de convergence route-driven topic↔MP

Le passage des écrans MP au modèle route-driven du topic (qui permettrait de porter la machinerie `topicPageSwipe` telle quelle et de fusionner les deux modèles de pagination) est **conditionné à la réunion des deux prérequis** :

1. cache MP en place (RAM a minima, Room si opt-in activé) ;
2. prefetch intra-conversation borné en place.

Tant qu'ils ne sont pas réunis, les MP restent in-place avec le swipe minimal (décision 1). Une fois réunis, la parité de ressenti avec le topic devient possible et la convergence peut être engagée — le swipe minimal in-place se remplace alors à coût nul, les fonctions pures partagées restant la base dans les deux cas ([addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671), [verdict #361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).

## Conséquences

- `:core:ui` gagne le composant scrollbar générique (`components/`) et les helpers purs du swipe ; `:feature:messages` et `:feature:topic` les consomment sans nouvelle arête de dépendance (les deux dépendent déjà de `:core:ui`).
- Prérequis UI côté MP : `selectPage()` / refresh ne doivent plus passer par `PrivateMessageThreadUiState.Mode.Loading` plein écran (qui efface le contenu affiché) — contenu conservé + indicateur de chargement, tranche a du [plan en trois tranches de #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989).
- La position de lecture locale introduit le premier stockage MP côté app : clé par conversation, format aligné MPStorage2 ([#6](https://github.com/ForumHFR/redface2/issues/6)), purge à la déconnexion comme le reste de l'état privé.
- Vie privée : rien de plus persistant par défaut qu'aujourd'hui. Le cache Room est OFF par défaut, purgé au logout ; les routes opaques de [#316](https://github.com/ForumHFR/redface2/issues/316) sont inchangées.
- Opportunité produit hors périmètre de cette ADR : exposer « Marquer comme non lu » dans l'app — le contrat `nonlu.php` est trivial (GET sans `hash_check`, [#361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).
- À l'acceptation, les pages canoniques sont à mettre à jour : [architecture.md]({{ site.baseurl }}/specs/architecture) (stratégie de cache, prefetch), [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) (exception MP à la règle prefetch, contrat `nonlu.php`), [navigation.md]({{ site.baseurl }}/specs/navigation) (si convergence route-driven engagée). D'ici là, elles ne portent qu'une note « proposition en cours ».

## Alternatives considérées

- **Option A — porter `topicPageSwipe` tel quel sur les MP** : rejetée. Le latch n'est réarmé que par destruction de composition (modèle route-driven) ; en pagination in-place l'écran serait gelé après le premier swipe — bug structurel garanti, pas un détail d'implémentation ([#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)).
- **Option B — module `:core:postlist` / composant de liste paginée unifié** : rejetée dans sa forme module dédié. Sur-ingénierie à deux consommateurs, et les modèles de pagination divergent précisément là où le composant devrait être commun ([#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)). Réévaluable au troisième consommateur.
- **Option C — statu quo (MP sans gestes)** : rejetée. Le coût de la version minimale est faible et le retour testeur resterait sans réponse ([#351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4662808989)).
- **Généraliser la machinerie gestuelle nav-driven pour couvrir les deux modèles** : rejetée — complexité spéculative pour deux consommateurs ; la frontière retenue (fonctions pures partagées, machinerie par modèle de pagination) est plus simple et suffisante.
- **Pas de cache MP du tout** (décision d'origine, époque [#316](https://github.com/ForumHFR/redface2/issues/316)) : remplacée — rouverte explicitement par XaaT ([addendum #351](https://github.com/ForumHFR/redface2/issues/351#issuecomment-4663229671)). Les garanties de #316 qui restent pertinentes (routes opaques, pas de métadonnée privée dans le back stack) sont conservées.
- **Cache Room par défaut (opt-out)** : rejeté — du contenu privé persisté sur disque sans consentement explicite irait contre l'esprit de [#316](https://github.com/ForumHFR/redface2/issues/316).
- **Prefetch depuis la liste avec compensation `nonlu.php`** : rejeté en v1 — deux mutations non atomiques sur un état serveur visible des autres clients (et des autres participants en MultiMP) ; une interruption entre les deux corromprait l'état ([#361](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).
- **Position de lecture serveur** : impossible, pas un choix — HFR n'offre aucun mécanisme de position pour `cat=prive`, l'état serveur est un dot binaire par conversation ([#361 Q3](https://github.com/ForumHFR/redface2/issues/361#issuecomment-4663312132)).
