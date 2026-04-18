# Changelog

Toutes les évolutions notables des specs Redface 2.

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions sont celles des specs, pas de l'app (pas encore de code).

---

## v0.4.0 — 2026-04-16

Cycle d'audit profond mené par Claude Opus 4.7 (1M context, effort max). 53 findings, plan 8 batchs, 42 findings résolus. Rapport archivé dans le tag `archive/drafts-v0.4.0`.

### Batch 1 — Corrections critiques specs (`db192e5`)
- **Sécurité credentials** : `EncryptedSharedPreferences` remplacé par **DataStore + Google Tink + Android Keystore** (ESP déprécié par Google en 04/2025, StrictMode violations + crashs keyset corruption sur OEMs).
- **Topic HFR** : `cat=23, post=29332` corrigé en `cat=23, post=35395` dans `rationale.md`. Le 29332 est le topic **Redface v1** d'Ayuget (2015), le 35395 le topic **Redface 2** créé par XaTriX le 11-04-2026.
- **Stack** : versions de `contributing.md` alignées sur stable 04/2026 (Kotlin 2.3.20, compose-bom 2026.03.01, Hilt 2.56, Room 2.8.4, Coil 3.4.0, Jsoup 1.22.1, +DataStore, +Tink, +Konsist, +Roborazzi).
- **Chiffre minSdk** : couverture Android 10+ corrigée à ~88-90% (au lieu de 96% incorrect).
- **Protocole HFR** : nouvelle section dans `architecture.md` pour `hash_check`, `verifrequet=1100`, `numreponse` unique par catégorie, `listenumreponse`, règle prefetch non-authentifié.
- **Modèles** : `Post.numreponse` commenté (scope catégorie), `isEditable`/`isOwnPost` clarifiés (client-side), `postsPerPage` paramétrable (réglage utilisateur HFR).

### Batch 2 — Infra multi-LLM (`380117b`)
- `AGENTS.md` devient **source of truth** (anciennement `CLAUDE.md`), avec section "Contributeurs multi-LLM" (attribution par fournisseur, invocation skills par outil, configuration par éditeur).
- `CLAUDE.md`, `GEMINI.md` : symlinks vers `AGENTS.md`.
- `.github/copilot-instructions.md` : symlink vers `../AGENTS.md`.
- `.cursor/rules/project.mdc` : `alwaysApply: true` + body `@AGENTS.md`.
- `SKILLS.md` racine : index humain multi-LLM des skills + mode d'emploi par éditeur.
- Migration `.claude/commands/` → `.claude/skills/<slug>/SKILL.md` au format [agentskills.io](https://agentskills.io/specification) (spec Anthropic 18/12/2025, portable Claude Code, Cursor, Codex, Copilot coding agent, Gemini CLI, Junie).
- 5 skills migrés avec corrections :
  - `hfr-post` : topic 35395 explicite + warning sur 29332, règle fausse "pas d'accents BBCode" retirée (HFR supporte les accents), attribution `Claude Opus <version>` générique.
  - `bump-version` : Co-Authored-By Opus 4.7, étape CHANGELOG ajoutée, rappel confirmation avant push.
  - `spec-audit`, `spec-check`, `parse-fixture` : bodies intacts, frontmatter agentskills ajouté.
- 2 stubs ajoutés, cités par `drafts/material3-ui-ux.md` :
  - `m3-check` : audit Material 3 sur Compose (19 règles critiques, rapport par sévérité).
  - `m3-screen` : bootstrap écran Compose complet (State/Intent/ViewModel/Screen/Content/Previews).
- `.github/ISSUE_TEMPLATE/` : `feature.md`, `bug.md`, `spec-question.md`.

### Batch 4 — Documentation protocole HFR (`9788245`)
- **`docs/protocol-hfr.md`** (nouveau, nav_order 11) : page complète 390+ lignes couvrant endpoints (`forum1.php`, `forum2.php`, `forum1f.php`, `bddpost.php`, `bdd.php`, `message.php`, `login_validation.php`, `user/addflag.php`, `modo.php`), form fields par endpoint, `hash_check`, `verifrequet`, `numreponse` scope, `listenumreponse`, `cryptlink`, smileys (2 sources), sessions/cookies, détection 403 et recovery, règle prefetch non-auth avec exemple `HfrClient` à 2 `OkHttpClient`, edge cases (posts édités, supprimés, emails obfusqués, pagination, `postsPerPage`), fixtures.
- `docs/rationale.md` : nav_order 11 → 12 (libère 11 pour protocol-hfr).
- `docs/architecture.md` : section "Protocole HFR" raccourcie en résumé + pointeur vers `docs/protocol-hfr.md` (détails complets là-bas).
- `docs/contributing.md` : catalogue fixtures enrichi avec colonne **"Source HFR"** (ex `cat=23 post=35395`, `message.php?numreponse=X`) pour chaque fixture. Écart 17 v1 physiques vs 13 testées clarifié. `profile_settings_p4` marquée "aucun test de régression requis" (page dépréciée par HFR). Règles fixtures renforcées (`.source.txt` par fixture, capture via `hfr-mcp` obligatoire, nettoyage données sensibles via skill `/parse-fixture`).

### Batch 5 — Stack moderne 2026 (`634bdc6`)
- `stack.md` : tableau Vue d'ensemble avec versions 04/2026 + lignes "Design system" (M3 + Adaptive), "Stockage sécurisé" (DataStore+Tink+Keystore), "Enforcement archi" (Konsist), "Screenshot testing" (Roborazzi).
- `stack.md` : nouvelles sections **Material 3 Adaptive** (NavigationSuiteScaffold, ListDetailPaneScaffold, SupportingPaneScaffold, WindowSizeClass breakpoints), **Edge-to-edge Android 15+** (`enableEdgeToEdge()`, insets), **Predictive back** (`PredictiveBackHandler`, manifest `enableOnBackInvokedCallback`).
- `stack.md` : note OkHttp mise à jour (5.3.2 stable depuis 07/2025, arbitrage Phase 0, défaut 4.12).
- `navigation.md` : exemple deep linking en **Compose Navigation 2.9 type-safe** (`@Serializable TopicRoute` + `toRoute()`) remplace l'ancienne API string-based.
- `navigation.md` : nouvelle sous-section "Predictive back" avec exemple `PredictiveBackHandler` pour écrans à draft custom.
- `contributing.md` : ajout **Roborazzi** (screenshot tests 4 variants/écran) et **Konsist** (enforcement architecture) à la stack de tests.
- `architecture.md` : `:core:ui` détaille les **6 sous-packages** (`theme/`, `components/`, `adaptive/`, `semantics/`, `util/`, `extensions/`) + règle "seul module à instancier `ColorScheme`, `Typography`, `Shapes`".
- `architecture.md` : nouvelle section **"Enforcement architecture au build"** avec décision **Konsist** (vs ArchUnit), rationale Kotlin-first, et exemples concrets de règles Konsist (features n'importent pas `:core:data`, tokens M3 centralisés, prefetch `@AnonymousClient`).

### Batch 6 — Affinages modèles et MVI (`ec2c48b`)
- `models.md` : `PrivateMessage` enrichi avec `messages: List<PMMessage>`, `page`, `totalPages`. Nouveau data class `PMMessage` (numreponse, author, date, content BBCode, isEditable client-side).
- `models.md` : `ImageProvider` enum supprimé, remplacé par deux interfaces séparées **`UploadProvider`** (upload + delete) et **`RehostProvider`** (rehost URL) — le pattern `ImageProvider.REHOST` qui ne supporte pas `upload` était une violation de typing. Un provider peut implémenter les deux si HFR expose les deux flux.
- `features.md` : mention du split `UploadProvider`/`RehostProvider` dans la section hébergement d'images.
- `mvi.md` : commentaire expliquant pourquoi `FlagsViewModel.pendingRemovals` vit hors StateFlow (Jobs de cancellation, pas de l'état UI observable, pattern mutex/debounce).
- `mvi.md` : définition explicite des helpers pure **`matchesFilter`** (par `FlagFilter` enum) et **`comparatorFor`** (par `SortMode`) — testables isolément.

### Hors scope (reporté cycle v0.5.0)
- ~~Promotion `drafts/material3-ui-ux.md` → `docs/material3.md`~~ — **résolu différemment** : 4 décisions actées dans [#9](https://github.com/ForumHFR/redface2/issues/9) (seed `#A62C2C`, dynamic OFF, Roboto, BBCode hybride), documentées dans `docs/stack.md` section "Décisions design". Draft préservé comme référence pédagogique, non promu.
- F-Droid Redface v1 (issue [Redface#243](https://github.com/ForumHFR/Redface/issues/243)).
- Création skills `/new-feature`, `/new-parser`, `/spec-diff` (O2, O3, O4 de l'audit).
- Harnesses de test `BaseParserTest`, `BaseViewModelTest` (O6).
- ADR formelles dans `docs/adr/` (O14).
- Commentaires sur issues #14, #2, #9, #17, #18, #20, #21 (Batch 7 de l'audit — à traiter par XaaT).

---

## v0.3.1 — 2026-04-15

Audit #1 appliqué (26/26 points) + rationale.md répondant aux 4 questions de gig-gic, Corran Horn, ezzz, Ayuget. Alternative 2 "faire tourner un LLM sur v1" argumentée et écartée.

## v0.3.0 — 2026-04-13

Architecture auditée (issue [#14](https://github.com/ForumHFR/redface2/issues/14)), 26 points relevés et corrigés. 23 modules Gradle : 8 core (model, domain, data, network, parser, database, ui, extension) + 7 features base (forum, topic, editor, messages, auth, search, settings) + 8 extensions (Phase 4).

## v0.2.0 et antérieur

Versions initiales des specs. Voir `git log --oneline` pour l'historique détaillé.
