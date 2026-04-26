# Changelog

Toutes les évolutions notables des specs Redface 2.

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions sont celles des specs (`docs/_config.yml` `footer_content`). À partir de v0.6.0, elles incluent les changements code/spec couplés : depuis Phase 1, les specs reflètent l'état réel du repo et sont bumpées en lockstep avec les PRs structurantes (cf. `/spec-reality` dans `AGENTS.md`).

---

## [Unreleased]

(rien pour le moment)

---

## v0.6.0 — 2026-04-25

Réalignement des specs sur la réalité du code après les PR [#78](https://github.com/ForumHFR/redface2/pull/78) (parser HTML topic + AST `PostContent`) et [#80](https://github.com/ForumHFR/redface2/pull/80) (`PostRenderer` Compose, retrait du fragment HTML brut de `Post.content`, sortie de Jsoup hors `:core:parser`). Phase courante : **Phase 1 — Core lecture**.

### Added
- `docs/adr/011-postcontent-ast.md` formalise `PostContent` comme AST sémantique commune pour le rendu des posts, alimentée par le HTML HFR lu et le BBCode éditeur (livré PR [#78](https://github.com/ForumHFR/redface2/pull/78) / [#80](https://github.com/ForumHFR/redface2/pull/80)).
- `LICENSE` ajouté à la racine avec le texte officiel **GNU GPL v3**, et `docs/adr/010-licence-client-android.md` formalise le choix de licence du client Android.
- `docs/guides/contributing.md` documente désormais le workflow **MCP documentaire optionnel** : Context7 recommandé, Docfork en fallback, lien vers les setups officiels et cas validés sur AGP 9 / built-in Kotlin et Navigation 3.
- bootstrap **Dev env Docker + Dev Container** : `Dockerfile`, `scripts/docker-dev.sh` et `.devcontainer/devcontainer.json` standardisent l'env Android sur `ghcr.io/cirruslabs/android-sdk:36`.
- CI minimale Phase 0 : workflow GitHub Actions (`detektAll`, `lintDebug`, `testDebugUnitTest`, `:app:assembleDebug`) + `Dependabot` pour `gradle` et `github-actions`.
- stack de tests Phase 0 effectivement bootstrapée dans le repo : **MockK**, **Robolectric** et **Turbine** rejoignent `JUnit 4` et `Konsist` dans le version catalog.
- workflow PR préparé avec `.github/pull_request_template.md` et `.github/CODEOWNERS`.
- `docs/specs/navigation.md` documente le pattern de deep link `cat=prive` pour les MPs (Phase 3).

### Changed
- `AGENTS.md` et `docs/guides/contributing.md` reflètent désormais la **Phase 1 — Core lecture** (Phase 0 bootstrap livrée). Setup pointe sur `./gradlew :app:assembleDebug` au lieu de "pas de build applicatif".
- `AGENTS.md` ne prescrit plus une identité git personnelle (`xat`, `xat@azora.fr`) et les lignes d'attribution IA utilisent désormais `@<demandeur>` pour mieux refléter le caractère multi-contributeur du repo.
- La licence du client Android Redface 2 passe de la mention implicite `Apache 2.0` à **`GPL-3.0-only`** dans le repo (`AGENTS.md`, `README.md`, `docs/guides/contributing.md`).
- le bootstrap Hilt Phase 0 s'aligne sur **Hilt 2.59.2** dans le version catalog, et `docs/specs/stack.md` reflète désormais cette référence d'implémentation.
- l'image Docker / CI de référence est désormais **épinglée par digest** et documentée comme manifest list multi-arch (`amd64` + `arm64`).
- le wrapper `docker-dev.sh` et le dev container ne tournent plus en root par défaut.
- `Dependabot` est recalibré en cadence mensuelle groupée et la CI annule les runs obsolètes sur la même ref.
- `Dependabot` n'ouvre plus une seule PR Gradle fourre-tout : les mises à jour sont désormais regroupées par lanes cohérentes (`build-toolchain`, `androidx-ui-navigation`, `network-imaging`, `test-quality`, etc.) pour faciliter la review.
- les checks Konsist Phase 0 n'acceptent plus des scopes vides silencieux et assertent désormais explicitement un scope non vide avant d'appliquer les règles.
- les specs `models`, `architecture`, `mvi`, `navigation`, `stack`, `methodology`, `roadmap`, `protocol-hfr` et `contributing` s'alignent sur le contrat `PostContent` au lieu de traiter `Post.content` comme une chaîne HTML ou BBCode brute.
- `docs/specs/architecture.md` aligne le tableau `:core:ui` sur la réalité (`theme/` + `post/`, autres sous-packages introduits feature par feature) et signale que plusieurs modules core/feature sont déclarés avec un squelette Gradle vide en attendant leur cycle d'implémentation.
- `docs/specs/navigation.md` réécrit l'exemple Nav 3 sur l'API stable réelle (`NavDisplay(backStack, onBack, entryDecorators, entryProvider)`, `entry<…>`), aligne sur `RedfaceNavKey` sealed, ajoute `ForumRoute` / `SearchRoute` et le couple `TopicRequest` / `TopicScreen.onOpenPage`.
- `docs/specs/mvi.md` aligne `EditorMode` sur la réalité (3 valeurs : `Reply`, `Edit`, `EditFirstPost`) et clarifie que la création de topic est un écran distinct, pas un mode de l'éditeur.
- `docs/specs/stack.md` documente le caveat insets de `NavigationSuiteScaffold` (status bars non consommées par défaut).
- `docs/specs/models.md` aligne le diagramme mermaid `PrivateMessage` sur la `data class` (ajout `page` + `totalPages`), passe `Post.postIndex` à `Int?` (le parser n'a pas toujours le contexte page/postsPerPage) et déplace `UserProfile` à la Phase 2 (popup profil) avec un renvoi vers l'extension Phase 4.
- `docs/adr/011-postcontent-ast.md` reformule la dette `Post.content: String` ([#65](https://github.com/ForumHFR/redface2/issues/65)) comme **résorbée** par PR [#80](https://github.com/ForumHFR/redface2/pull/80).
- `docs/specs/methodology.md` passe en `nav_order: 0` pour apparaître en premier dans le menu Spécifications (méthode foundationale).

### Fixed
- Spec audit post-#78/#80 ([#84](https://github.com/ForumHFR/redface2/issues/84)) : 21 écarts spec/code détectés et corrigés sans toucher au code.

---

## v0.5.1 — 2026-04-19

Réorganisation du site publié : séparation `specs/` / `guides/` et ADRs replacées à la racine `docs/adr/`.

### Added
- `docs/specs/index.md` comme index des pages canoniques publiées. Commit [`b2eab39`](https://github.com/ForumHFR/redface2/commit/b2eab39).
- `docs/guides/index.md` comme index des guides de contribution et de contexte. Commit [`b2eab39`](https://github.com/ForumHFR/redface2/commit/b2eab39).

### Changed
- Site Jekyll réorganisé en deux familles visibles : `docs/specs/` pour les pages canoniques et `docs/guides/` pour les pages d'accompagnement. Commit [`b2eab39`](https://github.com/ForumHFR/redface2/commit/b2eab39).
- ADRs déplacées de `docs/specs/adr/` vers `docs/adr/` pour revenir à une convention de repo plus classique tout en gardant leur rattachement conceptuel aux specs. Commit [`491067a`](https://github.com/ForumHFR/redface2/commit/491067a).
- `docs/index.md`, `README.md`, `AGENTS.md`, `CHANGELOG.md`, `docs/specs/stack.md`, `docs/specs/architecture.md`, `docs/specs/methodology.md`, `docs/guides/contributing.md` et `docs/guides/rationale.md` recâblés sur les nouvelles URLs publiques `/specs/*`, `/guides/*` et `/adr/*`.

### Fixed
- Les URLs publiques des ADRs reviennent en `/adr/*` au lieu de `/specs/adr/*`.
- La navigation du site sépare désormais clairement les pages canoniques des guides de contribution et de contexte.

---

## v0.5.0 — 2026-04-19

Pivot vers méthodologie hybride (SDD + Prototype + TDD). Allègement cross-docs et convention cross-client pour les skills.

### Added
- Méthodologie triple-hybride formalisée dans `AGENTS.md`, `README.md`, `docs/guides/contributing.md`, `docs/guides/rationale.md`.
- `docs/specs/methodology.md` comme **source canonique** de la méthode du projet. Commit [`917e2b4`](https://github.com/ForumHFR/redface2/commit/917e2b4).
- `docs/specs/scope.md` comme **source canonique** du scope produit et des use cases. Commit [`917e2b4`](https://github.com/ForumHFR/redface2/commit/917e2b4).
- `docs/adr/` bootstrappé avec `ADR-000`, `001`, `002`, `008`, `009`. Commit [`917e2b4`](https://github.com/ForumHFR/redface2/commit/917e2b4).
- **Detekt** + **Android Lint** (a11y critique) ajoutés Phase 0 dans `stack.md` et `contributing.md`.
- Règle "Vérification API actuelle" avec mot-clé "stable release" (Context7 / Docfork) dans `AGENTS.md`.
- Smileys HFR : distinction explicite builtin (`:code:`) vs perso (`[:name]`) dans `AGENTS.md`.
- **RedMark** comme candidat de nom ([#21](https://github.com/ForumHFR/redface2/issues/21), attribution Dintr-un-lemn) dans `naming.md`.
- `docs/specs/roadmap.md` : dashboard des phases (taille S/M/L/XL) + flowchart mermaid des dépendances internes et externes (MPStorage2, hfr-redflag Worker).

### Changed
- Skills migrés de `.claude/skills/` vers **`.agents/skills/`** (convention cross-client [agentskills.io](https://agentskills.io/specification)). `.claude/skills` devient un symlink vers `../.agents/skills` pour Claude Code.
- Stack versions : patches retirés de `stack.md` et `contributing.md`, pointeur vers futur `gradle/libs.versions.toml` comme source of truth.
- **Konsist gardé Phase 0** (revirement cf. [#22](https://github.com/ForumHFR/redface2/issues/22)) — enforcement structurel multi-LLM.
- `docs/features.md` devient `docs/specs/extensions.md` pour clarifier que cette page couvre les **extensions communautaires**, pas le scope produit global. Commit [`917e2b4`](https://github.com/ForumHFR/redface2/commit/917e2b4).
- `README.md`, `docs/index.md`, `docs/guides/contributing.md`, `docs/guides/rationale.md`, `AGENTS.md`, `docs/specs/stack.md`, `docs/specs/architecture.md`, `docs/specs/models.md`, `docs/specs/mvi.md` et `docs/specs/roadmap.md` recâblés autour des nouvelles sources canoniques. Commit [`917e2b4`](https://github.com/ForumHFR/redface2/commit/917e2b4).
- `mvi.md` : encadré méthodologie hybride en tête, Screen Compose détaillé remplacé par liste des patterns invariants (prototype-first).
- `architecture.md` : sections Protocole HFR et règle Prefetch non-authentifié dédoublonnées — `protocol-hfr.md` reste la source unique.
- Phase 5 Polish détaillée avec sous-items Play Store (Fastlane vs Gradle Play Publisher, beta testing, compte développeur ForumHFR).
- Décisions design [#9](https://github.com/ForumHFR/redface2/issues/9) documentées dans `stack.md` (seed `#A62C2C`, dynamic OFF, Roboto, BBCode hybride).
- **Navigation** : Compose Navigation 2.9 type-safe → **Compose Navigation 3 (1.1.0 stable depuis 08/04/2026)**. Réécriture `docs/specs/navigation.md` alignée sur l'API stable : routes `@Serializable` implémentant `NavKey`, `NavBackStack<NavKey>`, pipeline `rememberSceneState` + `rememberNavigationEventState` + `NavigationBackHandler`, `SinglePaneSceneStrategy`, deep linking via parsing `Uri` manuel, intégration `ListDetailPaneScaffold` M3 Adaptive. Cf. [#23](https://github.com/ForumHFR/redface2/issues/23) + commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).
- **HTTP** : OkHttp 4.12 → **OkHttp 5.3+** (stable depuis 07/2025, Happy Eyeballs, DoH, `callTimeout` via `kotlin.time.Duration`, `mockwebserver3`). Publié comme projet Kotlin Multiplatform ; le report KMP côté Redface ([#2](https://github.com/ForumHFR/redface2/issues/2)) est un choix de scope, pas une incompatibilité. Pas de dette de migration : on démarre neuf. Cf. [#23](https://github.com/ForumHFR/redface2/issues/23) + commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).
- **Stockage credentials** : simplification finale — uniquement **cookies de session HFR chiffrés** (DataStore + Keystore + Cipher AES/GCM). Pas de password stocké, pas de re-login transparent, pas de biométrie. À l'expiration de session, l'utilisateur ré-entre son mot de passe. `docs/specs/architecture.md` et `docs/specs/protocol-hfr.md` mis à jour. Cf. [#23](https://github.com/ForumHFR/redface2/issues/23).
- `docs/specs/extensions.md` : nouvelle sous-section "Chargement d'images lourdes" (preview + tap-to-full, auto-detect thumbs HFR, data saver mode). Cf. [#23](https://github.com/ForumHFR/redface2/issues/23).

### Decided
- **Credentials Option A** : DataStore + Keystore (sans Tink, sans password stocké, sans biométrie) pour simplifier la stack.
- **Navigation 3** retenu pour démarrage neuf (stable depuis 10 jours, Compose-first).
- **OkHttp 5** retenu ; Ktor reporté avec KMP post-v1.
- **Roborazzi** non retenu MVP (re-évaluable Phase 4+).
- Coverage hybride différenciée (100% parser/TDD, guidée par risque ailleurs), pas de gate chiffré.
- Smoke test HFR : mensuel (cron `0 2 1 * *`) pour sélecteurs CSS + catégories/sous-catégories.
- Nombre élevé de modules Gradle conservé ([#4](https://github.com/ForumHFR/redface2/issues/4)).
- **Images lourdes** : preview + tap-to-full par défaut, data saver mode en settings, pas de proxy tiers (privacy).

### Removed
- `drafts/audit-v04.md` et `drafts/deep-audit-prompt-v04.md` (archivés dans tag `archive/drafts-v0.4.0`).
- Gantt avec dates calendaires dans `roadmap.md` (remplacé par dashboard).
- Phase 5 "Migration automatique Redface v1" (hors scope) dans `roadmap.md`.

### Fixed
- Navigation Jekyll réordonnée avec `nav_order` uniques pour les pages publiées après ajout de `scope.md`, `methodology.md` et renommage `extensions.md`. Commit [`917e2b4`](https://github.com/ForumHFR/redface2/commit/917e2b4).
- **Cohérence AGENTS.md** : clause "Couverture 100% sur parser, database, ViewModels" contredisait la section Méthodologie ("pas d'objectif 100%"). Alignée sur la couverture hybride différenciée (100% transformers parser uniquement, guidée par risque ailleurs). Commit [`079ed4e`](https://github.com/ForumHFR/redface2/commit/079ed4e).
- **Cohérence `contributing.md`** : "smoke test CI hebdomadaire" (l.225) contredisait le cron mensuel `0 2 1 * *` défini l.144. Aligné sur mensuel. Commit [`079ed4e`](https://github.com/ForumHFR/redface2/commit/079ed4e).
- **Modèles canoniques** : `UserSettings` (référencé dans `protocol-hfr.md` l.313 et `models.md` l.147) et `EditInfo` (retourné par `HfrParser.parseEditPage` dans `architecture.md` l.214) étaient cités sans définition. Data classes canoniques ajoutées dans `models.md` (postsPerPage, isFirstPost, subject?, poll?, etc.). Commit [`079ed4e`](https://github.com/ForumHFR/redface2/commit/079ed4e).
- **API Compose Navigation 3** : exemples `docs/specs/navigation.md` alignés sur l'API stable 1.1.0 (`NavKey`, `NavBackStack<NavKey>`, `rememberSceneState`, `NavigationBackHandler`, `SinglePaneSceneStrategy`). Les versions antérieures utilisaient `backStack.push()` et `SceneStrategy.SingleTop` qui n'existent pas dans la stable. Commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).
- **OkHttp 5 KMP** : l'affirmation "non-compat KMP" dans `stack.md` était factuellement fausse — OkHttp 5 est publié comme projet KMP. Le report KMP côté Redface est un choix de scope, pas une incompatibilité. Commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).
- **Source déprécation EncryptedSharedPreferences** : `architecture.md` citait `1.1.0-alpha07` (04/2025). Corrigé sur la source officielle `1.1.0-beta01` (04/06/2025) puis deprecated en `1.1.0`. Les raisons StrictMode + keyset corruption deviennent des signaux terrain, pas la formulation officielle Google. Commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).

---

## v0.4.0 — 2026-04-16

Audit profond : 42/53 findings résolus sur 6 batches. Drafts d'audit archivés dans le tag `archive/drafts-v0.4.0`.

### Added
- `docs/specs/protocol-hfr.md` (390 lignes) : endpoints HFR, form fields par endpoint, `hash_check`, `verifrequet`, `numreponse`, `listenumreponse`, `cryptlink`, smileys (2 sources), sessions, détection 403, edge cases, fixtures.
- Material 3 Adaptive (`NavigationSuiteScaffold`, `ListDetailPaneScaffold`, `SupportingPaneScaffold`, `WindowSizeClass`), Edge-to-edge Android 15+, Predictive back (`PredictiveBackHandler`) dans `stack.md` et `navigation.md`.
- Compose Navigation 2.9 type-safe dans `navigation.md` (`@Serializable TopicRoute`, `toRoute()`).
- Enforcement architecture : **Konsist** (vs ArchUnit) avec exemples de règles dans `architecture.md`.
- Screenshot testing : **Roborazzi** (4 variants/écran) dans `contributing.md`.
- 5 skills au format [agentskills.io](https://agentskills.io/specification) (`hfr-post`, `bump-version`, `spec-audit`, `spec-check`, `parse-fixture`) + 2 stubs (`m3-check`, `m3-screen`).
- `SKILLS.md` racine (index humain multi-LLM), `.github/ISSUE_TEMPLATE/` (feature, bug, spec-question).

### Changed
- `AGENTS.md` devient **source of truth** multi-LLM (anciennement `CLAUDE.md`). `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md` deviennent des symlinks.
- Stack versions alignées sur stable 04/2026 : Kotlin 2.3.20, compose-bom 2026.03.01, Hilt 2.56, Room 2.8.4, Coil 3.4.0, Jsoup 1.22.1, +DataStore, +Tink, +Konsist, +Roborazzi.
- `:core:ui` détaille 6 sous-packages (`theme/`, `components/`, `adaptive/`, `semantics/`, `util/`, `extensions/`).
- `PrivateMessage` enrichi avec `messages: List<PMMessage>`, `page`, `totalPages`.

### Fixed
- Topic HFR : `cat=23, post=29332` (Redface v1 d'Ayuget, 2015) → `cat=23, post=35395` (Redface 2, XaTriX 11/04/2026).
- Couverture Android 10+ : 96% (incorrect) → ~88-90%.
- `ImageProvider` enum → deux interfaces séparées `UploadProvider` et `RehostProvider` (violation de typing : `ImageProvider.REHOST` ne supportait pas `upload`).

### Security
- Sécurité credentials : `EncryptedSharedPreferences` (déprécié par Google 04/2025, StrictMode violations + crashs keyset corruption OEMs) remplacé par **DataStore + Google Tink + Android Keystore** (révisé en Option A sans Tink dans cycle #24).

---

## v0.3.1 — 2026-04-15

Audit #1 appliqué (26/26 points) + rationale.md répondant aux 4 questions de gig-gic, Corran Horn, ezzz, Ayuget. Alternative 2 "faire tourner un LLM sur v1" argumentée et écartée.

## v0.3.0 — 2026-04-13

Architecture auditée (issue [#14](https://github.com/ForumHFR/redface2/issues/14)), 26 points relevés et corrigés. 23 modules Gradle : 8 core (model, domain, data, network, parser, database, ui, extension) + 7 features base (forum, topic, editor, messages, auth, search, settings) + 8 extensions (Phase 4).

## v0.2.0 et antérieur

Versions initiales des specs. Voir `git log --oneline` pour l'historique détaillé.
