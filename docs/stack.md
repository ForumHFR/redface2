---
title: Stack technique
nav_order: 2
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
| UI | **Jetpack Compose** | XML layouts | Direction officielle Google, déclaratif, plus maintenable |
| Architecture | **MVI** | MVVM | Flux unidirectionnel, état prévisible, idéal pour un forum reader |
| Navigation | **Compose Navigation** | Circuit, Decompose | Deep linking natif, type-safe (v2.8+), back stack solide |
| DI | **Hilt (KSP)** | Koin | Erreurs à la compilation, intégration Jetpack, standard contributeurs |
| HTTP | **OkHttp 4** | Retrofit, Ktor | Pas d'API REST à mapper, scraping HTML direct + cookies |
| Parsing HTML | **Jsoup** | Regex, custom parser | Standard JVM, CSS selectors, battle-tested |
| Cache locale | **Room** | DataStore, SQLDelight | Standard Android, intégration Flow, migrations |
| Images | **Coil** | Glide | Natif Compose, coroutines, plus idiomatique Kotlin |
| Async | **Coroutines + Flow** | RxJava | Standard Kotlin, plus léger, meilleure intégration Compose |
| minSdk | **29** | 26, 31 | Android 10 : Scoped Storage, TLS 1.3, dark thème natif |

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
fun PostContent(post: Post) {
    Text(text = post.content)
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

### Compose Navigation (pas Circuit, pas Decompose)

Trois options évaluées :

| | Compose Navigation | Circuit (Slack) | Decompose |
|---|---|---|---|
| Deep linking | Natif, first-class | Manuel | Manuel |
| Type safety | Oui (v2.8+, sérialisable routes) | Oui | Oui |
| Back stack | Solide, géré par le framework | Bon | Excellent |
| Courbe d'apprentissage | Modérée, bien documentée | Raide (pattern Presenter) | Raide (component tree) |
| Communauté | Énorme (Google) | Moyenne (Slack) | Petite |
| KMP | Non (Android only) | Oui | Oui |

**Compose Navigation gagne** pour Redface 2 :
- Le deep linking est critique : les URLs HFR (`forum.hardware.fr/forum1.php?cat=13&post=12345&page=3`) doivent ouvrir directement le bon écran
- Pas besoin de KMP (on reste Android natif)
- La plus grande base de contributeurs potentiels connait déjà ce framework
- Les type-safe routes (v2.8+) éliminent les strings magiques

### Hilt plutôt que Koin

| | Hilt | Koin |
|---|---|---|
| Validation | **Compilation** (erreurs avant le runtime) | Runtime (crash en prod) |
| Build time | Bon avec KSP (plus de KAPT) | Léger |
| Integration Android | ViewModel, WorkManager, Navigation — tout cable | Manuel |
| Contributeurs | Standard reconnu, doc Google | Moins répandu |
| Cold start | Aucun overhead runtime | ~200ms sur grosse app |

Hilt avec KSP (pas KAPT) résout le problème historique de build time. La sécurité à la compilation et l'intégration native avec Jetpack font la différence pour un projet open-source.

### OkHttp 4 direct (sans Retrofit)

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

**Note** : OkHttp 5 (Kotlin-first, meilleur support coroutines) sera évalué au moment du bootstrap (Phase 0). Si la version stable est disponible, elle sera adoptée directement. La migration depuis OkHttp 4 est mineure (API compatible).

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
- 29 couvre **96%+ des appareils actifs** en 2026
- Pas de gain majeur entre 29 et 31 pour notre use case

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
