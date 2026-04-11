---
title: Stack technique
nav_order: 2
---

# Stack technique
{: .fs-8 }

Chaque choix a ete evalue, compare et verrouille. Voici le detail.
{: .fs-5 .fw-300 }

---

## Vue d'ensemble

| Brique | Choix | Alternative ecartee | Raison |
|--------|-------|-------------------|--------|
| Langage | **Kotlin** | Java | Standard Android depuis 2019, null safety, coroutines |
| UI | **Jetpack Compose** | XML layouts | Direction officielle Google, declaratif, plus maintenable |
| Architecture | **MVI** | MVVM | Flux unidirectionnel, etat previsible, ideal pour un forum reader |
| Navigation | **Compose Navigation** | Circuit, Decompose | Deep linking natif, type-safe (v2.8+), back stack solide |
| DI | **Hilt (KSP)** | Koin | Erreurs a la compilation, integration Jetpack, standard contributeurs |
| HTTP | **OkHttp 4** | Retrofit, Ktor | Pas d'API REST a mapper, scraping HTML direct + cookies |
| Parsing HTML | **Jsoup** | Regex, custom parser | Standard JVM, CSS selectors, battle-tested |
| Cache locale | **Room** | DataStore, SQLDelight | Standard Android, integration Flow, migrations |
| Images | **Coil** | Glide | Natif Compose, coroutines, plus idiomatique Kotlin |
| Async | **Coroutines + Flow** | RxJava | Standard Kotlin, plus leger, meilleure integration Compose |
| minSdk | **29** | 26, 31 | Android 10 : Scoped Storage, TLS 1.3, dark theme natif |

---

## Detail des choix

### Kotlin

Pas de debat ici. Google a declare Kotlin "preferred language" pour Android en 2019. Java est toujours supporte mais toutes les nouvelles APIs, les exemples officiels et les bibliotheques modernes sont Kotlin-first.

Avantages concrets pour Redface 2 :
- **Null safety** : fini les NPE sur des champs HTML manquants
- **Coroutines** : async propre sans callback hell (adieu RxJava)
- **Extension functions** : enrichir les types Android sans sous-classes
- **Data classes** : modeles domaine en une ligne
- **Sealed classes** : MVI Intents et Effects type-safe

### Jetpack Compose

Le toolkit UI declaratif de Google. Remplace XML layouts + `findViewById` + ButterKnife + les adapters RecyclerView.

```kotlin
// Avant (XML + Java)
TextView textView = findViewById(R.id.post_content);
textView.setText(post.getContent());

// Apres (Compose)
@Composable
fun PostContent(post: Post) {
    Text(text = post.content)
}
```

Pour un forum reader, Compose apporte :
- **LazyColumn** : equivalent de RecyclerView mais declaratif, gere des milliers de posts
- **Recomposition intelligente** : seuls les composants dont l'etat change sont redessinés
- **Theming Material 3** : dark mode, dynamic colors, typographie
- **Preview** : voir le rendu directement dans l'IDE

### MVI plutot que MVVM

MVVM (Model-View-ViewModel) est le pattern Android classique. MVI (Model-View-Intent) ajoute une contrainte : **le flux de donnees est unidirectionnel**.

```
MVVM : View ↔ ViewModel ↔ Model  (bidirectionnel, etat disperse)
MVI  : Intent → ViewModel → State → View  (unidirectionnel, etat centralise)
```

Pour un forum reader, MVI est superieur :
- L'etat d'un ecran "Topic" est complexe (posts, page, loading, erreur, scroll position)
- Les actions utilisateur sont bien definies (charger page, quoter, repondre, flag)
- Le debugging est simple : on inspecte l'etat, on rejoue les intents
- Les tests sont des fonctions pures : intent + state actuel → nouveau state

### Compose Navigation (pas Circuit, pas Decompose)

Trois options evaluees :

| | Compose Navigation | Circuit (Slack) | Decompose |
|---|---|---|---|
| Deep linking | Natif, first-class | Manuel | Manuel |
| Type safety | Oui (v2.8+, serializable routes) | Oui | Oui |
| Back stack | Solide, gere par le framework | Bon | Excellent |
| Courbe d'apprentissage | Moderee, bien documentee | Raide (pattern Presenter) | Raide (component tree) |
| Communaute | Enorme (Google) | Moyenne (Slack) | Petite |
| KMP | Non (Android only) | Oui | Oui |

**Compose Navigation gagne** pour Redface 2 :
- Le deep linking est critique : les URLs HFR (`forum.hardware.fr/forum1.php?cat=13&post=12345&page=3`) doivent ouvrir directement le bon ecran
- Pas besoin de KMP (on reste Android natif)
- La plus grande base de contributeurs potentiels connait deja ce framework
- Les type-safe routes (v2.8+) eliminent les strings magiques

### Hilt plutot que Koin

| | Hilt | Koin |
|---|---|---|
| Validation | **Compilation** (erreurs avant le runtime) | Runtime (crash en prod) |
| Build time | Bon avec KSP (plus de KAPT) | Leger |
| Integration Android | ViewModel, WorkManager, Navigation — tout cable | Manuel |
| Contributeurs | Standard reconnu, doc Google | Moins repandu |
| Cold start | Aucun overhead runtime | ~200ms sur grosse app |

Hilt avec KSP (pas KAPT) resout le probleme historique de build time. La securite a la compilation et l'integration native avec Jetpack font la difference pour un projet open-source.

### OkHttp 4 direct (sans Retrofit)

Choix contre-intuitif. Retrofit est le standard Android pour le reseau. Mais Retrofit ajoute de la valeur quand on consomme une **API REST structuree** avec des endpoints types.

HFR n'a pas d'API. Redface fait du **scraping HTML** :
- `GET /forum1.php?cat=13&post=12345&page=3` → HTML brut a parser
- `POST /bddpost.php` → formulaire avec champs caches

Avec Retrofit, on definirait des interfaces qui retournent `ResponseBody`... pour ensuite parser le HTML manuellement. Autant utiliser OkHttp directement avec une couche d'abstraction propre.

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

### Jsoup

Standard inconteste pour le parsing HTML sur la JVM. CSS selectors, manipulation DOM, robuste face au HTML malformed (et celui de HFR l'est).

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

Base de donnees locale pour le cache et le stockage persistant :
- **Cache des topics** : relecture instantanee sans reseau
- **MPStorage** : tracking lu/non-lu des MultiMPs (pas natif HFR)
- **Bookmarks** : signets locaux sur des posts
- **Preferences** : reglages utilisateur

Room s'integre nativement avec **Flow** pour des donnees reactives :

```kotlin
@Query("SELECT * FROM flagged_topics ORDER BY last_date DESC")
fun observeFlags(): Flow<List<FlaggedTopicEntity>>
```

### Coil

Chargeur d'images concu pour Compose et les coroutines. Plus leger et plus idiomatique que Glide pour un projet Kotlin-first.

Utilise pour :
- Avatars des utilisateurs
- Images dans les posts
- **Smileys HFR** (cache agressif, ils ne changent jamais)
- Previews d'images en plein ecran

### minSdk 29 (Android 10)

Analyse detaillee dans [l'issue #241 de Redface v1](https://github.com/ForumHFR/Redface/issues/241).

Pourquoi 29 et pas moins :
- **Scoped Storage** disponible (opt-in) — pas besoin de permission stockage
- **TLS 1.3 garanti** — securite reseau sans configuration
- **Dark theme natif** — `isSystemInDarkTheme()` fonctionne
- **Supprime multidex** — build plus simple
- **Biometric API** — pour securiser le login HFR

Pourquoi pas 31+ :
- 29 couvre **96%+ des appareils actifs** en 2026
- Pas de gain majeur entre 29 et 31 pour notre use case

---

## Objectifs de performance

| Metrique | Cible |
|----------|-------|
| Cold start | < 1.5s |
| Scroll FPS | 60fps constant (120fps sur appareils compatibles) |
| Chargement topic (cache) | < 100ms |
| Chargement topic (reseau) | < 2s |
| Taille APK | < 15MB |
| Memoire max | < 200MB |
