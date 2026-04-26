---
title: Stack technique
parent: Spécifications
nav_order: 2
permalink: /specs/stack
---

# Stack technique
{: .fs-8 }

Chaque choix a été évalué, comparé et verrouillé. Voici le détail.
{: .fs-5 .fw-300 }

---

## Vue d'ensemble

| Brique | Choix | Alternative écartée | Raison |
|--------|-------|-------------------|--------|
| Langage | **Kotlin** | Java | Standard Android depuis 2019, null safety, coroutines |
| UI | **Jetpack Compose** (via compose-bom) | XML layouts | Direction officielle Google, déclaratif, plus maintenable |
| Design system | **Material 3** + **Material 3 Adaptive 1.2+** | Material 2 | Standard 2026, dynamic color, canonical layouts (list-detail, supporting pane). Décisions design détaillées ci-dessous. |
| Architecture | **MVI** (MVVM+UDF) | MVVM classique | Flux unidirectionnel, état prévisible, idéal pour un forum reader |
| Navigation | **Compose Navigation 3** (1.1.0+, stable depuis 08/04/2026) | Circuit, Decompose, Navigation 2.x | Compose-first : back stack en state (`NavBackStack<NavKey>`), rendu single-pane via `NavDisplay(backStack, onBack, entryDecorators, entryProvider { entry<…> })`, multi-pane via `ListDetailPaneScaffold` (M3 Adaptive). Cf. [ADR-008]({{ site.baseurl }}/adr/008-compose-navigation-3). |
| DI | **Hilt (KSP)** | Koin | Erreurs à la compilation, intégration Jetpack, standard contributeurs |
| HTTP | **OkHttp 5** (5.3+) | Retrofit, Ktor | Pas d'API REST à mapper, scraping HTML direct + cookies. Stable depuis 07/2025 (`callTimeout` via `kotlin.time.Duration`, `mockwebserver3`). |
| Parsing HTML | **Jsoup** | Regex, custom parser | Standard JVM, CSS selectors, battle-tested |
| Cache locale | **Room** | DataStore, SQLDelight | Standard Android, intégration Flow, migrations |
| Stockage sécurisé | **DataStore + Keystore** (cookies HFR, pas de password stocké) | EncryptedSharedPreferences (**déprécié**), Tink (overkill 1 secret) | Décision Option A : re-login manuel à l'expiration session. Cf. [ADR-002]({{ site.baseurl }}/adr/002-credentials-option-a). |
| Images | **Coil 3+** | Glide | Natif Compose, coroutines, plus idiomatique Kotlin |
| Async | **Coroutines + Flow** | RxJava | Standard Kotlin, plus léger, meilleure intégration Compose |
| Enforcement archi | **Konsist** | ArchUnit | Kotlin-first, voit les sealed/data/internal ; ArchUnit = bytecode-only, perd la finesse Kotlin |
| Style + deprecations | **Detekt** | ktlint | Plus riche, règles custom possibles |
| A11y + i18n + correctness | **Android Lint** (natif) | — | Déjà présent, config `lintOptions` |
| Screenshot testing | **Non retenu MVP** (Roborazzi reconsidéré Phase 4+) | — | Compose Preview + review manuelle suffisent en Phase 1-3 |
| minSdk | **29** | 26, 31 | Android 10 : Scoped Storage, TLS 1.3, dark thème natif, ~88-90% parc 04/2026 |

> **Versions précises** : le Gradle version catalog `gradle/libs.versions.toml` sera créé en Phase 0 comme source de vérité unique. Ce tableau garde les versions **major.minor** quand elles sont structurelles (Material 3 Adaptive 1.2+ pour les canonical layouts, Compose Navigation 3 pour les back stacks en state, OkHttp 5 pour le client HTTP + `CookieJar`). Les patches stables 2026 sont à résoudre via Context7/Docfork quand on interroge les docs officielles (cf. [#19](https://github.com/ForumHFR/redface2/issues/19)).

---

## Détail des choix

### Kotlin

Pas de débat ici. Google a déclaré Kotlin "preferred language" pour Android en 2019. Java est toujours supporté mais toutes les nouvelles APIs, les exemples officiels et les bibliothèques modernes sont Kotlin-first.

Avantages concrets pour Redface 2 :
- **Null safety** : fini les NPE sur des champs HTML manquants
- **Coroutines** : async propre sans callback hell (adieu RxJava)
- **Extension functions** : enrichir les types Android sans sous-classes
- **Data classes** : modèles domaine en une ligne
- **Sealed classes** : MVI Intents et Effects type-safe

### Jetpack Compose

Le toolkit UI déclaratif de Google. Remplace XML layouts + `findViewById` + ButterKnife + les adapters RecyclerView.

```kotlin
// Avant (XML + Java)
TextView textView = findViewById(R.id.post_content);
textView.setText(post.getContent());

// Après (Compose)
@Composable
fun PostBody(post: Post) {
    PostRenderer(content = post.content)
}
```

Pour un forum reader, Compose apporte :
- **LazyColumn** : équivalent de RecyclerView mais déclaratif, gère des milliers de posts
- **Recomposition intelligente** : seuls les composants dont l'état change sont redessinés
- **Theming Material 3** : dark mode, dynamic colors, typographie
- **Preview** : voir le rendu directement dans l'IDE

### MVI plutôt que MVVM

MVVM (Model-View-ViewModel) est le pattern Android classique. MVI (Model-View-Intent) ajoute une contrainte : **le flux de données est unidirectionnel**.

```
MVVM : View ↔ ViewModel ↔ Model  (bidirectionnel, état dispersé)
MVI  : Intent → ViewModel → State → View  (unidirectionnel, état centralisé)
```

Pour un forum reader, MVI est supérieur :
- L'état d'un écran "Topic" est complexe (posts, page, loading, erreur, scroll position)
- Les actions utilisateur sont bien définies (charger page, quoter, répondre, flag)
- Le debugging est simple : on inspecte l'état, on rejoue les intents
- Les tests sont des fonctions pures : intent + state actuel → nouveau state

### Décisions design (tranchées [#9](https://github.com/ForumHFR/redface2/issues/9))

Les 4 choix design de base sont actés pour Phase 0 :

| Décision | Valeur | Pourquoi |
|---|---|---|
| Seed color HFR | `#A62C2C` (rouge brique HFR) | Cohérent avec le nom "Redface". Material Theme Builder génère tout le `ColorScheme` depuis cette seed. À revoir si le naming final (#1) s'en écarte. |
| Dynamic color par défaut | **OFF** (opt-in settings Phase 5) | Préserve l'identité visuelle HFR constante ; Material You reste disponible via toggle utilisateur |
| Font family | **Roboto** (système Android) | 0 KB APK impact. Roboto Flex / Inter = trendy sans bénéfice technique pour une app forum |
| Rendu de posts | **`PostContent` AST** + `AnnotatedString` inline + composables block | Contrat cible acté, implémentation Phase 1. HTML HFR et BBCode éditeur convergent vers la même AST, avec le parser BBCode différable jusqu'à l'éditeur Phase 2. Cf. [ADR-011]({{ site.baseurl }}/adr/011-postcontent-ast) et [#3](https://github.com/ForumHFR/redface2/issues/3). |
| Thèmes v1 | **Clair, Sombre, AMOLED** | Material You et HFR Classique reportés Phase 5 polish (1-2 jours d'ajout chacun, pas d'imbrication architecturale) |

Le draft `drafts/material3-ui-ux.md` contient les détails étendus (30 color roles, 15 typography styles, motion tokens, adaptive layouts) — c'est un document de référence pédagogique, pas une spec canonique.

### Material 3 Adaptive (tablettes, pliables, desktop Android)

Depuis Material 3 Adaptive 1.0 stable (oct. 2024, actuellement 1.2.0), Google expose trois **canonical layouts** + un scaffold adaptatif :

| API | Usage dans Redface 2 |
|---|---|
| `NavigationSuiteScaffold` (artifact `material3-adaptive-navigation-suite`) | Remplace conditionnellement `NavigationBar` (Compact) / `NavigationRail` (Medium) / `PermanentNavigationDrawer` (Expanded) en fonction de `WindowSizeClass`. Utilisé dans `MainActivity`. **Caveat** : ce scaffold ne consomme pas les status bars en mode edge-to-edge — les écrans contenus doivent ajouter `Modifier.statusBarsPadding()` (ou un `Scaffold` interne qui consomme les insets) sinon le contenu passe sous la barre de statut. |
| `ListDetailPaneScaffold` | Écran Drapeaux → Topic : liste à gauche + détail à droite en Medium/Expanded, stack classique en Compact. |
| `SupportingPaneScaffold` | Éditeur Compact : contenu à gauche + preview BBCode à droite sur tablette. |
| `WindowSizeClass` | Breakpoints standards : Compact (< 600dp), Medium (600–840dp), Expanded (≥ 840dp). |

Tous ces composants sont annotés `@ExperimentalMaterial3AdaptiveApi` ou `@ExperimentalMaterial3Api` — à `@OptIn` explicitement.

### Edge-to-edge Android 15+

Sur API ≥ 35 (le projet vise `targetSdk = 36`, soumis à la même contrainte), Android impose l'edge-to-edge par défaut. Appeler `enableEdgeToEdge()` (artifact `androidx.activity 1.10+`) dans `MainActivity.onCreate()` avant `setContent`. Les composants M3 (`TopAppBar`, `NavigationBar`, `BottomAppBar`, `Scaffold`) consomment les insets automatiquement ; les écrans custom utilisent `Modifier.navigationBarsPadding()` / `imePadding()`.

### Predictive back

Android 14+ propose des animations de retour prévisuelles. En Compose :

- `PredictiveBackHandler` (Compose) pour gérer la progression custom
- `BackHandler` pour un callback standard
- Manifest : `android:enableOnBackInvokedCallback="true"` dans `<application>`

Les écrans de navigation standard n'ont pas besoin de custom — Compose Navigation 3 intègre nativement `PredictiveBackHandler` via `NavDisplay`.

### Compose Navigation 3 (pas Circuit, pas Decompose, pas Nav 2.x)

Quatre options évaluées :

| | **Navigation 3** | Navigation 2.x | Circuit (Slack) | Decompose |
|---|---|---|---|---|
| Paradigme | Compose-first, back stack en state | Fragment-inspired, graph DSL | Presenter pattern | Component tree |
| Deep linking | Parsing URI manuel → route typée | `NavDeepLink` DSL | Manuel | Manuel |
| Type safety | `@Serializable` + `NavKey` | `@Serializable` + `toRoute()` (2.8+) | Oui | Oui |
| Back stack | Explicite `NavBackStack<NavKey>` en `State` | Opaque (framework-managed) | Bon | Excellent |
| M3 Adaptive | Intégration native (`ListDetailPaneScaffold` proprement binding) | Bricolage | Manuel | Manuel |
| Shared Elements | Compatible avec `SharedTransitionScope` côté Compose ; pattern non encore exploité dans Redface 2 (Phase 5+) | Limité | Manuel | Manuel |
| Stabilité | **1.1.0 stable** (08/04/2026) | Mature | Stable | Stable |
| Courbe | Modérée, API plus simple qu'avant | Modérée | Raide | Raide |
| KMP | Runtime KMP ; UI Android-first | Non | Oui | Oui |

**Compose Navigation 3 gagne** pour Redface 2 :
- **Compose-first** : cohérent avec 100% Compose ; le back stack est du state observable normal, on peut le persist/restaurer trivialement
- **API stable simple** : `NavDisplay(backStack, onBack, entryDecorators, entryProvider { entry<…> })` couvre déjà single-pane + predictive back + lifecycle, sans DSL graph à apprendre
- **Plusieurs back stacks indépendants** : un par onglet de bottom nav (`rememberNavBackStack`), commutés via `NavigationSuiteScaffold` ; chaque onglet conserve son historique de navigation
- **M3 Adaptive** : `ListDetailPaneScaffold` (essentiel pour drapeaux/topic en tablette) se branche directement sur le même back stack que single-pane
- **Type safety** : les routes implémentent un sealed interface `RedfaceNavKey : NavKey` `@Serializable`, donc le back stack reste typé et sérialisable
- **Deep linking** : HFR ayant des fragments URI non supportés (`#t{numreponse}`) de toute façon, on parse la `Uri` entrante dans `RedfaceApp`, on identifie l'onglet cible, et on **réinitialise** le back stack de cet onglet via `resetStack(root, route)` pour que le retour ramène à la racine de l'onglet — voir `navigation.md` pour le code exact

Voir `docs/specs/navigation.md` pour les exemples concrets (`NavDisplay`, `entryProvider`, deep linking + `resetStack`, predictive back) et [ADR-008]({{ site.baseurl }}/adr/008-compose-navigation-3) pour la décision.

### Hilt plutôt que Koin

| | Hilt | Koin |
|---|---|---|
| Validation | **Compilation** (erreurs avant le runtime) | Runtime (crash en prod) |
| Build time | Bon avec KSP (plus de KAPT) | Léger |
| Integration Android | ViewModel, WorkManager, Navigation — tout cable | Manuel |
| Contributeurs | Standard reconnu, doc Google | Moins répandu |
| Cold start | Aucun overhead runtime | ~200ms sur grosse app |

Hilt avec KSP (pas KAPT) résout le problème historique de build time. La sécurité à la compilation et l'intégration native avec Jetpack font la différence pour un projet open-source.

Le bootstrap de code Phase 0 s'aligne actuellement sur **Hilt 2.59.2** dans le version catalog. Cette version sert de référence d'implémentation tant que le couple Kotlin/AGP 9 reste en place.

**Note** : Koin a évolué significativement. Le [compiler plugin K2](https://insert-koin.io/docs/reference/koin-annotations/start) (1.0.0-RC1) permet la génération du graphe de DI à la compilation, éliminant le risque de crash runtime. Koin est également KMP-natif. Si le projet évolue vers KMP, Koin deviendra le choix naturel. Hilt reste le choix pour la v1 Android-only grâce à son intégration Jetpack et sa base de contributeurs plus large.

### Perspectives KMP

La stack actuelle est Android-only. Cependant, l'architecture est conçue pour faciliter une migration KMP future :

- `:core:model` et `:core:domain` sont purs Kotlin/JVM, sans dépendance Android
- `:core:parser` utilise Jsoup (JVM-only), mais [Ksoup](https://github.com/fleeksoft/ksoup) (v0.2.6, API compatible Jsoup, KMP-natif) est une alternative crédible à valider
- Le passage KMP serait un refactor de dépendances, pas une réécriture

La décision KMP est reportée post-v1, confirmée par les retours communautaires (Corran Horn, ezzz).

### OkHttp 5 direct (sans Retrofit)

Choix contre-intuitif. Retrofit est le standard Android pour le réseau. Mais Retrofit ajoute de la valeur quand on consomme une **API REST structurée** avec des endpoints types.

HFR n'a pas d'API. Redface fait du **scraping HTML** :
- `GET /forum1.php?cat=13&post=12345&page=3` → HTML brut à parser
- `POST /bddpost.php` → formulaire avec champs cachés

Avec Retrofit, on définirait des interfaces qui retournent `ResponseBody`... pour ensuite parser le HTML manuellement. Autant utiliser OkHttp directement avec une couche d'abstraction propre.

```kotlin
// Ce qu'on ferait avec Retrofit (inutilement verbeux)
@GET("forum1.php")
suspend fun getTopicPage(
    @Query("cat") cat: Int,
    @Query("post") post: Int,
    @Query("page") page: Int,
): ResponseBody  // ... puis parser le HTML

// Ce qu'on fait avec OkHttp (direct)
suspend fun getTopicPage(cat: Int, post: Int, page: Int): Document {
    val url = baseUrl.newBuilder()
        .addPathSegment("forum1.php")
        .addQueryParameter("cat", cat.toString())
        .addQueryParameter("post", post.toString())
        .addQueryParameter("page", page.toString())
        .build()
    return client.newCall(Request.Builder().url(url).build())
        .await()
        .use { Jsoup.parse(it.body.string()) }
}
```

OkHttp fournit aussi le **CookieJar** pour la gestion de session HFR — essentiel pour l'authentification.

**Version retenue (04/2026)** : **OkHttp 5.3+** — stable depuis 07/2025, avec des gains concrets vs 4.x : Happy Eyeballs (dual-stack IPv4/IPv6), DoH opt-in, `callTimeout()` via `kotlin.time.Duration`, `mockwebserver3` aligné avec le test runner. KMP reste reporté post-v1 ([#2](https://github.com/ForumHFR/redface2/issues/2)) pour des raisons de scope ; ce report n'est pas lié à une incompatibilité d'OkHttp 5, publié comme projet Kotlin Multiplatform. API Interceptor/CookieJar API-compatible avec 4.x — pas de dette de migration à prévoir puisqu'on démarre neuf. Décision formalisée dans [ADR-009]({{ site.baseurl }}/adr/009-okhttp-5-3-plus).

### Jsoup

Standard incontesté pour le parsing HTML sur la JVM. CSS selectors, manipulation DOM, robuste face au HTML malformed (et celui de HFR l'est).

```kotlin
// Extraire les posts d'une page HFR
val posts = document.select("table.messagetable").map { table ->
    Post(
        author = table.select(".s2 b").text(),
        content = table.select("div[id^=para]").html(),
        date = table.select(".toolbar .s2").last()?.text() ?: "",
    )
}
```

### Room

Base de données locale pour le cache et le stockage persistant :
- **Cache des topics** : relecture instantanée sans réseau
- **MPStorage** : cache locale des données synchronisées via le MP de stockage HFR (drapeaux MultiMP, bookmarks)
- **Bookmarks** : signets locaux sur des posts
- **Préférences** : réglages utilisateur

Room s'intègre nativement avec **Flow** pour des données réactives :

```kotlin
@Query("SELECT * FROM flagged_topics ORDER BY last_date DESC")
fun observeFlags(): Flow<List<FlaggedTopicEntity>>
```

### Coil

Chargeur d'images conçu pour Compose et les coroutines. Plus léger et plus idiomatique que Glide pour un projet Kotlin-first.

Utilisé pour :
- Avatars des utilisateurs
- Images dans les posts
- **Smileys HFR** (cache agressif, ils ne changent jamais)
- Previews d'images en plein écran

### minSdk 29 (Android 10)

Analyse détaillée dans [l'issue #241 de Redface v1](https://github.com/ForumHFR/Redface/issues/241).

Pourquoi 29 et pas moins :
- **Scoped Storage** disponible (opt-in) — pas besoin de permission stockage
- **TLS 1.3 garanti** — sécurité réseau sans configuration
- **Dark thème natif** — `isSystemInDarkTheme()` fonctionne
- **Supprime multidex** — build plus simple
- **Biometric API** — pour sécuriser le login HFR

Pourquoi pas 31+ :
- 29 couvre **~88–90% des appareils actifs** en 2026 (source : [Android distribution dashboard](https://developer.android.com/about/dashboards), [apilevels.com](https://apilevels.com/))
- Pas de gain majeur entre 29 et 31 pour notre use case
- Monter à 31+ exclurait encore 8–10% d'appareils sans bénéfice technique tangible

---

## Objectifs de performance

| Métrique | Cible |
|----------|-------|
| Cold start | < 1.5s |
| Scroll FPS | 60fps constant (120fps sur appareils compatibles) |
| Chargement topic (cache) | < 100ms |
| Chargement topic (réseau) | < 2s |
| Taille APK | < 15MB |
| Mémoire max | < 200MB |

### Stratégie mémoire

Pour tenir les objectifs mémoire, en particulier sur les appareils bas de gamme :

- **Coil** : `ImageLoader` custom avec `memoryCache { maxSizePercent(context, 0.15) }` et `diskCache { maxSizeBytes(100L * 1024 * 1024) }` (100 MB disque)
- **LazyColumn** : `key(post.numreponse)` et `contentType` sur chaque item pour optimiser le recyclage Compose
- **Cache Room** : LRU sur les pages de topic — max 50 pages en cache, éviction par date d'accès
- **Images dans les posts** : thumbnails dans la liste, pleine résolution uniquement en plein écran

Profiling en debug avec **LeakCanary** et **StrictMode**. Optimisation du cold start avec **Baseline Profiles**.
