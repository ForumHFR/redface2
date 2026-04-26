---
title: Roadmap
parent: Spécifications
nav_order: 10
permalink: /specs/roadmap
mermaid: true
---

# Roadmap
{: .fs-8 }

Phases de développement, de la fondation au polish.
{: .fs-5 .fw-300 }

---

## Vue d'ensemble

Les phases sont **ordonnées par dépendances techniques**, pas datées. Le rythme réel dépend des contributeurs et des dépendances externes (ex. MPStorage2 dans hfr-redkit à finaliser avant la Phase 3). Cohérent avec la [méthodologie triple-hybride]({{ site.baseurl }}/specs/methodology) (prototype-driven).

Pour la liste des capabilities et des non-goals, voir le [scope fonctionnel]({{ site.baseurl }}/specs/scope).

### Dashboard des phases

| Phase | Objectif | Taille | Dépend de | Statut |
|---|---|---|---|---|
| **0 — Bootstrap** | Squelette qui compile, CI, thème, navigation | S | — | ✅ Livrée |
| **1 — Core** | Lecture du forum (drapeaux, topics, forum, deep links) | XL | Phase 0 | 🟡 En cours (slice topic fixe + AST `PostContent` livrés via [#78](https://github.com/ForumHFR/redface2/pull/78) / [#80](https://github.com/ForumHFR/redface2/pull/80)) |
| **2 — Écriture** | Post / edit / quote / create topic / recherche | L | Phase 1 | À faire |
| **3 — Messages** | MPs classiques + MultiMPs avec sync | M | Phase 2 + **MPStorage2** (hfr-redkit) | À faire |
| **4 — Extensions** | Bookmarks, Blacklist, Qualitay, Redflag | L | Phase 3 + **hfr-redflag Worker** | À faire |
| **5 — Polish** | Animations, offline, thème dynamique, Play Store | M | Phases 2, 3, 4 | À faire |

**Taille** : S = petit sous-chantier, M = quelques composants, L = plusieurs features indépendantes, XL = écran majeur + parseurs + cache (ex. `PostRenderer` natif).

### Graphe des dépendances

```mermaid
flowchart LR
    P0["Phase 0<br/>Bootstrap"]
    P1["Phase 1<br/>Core lecture"]
    P2["Phase 2<br/>Écriture"]
    P3["Phase 3<br/>Messages"]
    P4["Phase 4<br/>Extensions"]
    P5["Phase 5<br/>Polish"]

    MPS[("MPStorage2<br/>hfr-redkit")]
    RFL[("hfr-redflag<br/>CF Worker")]

    P0 --> P1 --> P2 --> P3 --> P4 --> P5
    MPS -.prérequis.-> P3
    RFL -.prérequis.-> P4
    P2 --> P5
    P3 --> P5

    classDef external fill:#fef3c7,stroke:#d97706
    class MPS,RFL external
```

Les dépôts en cylindre (`MPStorage2`, `hfr-redflag`) sont des **dépendances externes** hors de ce repo — leur état bloque le démarrage de la phase qui les consomme.

---

## Phase 0 — Bootstrap ✅ livrée

**Objectif :** un squelette d'app qui compile, avec CI, thème et navigation.

- [x] Structure Gradle multi-modules (8 core + 7 features base déclarés ; certains modules conservent un `build.gradle.kts` vide en attente de leur cycle, cf. ADR-001)
- [x] CI GitHub Actions (`detektAll`, `lintDebug`, `testDebugUnitTest`, `:app:assembleDebug`)
- [x] Thème Material 3 dans `:core:ui` (clair, sombre, AMOLED — Material You + HFR Classique différés Phase 5)
- [x] Navigation graph Compose Navigation 3 (bottom nav 4 onglets + back stacks par onglet, cf. [navigation.md]({{ site.baseurl }}/specs/navigation))
- [x] Hilt wiring (`build-logic` convention plugins, KSP, `@HiltAndroidApp`)
- [x] Design system de base (typographie, couleurs, composants thème)
- [x] Build signed AAB — pipeline `--init-script` + stamping `redface2-v<vc>-<YYYYMMDD>-<sha>.aab`

**Livrable :** une app qui démarre, affiche la bottom nav et navigue entre les écrans Phase 1 (drapeaux mock, forum/search/messages placeholder, topic fixe, éditeur placeholder).

---

## Phase 1 — Core (lecture seule) 🟡 en cours

**Objectif :** lire le forum. C'est 80% du use case.

- [ ] Login HFR (cookies persistants)
- [ ] Écran Drapeaux (accueil) — tri par date/catégorie, filtres (mock courant, repository réel à venir)
- [x] **Slice topic fixe** — `TopicScreen` rend une fixture HFR réelle (fixture `topic_khakha_page_146.html`) via le pipeline complet parser → AST → renderer
- [x] **Parser HTML topic** — `:core:parser` produit `PostContent` depuis le HTML HFR (cf. [PR #78](https://github.com/ForumHFR/redface2/pull/78))
- [x] **PostRenderer Compose** — rendu natif `PostContent` dans `:core:ui` (paragraphes, citations imbriquées avec collapse, spoilers, smileys builtin/perso, images, couleurs ; cf. [ADR-011]({{ site.baseurl }}/adr/011-postcontent-ast) et [PR #80](https://github.com/ForumHFR/redface2/pull/80))
- [ ] Écran Topic réel — `TopicRepository` au-dessus d'OkHttp + cache Room (remplace `TopicFixtureRepository`)
- [ ] Écran Topic — pagination, scroll vers `numreponse` cible, navigation entre pages
- [ ] Écran Forum — catégories, sous-catégories, liste de topics
- [ ] Cache Room — topics et drapeaux
- [ ] Deep linking (URLs HFR → app) — squelette `parseHfrDeepLink` posé dans `RedfaceNavigation.kt`, à brancher sur les écrans réels
- [ ] Prefetch pages suivantes (avec `@AnonymousClient`, cf. [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié))
- [ ] Images + smileys (Coil 3 — split fetcher déjà câblé via `coil-network-okhttp`)
- [ ] Blocs monospace `[fixed]` / `[code]` — restent suivis par [#79](https://github.com/ForumHFR/redface2/issues/79) et arriveront avec une fixture réelle

**Livrable :** une app utilisable pour **lire** le forum au quotidien. Pas encore de possibilité d'écrire.

### PostRenderer — le sous-chantier critique

Le rendu natif Compose du contenu HFR est le composant le plus complexe de toute l'app. Il doit gérer :

| Élément | Complexité |
|---------|-----------|
| Texte formaté (gras, italique, souligné, couleur, taille) | Moyenne |
| Citations imbriquées | Élevée |
| Blocs de code | Faible |
| Images inline | Moyenne |
| Smileys HFR | Moyenne (cache + mapping) |
| URLs cliquables | Faible |
| Spoilers (clic pour révéler) | Moyenne |
| Listes | Faible |

Le PostRenderer sera développé de manière incrémentale : texte brut d'abord, puis formatage, puis citations, puis images.

---

## Phase 2 — Écriture

**Objectif :** interagir avec le forum.

- [ ] Recherche — titres de topics et contenu de posts, filtres par catégorie/auteur/date
- [ ] Reply — répondre à un topic
- [ ] Quote — citer un post → reply pré-rempli
- [ ] Edit — éditer son propre post
- [ ] Edit FP — éditer le first post (sujet, contenu, sondage)
- [ ] Create topic — nouveau topic avec catégorie, sujet, contenu, sondage optionnel
- [ ] Toolbar BBCode — boutons de formatage dans l'éditeur
- [ ] Preview BBCode — avant-première du rendu
- [ ] Hébergement d'images — upload, rehost, bibliothèque (diberie, super-h.fr, imgur)

**Livrable :** une app complète pour lire ET écrire sur le forum.

---

## Phase 3 — Messages

**Objectif :** les messages privés, classiques et multi.

- [ ] Inbox MPs classiques — liste, lecture, reply
- [ ] Nouveau MP — création
- [ ] MultiMPs — liste avec vue drapeaux, lecture, reply, quote
- [ ] Nouveau MultiMP — création (2+ destinataires)
- [ ] Intégration MPStorage — synchronisation avec le MP de stockage HFR + cache Room
- [ ] Notifications MP

**Livrable :** gestion complète des MPs, y compris les MultiMPs avec état lu/non-lu.

---

## Phase 4 — Extensions communautaires

**Objectif :** les features inspirées des userscripts HFR.

- [ ] Architecture d'extensions (PostDecorator, TopicToolbarContributor)
- [ ] Bookmarks — sauvegarder des posts
- [ ] Blacklist — masquer des utilisateurs
- [ ] Alertes Qualitay — signaler un post remarquable
- [ ] Redflag — alertes intelligentes sur topics suivis

**Livrable :** les features communautaires les plus demandées, intégrées nativement.

---

## Phase 5 — Polish

**Objectif :** l'expérience utilisateur raffinée.

- [ ] Animations et transitions
- [ ] Mode offline complet (lecture + file d'attente d'écriture)
- [ ] Notifications push configurables
- [ ] Thème dynamique (Material You)
- [ ] Thème "HFR classique"
- [ ] Widgets Android
- [ ] Tests de performance (scroll, cold start, mémoire)
- [ ] Release automation
  - Signed release build (keystore en GitHub Secrets)
  - Publication Play Store (arbitrage Fastlane vs Gradle Play Publisher)
  - Beta testing (Play Console internal testing privilégié vs Firebase App Distribution)
  - Création compte développeur ForumHFR si inexistant

**Livrable :** une app prête pour le grand public.

---

## Participation

Chaque phase sera trackée via les [issues GitHub](https://github.com/ForumHFR/redface2/issues) et des milestones. Les contributions sont les bienvenues à partir de la Phase 1.

Pour contribuer :
1. Choisir une issue non assignée
2. Commenter pour signaler qu'on la prend
3. Ouvrir une PR sur une branche feature
4. Review par un mainteneur
