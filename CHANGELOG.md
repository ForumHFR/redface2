# Changelog

Toutes les évolutions notables des specs Redface 2.

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions sont celles des specs, pas de l'app (pas encore de code).

---

## [Unreleased] — cycle #24 simplification post-v0.4.0

Pivot vers méthodologie hybride (SDD + Prototype + TDD). Allègement cross-docs et convention cross-client pour les skills.

### Added
- Méthodologie triple-hybride formalisée dans `AGENTS.md`, `README.md`, `docs/contributing.md`, `docs/rationale.md`.
- **Detekt** + **Android Lint** (a11y critique) ajoutés Phase 0 dans `stack.md` et `contributing.md`.
- Règle "Vérification API actuelle" avec mot-clé "stable release" (Context7 / Docfork) dans `AGENTS.md`.
- Smileys HFR : distinction explicite builtin (`:code:`) vs perso (`[:name]`) dans `AGENTS.md`.
- **RedMark** comme candidat de nom ([#21](https://github.com/ForumHFR/redface2/issues/21), attribution Dintr-un-lemn) dans `naming.md`.
- `docs/roadmap.md` : dashboard des phases (taille S/M/L/XL) + flowchart mermaid des dépendances internes et externes (MPStorage2, hfr-redflag Worker).

### Changed
- Skills migrés de `.claude/skills/` vers **`.agents/skills/`** (convention cross-client [agentskills.io](https://agentskills.io/specification)). `.claude/skills` devient un symlink vers `../.agents/skills` pour Claude Code.
- Stack versions : patches retirés de `stack.md` et `contributing.md`, pointeur vers futur `gradle/libs.versions.toml` comme source of truth.
- **Konsist gardé Phase 0** (revirement cf. [#22](https://github.com/ForumHFR/redface2/issues/22)) — enforcement structurel multi-LLM.
- `mvi.md` : encadré méthodologie hybride en tête, Screen Compose détaillé remplacé par liste des patterns invariants (prototype-first).
- `architecture.md` : sections Protocole HFR et règle Prefetch non-authentifié dédoublonnées — `protocol-hfr.md` reste la source unique.
- Phase 5 Polish détaillée avec sous-items Play Store (Fastlane vs Gradle Play Publisher, beta testing, compte développeur ForumHFR).
- Décisions design [#9](https://github.com/ForumHFR/redface2/issues/9) documentées dans `stack.md` (seed `#A62C2C`, dynamic OFF, Roboto, BBCode hybride).

### Decided
- **Credentials Option A** : DataStore + Keystore (sans Tink) pour simplifier la stack.
- **Roborazzi** non retenu MVP (re-évaluable Phase 4+).
- Coverage hybride différenciée (100% parser/TDD, guidée par risque ailleurs), pas de gate chiffré.
- Smoke test HFR : mensuel (cron `0 2 1 * *`) pour sélecteurs CSS + catégories/sous-catégories.
- Nombre élevé de modules Gradle conservé ([#4](https://github.com/ForumHFR/redface2/issues/4)).

### Removed
- `drafts/audit-v04.md` et `drafts/deep-audit-prompt-v04.md` (archivés dans tag `archive/drafts-v0.4.0`).
- Gantt avec dates calendaires dans `roadmap.md` (remplacé par dashboard).
- Phase 5 "Migration automatique Redface v1" (hors scope) dans `roadmap.md`.

---

## v0.4.0 — 2026-04-16

Audit profond : 42/53 findings résolus sur 6 batches. Drafts d'audit archivés dans le tag `archive/drafts-v0.4.0`.

### Added
- `docs/protocol-hfr.md` (390 lignes) : endpoints HFR, form fields par endpoint, `hash_check`, `verifrequet`, `numreponse`, `listenumreponse`, `cryptlink`, smileys (2 sources), sessions, détection 403, edge cases, fixtures.
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
