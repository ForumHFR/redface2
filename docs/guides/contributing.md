---
title: Contribuer
parent: Guides
nav_order: 1
permalink: /guides/contributing
---

# Contribuer
{: .fs-8 }

Comment participer au projet.
{: .fs-5 .fw-300 }

---

## Phase actuelle : Spécifications

Le projet est en phase de spec. Le code n'est pas encore écrit. Les contributions les plus utiles en ce moment :

- **Commenter les issues** : donner son avis sur les choix techniques, proposer des alternatives
- **Proposer des features** : ouvrir une issue avec le label `feature`
- **Signaler des oublis** : quelque chose manque dans les specs ? Dites-le
- **Proposer un nom** : voir la [page nommage]({{ site.baseurl }}/guides/naming)

---

## Quand le dev commencera

### Prérequis

- Android Studio (dernière version stable)
- JDK 21+
- Un appareil ou émulateur Android API 29+
- Un compte HFR (pour tester)

### Structure du projet

```
redface2/
  app/                    # Point d'entrée, DI, navigation
  core/
    model/                # Modèles domaine
    domain/               # Interfaces de repositories, règles métier
    data/                 # Implémentations de repositories
    network/              # OkHttp, session HFR
    parser/               # Jsoup, HTML → modèles
    database/             # Room, cache, sync MPStorage
    ui/                   # Thème, composants partagés, PostRenderer
    extension/            # Points d'extension (Phase 4)
  feature/
    forum/                # Catégories, topics
    topic/                # Lecture de topic
    editor/               # Reply, edit, FP, création topic
    messages/             # MPs, MultiMPs
    auth/                 # Login
    search/               # Recherche
    settings/             # Préférences
```

### Gestion des dépendances

Le projet utilisera un **Gradle version catalog** (`gradle/libs.versions.toml`) créé en Phase 0 comme **source de vérité unique** des versions. Choix de stack et versions structurelles (major.minor) documentés dans [stack.md]({{ site.baseurl }}/specs/stack) — les patches exacts sont résolus dans le TOML au bootstrap et maintenus via Renovate/Dependabot.

**Pourquoi pas de tableau de versions exact ici** : une doc qui liste `kotlin = "2.3.20"` dérive en 3 mois. La source unique est le fichier `libs.versions.toml` du repo, interrogeable aussi via Context7/Docfork (cf. [#19](https://github.com/ForumHFR/redface2/issues/19)) pour générer du code aligné avec les APIs stables courantes.

### Convention par feature

Chaque feature suit la même organisation :

```
feature/topic/
  TopicScreen.kt          # @Composable, collecte state + effects
  TopicContent.kt         # @Composable stateless, previewable
  TopicViewModel.kt       # MVI ViewModel
  TopicState.kt           # State + Intent + Effect
```

### Méthodologie

La méthode canonique du projet est documentée dans [Méthodologie]({{ site.baseurl }}/specs/methodology) et formalisée dans [ADR-000]({{ site.baseurl }}/specs/adr/000-methodologie-triple-hybride).

Cette page décrit **comment** contribuer ; elle ne redéfinit pas la méthode du projet. Pour une contribution structurante, lire `docs/specs/methodology.md` puis `AGENTS.md`.

### Style de code

- **Kotlin** : suivre les [conventions officielles](https://kotlinlang.org/docs/coding-conventions.html)
- **Compose** : suivre les [guidelines API](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md)
- **Nommage** : anglais pour le code, français pour les issues et la documentation
- **Pas de code commenté** : si c'est supprimé, c'est supprimé
- **Pas de TODO dans le code** : ouvrir une issue à la place

### Accessibilité

- Tous les éléments interactifs : `contentDescription` ou `semantics { }` en Compose
- Touch targets minimum 48dp
- Contraste WCAG AA (4.5:1 texte, 3:1 gros texte)
- Support du scaling de police (pas de tailles en `dp` pour le texte, toujours `sp`)
- Navigation TalkBack : headings sémantiques, custom actions pour les posts
- Lint a11y activé en CI dès Phase 0

### Localisation

- Toutes les chaînes UI dans `strings.xml` dès Phase 0 (français par défaut)
- `values-en/strings.xml` pour le listing Play Store (anglais)
- Pas de strings hardcodées dans les Composables — détecté par lint
- L'app est conçue pour la communauté francophone HFR, mais la structure i18n est en place dès le départ

### Workflow Git

- Branche principale : `main`
- Branches feature : `feature/nom-court`
- Branches fix : `fix/nom-court`
- Commits : [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`)
- PR obligatoire pour merger dans `main`
- Review par au moins un mainteneur

### Tests

**Stack de tests (Phase 0) :**
- **JUnit 4** — framework de test
- **MockK** — mocking Kotlin-first
- **Robolectric** — tests Android sans émulateur
- **Turbine** — test des `Flow` et `StateFlow`
- **Compose Testing** — tests UI pour les écrans critiques (Phase 1+)

**Enforcement au build (Phase 0) :**
- **Konsist** — règles d'architecture (imports inter-modules, annotations Hilt, layers, tokens M3 centralisés dans `:core:ui`, `@AnonymousClient` sur prefetch). Voir [architecture.md]({{ site.baseurl }}/specs/architecture) pour les règles. Adopté Phase 0 pour neutraliser les biais multi-LLM.
- **Detekt** — style Kotlin + deprecations (`runBlocking`, `GlobalScope`, `LiveData`, imports dépréciés).
- **Android Lint** — a11y + i18n + correctness. `MissingContentDescription`, `TouchTargetSizeCheck`, `HardcodedText` en `error` (abort build). Config `lintOptions` dans `build.gradle.kts`.

**Couverture (hybride différenciée) :**
- **100% sur les transformers du parser HFR** — naturel, fixtures dictent exhaustivité
- **Guidée par risque ailleurs** (ViewModels, mappers, repositories) — tests sur edge cases réels + fixtures, pas de quota chiffré
- Outil de mesure (Kover) pour info, pas comme gate CI

**Stratégie :**
- **TDD sélectif** sur fonctions pures (parser, BBCode AST, ViewModels, helpers, mappers) — red → green → refactor
- **Test-after** sur intégrations (repositories cache/network, deep linking)
- **Pas de TDD** sur UI Compose (Compose Preview + review visuelle suffisent ; Roborazzi non retenu en MVP, à reconsidérer Phase 4+ si régressions visuelles multi-features)

**Smoke test mensuel HFR (Phase 1 fin) :**

Workflow GitHub Actions (`cron: '0 2 1 * *'`, 1er du mois, 2h UTC) qui vérifie contre HFR réel :

- Sélecteurs CSS critiques (`HfrSelectors`) matchent toujours
- Liste catégories + sous-catégories (`HfrCategories.ALL` hardcodée) matche le HTML de la page d'accueil — détecte les ajouts/renommages HFR rares mais impactants

Alerte via issue GitHub auto si diff détecté. Activé dès que `HfrSelectors` est significatif (~10 entrées) + parser cats codé, typiquement fin Phase 1.

### Héritage Redface v1

Le code de [Redface v1](https://github.com/ForumHFR/Redface) contient ~10 transformers de parsing, 17 fixtures HTML et 13 tests. Cette base est reprise comme point de départ :
- Les **fixtures HTML** servent de référence pour les edge cases du parser HFR
- La **logique de parsing** (gestion des posts supprimés, pages instables, encoding) est analysée pour ne pas réinventer la roue
- Les edge cases identifiés dans les tests v1 deviennent des cas de test dans v2

### Fixtures HTML pour le parser

Le parser HTML est testé contre des **fixtures capturées depuis de vraies pages HFR** (jamais fabriquées par une IA). Chaque page nécessitant une authentification est capturée en version logué **et** non-logué quand la distinction est pertinente (contenu différent, champs manquants, redirections).

```
core/parser/src/test/resources/fixtures/
```

**Reprises de Redface v1** (13 fixtures testées dans `app/src/test/resources/` de ForumHFR/Redface, v1 en compte 17 physiquement, 4 non testées) :

| Fixture | Page HFR | Auth ? | Source HFR (exemple à capturer) |
|---------|----------|--------|---------------------------------|
| `topic_multipage.html` | Topic multi-pages | logué + non-logué | `cat=23, post=35395` (>1 page) |
| `topic_singlepage.html` | Topic 1 seule page | non-logué | topic court cat=23 |
| `topic_posts.html` | Posts d'un topic | logué + non-logué | `cat=23, post=35395, page=1` |
| `edit_post.html` | Page d'édition d'un post | logué uniquement | `message.php?numreponse=X` sur propre post |
| `quote.html` | Contenu de citation BBCode | logué uniquement | `message.php?quote=X` |
| `categories.html` | Page d'accueil (catégories) | non-logué | `/hfr/` |
| `topic_list.html` | Liste topics d'une sous-catégorie | logué + non-logué | `forum2.php?cat=23&subcat=0` |
| `profile_standard.html` | Profil utilisateur standard | non-logué | `hfr/profil-<id>.htm` (membre) |
| `profile_admin.html` | Profil admin/modo | non-logué | `hfr/profil-<id>.htm` (modo) |
| `mp_list.html` | Liste des MPs classiques | logué uniquement | `message.php` |
| `mp_conversation.html` | Conversation MP | logué uniquement | `message.php?cat=prive&post=<mp_id>` |
| `smiley_search.html` | Résultats recherche de smileys | non-logué | `message-smi-mp-aj.php?search=X` |
| `rehost_response.html` | Réponse reho.st | non-logué | `reho.st` HTML de réponse |

**Nouvelles fixtures v2** (pages non couvertes par v1) :

| Fixture | Page HFR | Auth ? | Pourquoi | Source HFR (à capturer) |
|---------|----------|--------|----------|-------------------------|
| `flags_page.html` | `/forum1f.php` (drapeaux) | logué uniquement | Écran d'accueil, pas dans v1 | `forum1f.php?owntopic=1` |
| `flags_page_empty.html` | Drapeaux vides | logué uniquement | Cas edge : aucun drapeau | compte neuf ou nettoyé |
| `search_results.html` | `/search.php` | logué + non-logué | Recherche | `search.php?search=redface` |
| `login_success.html` | Réponse login OK | — | Détection succès auth | Après POST login OK |
| `login_failure.html` | Réponse login échoué | — | Détection échec auth | Après POST bad pass |
| `edit_fp.html` | Édition First Post (sujet + sondage) | logué uniquement | Distinct de l'édition normale | propre topic avec sondage |
| `new_topic.html` | Page de création de topic | logué uniquement | Formulaire avec sous-catégories | `forum1.php?cat=23&action=new` |
| `multimp_conversation.html` | MultiMP | logué uniquement | Différent des MPs classiques | MultiMP existant |
| `topic_with_poll.html` | Topic avec sondage | logué + non-logué | Parsing du sondage | topic public avec sondage |
| `topic_last_page.html` | Dernière page (< 40 posts) | non-logué | Pagination edge case | dernière page d'un topic |
| `topic_deleted_posts.html` | Page avec posts supprimés | non-logué | Décalage de numérotation | topic modéré connu |
| `modo_not_flagged.html` | modo.php — formulaire d'alerte | logué uniquement | Redflag : post pas encore alerté | `modo.php?numreponse=X` |
| `modo_flagged.html` | modo.php — déjà alerté | logué uniquement | Redflag : post alerté | `modo.php?numreponse=X` (déjà alerté) |
| `modo_join.html` | modo.php — rejoindre une alerte | logué uniquement | Redflag : alerte en cours | `modo.php?numreponse=X` (alerte ouverte) |

**Profil et paramètres** (pages `editprofil.php`, loguées uniquement) :

| Fixture | Page HFR | Contenu | Source HFR |
|---------|----------|---------|------------|
| `profile_settings_p1.html` | `editprofil.php?page=1` | Infos générales : email, date naissance, sexe, ville, profession, loisirs | compte de test |
| `profile_settings_p2.html` | `editprofil.php?page=2` | Infos forum : citation, signature (BBCode), config matérielle | idem |
| `profile_settings_p3.html` | `editprofil.php?page=3` | Paramètres : réponses/page, avatars, signatures, thème CSS, jeu d'icônes, langue, fuseau, notifs MP | idem |
| `profile_settings_p4.html` | `editprofil.php?page=4` | **Déprécié** — messageries instantanées (ICQ, MSN). Page existe encore. Fixture capturée pour exhaustivité, **aucun test de régression requis**. | idem |
| `profile_settings_p5.html` | `editprofil.php?page=5` | Gestion d'images : avatar, smileys persos, smileys favoris, wiki smileys | idem |
| `profile_settings_p6.html` | `editprofil.php?page=6` | Notifications : mots-clés (max 3) pour alerte par mail/MP à la création de topics | idem |
| `profile_settings_p7.html` | `editprofil.php?page=7` | Personnalisation barre d'outils : 15 icônes repositionnables (9 positions + masquer) | idem |
| `contact_list.html` | `contactlist.php` | Liste de contacts : ajout/suppression, statut en ligne, liens MP | idem |
| `modo_history.html` | `modo/historique.php` | Historique des sanctions : modérateur, catégorie, date, raison | modérateur test |

**Total : ~36 fixtures** (13 reprises testées de v1 + 14 nouvelles + 9 profil/paramètres).

**Règles :**
- Les fixtures sont capturées depuis le vrai site HFR, **jamais** fabriquées par une IA ou à la main.
- Capture via MCP `hfr-mcp` : `hfr_read cat=X post=Y page=Z output=path/to/fixture.html` écrit le HTML brut.
- Chaque fixture est accompagnée d'un fichier `.source.txt` frère ou d'un commentaire HTML en tête précisant `cat`, `post`, `numreponse`, date de capture.
- Les fixtures loguées ne doivent **jamais** contenir de cookies, tokens `hash_check`, emails, identifiants réels — nettoyer avant commit (voir skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) étape 9).
- Quand un bug de parsing est corrigé, le HTML problématique est ajouté aux fixtures avec un test de non-régression.
- Un **smoke test CI mensuel** (cf. cron `0 2 1 * *` ci-dessus) vérifie que les sélecteurs CSS critiques matchent toujours sur une vraie page HFR publique.

---

## Communication

- **Issues GitHub** : pour les bugs, features, questions techniques
- **Topic HFR** : pour les discussions générales avec la communauté (lien à venir)

---

## Attribution

Les contributions sont reconnues dans le CHANGELOG et les release notes. Merci à tous ceux qui participent.
