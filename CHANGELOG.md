# Changelog

Toutes les évolutions notables des specs Redface 2.

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions sont celles des specs (`docs/_config.yml` `footer_content`). À partir de v0.6.0, elles incluent les changements code/spec couplés : depuis Phase 1, les specs reflètent l'état réel du repo et sont bumpées en lockstep avec les PRs structurantes (cf. `/spec-reality` dans `AGENTS.md`).

---

## [Unreleased]

---

## v0.8.0 — 2026-05-05

Phase 1 close-out. Toutes les cases du Definition-of-Done ([#87](https://github.com/ForumHFR/redface2/issues/87)) sont cochées : parser `PostContent` complet avec `[fixed]` / `[code]` ([#79](https://github.com/ForumHFR/redface2/issues/79), [#123](https://github.com/ForumHFR/redface2/pull/123)), rendu Compose des images et smileys avec Coil ([#109](https://github.com/ForumHFR/redface2/issues/109), [#126](https://github.com/ForumHFR/redface2/pull/126)) et hotfix visuel sur les perso smileys inline ([#129](https://github.com/ForumHFR/redface2/pull/129)). L'umbrella `HfrParser` ([#15](https://github.com/ForumHFR/redface2/issues/15)) est fermée par la même occasion. AAB final `0.1.0-phase1.1` (versionCode 32) prêt pour ouverture du canal Play Console internal testing ([#72](https://github.com/ForumHFR/redface2/issues/72)).

### Phase 1 — Rendu médias post (#109, [#126](https://github.com/ForumHFR/redface2/pull/126), [#129](https://github.com/ForumHFR/redface2/pull/129))

Décision **B+** retenue après arbitrage Codex sur trois stratégies (taille fixe / buckets / mesure async + cache) : **buckets simples + `InlineTextContent` + autoplay GIFs**, sans cache de tailles intrinsèques mesurées async (qui causait un "size pop" visible au premier scroll), sans prefetch agressif (le HTTP cache OkHttp suffit en Phase 1). À ré-évaluer Phase 2/4 si le downscale des perso devient un problème UX en pratique.

Trajectoire en deux PRs : #126 livre la policy initiale (bucket perso 64×64 + `ContentScale.Fit`), #129 corrige un bug visuel reproduit en dogfood sur le post HFR [#74625731](https://forum.hardware.fr/forum2.php?cat=13&post=78667&page=15880#t74625731) où trois perso smileys oversize intrudaient les lignes de texte adjacentes dans une citation.

#### Added
- `:core:ui` — `PostMediaDisplayPolicy` (pure JVM) : 4 buckets fixes (builtin smiley 18×18, perso smiley **40×40**, inline image 240×180, block image `min 160dp / max 480dp`). **`ContentScale.Inside`** (downscale only, **jamais d'upscale**) pour les médias inline — un perso 70×50 est ramené à un ratio préservé (≈ 40×29 à density 1), un perso 15×15 reste à 15×15 centré avec padding visible (pas de pixelisation par 4× upscale). `Modifier.fillMaxSize()` côté `AsyncImage` enfant : l'image suit le placeholder en `sp` même quand `fontScale ≠ 1` (accessibility).
- `:app` — `RedfaceApplication` implémente `SingletonImageLoader.Factory` avec `AnimatedImageDecoder.Factory()` (autoplay GIFs builtin + perso, API 28+, minSdk 29 = pas de fallback legacy).
- `:core:ui` — `PostRenderer.ImageBlock` migré sur `SubcomposeAsyncImage` avec slots loading / error visibles (rehost.diberie.com, super-h.fr offline). `defaultMinSize(160dp)` réserve la hauteur du placeholder pour éviter un layout jump à la résolution de la bitmap (review Codex PR #126).
- Strings FR `post_image_loading`, `post_image_error`, `post_image_error_with_alt` dans `:core:ui`.
- Aliases `coil-core`, `coil-gif`, `coil-network-okhttp` dans `gradle/libs.versions.toml`.
- Fonction pure `insideScaledMediaSize(source, bucket)` miroir de `ContentScale.Inside`, exposée pour tester le corpus HFR réel sans Compose runtime. `coerceAtLeast(1)` sur les sorties pour éviter qu'un ratio extrême (1×100) ne collapse une dimension à 0.

#### Fixed
- **Perso smileys inline oversize** (#129) : trois facteurs cumulés diagnostiqués via arbitrage Codex et corrigés ensemble. (1) Bucket perso 64sp dans un paragraphe `bodyMedium` (`lineHeight = 20.sp` explicite) → placeholder 3.2× la hauteur de ligne, le `LineHeightStyleSpan` figé contraignait l'expansion du `PlaceholderSpan` → débordement vertical ; bucket réduit à 40sp avec invariant `placeholderHeight ≤ 2.5 × bodyMedium.lineHeight` pinned dans les tests. (2) `ContentScale.Fit` upscalait les petits sprites (15×15 → 64×64 = 4× upscale pixelisé) → remplacé par `ContentScale.Inside`. (3) `Modifier.size(64.dp)` figé en dp côté `AsyncImage` divergeait du placeholder en sp sous `fontScale ≠ 1` → remplacé par `Modifier.fillMaxSize()`.

#### Changed
- `docs/specs/protocol-hfr.md` § Smileys : section réécrite. Source de vérité côté lecture = `<img src=…>` (jamais reconstruction d'URL depuis le nom). Builtins ET perso peuvent être animés ET de tailles hétérogènes (corpus échantillonné live : majorité 50×50 à 70×50, fraction <30 px, rares grands formats). Politique de buckets explicite avec `ContentScale.Inside` + `Modifier.fillMaxSize()` + invariant `2.5 × bodyMedium.lineHeight`.
- `docs/specs/roadmap.md` Phase 1 — case "Images + smileys" cochée.

#### Tests
- `PostMediaDisplayPolicyTest` (pure JVM, `:core:ui`) : pin les 4 buckets, invariant typographique `persoSmiley.placeholderHeight ≤ 2.5 × bodyMedium.lineHeight` (lecture dynamique via `RedfaceTypography`), invariant `inlineMediaContentScale === ContentScale.Inside`, garde-fou anti-collapse builtin/perso. Test corpus HFR réel `[(15,15), (39,15), (40,40), (50,50), (70,50), (200,150)]` via `insideScaledMediaSize`. Test ratios extrêmes `1×100` / `100×1` (garde anti-collapse via `coerceAtLeast(1)`).
- `PostRendererInlineTest` (pure JVM, `:core:ui`) : invariant `appendInlineContent` IDs ↔ `collectInlineMedia` keys vérifié pour les 6 conteneurs `PostInline` (Strong/Emphasis/Underline/Strike/Color/Link). Vérif buckets builtin vs perso vs inline image avec asserts `PlaceholderVerticalAlign.Center` sur les trois chemins.

### Phase 1 — Parser blocs monospace (#79, [#123](https://github.com/ForumHFR/redface2/pull/123))

#### Added
- `:core:parser` — `PostBlock.Fixed(text)` et `PostBlock.CodeBlock(text, language?)` produits depuis `<table class="fixed">` / `<table class="code">`, y compris à l'intérieur des citations imbriquées.
- Hint langue depuis `<pre class="<lang>">` (`cpp`, `java`, …) exposé via `CodeBlock.language`. Coloration syntaxique HFR volontairement aplatie en texte brut en Phase 1.
- `:core:ui` — `PostRenderer.MonospaceContainer` : `Card` monospace avec scroll horizontal, `softWrap = false`, ordre modifier `padding(12.dp).horizontalScroll(rememberScrollState())` (padding avant scroll, pas de `fillMaxWidth()` interne — sans quoi le contenu se clamp avant scroll).

#### Fixed
- Le parser ne fait plus de `trim()` global sur les blocs `[fixed]` / `[code]` : seules les lignes vides structurelles en bordure sont retirées (l'indentation interne est préservée — c'est le point de monospace).

### Builds AAB Phase 1

| versionCode | versionName | Commit | Statut | Note |
|---|---|---|---|---|
| 30 | `0.1.0-phase1d.2` | `2a3b2b5` (post #123) puis `80f5ece` (post #126) | livré | AAB intermédiaire après merge `[fixed]` / `[code]`, avant que les médias #109 ne soient sur `main`. |
| 31 | `0.1.0-phase1.0` | `7e79bd3` | **burnt** | preview locale Phase 1 close-out (bucket perso 64×64 + `ContentScale.Fit` issu de #126). Bug visuel reproduit en dogfood sur le post HFR #74625731 (perso smileys oversize, lignes intrudées) → jamais distribuée. |
| 32 | `0.1.0-phase1.1` | à venir | livré | Phase 1 close-out — toutes les cases #87 cochées, hotfix smileys de #129 inclus, prêt pour Play Console internal testing #72. |

---

## v0.7.0 — 2026-05-03

Phase 1D livrée : drapeaux REST, lecture longue topic, cache Room (TTL + persistance + isolation par compte), prefetch anonyme. AAB final `0.1.0-phase1d.1` (versionCode 29, commit `af66363`) couvrant Phase 1D-1 → 1D-4, le hotfix Room v3 (PR [#119](https://github.com/ForumHFR/redface2/pull/119)) et l'audit de gel des `@SerialName` (PR [#120](https://github.com/ForumHFR/redface2/pull/120)).

### Builds AAB Phase 1D

Trajectoire des builds Phase 1D — Play Console n'accepte pas un `versionCode` déjà uploadé, donc chaque tentative consomme un slot ; les builds preview/dogfood listés ci-dessous explicitent ce qui a été émis localement même quand la PR associée n'a pas atteint `main`.

| versionCode | versionName | Commit | Statut | Note |
|---|---|---|---|---|
| 25 | `0.1.0-phase1c.3` (preview) | `3d068e4` | preview dogfood | rebase Phase 1C-B avant pull-to-refresh, jamais sur `main` |
| 26 | `0.1.0-phase1c.2` | `4fb376a` | livré | Phase 1C-B post review fixes (PR [#106](https://github.com/ForumHFR/redface2/pull/106) déjà mergée à `8be7a0d`) |
| 27 | `0.1.0-phase1d.0` (preview) | `d44ccfd` puis `be8c712` | preview dogfood | Phase 1D-1 → 1D-3 en cours de stabilisation, jamais sur `main` |
| 28 | `0.1.0-phase1d.0` | `c6c580c` | **burnt** | PR [#118](https://github.com/ForumHFR/redface2/pull/118) **fermée** : Room v2 retravaillé sans bump → `IllegalStateException: Room cannot verify the data integrity` au démarrage (cf. hotfix ci-dessous) |
| 29 | `0.1.0-phase1d.1` | `be8c712` puis `af66363` | livré | AAB stamp `be8c712` = Room v3 only ; AAB stamp `af66363` = Room v3 + serialization hardening (PR [#120](https://github.com/ForumHFR/redface2/pull/120)) — c'est ce dernier qui est canonique |

### Tooling — Gradle wrapper 9.5.0 ([#103](https://github.com/ForumHFR/redface2/pull/103))

#### Changed
- `gradle/wrapper/gradle-wrapper.properties` bump `9.4.1` → `9.5.0` ; aligne le wrapper repo sur le Gradle stable consommé par AGP 9 / Kotlin 2.3 build chain.

### Hardening — Audit `@SerialName` post-Room ([#120](https://github.com/ForumHFR/redface2/pull/120))

Audit déclenché après le crash identityHash : recherche systématique de bugs analogues sur tout objet sérialisé sur disque ou via DataStore, où un rename Kotlin sans `@SerialName` ferait silencieusement crasher la lecture du cache.

#### Changed
- `core/model/PostContent.kt` : tous les sealed cases (`PostBlock.*`, `PostInline.*`, `SmileyKind.*`) et toutes les data class properties figées avec `@SerialName` au FQN historique. Le discriminator polymorphique kotlinx.serialization ne dépend plus du package — déplacer une classe dans un autre fichier ou module ne casse plus les rows `posts.content` déjà persistées.
- `core/data/topic/TopicMappers.PollDto` : `@SerialName` ajouté sur toutes les properties + defaults pour absorber un row legacy sans champ. Decode best-effort entouré de `runCatching` qui dégrade en `Poll = null` plutôt que crasher tout le topic.
- `core/data/auth/DataStoreCookieStore.CookieDto` : `@SerialName` ajouté sur toutes les properties + defaults. Un rename Kotlin ne déclenche plus un logout silencieux (le `runCatching` upstream attrapait déjà le `MissingFieldException`, mais préférable de figer la forme on-disk).
- Lectures défensives sur les colonnes `FetchMode` / `FlagType` dans `:core:database` : un row écrit avec une valeur enum inconnue (rare) est désormais rejeté de la liste plutôt que de crasher la query.
- `PostContentSerializerTest` (nouveau, dans `:core:database`) bloque la régression : il décode des fixtures historiques byte-identiques produites avant l'audit, et échoue si le discriminator JSON ne match plus la valeur figée.

### Hotfix Phase 1D — Room schema v3

Pendant la séquence de PRs Phase 1D, les fixes superpowers sur `flag_topics` (drop `views`, `firstUnreadPostId NOT NULL` → `lastPostReadId INTEGER nullable`) ont été appliqués sans bumper la version Room. Les builds intermédiaires (versionCode 25-28) ont donc écrit la table avec la forme legacy, et au passage à un build avec l'entité corrigée, Room throw `IllegalStateException: Room cannot verify the data integrity` (identityHash mismatch). Fix : bump @Database à v3 + `MIGRATION_2_3` qui drop-recrée `flag_topics` à la forme REST. Sûr car drapeaux = cache pur (re-fetch à l'observe suivant). `topic_pages` / `posts` non touchés.

### Phase 1D-4 — Prefetch anonyme (#108)

#### Added
- `TopicRepository.prefetch(cat, post, page)` : fetch topic en client anonyme, persistance `ANONYMOUS`, erreurs swallowed. Le cache auth plus riche reste protégé par l'anti-écrasement `authMode`.
- `ForumRepository.prefetchTopicList(cat, subcat, page)` : warm-up anonyme du listing suivant, payload jeté volontairement pour ne pas exposer de données sans champs per-user.
- `TopicViewModel` et `CategoryViewModel` lancent un prefetch `page + 1` best-effort après émission `Content`, avec un seul prefetch en vol et annulation à la sortie d'écran.
- Tests ViewModel/repository + règle Konsist : les chemins prefetch doivent utiliser les APIs dédiées et ne pas appeler les méthodes `refresh*` authentifiées.

#### Changed
- `docs/specs/architecture.md` explicite l'asymétrie : topic prefetch persiste une row `ANONYMOUS`, listing forum prefetch réchauffe seulement HFR/CDN.

### Phase 1D-3 — Cache Room (#26)

#### Added
- `CachePolicy` centralise les TTL Phase 1D : pages topic 60 s, listings topics 30 s, drapeaux 30 s, catégories 24 h, sous-catégories 6 h.
- Room schema v2 : `topic_pages.authMode`, `posts.authMode` et nouvelle table `flag_topics` scopée par `userId` + `FlagType`, avec migration `MIGRATION_1_2` et schema JSON exporté.
- `CacheInvalidator` observe les transitions de session HFR et purge les drapeaux Room + cache mémoire au logout ou changement de compte.

#### Changed
- `TopicRepositoryImpl.observeTopicPage` applique la sémantique fresh/stale : cache fresh = pas de réseau, cache stale = cache affiché puis refresh, cache absent = fetch direct.
- `DefaultFlagRepository` persiste les drapeaux REST dans `flag_topics`, relit un cache Room fresh sans re-fetch et remplace atomiquement le cache d'un onglet après chaque fetch réussi.
- Le prefetch anonyme de topic ne peut pas écraser une row `AUTHENTICATED` plus riche.

### Phase 1D-2 — Lecture longue topic (#107)

#### Added
- `TopicEffect.ScrollToPost(numreponse)` : nouveau side-effect one-shot consommé par `LaunchedEffect(Unit)` côté `TopicScreen`. Le ViewModel garde un flag `scrollEffectEmitted` pour empêcher un re-emit après refresh ; nouvelle TopicRoute (page différente) crée un nouveau ViewModel, le flag se réinitialise naturellement.
- Pagination UX : Précédent / Suivant désactivés aux bornes, indicateur `page X/Y`, champ "Aller à la page" qui n'accepte que `1..totalPages`. Rangée 1..N conservée en complément ≤ 40 pages.
- `TopicUiState.canGoPrevious` / `canGoNext` exposés pour piloter l'UI sans dupliquer la logique côté Compose.
- Tests : scroll effect émis pile une fois, ignoré quand le post cible n'est pas dans la page chargée, jamais re-émis sur refresh ; bornes Précédent/Suivant.

#### Changed
- `TopicScreen` : suppression du `LaunchedEffect(state.mode, request.scrollTo)` qui re-scrollait à chaque transition state, remplacé par la consommation de `viewModel.effects` via `LaunchedEffect(Unit)`.
- Strings drapeaux `topic_page_previous` / `topic_page_next` / `topic_page_indicator` / `topic_page_jump_label` / `topic_page_jump_action` ajoutés.

### Phase 1D-1 — Drapeaux REST (#110)

#### Added
- `core/network/HfrRestFlagBucket` (enum `PARTICIPATED`/`READ`/`FAVORITES`) + `HfrApiClient.getCategoryFlagTopics(cat, bucket, …, useAuth=true)` (per-cat, seule voie consommée par la prod). La voie globale REST `forums/hardwarefr/topics/{bucket}/` n'est pas exposée — son format groupé par catégorie n'a pas été capturé live (cf. `protocol-hfr.md`).
- `core/data/flags/RestFlagMappers` : mapping `RestListEnvelope<RestTopic>` → `List<Flag>`, `cat` dérivé soit de `fallbackCat` (per-cat REST) soit de `links.category.href` (synthétique pour la forme globale future).
- `core/data/flags/RestFlagMappersTest` : assertion contractuelle sur la fixture capturée `rest_cat23_participated.json` + cas défensifs synthétiques (champs absents, bucket inconnu, href malformé).
- `core/data/flags/DefaultFlagRepositoryTest` : tests sur la fixture capturée + mock `ForumRepository` pour la liste des catégories ; couvre fan-out per-cat, pagination multi-page (synthétique), `Failure` propagé depuis le réseau ou la liste de catégories.

#### Changed
- `core/data/flags/DefaultFlagRepository` consomme désormais `HfrApiClient.getCategoryFlagTopics(...)` + JSON lenient (`@FlagsJson`) au lieu de `HfrClient.getFlagsPage(...)` + `FlagsListParser`. Le repository injecte aussi `ForumRepository` pour récupérer la liste des catégories publiques ; la voie globale REST `forums/hardwarefr/topics/{bucket}/` n'est pas consommée car son format groupé n'a pas été capturé live (cf. `protocol-hfr.md`). Pagination multi-page implémentée par catégorie. Sémantique observe / refresh / clearSessionCache inchangée.
- `core/data/forum/RestForumDtos.RestTopic.lastPostReadId` : `Int?` → `Long?` pour absorber sans crash un `numreponse` HFR au-delà de `Int.MAX_VALUE`. `core/model/TopicSummary.lastPostReadId` aligné en `Long?` ; `app/RedfaceNavigation` ajoute le narrowing `Long → Int` borné côté category route, déjà présent côté flag.
- `core/model/Flag` : champ `views: Int` retiré (REST ne l'expose pas, aucun consumer UI). `firstUnreadPostId: Long` renommé `lastPostReadId: Long?` pour refléter la sémantique REST `last_post_read_id` (id du dernier post lu, pas du premier non lu).
- `core/domain/flags/FlagRepository` : KDoc rafraîchie Phase 1B.4 → Phase 1D-1.
- `docs/specs/protocol-hfr.md`, `architecture.md`, `models.md`, `navigation.md`, `roadmap.md` + `rest_cat23_participated.source.txt` mis à jour pour acter la voie per-cat consommée et le statut "non capturé" du global.

#### Removed
- `core/parser/flags/FlagsListParser` + ses tests + ses fixtures HTML (`flags_page_owntopic-{1,2,3}.html`).
- `core/network/HfrClient.getFlagsPage(owntopic)` (et son test associé).
- Provider Hilt `provideFlagsListParser` dans `PlatformBindingsModule`.

#### Limites connues
- Le format global REST des drapeaux (`forums/hardwarefr/topics/{bucket}/`, groupé par catégorie) n'a pas été capturé live ; la prod itère donc sur les ~19 catégories publiques en parallèle (OkHttp cap = 5 connexions concurrentes par host). Une PR de suivi pourra capturer le global et basculer la voie de consommation pour économiser N-1 round-trips.

### Added
- `docs/specs/models.md` : nouvelle section **Authentification** documentant `AuthState` (sealed `Anonymous` / `Authenticated(pseudo)`) et `LoginError` (sealed `InvalidCredentials` / `RateLimited` / `Network` / `Unknown`). Le `classDiagram` Mermaid expose la hiérarchie sealed.
- `docs/specs/roadmap.md` Phase 1 : entrée "Login HFR" cochée.

### Changed
- **ADR-002 amendé** : alignement avec la décision originale du cycle [#24 thème 13](https://github.com/ForumHFR/redface2/issues/24#issuecomment-3526003625). La décision actée était **DataStore non chiffré + FBE plateforme + `allowBackup="false"`**, sans clé Keystore custom. La rédaction initiale de l'ADR avait réintroduit une couche AES/GCM Keystore qui n'était pas dans la décision. Rationale : le password transite en clair côté HFR, donc tout chiffrement local du cookie est redondant face à un attaquant runtime.
- `docs/specs/architecture.md` § Stockage sécurisé des credentials : section réécrite (suppression du snippet `Cipher`/`KeyGenParameterSpec`).
- `docs/specs/stack.md` ligne "Stockage sécurisé" : "DataStore + Keystore" → "DataStore non chiffré + FBE plateforme".
- `AGENTS.md` règle Deprecations : alignement sur la nouvelle formulation Option A.

---

## v0.6.0 — 2026-04-25

Réalignement des specs sur la réalité du code après les PR [#78](https://github.com/ForumHFR/redface2/pull/78) (parser HTML topic + AST `PostContent`) et [#80](https://github.com/ForumHFR/redface2/pull/80) (`PostRenderer` Compose, retrait du fragment HTML brut de `Post.content`, sortie de Jsoup hors `:core:parser`). Phase courante : **Phase 1 — Core lecture**.

### Builds AAB Phase 1B → 1C (rétrospectif)

Cette section a été ajoutée a posteriori dans v0.7.0 pour combler le trou de tracking : entre le bootstrap Phase 0 et la livraison Phase 1D, plusieurs AABs ont été émis sans être notés ici. Chaque ligne ne correspond pas à un changement de spec, juste à un slot Play Console consommé.

| versionCode | versionName | Commit | Note |
|---|---|---|---|
| 14 | `0.1.0-phase1b.0` | `99f08c6` | premier upload Play Console interne après livraison Phase 1A |
| 15 | `0.1.0-phase1b.1` | `bcdbc09` | bump Play Console |
| 16 | `0.1.0-phase1b.2` | `15c6c34` | bump Play Console |
| 17 | `0.1.0-phase1b.3` | `940301c` | unread MP count sur `FlagsScreen` |
| 18 | `0.1.0-phase1b.4` | `49eb713` | bump Play Console (prépare le lancement réel auth Phase 1B.1) |
| 19 | `0.1.0-phase1b.5` | `57168e6` | bouton CSAE in-app sur `FlagsScreen` |
| 23 | `0.1.0-phase1b.9` | `00ab443` | drapeaux HFR live + diagnostics login + URLDecoder hotfix (PR [#94](https://github.com/ForumHFR/redface2/pull/94)) — saut de versionCode dû aux builds dogfood intermédiaires consommés en local |
| 24 | `0.1.0-phase1b.10` | `4cc60d3` | hardening session Phase 1B post review (`postRefreshFlags()` + `MaxAge`) |
| 26 | `0.1.0-phase1c.2` | `4fb376a` | Phase 1C-B post review fixes (cf. v0.7.0 ci-dessus pour la suite) |

### Added
- **Slice topic fixe** livré : `TopicScreen` rend une fixture HFR réelle (`topic_khakha_page_146.html`) via le pipeline complet `:core:parser` → AST `PostContent` → `:core:ui` `PostRenderer`, alimenté par `TopicFixtureRepository` en attendant le repository réseau (PR [#78](https://github.com/ForumHFR/redface2/pull/78) + [#80](https://github.com/ForumHFR/redface2/pull/80)).
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
- `docs/specs/navigation.md` réécrit l'exemple Nav 3 sur l'API stable réelle (`NavDisplay(backStack, onBack, entryDecorators, entryProvider)`, `entry<…>`), aligne sur `RedfaceNavKey` sealed, ajoute `ForumRoute` / `SearchRoute` et explicite le couple `TopicRequest` / `TopicScreen.onOpenPage` (alignement spec → code existant).
- `docs/specs/mvi.md` aligne `EditorMode` sur la réalité Phase 1 (placeholder à 3 valeurs : `Reply`, `Edit`, `EditFirstPost`) et trace la cible Phase 2 suivie par [#86](https://github.com/ForumHFR/redface2/issues/86) : `PostEditorMode { Reply, Edit }` + `TopicFormMode { New, EditFirstPost }`.
- `docs/specs/stack.md` documente le caveat insets de `NavigationSuiteScaffold` (status bars non consommées par défaut).
- `docs/specs/models.md` aligne le diagramme mermaid `PrivateMessage` sur la `data class` (ajout `page` + `totalPages`), passe `Post.postIndex` à `Int?` (le parser n'a pas toujours le contexte page/postsPerPage) et déplace `UserProfile` à la Phase 2 (popup profil) avec un renvoi vers l'extension Phase 4.
- `docs/adr/011-postcontent-ast.md` reformule la dette `Post.content: String` ([#65](https://github.com/ForumHFR/redface2/issues/65)) comme **résorbée** par PR [#80](https://github.com/ForumHFR/redface2/pull/80).
- `docs/specs/methodology.md` passe en `nav_order: 0` pour apparaître en premier dans le menu Spécifications (méthode foundationale).

### Fixed
- Spec audit post-#78/#80 ([#84](https://github.com/ForumHFR/redface2/issues/84)) : 21 écarts spec/code détectés et corrigés sans toucher au code.
- 4e passe `/spec-check` + `/spec-audit` + `/spec-reality` (PR #85) : `AdaptiveNavHost` réécrit avec les vraies signatures (`TopicRequest`, `FlagsScreen.onOpenUnreadTopic`, `EditorScreen(mode = String, cat, post)`), localisation de `FlagsScreen` dans `:app` documentée explicitement (pas de `:feature:flags` tant qu'il reste un placeholder), `TopicUiState` Phase 1 documenté comme slice `(request, mode, availablePages)` distinct du contrat cible Phase 2, `HfrParser` surface réelle (Phase 1 = `parseTopicPage` only) annotée par phase, Konsist emplacement réel (`app/src/test/.../ArchitectureKonsistTest.kt`) corrigé, test prefetch flagué Phase 1+ avec `:core:network`, `mvi.md` ajoute `src/test/kotlin/` + pointeur vers `RedfaceNavigation.kt` pour la NavKey, `extensions.md` clarifie le phasage Phase 1-2 (natif) vs Phase 4 (modules Gradle), `Topic.isFirstPostOwner` figé à `false` Phase 1 noté, `Compose Testing` claim aligné sur "câblé pas consommé", `nav_order` réordonné en séquence pédagogique sans trou (Méthodologie → Scope → Stack → Architecture → Navigation → Models → MVI → Extensions → Protocol → Roadmap), mermaid Navigation Graph annoté pour distinguer flow utilisateur des routes typées, élision `// …` remplacée par un pointeur vers le fichier source.

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
- **Navigation** : Compose Navigation 2.9 type-safe → **Compose Navigation 3**. Première réécriture de `docs/specs/navigation.md` vers les routes `@Serializable` implémentant `NavKey`, `NavBackStack<NavKey>`, deep linking via parsing `Uri` manuel et intégration `ListDetailPaneScaffold` M3 Adaptive. L'API exacte a ensuite été réalignée en v0.6.0 sur `NavDisplay(backStack, onBack, entryProvider)`. Cf. [#23](https://github.com/ForumHFR/redface2/issues/23) + commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).
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
- **API Compose Navigation 3** : exemples `docs/specs/navigation.md` migrés vers `NavKey` et `NavBackStack<NavKey>` ; l'alignement final sur `NavDisplay(backStack, onBack, entryProvider)` est corrigé en v0.6.0. Les versions antérieures utilisaient `backStack.push()` et `SceneStrategy.SingleTop` qui n'existent pas dans la stable. Commit [`2464ac9`](https://github.com/ForumHFR/redface2/commit/2464ac9).
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
