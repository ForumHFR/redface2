# Audit profond Redface 2 — cycle v0.4.0

> Rapport d'audit et plan d'exécution, produit par **Claude Opus 4.7 (1M context) effort max / ultrathink** en avril 2026.
> **Action par Claude Opus 4.7 (demandée par @XaaT)**.
>
> Scope imposé par l'utilisateur : specs et infrastructure LLM uniquement, **pas de code applicatif Kotlin**.
> Périmètre : 1–2 h de focus, budget quota ~5 h. Pas de coordination communauté avant push (utilisateur refait le repo lui-même).

---

## Sommaire exécutif

| Catégorie | Compte | Estimation effort |
|---|---|---|
| Critique (bloque Phase 0) | **9** | 3–4 h |
| Important (ambiguïté LLM) | **19** | 4–5 h |
| Moyen (polish) | **11** | 1–2 h |
| Opportunités LLM-first | **14** | 3–4 h |
| **Total** | **53** | 11–15 h |

**Batch pilotable en 1–2 h** : Batchs 1–4 ci-dessous (critiques + infrastructure multi-LLM + correctifs skills + edge cases HFR documentés). Batchs 5–8 requièrent arbitrages supplémentaires ou un cycle dédié.

**Top 3 risques si non corrigés immédiatement** :
1. `EncryptedSharedPreferences` cité dans `architecture.md` est déprécié depuis 04/2025 — Phase 0 démarrerait avec du code qui émet des warnings Google dès le premier commit.
2. Post ID HFR faux dans mémoire et `rationale.md` (29332 = topic Redface v1 d'Ayuget 2015, correct = 35395 topic XaTriX 2026) — chaque prochaine référence communauté pointerait au mauvais endroit.
3. `.claude/commands/` format legacy + CLAUDE.md-only sans AGENTS.md racine — aucun autre LLM (Cursor, Codex, Copilot agent, Gemini CLI) ne peut contribuer efficacement, ce qui contredit l'objectif de maximiser les contributeurs.

---

## Méthodologie

Phase A (ingestion) parallélisée :
- Lecture intégrale de `docs/*.md` (11 pages), `.claude/commands/*.md` (5 skills), `drafts/*.md` (3 drafts dont material3-ui-ux et deep-audit-prompt-v04), `CLAUDE.md`, `AGENTS.md` symlink, `README.md`, `docs/_config.yml`, `docs/_includes/head_custom.html`.
- 5 agents lancés en parallèle :
  - **Issues GitHub** (21 ouvertes + commentaires, via `gh issue view`) → rapport thématique, inconsistances, fermables.
  - **Code Redface v1** (/work/xaat/ForumHFR/Redface/) → edge cases parser, session, drapeaux, BBCode, 17 fixtures vs 13 tests.
  - **État de l'art multi-LLM 2026** → agents.md (Linux Foundation), agentskills.io (Anthropic 18/12/2025), tableau comparatif 7 éditeurs, recommandation canonique.
  - **Validation stack Android 04/2026** → versions stables, API deprecations (EncryptedSharedPreferences !), Material 3 Adaptive, edge-to-edge, predictive back.
  - **Résumé draft material3-ui-ux.md** (1651 L) → TOC, 10 couches enforcement, composants M3, incohérences cross-docs.
- 2 lectures hfr-mcp :
  - `hfr_read cat=23 post=29332 p1` → confirme topic = Redface **v1** (Ayuget 03-04-2015).
  - `hfr_read cat=23 post=35395 p1` → confirme topic = Redface **2** (XaTriX 11-04-2026, avec Corran Horn, CAMPEDEL, ezzz, tryptique, Dintr-un lemn).

Ce rapport est autosuffisant — il doit pouvoir guider Phase D sans relire ni les docs ni les sorties d'agents.

---

## 1. Findings critiques (bloque Phase 0)

### C1 — `EncryptedSharedPreferences` déprécié depuis avril 2025

- **Fichier** : `docs/architecture.md:333-349`
- **Problème** : le bloc "Stockage sécurisé des credentials" prescrit `EncryptedSharedPreferences` + `MasterKey` (AndroidX Security). Google a marqué le package **déprécié** dans `security-crypto 1.1.0-alpha07` (avril 2025). Raisons officielles : StrictMode violations (reads synchrones sur main thread), crashs "keyset corruption" sur certains OEMs. Une future version d'Android peut le retirer complètement.
- **Impact si non corrigé** : le premier commit de code appliquerait la spec et déclencherait immédiatement des warnings Google + risque d'incident production.
- **Fix proposé** : remplacer par **DataStore (typed preferences ou proto) + Google Tink (chiffrement) + Android Keystore (clés)**. Laisser la biométrie en extension post-v1 comme aujourd'hui.
- **Effort** : moyen (réécrire la sous-section + référencer `androidx.datastore` + `com.google.crypto.tink` + ajouter `Tink` au version catalog).
- **Dépendances** : M1 (stack.md doit aussi mentionner DataStore + Tink).

### C2 — Post ID du topic HFR de Redface 2 incorrect dans rationale.md

- **Fichier** : `docs/rationale.md:16`
- **Problème** : le lien gig-gic pointe vers `forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=29332`. Vérification par `hfr_read` : **29332 est le topic original de Redface v1 par Ayuget (avril 2015)**. Le topic Redface 2 créé par XaTriX le 11 avril 2026 a l'ID **35395**.
- **Impact si non corrigé** : toute référence future pointe sur le topic v1, contresens historique.
- **Fix proposé** :
  - Corriger `rationale.md:16` : remplacer `post=29332` par `post=35395`.
  - Vérifier toutes les autres occurrences de `post=29332` dans le repo (aucune trouvée dans docs/ hors rationale).
  - Le skill `.claude/commands/hfr-post.md:3` utilise déjà le bon ID `post=35395` → OK.
- **Effort** : petit.

### C3 — Stack obsolète de deux versions majeures sur plusieurs briques

- **Fichier** : `docs/contributing.md:60-68`
- **Problème** : le bloc `libs.versions.toml` d'exemple cite des versions périmées en avril 2026 :
  - `kotlin = "2.1.0"` → stable actuelle **2.3.20** (mars 2026), minimum raisonnable 2.2.20.
  - `compose-bom = "2026.04.00"` → **n'existe pas encore** (dernier publié : 2026.03.01).
  - `hilt = "2.52"` → **2.56**.
  - `room = "2.7.0"` → **2.8.4**.
  - `coil = "3.0.0"` → **3.4.0**.
  - `jsoup = "1.18.0"` → **1.22.1**.
  - `okhttp = "4.12.0"` → à arbitrer (4.12 maintenu security-only, **5.3.2** stable depuis juillet 2025).
- **Impact si non corrigé** : le LLM en Phase 0 écrirait un `libs.versions.toml` périmé ou bloqué sur compose-bom inexistant.
- **Fix proposé** : aligner sur versions stables 04/2026 (voir recommandations détaillées §R1 ci-dessous). Expliciter dans `stack.md` le fait que les versions de `contributing.md` sont le **source of truth** pour le version catalog.
- **Effort** : petit.

### C4 — `hash_check` non documenté dans les specs alors qu'il est critique

- **Fichier** : `docs/architecture.md` (manque), `docs/models.md` (manque)
- **Problème** : dans le code v1 (HashcheckExtractor.java, HFRMessageSender.java lignes 89, 150, 200, 286, 332), `hash_check` est injecté dans **chaque** POST vers HFR (reply, edit, delete, flag, MP). Pattern `<input type="hidden" name="hash_check" value="(.+?)" />`. Les specs v2 décrivent `HfrClient.postReply()`, `editPost()`, etc. sans mentionner cette contrainte.
- **Impact si non corrigé** : en Phase 1/2, le LLM écrirait des POST sans hash_check → échec silencieux côté HFR, débogage douloureux.
- **Fix proposé** :
  - Ajouter dans `architecture.md` une sous-section "Protocole HFR : anti-CSRF via `hash_check`" avec : où il est extrait (edit page, profile page), où il doit être ré-injecté (tous les POST), comportement si absent (fail fast visible, pas silencieux).
  - Alternativement, créer la page `docs/protocol-hfr.md` demandée par l'issue #18 et y regrouper hash_check, verifrequet, numreponse scope.
- **Effort** : moyen.

### C5 — `verifrequet=1100` constante anti-bot non documentée

- **Fichier** : `docs/architecture.md` (manque)
- **Problème** : dans le code v1, `verifrequet=1100` est passé dans tous les POST (HFRMessageSender.java:92, 154, 202). C'est une constante anti-bot spécifique HFR. Non documentée dans les specs v2 → le LLM l'oublierait.
- **Fix proposé** : documenter dans `docs/protocol-hfr.md` (ou dans `architecture.md` si on n'ouvre pas la page dédiée). Préciser que c'est une string "1100", pas un entier dynamique.
- **Effort** : petit (une ligne dans une table).

### C6 — `numreponse` unique par catégorie documenté dans CLAUDE.md mais pas dans models.md

- **Fichier** : `docs/models.md:127`, `CLAUDE.md:107`
- **Problème** : `CLAUDE.md:107` note "`numreponse` est unique par **catégorie**, pas globalement sur le forum". Mais dans `models.md`, `data class Post { val numreponse: Int ... }` n'a aucun commentaire sur la contrainte. Le LLM qui écrit le parser ou la base Room peut penser que `numreponse` est une clé primaire globale → corruption.
- **Fix proposé** :
  - Ajouter un commentaire Kotlin dans `models.md:127` : `val numreponse: Int, // unique par (cat), PAS globalement — clé composite (cat, numreponse)`.
  - Room Entity : documenter `@PrimaryKey(autoGenerate = false)` avec composite index (`cat` + `numreponse`).
- **Effort** : petit.

### C7 — Prefetch authentifié documenté sans alerte

- **Fichier** : `docs/architecture.md:289-302`
- **Problème** : la sous-section "Prefetch intelligent" décrit le prefetch de la page suivante d'un topic et des drapeaux, sans préciser que les requêtes doivent être **non-authentifiées** (sans cookies HFR) pour éviter de marquer les drapeaux comme lus. Règle projet confirmée dans `feedback_prefetch_no_auth.md` memory et par Corran Horn sur le topic 35395 ("en utilisant un cookie d'un compte anonyme pour pas péter les drapeaux"). v1 faisait l'erreur.
- **Fix proposé** : ajouter un paragraphe "Contrainte d'authentification" indiquant que le prefetch utilise une couche réseau séparée sans CookieJar HFR (ou un CookieJar vide). Ajouter un test Konsist/Konsistence pour l'enforcer à terme.
- **Effort** : petit (paragraphe + TODO).

### C8 — Parser `forum1f.php` (drapeaux) non spécifié alors que c'est l'écran d'accueil

- **Fichier** : `docs/architecture.md:214` (signature `parseFlags`), `docs/contributing.md:168` (fixture `flags_page.html` listée)
- **Problème** : la fixture `flags_page.html` est listée dans `contributing.md` comme "nouvelle v2" (car v1 ne la couvrait pas). Aucun sélecteur CSS, aucun exemple de structure DOM n'est décrit. Le code v1 NE parse PAS `/forum1f.php` (confirmé par l'agent : aucun `FlagPageParser` dans Redface v1). → le LLM partirait de zéro, risque d'erreurs.
- **Fix proposé** : capturer une fixture `forum1f.php` réelle via `hfr_read` (écran drapeaux, logué), l'analyser via skill `/parse-fixture`, documenter les sélecteurs dans `docs/protocol-hfr.md` (ou annexe dédiée). Ajouter le mapping DOM → `FlaggedTopic` dans `models.md`.
- **Effort** : moyen (nécessite capture + analyse). Peut être reporté si budget serré, mais c'est l'écran d'accueil — bloque Phase 1.

### C9 — Infrastructure multi-LLM absente (CLAUDE.md-only)

- **Fichier** : racine du repo
- **Problème** : le repo ne contient que `CLAUDE.md` + symlink inverse `AGENTS.md → CLAUDE.md`. Les skills sont dans `.claude/commands/*.md` (format legacy Claude Code). En 04/2026, `AGENTS.md` est le standard de facto soutenu par OpenAI Codex, Cursor, Copilot coding agent, Gemini CLI, Windsurf, aider, Zed, Warp, Junie, Devin, Factory, Amp, Continue.dev. `agentskills.io` (spec Anthropic 18/12/2025) est supporté par 30+ outils. Le repo actuel est donc Claude-only → exclut les autres LLMs que l'auteur veut accueillir.
- **Impact si non corrigé** : aucun contributeur qui utilise un autre éditeur que Claude Code ne peut tirer parti des instructions + skills. L'objectif communautaire du projet tombe.
- **Fix proposé** (§R9 détaillé ci-dessous) :
  - Flipper le symlink : `AGENTS.md` devient source of truth ; `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md` deviennent symlinks vers `AGENTS.md`.
  - `.cursor/rules/project.mdc` avec `alwaysApply: true` + body `@AGENTS.md`.
  - Migrer `.claude/commands/*.md` → `.claude/skills/<name>/SKILL.md` au format agentskills.io (frontmatter `name`, `description`, `disable-model-invocation`, `argument-hint`).
  - Créer `SKILLS.md` racine = index humain des skills + mode d'emploi par éditeur.
- **Effort** : moyen (1–2 h).

---

## 2. Findings importants (ambiguïté qui force le LLM à deviner)

### I1 — Deep linking exemple utilise l'ancienne API Compose Navigation

- **Fichier** : `docs/navigation.md:188-203`
- **Problème** : l'exemple utilise `composable(route = "topic/{cat}/{post}/{page}", deepLinks = ...)` — API string-based antérieure à Compose Navigation 2.8. `stack.md:23` annonce pourtant "type-safe (v2.8+)". Incohérence.
- **Fix** : remplacer par l'API typée avec `@Serializable data class TopicRoute(val cat: Int, val post: Int, val page: Int = 1)` + `composable<TopicRoute> { entry -> val route = entry.toRoute<TopicRoute>() ... }`.
- **Effort** : petit.

### I2 — `stack.md` ne mentionne pas Material 3 Adaptive

- **Fichier** : `docs/stack.md`
- **Problème** : `NavigationSuiteScaffold`, `ListDetailPaneScaffold`, `WindowSizeClass` sont cœur du draft `material3-ui-ux.md` mais absents de `stack.md`. `androidx.compose.material3.adaptive:adaptive-navigation-suite` 1.2.0 stable depuis 10/2025.
- **Fix** : ajouter ligne au tableau "Vue d'ensemble" + sous-section dédiée mentionnant les 3 canonical layouts (list-detail, supporting pane, feed).
- **Effort** : petit.

### I3 — Edge-to-edge Android 15+ non documenté

- **Fichier** : `docs/stack.md`, `docs/architecture.md`
- **Problème** : `enableEdgeToEdge()` (`androidx.activity 1.8+`) est la norme pour targetSdk ≥ 35. Redface 2 cible Android moderne → doit en parler.
- **Fix** : section dans `stack.md` + paragraphe dans `architecture.md` côté `MainActivity`.
- **Effort** : petit.

### I4 — Predictive back non documenté

- **Fichier** : `docs/stack.md`, `docs/navigation.md`
- **Problème** : `PredictiveBackHandler` Compose + manifest `android:enableOnBackInvokedCallback="true"` sont standard pour Android 14+.
- **Fix** : mention dans `stack.md` + référence dans la section Back Stack de `navigation.md:237`.
- **Effort** : petit.

### I5 — Écart fixtures 17 annoncées vs 13 testées en v1

- **Fichier** : `docs/contributing.md:150-166`
- **Problème** : le tableau "Reprises de Redface v1 (17 fixtures)" liste 13 entrées. L'agent code v1 a confirmé qu'il y a 17 fichiers `.html` dans `app/src/test/resources/` mais seuls 13 sont testés (`hfr_meta_page.html`, `hfr_profile_images_handling.html`, `hfr_rehost_response_page.html` + 1 non trouvée).
- **Fix** : recenser les 17 fichiers exacts, mettre à jour le tableau, marquer les fixtures non testées avec "v1 capturée, test v2 à écrire".
- **Effort** : petit.

### I6 — Roborazzi (screenshot testing) absent de contributing.md

- **Fichier** : `docs/contributing.md:117-130`
- **Problème** : la stack de tests liste JUnit 4 + MockK + Robolectric + Turbine + Compose Testing. Le draft `material3-ui-ux.md` (couche 6 enforcement) ajoute Roborazzi pour les screenshots tests M3. Incohérence.
- **Fix** : ajouter Roborazzi à la liste et préciser son usage (4 variants par écran : compact light/dark, medium light, fontScale 2.0).
- **Effort** : petit.

### I7 — Issue #20 Konsist vs ArchUnit non tranchée

- **Fichier** : `docs/architecture.md`, `docs/rationale.md:110`
- **Problème** : `rationale.md:110` mentionne "règles d'architecture en tests (Konsist/ArchUnit)". Issue #20 non résolue. `contributing.md` ne mentionne aucun des deux. Le draft M3 fixe Konsist implicitement (section 14 couche 3).
- **Fix** : **décision** — Konsist (Kotlin-first, voit les sealed/data/Kotlin specifiques, supporté KMP). ArchUnit ne lit que le bytecode et perd la finesse Kotlin. Documenter le choix dans `architecture.md` + ajouter sous-section "Enforcement architecture".
- **Effort** : moyen (décision + doc).

### I8 — `docs/protocol-hfr.md` absent (issue #18)

- **Fichier** : à créer
- **Problème** : issue #18 demande la documentation du protocole HFR (endpoints, form fields, formats, session). Centralisation naturelle des findings C4 + C5 + C6 + constantes v1 + mapping URL↔écran.
- **Fix** : créer la page avec :
  - Endpoints par fonctionnalité (forum1.php, forum1f.php, forum2.php, bddpost.php, bdd.php, login_validation.php, message.php, profil.php, editprofil.php, modo.php)
  - Form fields requis par endpoint (hash_check, verifrequet, cat, post, numreponse, etc.)
  - Règle numreponse scope catégorie
  - Règle prefetch non-auth
  - Gestion 403 / session expirée
  - Nav_order à insérer après `contributing` (probablement 10.5 ou nav_order 11, en poussant `rationale.md` à 12).
- **Effort** : gros (1 h+).

### I9 — Skills `/m3-check` et `/m3-screen` promis mais inexistants

- **Fichier** : `drafts/material3-ui-ux.md` section 14 couches 4 et 5, `.claude/commands/` (absents)
- **Problème** : le draft M3 s'appuie sur ces deux skills pour l'enforcement LLM. Non créés → impossible de les invoquer → Phase 0 du draft M3 non exécutable.
- **Fix** : créer `.claude/skills/m3-check/SKILL.md` et `.claude/skills/m3-screen/SKILL.md` au format agentskills (dans le même batch que la migration C9). Contenu minimal par skill, à enrichir en phase 0 du draft M3.
- **Effort** : moyen.

### I10 — `:core:ui` structure vague dans architecture.md vs détaillée dans draft M3

- **Fichier** : `docs/architecture.md:122`, `drafts/material3-ui-ux.md` section 13
- **Problème** : `architecture.md` dit juste "`:core:ui` : Thème Material 3, composants partagés, `PostRenderer`". Le draft M3 détaille 6 sous-répertoires (`theme/`, `components/`, `adaptive/`, `semantics/`, `util/`, + extensions ?). Incohérence.
- **Fix** : aligner `architecture.md` avec le draft pour la structure interne de `:core:ui` (au minimum : lister les sous-packages attendus).
- **Effort** : petit.

### I11 — `ImageProvider.REHOST` violation typing

- **Fichier** : `docs/models.md:303-309`, `docs/features.md:140-150`
- **Problème** : l'enum `ImageProvider` a une valeur `REHOST` ("rehost par préfixe URL uniquement, plus d'upload manuel"). Mais l'API attendue (upload, delete, rehost) suppose que `upload` et `delete` fonctionnent pour tout provider. Contradiction relevée aussi dans audit #17 finding #12.
- **Fix** : séparer deux interfaces : `UploadProvider` (upload + delete) et `RehostProvider` (rehost URL). Documenter dans `models.md` + `features.md` + `architecture.md`.
- **Effort** : petit.

### I12 — `PrivateMessage.content` manquant dans models.md

- **Fichier** : `docs/models.md:202-211`
- **Problème** : la conversation MP nécessite le texte des messages. `PrivateMessage` n'a que `subject, participants, lastAuthor, lastDate, isRead, isMultiMP`. Absent aussi `messages: List<PMMessage>` pour afficher la conversation.
- **Fix** : ajouter `data class PMMessage(val author, val date, val content, val numreponse)` + relation `PrivateMessage.messages: List<PMMessage>` chargée à l'ouverture.
- **Effort** : petit.

### I13 — `Post.isOwnPost` et `Post.isEditable` : parse-derivé ou client-side ?

- **Fichier** : `docs/models.md:127-137`
- **Problème** : les deux booléens sont listés dans `Post` sans source précisée. Sont-ils calculés par le parser (→ dépendance forte entre parser et session utilisateur), ou par le client (→ pure fonction `currentUser + post`) ?
- **Fix** : clarifier. Recommandation : `isOwnPost` et `isEditable` calculés **côté client** (pas dans le parser). Ajouter commentaire dans `models.md` + un helper dans `:core:domain` : `fun Post.isEditableBy(user: CurrentUser): Boolean`.
- **Effort** : petit.

### I14 — `Post.postIndex = (page-1)*40 + position` : le 40 n'est pas constant

- **Fichier** : `docs/models.md:136`
- **Problème** : le commentaire cite `40 posts/page`. Or HFR permet à l'utilisateur de configurer le nombre de posts par page (`editprofil.php?page=3` → réponses/page). Le LLM codant `postIndex` forcerait 40.
- **Fix** : retirer la constante du commentaire, expliciter que `postsPerPage` est une valeur lue depuis les préférences utilisateur HFR. Ajouter `val postsPerPage: Int` au modèle `UserSettings` ou `TopicPage`.
- **Effort** : petit.

### I15 — `FlagsViewModel.pendingRemovals` hors StateFlow

- **Fichier** : `docs/mvi.md:131`
- **Problème** : `private val pendingRemovals = mutableMapOf<Int, Job>()` est un état mutable hors StateFlow. Rompt le pattern "state centralisé immutable" annoncé dans la section Principe. Accepté techniquement (ce sont des Jobs, pas de l'état UI), mais la contradiction n'est pas expliquée.
- **Fix** : ajouter 2–3 lignes de commentaire avant la déclaration : "Les Jobs de cancellation sont gérés hors StateFlow car ils ne font pas partie de l'état UI observable — seule leur existence est pertinente pour éviter la double-exécution. Équivalent d'un mutex / d'une map de transactions en cours."
- **Effort** : petit.

### I16 — `matchesFilter` et `comparatorFor` helpers non définis

- **Fichier** : `docs/mvi.md:164-165`
- **Problème** : `FlagsViewModel.updateFilteredFlags()` appelle `matchesFilter(it, state.filter)` et `comparatorFor(state.sortMode)` sans jamais les définir. Le LLM pourrait soit les inventer maladroitement, soit manquer leur existence.
- **Fix** : les définir explicitement sous la section ViewModel : `private fun matchesFilter(topic: FlaggedTopic, filter: FlagFilter): Boolean = ...` + `private fun comparatorFor(mode: SortMode): Comparator<FlaggedTopic> = ...`. Ou déclarer explicitement "helpers à implémenter en Phase 1".
- **Effort** : petit.

### I17 — Catalogue fixtures sans pointeur vers la source HFR

- **Fichier** : `docs/contributing.md:150-200`
- **Problème** : chaque fixture est listée avec son rôle, mais pas avec le `numreponse` ou URL d'origine. Quand une fixture doit être mise à jour (HFR change son HTML), impossible de retrouver la page source.
- **Fix** : ajouter une colonne "Source HFR (ou `--` si fabriquée)". Pour les fixtures v2 à créer : obligation de renseigner la source.
- **Effort** : moyen (nécessite de retrouver ou capturer).

### I18 — `listenumreponse` (JS inline) non documenté alors qu'il est une optimisation gratuite

- **Fichier** : `docs/architecture.md` (manque)
- **Problème** : HFR embarque dans chaque page topic un script `var listenumreponse = [...]` avec le tableau des `numreponse` des posts de la page. v1 ne l'utilisait pas (confirmé par agent). En l'extrayant, v2 évite une seconde requête pour lister les posts à actualiser.
- **Fix** : documenter dans `docs/protocol-hfr.md` + le skill `/parse-fixture.md:82-88` couvre déjà l'extraction JS — parfait. Ajouter un exemple dans `docs/architecture.md` ou dans `models.md`.
- **Effort** : petit.

### I19 — Stack.md chiffre "29 couvre 96%+" incorrect

- **Fichier** : `docs/stack.md:214`
- **Problème** : dernières données Google dashboards octobre 2025 = API 29+ cumulé **~89.1%**. Extrapolation avril 2026 ≈ 88–90%. Pas 96%.
- **Fix** : corriger à "≈88–90%" avec lien vers [apilevels.com](https://apilevels.com/) ou [distribution dashboard](https://developer.android.com/about/dashboards).
- **Effort** : trivial.

---

## 3. Findings moyens (polish)

### M1 — stack.md versions à expliciter dans le tableau vue d'ensemble

`docs/stack.md:14-28` — ajouter colonne "Version cible 04/2026" avec valeurs stables (alignées sur C3).

### M2 — hfr-post.md attribution `Claude Opus 4.6`

`.claude/commands/hfr-post.md:26` — remplacer par un placeholder générique ou par la convention `Claude Opus <modèle en cours>`. Idem `bump-version.md:19`.

### M3 — hfr-post.md règle "pas d'accents dans le BBCode" contradictoire

`.claude/commands/hfr-post.md:16` — la règle interdit les accents en BBCode pour "compatibilité". Les autres règles projet imposent des accents complets en français. Clarifier : est-ce que HFR casse les accents dans BBCode, ou bien la règle est fausse ? Les posts vus sur le topic 35395 (page 1 de Corran Horn : "maîtrise", "les périodes d'essai") sont accentués. **La règle est fausse** → retirer.

### M4 — `_config.yml` footer encore v0.3.1

`docs/_config.yml:21` — à bumper en v0.4.0 avec changelog une fois ce cycle validé.

### M5 — `contributing.md:194` fixture `profile_settings_p4` marquée déprécié

Expliciter : on la capture pour exhaustivité, OK — mais ajouter "aucun test de régression requis sur cette fixture". Ou la retirer complètement du catalogue.

### M6 — `stack.md:113` référence Koin "1.0.0-RC1"

Version potentiellement périmée en 2026. Réviser ou enlever le numéro de version.

### M7 — `README.md` mentionne "Dagger 2"

`README.md:33` dit `Dagger 2` pour v1. En réalité v1 utilise **Dagger Android mode classique** (pas Dagger 2 direct). Aligner avec `rationale.md:54`.

### M8 — `docs/architecture.md:147` dépendances extensions non uniformes

`:feature:redflag` dépend de `:core:extension`, `:core:model`, `:core:network`.
`:feature:imagehost` dépend de `:core:model`, `:core:network`, `:core:ui` (pas `:core:extension`).
`:feature:gifpicker` idem.

Les "extensions" ne sont pas toutes des `PostDecorator`/`TopicToolbarContributor`. Certaines sont des features presque-normales. À clarifier : soit renommer les modules qui ne sont pas extension, soit justifier la divergence.

### M9 — `features.md:416` YAML dynamic themes — cohérence avec M3

La feature "Thèmes YAML personnalisés" impose une représentation de `Color` en YAML. Cohérence à vérifier avec la palette M3 (30 rôles). Question ouverte : on expose les 30 rôles dans le YAML ou on limite à 6-8 ?

### M10 — `navigation.md:65` mermaid node `TABMULTI` affichage non convergent

Le diagramme réfère `TABMULTI --> CONVMULTI` mais la légende texte dit "MultiMPs se comportent comme un topic : pagination, quote, reply". Ajouter explicitement `CONVMULTI --> REPLYMULTI` (déjà présent) et vérifier que la distinction TABMP/TABMULTI correspond bien à deux routes distinctes en Compose Navigation.

### M11 — `naming.md` : topic naming #1 vs proposition #21 non réconciliée

Issue #21 (par Dintr-un-lemn, 15/04) propose un naming hommage. Vu sur topic 35395 : "il faudra rendre hommage au C.réateur" → référence à un créateur HFR. `naming.md` n'intègre pas cette branche. À commenter sur #21 (voir Batch 7).

---

## 4. Opportunités LLM-first

### O1 — SKILLS.md racine = index humain multi-LLM

Créer `SKILLS.md` à la racine qui liste les skills avec chemin relatif vers `.claude/skills/<name>/SKILL.md`, description, mode d'emploi par éditeur (Claude Code : `/skill-name`, Cursor/Codex/Copilot/Gemini : invocation via agent). Non-standard mais utile pour discovery humaine et par LLMs qui ne scannent pas `.claude/skills/` nativement (aider p.ex.).

### O2 — Skill `/new-feature`

Squelette pour créer un nouveau module `:feature:<name>` : copie les fichiers conventions (State, Screen, Content, ViewModel), ajoute au settings.gradle.kts, crée les dépendances sur `:core:domain` + `:core:ui`, enregistre une Route typée dans le NavGraph, génère un `@Preview` + test unitaire minimal.

### O3 — Skill `/new-parser`

Squelette pour ajouter un parser dans `:core:parser` : prend une fixture HTML en argument, lance `/parse-fixture`, génère le `<Name>Parser.kt` + data class domain + test unitaire + ajout à `HfrParser` index.

### O4 — Skill `/spec-diff`

Compare deux branches de specs et produit un diff structuré par fichier, par type de changement (ajout, suppression, modification), par sévérité (breaking / compatible). Utile avant bump de version.

### O5 — Tokens `Dimens`, `Motion`, `Color`, `Typography`, `Shapes` centralisés dans `:core:ui`

Déjà mentionné dans draft M3 section 13. Documenter dans `architecture.md` que **seul** `:core:ui` peut instantier des `ColorScheme`, `Typography`, `Shapes`. Enforcer via Konsist rule.

### O6 — Harnesses de test `BaseParserTest`, `BaseViewModelTest`

Classes abstraites dans `:core:testing` (nouveau module de tests) qui définissent le pattern commun : chargement fixture, assertion sur champs critiques (numreponse, cat, post), helpers `given`/`whenIntent`/`thenState`. Réduit la répétition et force le style aux nouveaux parsers/VM.

### O7 — Exemples `@Preview` inline dans les specs

Chaque écran de `navigation.md` a une section "Exemple visuel" avec un bloc de code `@Preview` Compose. Transforme la spec en documentation executable (en Phase 1+).

### O8 — Smoke test CI hebdomadaire

Déjà mentionné dans `architecture.md:369`. Concrétiser en : GitHub Action cron weekly qui :
1. Fait tourner `hfr_read cat=XX post=YY` sur 3 topics connus.
2. Compare les sélecteurs CSS critiques (listés dans `HfrSelectors.kt`) avec ce qui existe dans le HTML.
3. Ouvre une issue si un sélecteur casse.

### O9 — CHANGELOG.md spec-level

Pas de CHANGELOG pour les specs elles-mêmes. `_config.yml` n'a qu'un footer ponctuel. Créer `CHANGELOG.md` avec entrée par version (v0.1.0 → v0.2.0 → v0.3.0 → v0.3.1 → v0.4.0). Mettre à jour par le skill `/bump-version`.

### O10 — `.github/` absent

Le repo n'a pas de `.github/` (`.github/workflows`, `.github/ISSUE_TEMPLATE`, `.github/copilot-instructions.md`). Créer le squelette maintenant, même sans CI active. Le template d'issue (`feature` / `bug` / `spec-question`) guide les contributions.

### O11 — Documentation `@claude` / `@codex` / `@copilot` dans issues

`CLAUDE.md` section "Attribution IA" impose `> Action par Claude Opus X.Y (demandée par @XaaT)`. Étendre à d'autres LLMs : prévoir dans `AGENTS.md` une liste des mentions attendues par provider + comportement attendu (demande, proposition, exécution).

### O12 — Kotlin `kotlinx.serialization` pour schémas MPStorage

Le draft MPStorage (features.md:270-290 + models.md:227-259) ne verrouille pas le format JSON. Verrouiller via un schéma `@Serializable` dans `:core:model`, partagé avec les userscripts (schéma JSON exporté).

### O13 — Fiches de personas LLM

Dans `AGENTS.md`, ajouter 3 personas pour contextualiser les prompts futurs :
- **Contributor** : dev Android expérimenté qui ouvre une PR.
- **Reviewer** : mainteneur qui valide la PR.
- **First-timer** : étudiant qui ouvre sa première issue.
Chaque persona décrit ses outils, son niveau d'autonomie attendue, et les tâches qui lui conviennent.

### O14 — Index de décisions ADR

`docs/rationale.md` commence à faire ADR (Architecture Decision Record). Formaliser un dossier `docs/adr/` avec un fichier par décision majeure (`0001-mvi-vs-mvvm.md`, `0002-hilt-vs-koin.md`, `0003-kmp-reported-to-v2.md`, ...). Modèle léger : Contexte / Décision / Conséquences. Facilite la review communautaire et les retours en arrière.

---

## 5. Plan d'exécution — 8 batchs

Chaque batch est **committable indépendamment**. Validation humaine par l'utilisateur **avant push sur main** à chaque batch.

### Batch 1 — Corrections critiques specs (~45 min)

**But** : rendre les specs utilisables en Phase 0 sans pièges.
**Findings couverts** : C1, C2, C3, C4, C5, C6, C7, I19.
**Fichiers touchés** :
- `docs/architecture.md` (ESP → DataStore+Tink, prefetch non-auth, hash_check doc, verifrequet, listenumreponse, numreponse scope)
- `docs/rationale.md` (post ID 29332 → 35395)
- `docs/contributing.md` (versions libs.versions.toml alignées 04/2026)
- `docs/stack.md` (tableau versions + chiffre 88-90%)
- `docs/models.md` (commentaire `numreponse` scope)

**Commits prévus** :
- `fix(specs): correct HFR topic ID (29332 Redface v1 → 35395 Redface 2)`
- `fix(specs): replace deprecated EncryptedSharedPreferences by DataStore + Tink + Keystore`
- `docs(architecture): document hash_check, verifrequet, listenumreponse, numreponse scope and prefetch no-auth rule`
- `docs(stack): align versions with stable 04/2026 (Kotlin 2.3.20, compose-bom 2026.03.01, Hilt 2.56, Room 2.8.4, Coil 3.4.0, Jsoup 1.22.1)`
- `docs(stack): fix minSdk 29 coverage claim (88–90%, not 96%)`

**Validation** : humaine avant push.

### Batch 2 — Infrastructure multi-LLM (~60 min)

**But** : rendre le repo compatible Claude Code + Cursor + Codex + Copilot agent + Gemini CLI + aider.
**Findings couverts** : C9, I9, O1, O10, O11.
**Fichiers touchés** :
- Flip symlink `AGENTS.md` ↔ `CLAUDE.md` (AGENTS.md devient source of truth).
- Création `GEMINI.md` symlink → `AGENTS.md`.
- Création `.cursor/rules/project.mdc` avec `{alwaysApply:true}` + body `@AGENTS.md`.
- Création `.github/` avec `copilot-instructions.md` symlink → `../AGENTS.md` + `ISSUE_TEMPLATE/` (feature, bug, spec-question).
- Migration `.claude/commands/*.md` → `.claude/skills/<name>/SKILL.md` (5 skills + 2 nouveaux `/m3-check`, `/m3-screen`).
- Création `SKILLS.md` racine (index humain).
- Ajout dans AGENTS.md d'une section "Pour les LLMs non-Claude".

**Commits prévus** :
- `refactor(agents): migrate to AGENTS.md as source of truth with symlinks for CLAUDE/GEMINI/Copilot`
- `feat(.cursor): add project.mdc importing AGENTS.md for Cursor compatibility`
- `refactor(skills): migrate .claude/commands/ to .claude/skills/ in agentskills.io format`
- `feat(skills): add stub skills m3-check and m3-screen for Phase 0 draft M3 enforcement`
- `docs: add SKILLS.md root index for multi-LLM skills discovery`
- `chore(.github): add issue templates and Copilot instructions symlink`

**Validation** : humaine avant push. Demande de confirmation sur les symlinks (Windows contributors) — si refus, on remplace par copies + script de sync.

### Batch 3 — Correctifs skills + versioning LLM (~20 min)

**But** : skills cohérents, attribution modèle à jour, règle BBCode juste.
**Findings couverts** : M2, M3, I18, O11.
**Fichiers touchés** :
- `.claude/skills/hfr-post/SKILL.md` : attribution → `Claude Opus 4.7` (ou placeholder `${LLM_MODEL}`), retrait règle "pas d'accents".
- `.claude/skills/bump-version/SKILL.md` : Co-Authored-By → 4.7.
- Tous les skills : alignement post ID 35395 (déjà ok dans hfr-post).

**Commits prévus** :
- `fix(skills): update Claude model attribution (4.6 → 4.7) and remove false "no accents in BBCode" rule`

**Validation** : humaine avant push.

### Batch 4 — Edge cases HFR documentés (~60 min)

**But** : capitaliser le savoir-faire v1 non publié et le cataloguer.
**Findings couverts** : C4 (approfondi), C5, C8, I5, I8, I17, I18, O3.
**Fichiers touchés / créés** :
- `docs/protocol-hfr.md` (nouvelle page, nav_order entre `contributing` et `rationale`) : endpoints, form fields, hash_check, verifrequet, numreponse scope, listenumreponse, prefetch non-auth, 403/session, `cryptlink` handling.
- `docs/contributing.md` (catalogue fixtures avec colonne "source HFR").
- `_config.yml` (adjust nav si besoin).

**Commits prévus** :
- `docs(protocol): add protocol-hfr.md documenting HFR endpoints, form fields, and anti-CSRF/anti-bot constants`
- `docs(contributing): add "source HFR" column to fixtures catalog`

**Validation** : humaine avant push.

### Batch 5 — Stack moderne 2026 (~40 min)

**But** : mettre les specs au niveau de ce qui se fait en 04/2026.
**Findings couverts** : I1, I2, I3, I4, I6, I7, M1, M6.
**Fichiers touchés** :
- `docs/stack.md` : ajout Material 3 Adaptive + edge-to-edge + predictive back + Konsist (choix définitif) + Roborazzi + version table.
- `docs/navigation.md` : exemple type-safe route.
- `docs/architecture.md` : mention `:core:ui` sous-structure + Konsist.
- `docs/contributing.md` : ajout Roborazzi à stack tests.

**Commits prévus** :
- `docs(stack): add Material 3 Adaptive, edge-to-edge, predictive back, Konsist, Roborazzi`
- `docs(navigation): update deep linking example to Compose Navigation 2.9 type-safe routes`
- `docs(contributing): add Roborazzi to test stack`

**Validation** : humaine avant push.

### Batch 6 — Affinages modèles et MVI (~30 min)

**But** : lever les ambiguïtés qui forceraient le LLM à deviner en Phase 1.
**Findings couverts** : I10, I11, I12, I13, I14, I15, I16, O5, O12.
**Fichiers touchés** :
- `docs/models.md` : `PrivateMessage.messages`, `PMMessage`, `UploadProvider`/`RehostProvider` split, `numreponse` scope commentaire, `postsPerPage` paramétrable, isOwnPost/isEditable clarifiés, schéma `@Serializable` MPStorage.
- `docs/mvi.md` : commentaire `pendingRemovals`, définition `matchesFilter` + `comparatorFor`.
- `docs/features.md` : aligner avec split Upload/Rehost.
- `docs/architecture.md` : `:core:ui` sous-structure explicite + tokens centralisés rule.

**Commits prévus** :
- `docs(models): split ImageProvider into UploadProvider/RehostProvider, add PMMessage, clarify numreponse and postsPerPage`
- `docs(mvi): add helpers matchesFilter/comparatorFor and document pendingRemovals rationale`
- `docs(architecture): detail :core:ui internal structure and tokens centralisation rule`

**Validation** : humaine avant push.

### Batch 7 — Issues cleanup (~30 min)

**But** : mettre à jour GitHub pour refléter l'état post-audit.
**Findings couverts** : commentaires sur issues (pas de fermeture sans validation humaine).
**Actions** :
- #14 (audit#1) : déjà pointé comme fermable — commenter "26/26 appliqués, fermeture proposée" puis **demander confirmation avant close**.
- #2 (stack) : commenter avec pointeurs vers stack.md mis à jour.
- #9 (M3) : commenter pointant vers `drafts/material3-ui-ux.md` + trois questions ouvertes à trancher.
- #17 (audit#2) : commenter avec finding-par-finding (appliqué / reporté / reformulé).
- #18 (protocol HFR) : commenter avec pointeur vers `docs/protocol-hfr.md` créé.
- #20 (Konsist vs ArchUnit) : commenter avec décision Konsist + rationale.
- #21 (naming hommage) : commenter avec état du sujet.

**Pas de fermeture automatique** — liste proposée à l'utilisateur pour review.

### Batch 8 — Bump v0.4.0 + changelog + communication (~20 min)

**But** : publier la nouvelle version des specs.
**Findings couverts** : M4, O9.
**Fichiers touchés** :
- `docs/_config.yml` : footer v0.4.0.
- `CHANGELOG.md` : nouvelle entrée v0.4.0 avec résumé batchs 1–6.
- Éventuel post sur topic HFR 35395 (à valider avec utilisateur, vu la règle "refait le repo toi-même, pas besoin de demander" — mais un post sur HFR reste communication publique).

**Commits prévus** :
- `chore: bump specs to v0.4.0 and update CHANGELOG`

**Validation** : humaine avant push + avant post HFR.

---

## 6. Décisions humaines requises

Points qui nécessitent **arbitrage humain explicite** avant exécution. L'IA propose un défaut mais ne doit pas trancher seule :

| # | Point | Défaut proposé |
|---|---|---|
| D1 | OkHttp 4 vs 5 en Phase 0 | **4.12.0** (moins de risques, migration facile plus tard) |
| D2 | Konsist vs ArchUnit | **Konsist** (Kotlin-first) |
| D3 | minSdk rester 29 ou monter | **Rester 29** (chiffre corrigé à 88–90%, aucune feature 2026 ne requiert plus haut) |
| D4 | Kotlin 2.2.20 vs 2.3.20 | **2.3.20** (dernier, compatible avec AGP 9.1 et Compose BOM 2026.03.01) |
| D5 | Symlinks agents vs copies | **Symlinks** (légers, pas de dérive) ; fallback copies + sync si support Windows requis |
| D6 | Material 3 Expressive (2025) adopter | **Pas en v1** ; laisser en opportunité post-Phase 2 |
| D7 | Dynamic color par défaut | **OFF (opt-in)** — Material You est lent sur vieux devices, risque de décevoir la perception branding HFR |
| D8 | Seed color M3 | **`#A62C2C`** (rouge HFR) proposée dans draft, à valider |
| D9 | Issue #1 naming : le nom définitif | Laisser ouvert jusqu'à sondage communauté ; **ce cycle ne tranche pas** |
| D10 | Fermeture issues (#14, #2) | **Proposer** avec commentaire, ne pas fermer sans OK utilisateur |
| D11 | Publication `docs/material3.md` (promotion draft) | **Pas dans ce cycle** (utilisateur a dit "on verra après"), rester en `drafts/` |

---

## 7. Métriques

**Avant audit v0.4.0** :
- Pages docs/ : 11
- Skills : 5 (format legacy)
- Fichiers racine : 3 (CLAUDE.md, README.md, LICENSE) + AGENTS.md symlink
- Lignes totales docs/ : ~2800
- Issues ouvertes : 21
- Tests planifiés : 0
- `.github/` : absent
- CHANGELOG : absent

**Après Batchs 1–6 appliqués** :
- Pages docs/ : 12 (+1 `protocol-hfr.md`)
- Skills : 7 au format agentskills (+2 : `/m3-check`, `/m3-screen`)
- Fichiers racine : 5 (AGENTS.md source + CLAUDE.md symlink + GEMINI.md symlink + SKILLS.md + README.md + LICENSE)
- Lignes totales docs/ : ~4000
- Issues commentées : 7
- `.github/` : présent (ISSUE_TEMPLATE + copilot-instructions symlink)
- CHANGELOG : présent

**Hors scope** (cycle v0.5.0 éventuel) :
- Promotion `docs/material3.md` (questions ouvertes à trancher)
- F-Droid Redface v1 (#243)
- Décisions D1/D2/D4/D6/D7/D8 verrouillées
- ADR formelles `docs/adr/`
- Tests et code applicatif Phase 0

---

## 8. Critères de succès

À la fin des Batchs 1–6 appliqués et mergés sur main :

1. ✅ Un LLM en Phase 0 peut lire `AGENTS.md` + `docs/` et produire du code conforme sans deviner.
2. ✅ Zéro référence à `EncryptedSharedPreferences`.
3. ✅ Zéro référence à `post=29332` (hors mention explicite "topic v1").
4. ✅ `hash_check`, `verifrequet`, `numreponse` scope, `listenumreponse`, prefetch non-auth documentés.
5. ✅ Konsist ou ArchUnit tranché et documenté.
6. ✅ Material 3 Adaptive + edge-to-edge + predictive back présents dans `stack.md`.
7. ✅ Skills fonctionnels en `agentskills.io` format, portables Cursor/Codex/Copilot/Gemini.
8. ✅ Structure `:core:ui` documentée (au moins les 6 sous-packages de draft M3).
9. ✅ Modèles (models.md) couvrent `PMMessage`, `UploadProvider`/`RehostProvider`, `postsPerPage`.
10. ✅ `docs/protocol-hfr.md` existe.

---

## 9. Pour lancer Phase D (exécution)

Quand l'utilisateur valide ce plan :

1. Lancer **Batch 1** en foreground (corrections critiques) → commit → **stop + demande de confirmation avant push**.
2. Sur GO utilisateur : push Batch 1, puis lancer Batch 2 (infrastructure multi-LLM) → commit → **stop + confirmation avant push** (pivot majeur).
3. Continuer par Batchs 3, 4, 5, 6 avec la même discipline.
4. Batch 7 (issues cleanup) : proposer chaque commentaire à l'utilisateur **avant** de poster.
5. Batch 8 (bump + CHANGELOG + éventuel post HFR) : bump en dernier, pas avant validation des autres batchs, post HFR seulement si utilisateur le demande.

**Règle non négociable** : aucun `git push`, aucune fermeture d'issue, aucun post HFR sans confirmation humaine explicite.

---

*Généré le 16 avril 2026 par Claude Opus 4.7 (1M context) effort max, sur demande de @XaaT. Rapport destiné à guider le cycle v0.4.0 de ForumHFR/redface2.*
