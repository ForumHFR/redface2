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

## Phase actuelle : Phase 4 — UI & extensions

Phases 0 à 3 sont livrées (bootstrap ; lecture du forum ; écriture : poster/citer/`[img]`/upload ; messages : MP + DT/MultiMP), bêta **0.18.0** publiée (Play open testing + F-Droid ; refonte vue Drapeaux [#603](https://github.com/ForumHFR/redface2/issues/603) livrée). Phase 4 en cours : refonte vue Topic ([#604](https://github.com/ForumHFR/redface2/issues/604)), aide & réglages, et architecture d'extensions ([#6](https://github.com/ForumHFR/redface2/issues/6), [#7](https://github.com/ForumHFR/redface2/issues/7)). Voir [roadmap]({{ site.baseurl }}/specs/roadmap). Les contributions utiles maintenant :

- **Implémenter une issue Phase 4** ouverte (refonte Drapeaux/Topic, extensions, polish UX)
- **Proposer des features** : ouvrir une issue avec le label `feature`
- **Signaler des oublis ou divergences spec/code** : skill `/spec-reality` ou commentaire d'issue
- **Capturer des fixtures HFR réelles** via `hfr-mcp` (skill `/parse-fixture`)

> Ce guide est la **source canonique du workflow opérationnel** (environnement, tests, rendu visuel, Git). La *méthode* (quoi spécifier / prototyper / TDD) vit dans [methodology.md]({{ site.baseurl }}/specs/methodology) ; la *promotion / release* dans [release.md]({{ site.baseurl }}/guides/release). Ne pas dupliquer le cycle ailleurs.

---

## Environnement de développement

### Prérequis

- Android Studio (dernière version stable)
- JDK 21+
- Un appareil ou émulateur Android API 29+
- Un compte HFR (pour tester)

### Environnement Docker optionnel

Le chemin de référence mainteneur repose sur **`ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31`**. L'image a servi à valider `:app:assembleDebug` pendant [#4](https://github.com/ForumHFR/redface2/issues/4) et [#5](https://github.com/ForumHFR/redface2/issues/5), puis a été **épinglée** et codifiée dans [#35](https://github.com/ForumHFR/redface2/issues/35).

- **Image de base** : `ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31` (manifest list multi-arch vérifiée le 2026-04-20 : `amd64` + `arm64`, JDK 21 + Android SDK 36 dans l'image brute)
- **Dockerfile** : [Dockerfile](https://github.com/ForumHFR/redface2/blob/main/Dockerfile) à la racine, ajoute `platforms;android-37.0` pour le dev container
- **Wrapper local** : `scripts/docker-dev.sh`, avec cache SDK plateformes sous `.gradle-user/.../android-sdk/`
- **Dev container** : `.devcontainer/devcontainer.json`

Exemples :

```bash
# Build debug dans le container de référence
./scripts/docker-dev.sh

# Lancer une commande Gradle arbitraire dans le même env
./scripts/docker-dev.sh ./gradlew lintDebug testDebugUnitTest
```

Le script monte le repo dans `/workspace`, persiste les caches Gradle / Android dans `.gradle-user/` et exécute le container avec l'UID/GID de l'utilisateur hôte pour éviter les fichiers root-owned sur Linux. En rootless Podman, `--userns keep-id` est ajouté automatiquement pour garder le mapping d'identité.

Pour `compileSdk 37`, le wrapper monte aussi `.gradle-user/<cache>/android-sdk/platforms` sur `/opt/android-sdk-linux/platforms` et `.gradle-user/<cache>/android-sdk/temp` sur `/opt/android-sdk-linux/temp`. Au premier run, il copie dans ce cache les plateformes déjà présentes dans l'image (dont `android-36`) ; ensuite AGP installe les plateformes manquantes, comme `android-37.0`, dans ce cache utilisateur inscriptible. Les workflows GitHub Actions restent sur l'image brute : leurs jobs tournent en root, donc l'auto-download AGP peut écrire directement dans le SDK du container.

#### Signature debug canonique et `adb install -r`

Les builds **debug** sont signés par une clé canonique committée (`config/signing/redface2-debug.keystore` — debug-only, password `android`, alias `androiddebugkey`), câblée sur `buildTypes.debug` dans `app/build.gradle.kts`. Objectif : **tout** build debug — CLI, Docker (n'importe quel UID), Android Studio, CI — signe à l'identique, donc `adb install -r` fonctionne entre environnements.

Sans cette clé, AGP génère un `debug.keystore` par `ANDROID_USER_HOME` : un APK debug buildé sous un environnement (ex. Docker UID 1000) ne peut pas remplacer (`-r`) une install signée ailleurs → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Empreintes : `SHA-1 E2:C6:9D:12:DC:99:D2:06:B9:E3:7D:15:EA:B6:80:44:C1:A5:DC:22`, `SHA-256 55:E9:56:A9:BD:1C:99:E8:F0:C4:55:C3:C5:8B:7C:55:C0:7A:B9:D9:7C:D8:22:CF:62:0E:F8:A3:26:73:2A:26` (vérif : `keytool -list -v -keystore config/signing/redface2-debug.keystore -storepass android`).

**Migration unique** : si `fr.forumhfr.redface2.debug` est déjà installé avec une ancienne clé, le désinstaller une fois avant le premier `install -r` :

```bash
adb uninstall fr.forumhfr.redface2.debug
```

⚠️ Clé **debug uniquement** (insecure by design, package suffixé `.debug`) — jamais acceptée par Play/F-Droid pour publier. La signature release/upload est séparée (secrets CI `UPLOAD_*` + `.gradle-user/signing/`), non concernée par ce changement.

#### Dogfood : installer en parallèle d'une release Play

Pour installer un build local **à côté** d'une version Redface 2 déjà publiée (alpha / closed testing) sans clobber l'existant, créer un init-script Gradle **gitignored** sous `.gradle-user/dogfood.init.gradle` :

```groovy
allprojects {
  afterEvaluate { project ->
    if (project.plugins.hasPlugin('com.android.application')) {
      project.android.defaultConfig.applicationIdSuffix = '.dogfood'
      project.android.defaultConfig.versionNameSuffix = '-dogfood'
      project.android.defaultConfig.manifestPlaceholders.put('appLabel', 'Redface 2 dog')
    }
  }
}
```

Puis builder :

```bash
./scripts/docker-dev.sh ./gradlew :app:assembleRelease \
  --init-script .gradle-user/signing/signing.init.gradle \
  --init-script .gradle-user/dogfood.init.gradle
```

L'APK résultant a `applicationId fr.forumhfr.redface2.dogfood`, un launcher label distinct, et coexiste avec la release prod sur le même appareil. **Ne jamais utiliser cet overlay pour un AAB destiné à Play Console** — Play attend l'`applicationId` bare `fr.forumhfr.redface2` déclaré dans la console.

Le placeholder `${appLabel}` est tracké dans `app/src/main/AndroidManifest.xml` + `app/build.gradle.kts` (défault `@string/app_name`), donc les builds standard sans overlay ne sont pas affectés.

### Structure du projet

```
redface2/
  app/                    # Point d'entrée, DI, navigation
  core/
    model/                # Modèles domaine
    domain/               # Interfaces de repositories, règles métier
    data/                 # Implémentations de repositories
    network/              # OkHttp, session HFR
    parser/               # Jsoup, HTML HFR → modèles + PostContent ; BBCode éditeur en Phase 2
    database/             # Room, cache, sync MPStorage
    ui/                   # Thème, composants partagés, PostRenderer
    extension/            # Points d'extension (Phase 4)
  feature/
    flags/                # Écran d'accueil (drapeaux)
    forum/                # Catégories, topics
    topic/                # Lecture de topic
    editor/               # Reply, edit, FP, création topic
    messages/             # MPs, MultiMPs
    auth/                 # Login
    search/               # Recherche
    settings/             # Préférences
    profile/              # Profil utilisateur (sheet aperçu + page complète)
```

### Gestion des dépendances

Le projet utilisera un **Gradle version catalog** (`gradle/libs.versions.toml`) créé en Phase 0 comme **source de vérité unique** des versions. Choix de stack et versions structurelles (major.minor) documentés dans [stack.md]({{ site.baseurl }}/specs/stack) — les patches exacts sont résolus dans le TOML au bootstrap et maintenus via Renovate/Dependabot.

**Pourquoi pas de tableau de versions exact ici** : une doc qui liste `kotlin = "2.3.20"` dérive en 3 mois. La source unique est le fichier `libs.versions.toml` du repo, interrogeable aussi via Context7/Docfork (cf. [#19](https://github.com/ForumHFR/redface2/issues/19)) pour générer du code aligné avec les APIs stables courantes.

### MCP documentaire optionnel

Pour les contributeurs qui travaillent avec un agent IA, l'outil recommandé par défaut est **Context7**. **Docfork** reste un fallback crédible si votre client l'intègre mieux. Aucun des deux n'est un prérequis du projet.

- **Context7 (recommandé)** : setup officiel via [`ctx7 setup`](https://context7.com/docs/clients/cli) avec choix entre mode **MCP** et **CLI + Skills**. Documentation d'installation et configuration manuelle : [installation Context7](https://context7.com/docs/installation).
- **Docfork (fallback)** : setup officiel via [`npx dgrep setup --cursor`](https://github.com/docfork/docfork) ou équivalent `--claude` / `--opencode`, avec outils `search_docs` et `fetch_doc`. Configuration manuelle MCP : `https://mcp.docfork.com/mcp`.
- **Règle d'usage** : préciser **`stable release`** dans la requête, et si possible l'ID exact de la lib ou sa version majeure/minor pour éviter les snapshots et pre-releases.
- **Source of truth** : ces MCP servent à vérifier une API actuelle, pas à décider seuls de l'architecture. Les choix de projet restent dans les pages canoniques (`stack`, `architecture`, `methodology`) et le `libs.versions.toml`.

**Cas validés côté mainteneur** :

- pendant [#4](https://github.com/ForumHFR/redface2/issues/4), la doc officielle Android sur **built-in Kotlin dans AGP 9** a servi à confirmer que `org.jetbrains.kotlin.android` ne devait plus être appliqué. La page [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) indique qu'AGP 9 active Kotlin built-in et que le plugin `org.jetbrains.kotlin.android` n'est plus requis.
- pendant [#5](https://github.com/ForumHFR/redface2/issues/5), Context7 a servi à recaler le setup **Navigation 3** (plugin serialization côté app, dépendances stables `navigation3-runtime` / `navigation3-ui`) et à éviter plusieurs faux détails d'API avant implémentation.

Ces vérifications ont été répercutées dans le bootstrap Gradle et le code livré.

### Convention par feature

Chaque feature suit la même organisation. Source set : **`src/main/kotlin/`** (pas `src/main/java/`) — convention uniforme sur tout le projet pour le code Kotlin, alignée avec `core/model`, `core/domain`, `core/parser`. Tests sous `src/test/kotlin/`.

```
feature/topic/
  src/main/kotlin/fr/forumhfr/redface2/feature/topic/
    TopicScreen.kt          # @Composable, collecte state + effects
    TopicContent.kt         # @Composable stateless, previewable (si extrait)
    TopicViewModel.kt       # MVI ViewModel (Hilt-injected via @HiltViewModel)
    TopicUiState.kt         # State UI + Intents (consolider dans le même fichier tant que c'est court ; extraire en TopicIntent.kt si > ~80 lignes)
    TopicRequest.kt         # Paramètre d'entrée du screen, dérivé de la route Nav3 (la NavKey vit dans app/.../navigation/, cf. § ci-dessous)
  src/test/kotlin/fr/forumhfr/redface2/feature/topic/
    TopicViewModelTest.kt
```

Convention de nommage MVI retenue (Phase 1, validée sur `:feature:topic`) :

- **`<Feature>UiState`** plutôt que `<Feature>State` — cohérent avec Compose et avec le wording « UI state » utilisé dans la spec MVI.
- **`<Feature>Intent`** = actions utilisateur internes au ViewModel. Vit dans `<Feature>UiState.kt` tant que la liste est courte ; extraire en `<Feature>Intent.kt` quand le fichier dépasse ~80 lignes ou quand la sealed hierarchy a plus de 5-6 cas.
- **`<Feature>Request`** = DTO d'entrée du screen, **dérivé** de la `NavKey` Navigation 3 (la clé Nav3 vit côté `app/` dans `RedfaceNavigation.kt` sous le nom `<Feature>Route : RedfaceNavKey` — cf. ADR-008). Le `Request` est construit par le `entryProvider` / `RedfaceNavHost` à partir de la route et passé au `<Feature>Screen` ; toujours dans son propre fichier (contrat externe).
- **Effects** (one-shot side-effects vers la vue : snackbar, navigation programmatique, finish) : à introduire **uniquement quand le besoin émerge**. Ne pas les anticiper en Phase 1 si la feature n'en a pas besoin (cohérent avec `AGENTS.md` § « Charte anti-derive IA-first » → « Spike avant architecture » et « Le noyau avant l'écosystème »).
- **`<Feature>Screen` vs `<Feature>Route`** : le call-site stateful (qui résout `hiltViewModel()` et collecte les flows) s'appelle généralement `<Feature>Screen`. Une variante `<Feature>Route` est acceptable quand le composable doit aussi recevoir des données runtime depuis `:app` (ex. `BuildConfig.VERSION_NAME` dans `:feature:flags.FlagsRoute`) : le suffixe « Route » signale alors qu'il s'agit du call-site directement attaché à l'`entry<…Route>` de Compose Navigation 3.

### Méthodologie

La méthode canonique du projet est documentée dans [Méthodologie]({{ site.baseurl }}/specs/methodology) et formalisée dans [ADR-000]({{ site.baseurl }}/adr/000-methodologie-triple-hybride).

Cette page décrit **comment** contribuer ; elle ne redéfinit pas la méthode du projet. Pour une contribution structurante, lire `docs/specs/methodology.md` puis `AGENTS.md`.

Cas particulier : toute contribution qui ajoute ou modifie une fonction de la **surface de lecture** (rendu d'un post, gestes de page, préférences de lecture) met à jour sa ligne dans la [matrice de parité Topic ↔ MP]({{ site.baseurl }}/specs/reading-parity) — la règle d'entretien et les verdicts possibles vivent sur cette page.

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

### Licence des contributions

- Le client Android Redface 2 est publié sous **`GPL-3.0-only`**.
- Toute contribution acceptée au code ou aux fichiers de spec du repo est distribuée sous cette même licence.
- Si un futur composant réseau dédié apparaît, sa licence sera tranchée séparément et ne doit pas être présumée depuis celle du client.

### Tests

**Stack de tests (Phase 0) :**
- **JUnit 4** — framework de test
- **MockK** — mocking Kotlin-first
- **Robolectric** — tests Android sans émulateur
- **Turbine** — test des `Flow` et `StateFlow`
- **Compose Testing** — `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` câblés sur `:core:ui` (Phase 2F-A #130) pour des tests Compose en JVM via Robolectric. Pattern canonique : `createComposeRule` (package `v2`, l'API que le compilateur Compose pousse en remplacement de la v1 désormais deprecated), mount via `composeTestRule.setContent { … }` UNE seule fois par test, puis drive le state d'entrée (fontScale, mode, …) via un `mutableStateOf` et `waitForIdle()` entre chaque mutation. Exemple canonique : `core/ui/src/test/.../post/PostRendererInlineLayoutTest.kt`. Important : `setContent` ne peut être appelé qu'une fois par test (host Activity unique) ; les tests paramétrés piloteraient le state Compose plutôt que de re-mount. `android.testOptions.unitTests.isIncludeAndroidResources = true` est nécessaire côté module et `debugImplementation(ui-test-manifest)` injecte l'AndroidManifest activité-host minimal que la rule lit à l'inflation. Pour les nodes asynchrones (Coil `AsyncImage`), assumer qu'ils restent sur leur placeholder sous Robolectric — soit injecter un fake `ImageLoader`, soit asserter uniquement sur la structure layout (`TextLayoutResult.placeholderRects` plutôt que le bitmap rendu).

**Enforcement au build (Phase 0) :**
- **Konsist** — règles d'architecture (imports inter-modules, `:core:extension` limité à `topic/editor`, tokens M3 centralisés dans `:core:ui`). Voir [architecture.md]({{ site.baseurl }}/specs/architecture) pour les règles. La règle `@AnonymousClient` sur prefetch sera activée dès que le code réseau/prefetch existera réellement.
- **Detekt** — style Kotlin + imports interdits (`ForbiddenImport` : `GlobalScope`, `LiveData`, `material.*`). NB : `runBlocking` n'est **pas** attrapé par Detekt aujourd'hui. L'interdiction de **nouveaux** `runBlocking` en prod (allow-list des 2 sites existants : `DataStoreUserPreferencesRepository.kt`, `PersistentCookieJar.kt`) est une garde dédiée à ajouter, pas un défaut Detekt.
- **Android Lint** — a11y + i18n + correctness. Lint tourne avec `abortOnError = true` (convention plugin dans `build-logic/`). NB : `MissingContentDescription`/`TouchTargetSizeCheck`/`HardcodedText` restent à leur **sévérité par défaut (warning)** — ils ne font pas échouer le build tant qu'ils ne sont pas promus en `error` (`lint { error += listOf(...) }`), ce qui n'est pas le cas actuellement.
- **Parité de lecture** — deux gardes ([#1045](https://github.com/ForumHFR/redface2/issues/1045)) rendent `[enforced]` la règle d'entretien de [reading-parity.md]({{ site.baseurl }}/specs/reading-parity) : **garde A** par chemins (`scripts/check-reading-parity-touch.sh`, job `repo-guards`, PRs uniquement — échappatoire `Parity-Impact: none — <raison>` dans le corps de la PR, relu **en direct** au re-run du job) ; **garde B** par symboles (cas de `DocsConsistencyTest`, exécuté par `test` en CI et par `/validate` en local — index de **définitions** réelles : commentaires, littéraux d'annotation et attestations de fichiers de test exclus ; aucun compteur de lignes, la matrice est faite pour grandir).

**CI Phase 0 :**
- workflow GitHub Actions sur push `main` et PR
- exécution dans le même env Docker de référence, épinglé par digest
- pipeline actuelle (CI) : `detektAll`, `lintDebug` + `:app:lintProdDebug`, `test` + `testDebugUnitTest` (inclut les checks Konsist), `:app:assembleProdDebug` — le `:app:assembleDebug` **non flavoré ne résout plus** (variantes `dev`/`prod`, [#233](https://github.com/ForumHFR/redface2/issues/233))
- **Dependabot** configuré pour `gradle` et `github-actions`

**Couverture (hybride différenciée) :**
- **100% sur les transformers du parser HFR** — naturel, fixtures dictent exhaustivité
- **Guidée par risque ailleurs** (ViewModels, mappers, repositories) — tests sur edge cases réels + fixtures, pas de quota chiffré
- Aucun outil de mesure branché à ce jour (Kover proposé par la RFC [#761](https://github.com/ForumHFR/redface2/issues/761)) ; pas de gate CI de couverture

**Stratégie :**
- **TDD sélectif** sur fonctions pures (parser, PostContent AST, ViewModels, helpers, mappers) — red → green → refactor
- **Test-after** sur intégrations (repositories cache/network, deep linking)
- **Pas de TDD red-green** sur UI Compose, mais **rendu visuel Roborazzi adopté en Phase 4** (cf. sous-section ci-dessous + [ADR-016]({{ site.baseurl }}/adr/016-roborazzi-screenshot-testing)) : Compose Preview pour le design, Roborazzi pour **capturer et inspecter** le rendu sans device.

**Rendu visuel (Roborazzi) :**

Screenshot testing **JVM** (sur Robolectric, sans device) via Roborazzi 1.63, consommé comme artefact de test simple (pas le plugin Gradle), avec `roborazzi.test.record=true` forcé.

- **Mode `record`** (par défaut ici) : un test `captureRoboImage` génère un PNG dans `<module>/build/outputs/roborazzi/` (non versionné) → **inspection visuelle** rapide (~40 s). Lancer via l'env Docker, ex. `./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest`. (Il n'existe **pas** de tâche `recordRoborazzi` : le plugin Gradle Roborazzi n'est pas appliqué — record forcé via `roborazzi.test.record=true`.)
- **Quand l'utiliser** : `record` est réservé aux **changements de rendu intentionnels** (PostRenderer, écrans refondus) — on régénère puis on regarde l'image. `verifyRoborazzi` (compare) existe mais les **baselines ne sont pas versionnées** en V1 (elles bougeraient à chaque itération des refontes #603/#604).
- **Statut** : **recommandé** pour tout changement de rendu UI structurant, **pas encore un gate CI dur**. Le passage en `verify` + baselines committées + gate fera l'objet d'une décision dédiée une fois les refontes UI stabilisées.
- **Couverture actuelle** : `:core:ui` (PostRenderer — code, smiley, citation ; `FlagItem` refonte #603 ; vitrine réglages `SettingsHomeShowcase`) et quelques surfaces ciblées dans `:feature:topic` (extraction zoom) / `:feature:messages` (`MessageCard`, chrome de conversation). Elle s'étend au cas par cas — **pas** d'obligation « tout écran doit avoir un snapshot ».
- Décision et rationale : [ADR-016]({{ site.baseurl }}/adr/016-roborazzi-screenshot-testing).

**Smoke test mensuel HFR (fin de phase de lecture) :**

**Prévu — pas encore implémenté** (aucun workflow `cron` n'existe à ce jour dans `.github/workflows/`). Conception : un workflow GitHub Actions (`cron: '0 2 1 * *'`, 1er du mois, 2h UTC) qui vérifierait, **sans authentification** (cf. règle prefetch non-authentifié), contre HFR réel :

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
| `edit_post.html` | Page d'édition d'un post | logué uniquement | `message.php?...&numreponse=X` sur propre post |
| `quote.html` | Contenu de citation BBCode | logué uniquement | `message.php?...&numrep=X` |
| `categories.html` | Page d'accueil (catégories) | non-logué | `/hfr/` |
| `topic_list.html` | Liste topics d'une sous-catégorie | logué + non-logué | `forum2.php?cat=23&subcat=0` |
| `profile_standard.html` | Profil utilisateur standard | non-logué | `hfr/profil-<id>.htm` (membre) |
| `profile_admin.html` | Profil admin/modo | non-logué | `hfr/profil-<id>.htm` (modo) |
| `mp_list.html` | Liste des MPs classiques | logué uniquement | `forum1.php?cat=prive` |
| `mp_conversation.html` | Conversation MP | logué uniquement | `forum2.php?cat=prive&post=<mp_id>` |
| `smiley_search.html` | Résultats recherche de smileys | non-logué | `message-smi-mp-aj.php?search=X` |
| `rehost_response.html` | Réponse reho.st | non-logué | `reho.st` HTML de réponse |

**Nouvelles fixtures v2** (pages non couvertes par v1) :

| Fixture | Page HFR | Auth ? | Pourquoi | Source HFR (à capturer) |
|---------|----------|--------|----------|-------------------------|
| `flags_page_owntopic-{1,2,3}.html` | `/forum1f.php` (drapeaux) | logué uniquement | Écran d'accueil, pas dans v1 | `forum1f.php?owntopic={1,2,3}` |
| `flags_page_empty.html` | Drapeaux vides | logué uniquement | Cas edge : aucun drapeau | compte neuf ou nettoyé |
| `search_results.html` | `/search.php` | logué + non-logué | Recherche | `search.php?search=redface` |
| `login_success.html` | Réponse login OK | — | Détection succès auth | Après POST login OK |
| `login_failure.html` | Réponse login échoué | — | Détection échec auth | Après POST bad pass |
| `edit_fp.html` | Édition First Post avec sondage | logué uniquement | Distinct de l'édition normale | propre topic avec sondage |
| `write_reply_form_open_topic.html` | Formulaire reply Phase 2A | logué uniquement | Contrat `bddpost.php` réel | `message.php?...&post=35395&page=20&subcat=550` |
| `write_quote_form_test_post.html` | Formulaire quote Phase 2A | logué uniquement | `numrep` + `[quotemsg=...]` prérempli | `message.php?...&numrep=2784595` |
| `write_quote_form_bbcode_rich.html` | Formulaire quote BBCode riche Phase 2A | logué uniquement | Quote préremplie depuis un post contenant `b/i/u/strike/url/fixed/spoiler/img` | `message.php?...&numrep=2523833` |
| `write_edit_form_test_post.html` | Formulaire edit Phase 2A | logué uniquement | `numreponse` + contrat `bdd.php` réel | `message.php?...&numreponse=2784595` |
| `write_create_topic_form_android_cat.html` | Formulaire création topic Phase 2A | logué uniquement | `sujet`, `from_subcat`, `new=0`, sous-catégories | `message.php?...&cat=23&subcat=550` |
| `write_reply_anonymous_form.html` | Formulaire reply anonyme Phase 2A | non-logué | Composer legacy avec pseudo/password | `message.php?...&post=35395&page=20` |
| `write_create_topic_anonymous_form.html` | Formulaire création anonyme Phase 2A | non-logué | Composer legacy avec pseudo/password | `message.php?...&cat=23&subcat=550` |
| `write_edit_success_response.html` | Réponse succès edit Phase 2A | logué uniquement | Message succès `bdd.php` | `POST bdd.php?config=hfr.inc` |
| `write_reply_success_response.html` | Réponse succès reply Phase 2A | logué uniquement | Message succès `bddpost.php` | `POST bddpost.php?config=hfr.inc` |
| `write_empty_message_error.html` | Erreur contenu vide Phase 2A | logué uniquement | Validation HFR, aucun post créé | `POST bddpost.php?config=hfr.inc` |
| `write_invalid_token_error.html` | Erreur `hash_check` invalide Phase 2A | logué uniquement | Rejet HFR avant post | `POST bddpost.php?config=hfr.inc` |
| `write_antiflood_error.html` | Erreur anti-flood Phase 2A | logué uniquement | Plus de 3 réponses consécutives en 10 minutes refusées | `POST bddpost.php?config=hfr.inc` |
| `write_locked_topic_page.html` | Topic fermé Phase 2A | logué uniquement | Pas de lien reply exposé | topic fermé `post=14227` |
| `write_reply_locked_topic_forced_form.html` | Formulaire forcé topic fermé Phase 2A | logué uniquement | `message.php` sert encore un composer | `message.php?...&post=14227` |
| `write_locked_topic_error.html` | Erreur POST topic fermé Phase 2A | logué uniquement | Rejet `bddpost.php`, aucun post créé | `POST bddpost.php?config=hfr.inc` |
| `write_created_owned_topic_page.html` | Topic temporaire owned Phase 2A | logué uniquement | Page topic après création + édition FP | topic temporaire `cat=10 post=148749` |
| `write_edit_first_post_form.html` | Formulaire edit FP Phase 2A | logué uniquement | `sujet`, `subcat`, sondage, `delete=1` topic | `message.php?...&numreponse=2523829` |
| `write_edit_first_post_success_response.html` | Réponse succès edit FP Phase 2A | logué uniquement | Message succès `bdd.php` | `POST bdd.php?config=hfr.inc` |
| `write_edit_form_bbcode_rich.html` | Formulaire edit BBCode riche Phase 2A | logué uniquement | `content_form` conserve `b/i/u/strike/url/fixed/spoiler/img` | `message.php?...&numreponse=2523833` |
| `write_quote_success_response.html` | Réponse succès quote Phase 2A | logué uniquement | Même succès `bddpost.php` que reply simple | `POST bddpost.php?config=hfr.inc` |
| `write_delete_post_form.html` | Formulaire suppression post Phase 2A | logué uniquement | `delete=1` libellé `Effacer ce message` | `message.php?...&numreponse=2523830` |
| `write_delete_post_success_response.html` | Réponse succès suppression post Phase 2A | logué uniquement | Message succès `bdd.php` | `POST bdd.php?config=hfr.inc` |
| `write_delete_topic_form.html` | Formulaire suppression topic Phase 2A | logué uniquement | `delete=1` libellé `Effacer l'intégralité du sujet` | edit FP avant suppression |
| `write_delete_topic_success_response.html` | Réponse succès suppression topic Phase 2A | logué uniquement | Message succès + refresh vers sous-catégorie | `POST bdd.php?config=hfr.inc` |
| `write_deleted_topic_404.html` | Topic supprimé Phase 2A | logué uniquement | L'URL du topic supprimé répond HTTP 404 | `forum2.php?...&post=148749` |
| `multimp_conversation.html` | MultiMP | logué uniquement | Différent des MPs classiques | MultiMP existant |
| `topic_with_poll.html` | Topic avec sondage | logué + non-logué | Parsing du sondage | topic public avec sondage |
| `topic_last_page.html` | Dernière page (< 40 posts) | non-logué | Pagination edge case | dernière page d'un topic |
| `topic_deleted_posts.html` | Page avec posts supprimés | non-logué | Décalage de numérotation | topic modéré connu |
| `modo_not_flagged.html` | modo.php — formulaire d'alerte | logué uniquement | Redflag : post pas encore alerté | `modo.php?numreponse=X` |
| `modo_flagged.html` | modo.php — déjà alerté | logué uniquement | Redflag : post alerté | `modo.php?numreponse=X` (déjà alerté) |
| `modo_join.html` | modo.php — rejoindre une alerte | logué uniquement | Redflag : alerte en cours | `modo.php?numreponse=X` (alerte ouverte) |
| `private_message_quote_form.html` | `message.php` — citation MP | logué uniquement | Contrat de la citation MP : `numrep` = message cité, `[quotemsg=…]` prérempli (#1041) | lien « citer » d'une page `cat=prive` — **fixture réduite au `form[name=hop]`**, cf. [recette]({{ site.baseurl }}/guides/capture-fixture-citation-mp) |
| `private_message_reply_form.html` | `message.php` — réponse simple MP | logué uniquement | Témoin de la précédente : `numrep` **vide**, textarea vide — leur delta EST le contrat (#1041) | `form#repondre_form` de la même conversation, même session — **fixture réduite** |

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

**Ces tableaux sont un plan de capture, pas un inventaire.** Le décompte fait foi côté disque, pas ici :
au 2026-08-14, `core/parser/src/test/resources/fixtures/` porte **70 fixtures HTML** et l'arbre entier
en compte 106 (plus 11 fixtures REST JSON dans `:core:data`). Le plan initial en annonçait ~61
(13 reprises de v1 + 39 nouvelles + 9 profil/paramètres) : ne pas propager ce chiffre comme un état
courant — un décompte recopié pourrit (constat #1041). `ls` sur le dossier répond mieux que cette page.

### Fixtures REST JSON (Phase 1C-A)

À côté des fixtures HTML, le browsing REST (cf. [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride)) consomme des fixtures JSON capturées live le 2026-05-01. Elles vivent à côté de leurs consumers (DTO + mappers) dans `:core:data` :

```
core/data/src/test/resources/fixtures/
├── rest_categories.json              # 19 catégories anonymes
├── rest_categories_auth.json         # 19 catégories + liens drapeaux (auth)
├── rest_subcategories_cat13.json     # 15 sous-catégories de Discussions
├── rest_topics_cat23_subcat550_p1.json  # page 1 — Tech Mobiles / Android
├── rest_topic_meta_35395.json        # metadata d'un topic isolé
└── rest_cat23_participated.json      # un topic en mode authentifié
```

Mêmes règles que les fixtures HTML : capturées live, **jamais inventées**, nettoyées des données sensibles avant commit, accompagnées d'un `.source.txt` qui documente la commande curl d'origine + caveats. Capture via `curl` direct contre `/webservices/rest_api.php?uri=…`, avec ou sans cookies selon le mode visé.

**Règles :**
- Les fixtures sont capturées depuis le vrai site HFR, **jamais** fabriquées par une IA ou à la main.
- Capture via MCP `hfr-mcp` : `hfr_read cat=X post=Y page=Z output=path/to/fixture.html` écrit le HTML brut.
- Chaque fixture est accompagnée d'un fichier `.source.txt` frère ou d'un commentaire HTML en tête précisant `cat`, `post`, `numreponse`, date de capture.
- Les fixtures loguées ne doivent **jamais** contenir de cookies, tokens `hash_check`, emails, mots de passe, identifiants réels personnels — nettoyer avant commit (voir skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) étape 9).
- Exception contrôlée : les pseudos et profils des comptes de test dédiés publics (`XaTelitte` / `xatelitte`, profil HFR `1214571`) peuvent rester en clair pour conserver la fidélité parser. Ne jamais appliquer cette exception à un compte personnel non dédié.
- Ne pas reformatter les fixtures HTML : elles doivent rester proches de la réponse HFR. Un nettoyage minimal des fins de ligne/trailing whitespace est acceptable pour satisfaire `git diff --check`, sans modifier la structure DOM.
- Quand un bug de parsing est corrigé, le HTML problématique est ajouté aux fixtures avec un test de non-régression.
- Un **smoke test CI mensuel** (cf. cron `0 2 1 * *` ci-dessus, **prévu**) vérifiera que les sélecteurs CSS critiques matchent toujours sur une vraie page HFR publique.

---

## Communication

- **Issues GitHub** : pour les bugs, features, questions techniques
- **Topic HFR** : pour les discussions générales avec la communauté (lien à venir)

---

## Attribution

Les contributions sont reconnues dans le CHANGELOG et les release notes. Merci à tous ceux qui participent.
