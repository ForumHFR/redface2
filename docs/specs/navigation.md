---
title: Navigation
parent: Spécifications
nav_order: 4
permalink: /specs/navigation
mermaid: true
---

# Navigation
{: .fs-8 }

Écrans, flows, deep linking et bottom navigation.
{: .fs-5 .fw-300 }

---

## Bottom Navigation

L'application utilise une barre de navigation en bas avec 4 onglets principaux + les réglages accessibles depuis chaque écran.

```
┌───────────┬───────────┬───────────┬───────────┐
│  Drapeaux │  Forum    │  Recherche│  Messages  │
│  (accueil)│           │           │            │
└───────────┴───────────┴───────────┴────────────┘
```

**Drapeaux** est l'écran d'accueil. C'est le point d'entrée principal — la plupart des utilisateurs HFR ouvrent l'app pour vérifier "quoi de neuf sur mes topics suivis".

---

## Navigation Graph

```mermaid
graph TB
    LOGIN[Auth / Login] --> HOME

    subgraph HOME["Bottom Navigation"]
        FLAGS["Drapeaux (accueil)"]
        FORUM[Forum]
        SEARCH[Recherche]
        MSGS[Messages]
    end

    FLAGS -->|"tri: date / catégorie"| FLAGS
    FLAGS -->|"filtre: tous / cyan / favori / rouge"| FLAGS
    FLAGS --> TOPIC

    FORUM --> CATS[Catégories]
    CATS --> SUBCATS[Sous-catégories]
    SUBCATS --> TOPICLIST[Liste de topics]
    TOPICLIST --> TOPIC
    TOPICLIST --> NEWTOPIC["Créer un topic"]

    SEARCH --> RESULTS[Résultats]
    RESULTS --> TOPIC

    MSGS --> TABMP["MPs classiques"]
    MSGS --> TABMULTI["MultiMPs (vue drapeaux)"]
    TABMP --> CONV[Conversation]
    TABMP --> NEWMP["Nouveau MP"]
    TABMULTI --> CONVMULTI["Conversation groupe"]
    TABMULTI --> NEWMULTI["Nouveau MultiMP"]
    CONV --> REPLYMP[Reply MP]
    CONVMULTI --> REPLYMULTI[Reply MultiMP]
    CONVMULTI --> QUOTEMULTI["Quote → Reply"]

    TOPIC --> REPLY[Reply]
    TOPIC --> EDIT["Edit post"]
    TOPIC --> EDITFP["Edit FP (sujet, contenu, sondage)"]
    TOPIC --> QUOTE["Quote → Reply"]
    TOPIC --> IMAGE[ImageViewer fullscreen]

    NEWTOPIC --> TOPIC

    style FLAGS fill:#00bcd4,color:#fff
    style FORUM fill:#4caf50,color:#fff
    style SEARCH fill:#ff9800,color:#fff
    style MSGS fill:#9c27b0,color:#fff
    style TOPIC fill:#e74c3c,color:#fff
```

> **Lecture du graphe** : ce diagramme décrit le **flow utilisateur**, pas le découpage en `NavKey`. Les huit routes typées réelles sont `FlagsListRoute`, `ForumRoute`, `CategoryRoute`, `TopicRoute`, `SearchRoute`, `MessagesRoute`, `PostEditorRoute`, `TopicFormRoute` (cf. § Implémentation ci-dessous). Plusieurs nœuds du graphe sont des **states internes au screen** plutôt que des routes distinctes : `TABMP` / `TABMULTI` correspondent à `MessageTab.CLASSIC` / `MessageTab.MULTI` dans le `MessagesState` ; `CATS` / `SUBCATS` / `TOPICLIST` sont couverts par la même `CategoryRoute(cat, subcat?, page)`. Le mapping flow → routes typées est explicite dans le code de `entryProvider` plus bas.

---

## Écrans en détail

### Drapeaux (accueil)

L'écran le plus important de l'app. Affiche les topics suivis par l'utilisateur.

**Tri :**
- **Par date** (défaut) : tous les topics mélangés, triés par dernier message
- **Par catégorie** : groupes par cat/subcat, chaque groupe trie par date

**Filtres :**
- **Tous** : tous les drapeaux confondus
- **Cyan** : topics où l'utilisateur a participé
- **Favori** : topics marqués d'une étoile jaune
- **Rouge** : topics lus uniquement (drapeau de lecture sans participation)

**Actions sur un topic :**
- Tap → ouvrir le topic à la dernière position non lue
- Long press → menu contextuel (retirer drapeau, copier URL, partager)
- Swipe → retirer le drapeau (avec undo)

### Topic (lecture)

L'écran central de l'app. Affiche les posts d'un topic avec pagination.

**Navigation dans le topic :**
- Scroll vertical pour lire les posts
- Boutons page précédente / suivante
- Saut direct à une page (champ numéro)
- Saut au premier / dernier post
- Indicateur de page courante / total

**Actions sur un post :**
- **Quoter** → ouvre l'éditeur avec la citation pré-remplie
- **Editer** (si c'est notre post) → ouvre l'éditeur avec le contenu actuel
- **Editer le FP** (si `isFirstPostOwner`) → éditeur spécial avec sujet + sondage
- **Copier le texte**
- **Voir l'image en plein écran**
- **Partager le lien du post**

### Forum (catégories)

Navigation hiérarchique dans le forum.

```
Catégories
  └── Hardware
       ├── HFR
       ├── Overclocking
       └── ...
  └── Programmation
       ├── C/C++
       ├── Java
       └── ...
```

Chaque catégorie affiche le nombre de topics et l'activité récente.

### Création de topic

Formulaire complet :
- **Catégorie** : sélecteur hiérarchique
- **Sous-catégorie** : dépend de la catégorie choisie
- **Sujet** : titre du topic
- **Contenu** : éditeur BBCode avec toolbar
- **Sondage** (optionnel) : question + options + choix multiple oui/non
- **Preview** : avant-première du rendu du BBCode

### Messages

Deux onglets :

**MPs classiques :**
- Inbox : liste des conversations 1-to-1, triées par date
- Chaque MP affiche : sujet, correspondant, date, lu/non-lu
- Nouveau MP : destinataire + sujet + contenu

**MultiMPs :**
- Vue style drapeaux : fils de groupe triés par dernier message
- État lu/non-lu géré via **MPStorage** (données synchronisées depuis un MP HFR dédié, cachées en Room)
- Chaque MultiMP se comporte comme un topic : pagination, quote, reply
- Nouveau MultiMP : destinataires (2+) + sujet + contenu

### Recherche

- Recherche dans les topics (titre) et dans les posts (contenu)
- Filtres : catégorie, auteur, date
- Résultats avec preview du contexte

---

## Deep Linking

Les URLs HFR doivent ouvrir directement le bon écran dans l'app.

| Pattern URL | Écran cible | Statut |
|-------------|-------------|--------|
| `forum.hardware.fr/forum1.php?cat=X&post=Y&page=Z` | Topic page Z | Phase 1 |
| `forum.hardware.fr/forum1.php?cat=X&post=Y` | Topic page 1 | Phase 1 |
| `forum.hardware.fr/forum2.php?config=hfr.inc&cat=X&subcat=Y` | Liste topics | Phase 1 |
| `forum.hardware.fr/forum1f.php` | Drapeaux | Phase 1 |
| `forum.hardware.fr/forum1.php?cat=X&post=Y#t12345` | Post spécifique (traitement custom, voir ci-dessous) | Phase 1 |
| `forum.hardware.fr/forum2.php?config=hfr.inc&cat=prive&page=Z` ⚠️ pattern non vérifié | Inbox MP / conversation | Phase 3 — à confirmer sur MP réel |

> **Patterns Phase 3 — à valider sur HFR réel** : la ligne `cat=prive` ci-dessus est une **hypothèse à confirmer**, pas un contrat. Aucun MP réel n'a été observé sur HFR pour valider la forme exacte de l'URL (présence ou non de `&page`, encodage du `post={mp_id}`, comportement quand l'utilisateur n'est pas connecté, etc.). Le pattern sera capturé via `hfr-mcp` au démarrage du cycle `:feature:messages` (Phase 3) et la table mise à jour à ce moment-là. Le code de `parseHfrDeepLink` ignore aujourd'hui ces URLs — elles retombent sur le `else -> null` et l'app ouvre l'écran d'accueil par défaut.

Implémentation via **Compose Navigation 3** (1.1.0+, stable depuis 08/04/2026). Les routes sont des types `@Serializable` qui implémentent un sealed interface marqueur `RedfaceNavKey : NavKey` :

```kotlin
// app/src/main/kotlin/.../navigation/RedfaceNavigation.kt
@Serializable sealed interface RedfaceNavKey : NavKey

@Serializable data object FlagsListRoute : RedfaceNavKey
@Serializable data object ForumRoute : RedfaceNavKey
@Serializable data object SearchRoute : RedfaceNavKey
@Serializable data object MessagesRoute : RedfaceNavKey
@Serializable data class CategoryRoute(
    val cat: Int,
    val subcat: Int? = null,
    val page: Int = 1,
) : RedfaceNavKey
@Serializable data class TopicRoute(
    val cat: Int,
    val post: Int,
    val page: Int = 1,
    val scrollTo: Int? = null,            // numreponse cible pour #t{numreponse}
) : RedfaceNavKey
@Serializable data class PostEditorRoute(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int? = null,             // requis pour Reply (Phase 2C)
    val numreponse: Int? = null,          // requis à terme pour Edit
    val page: Int? = null,                // page topic en cours, requis Reply (#145)
    val subcat: Int? = null,              // sous-cat HFR, requis Reply (#145) — TopicScreen ne pousse PostEditorRoute que si topic.hasSubcat
) : RedfaceNavKey

@Serializable data class TopicFormRoute(
    val mode: TopicFormMode,
    val cat: Int? = null,
    val subcat: Int? = null,
    val topicId: Int? = null,
) : RedfaceNavKey

@Serializable enum class PostEditorMode { Reply, Edit }
@Serializable enum class TopicFormMode { New, EditFirstPost }
```

Chaque onglet de bottom nav a son propre back stack (`rememberNavBackStack`), partagé via une `Map<TopLevelDestination, NavBackStack<NavKey>>` côté `RedfaceApp`. Le rendu se fait via l'API stable `NavDisplay(backStack, onBack, entryDecorators, entryProvider)` — pas besoin du couple `rememberDecoratedNavEntries` + `rememberSceneState` pour le cas single-pane :

```kotlin
@Composable
private fun RedfaceNavHost(backStack: NavBackStack<NavKey>) {
    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<FlagsListRoute> {
                FlagsRoute(
                    onOpenFlag = { flag -> /* ... */ },
                    onLoginRequested = { /* ... */ },
                )
            }
            entry<ForumRoute> { ForumScreen(onOpenCategory = { /* ... */ }) }
            entry<SearchRoute> { SearchScreen() }
            entry<MessagesRoute> {
                MessagesScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onLoginRequested = { /* ... */ },
                    onOpenDiagnostics = { backStack.add(DiagnosticsRoute) },
                )
            }
            entry<CategoryRoute> { route ->
                ForumCategoryScreen(
                    request = CategoryRequest(
                        cat = route.cat,
                        initialSubcat = route.subcat,
                        initialPage = route.page,
                    ),
                    onOpenTopic = { topic ->
                        backStack.add(
                            TopicRoute(
                                cat = topic.cat,
                                post = topic.topicId,
                                page = topic.lastReadPage ?: 1,
                                scrollTo = topic.lastPostReadId,
                            ),
                        )
                    },
                )
            }
            entry<TopicRoute> { route ->
                TopicScreen(
                    request = TopicRequest(route.cat, route.post, route.page, route.scrollTo),
                    // Phase 2C (#145): TopicScreen exposes subcat from the parsed topic
                    // page; the reply button is disabled when `topic.hasSubcat` is false
                    // (cached row from before the v3 → v4 Room migration).
                    onReply = { subcat, page ->
                        backStack.add(
                            PostEditorRoute(
                                PostEditorMode.Reply,
                                route.cat,
                                topicId = route.post,
                                page = page,
                                subcat = subcat,
                            ),
                        )
                    },
                    onOpenPage = { targetPage ->
                        backStack.removeAt(backStack.lastIndex)
                        backStack.add(route.copy(page = targetPage, scrollTo = null))
                    },
                )
            }
            entry<PostEditorRoute> { route ->
                PostEditorScreen(
                    request = PostEditorRequest(
                        route.mode,
                        route.cat,
                        route.topicId,
                        route.numreponse,
                        route.page,
                        route.subcat,
                    ),
                    // Phase 2C (#145): pop editor + replace topic entry to refresh the
                    // target page (defaults to the page the user replied from when HFR
                    // does not surface a different one in the meta refresh URL).
                    onSubmitSucceeded = { targetPage ->
                        backStack.removeAt(backStack.lastIndex)
                        val topicEntry = backStack.lastOrNull() as? TopicRoute
                        if (topicEntry != null) {
                            backStack.removeAt(backStack.lastIndex)
                            backStack.add(topicEntry.copy(page = targetPage ?: topicEntry.page, scrollTo = null))
                        }
                    },
                )
            }
            entry<TopicFormRoute> { route ->
                TopicFormScreen(
                    mode = route.mode,
                    cat = route.cat,
                    subcat = route.subcat,
                    topicId = route.topicId,
                )
            }
        },
    )
}
```

`NavigationSuiteScaffold` (Material 3 Adaptive) commute la `currentDestination` (état `rememberSaveable`) et passe le back stack actif à `RedfaceNavHost`. Les autres back stacks restent en mémoire — quand l'utilisateur revient sur l'onglet Forum, il retombe à l'écran où il l'a quitté.

**Avantages Nav 3 vs Nav 2.x pour Redface 2** :
- Le back stack est du **state observable standard** — facile à persister/restaurer, à inspecter pour debug, à manipuler dans des tests
- Plusieurs back stacks indépendants (un par onglet) sans avoir à hiérarchiser un nav graph
- Intégration directe avec `ListDetailPaneScaffold` (Material 3 Adaptive 1.2+) — la liste et le détail vivent dans le même back stack mais s'affichent en parallèle sur tablette
- API stable simple : `NavDisplay(backStack, onBack, entryDecorators, entryProvider { entry<…> })`, pas de DSL graph à apprendre

### Cas particulier : lien vers un post spécifique

Nav 3 (comme Nav 2.x) **ne gère pas les fragments URI** (`#t{numreponse}`) nativement : on parse l'URI dans `RedfaceApp`, on identifie l'**onglet cible** (drapeaux, forum, …) et on **réinitialise** le back stack de cet onglet pour que le bouton retour ramène à la racine de l'onglet plutôt qu'à un état antérieur arbitraire :

```kotlin
// app/.../navigation/RedfaceNavigation.kt — extrait
@Composable
fun RedfaceApp(intent: Intent?) {
    val flagsBackStack = rememberNavBackStack(FlagsListRoute)
    val forumBackStack = rememberNavBackStack(ForumRoute)
    val searchBackStack = rememberNavBackStack(SearchRoute)
    val messagesBackStack = rememberNavBackStack(MessagesRoute)
    var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.Flags) }

    val backStacks = mapOf(
        TopLevelDestination.Flags to flagsBackStack,
        TopLevelDestination.Forum to forumBackStack,
        TopLevelDestination.Search to searchBackStack,
        TopLevelDestination.Messages to messagesBackStack,
    )

    LaunchedEffect(intent) {
        val parsed = intent?.data?.let(::parseHfrDeepLink) ?: return@LaunchedEffect
        currentDestination = parsed.destination
        resetStack(
            backStack = backStacks.getValue(parsed.destination),
            root = parsed.destination.rootRoute,
            route = parsed.route,
        )
    }
    // Pour la suite (NavigationSuiteScaffold avec les 4 onglets, Surface wrapper et
    // RedfaceNavHost(backStack = backStacks.getValue(currentDestination))), voir
    // app/src/main/kotlin/.../navigation/RedfaceNavigation.kt ligne 126-142.
}

private data class ParsedDeepLink(val destination: TopLevelDestination, val route: RedfaceNavKey)

private fun parseHfrDeepLink(uri: Uri): ParsedDeepLink? = when (uri.path) {
    "/forum1.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val subcat = uri.getQueryParameter("subcat")?.toIntOrNull()
        val page = uri.getQueryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        ParsedDeepLink(
            destination = TopLevelDestination.Forum,
            route = CategoryRoute(cat = cat, subcat = subcat, page = page),
        )
    }
    "/forum2.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val post = uri.getQueryParameter("post")?.toIntOrNull() ?: return null
        val page = uri.getQueryParameter("page")?.toIntOrNull() ?: 1
        val scrollTo = uri.fragment?.removePrefix("t")?.toIntOrNull()
        ParsedDeepLink(
            destination = TopLevelDestination.Flags,
            route = TopicRoute(cat = cat, post = post, page = page, scrollTo = scrollTo),
        )
    }
    "/forum1f.php" -> ParsedDeepLink(TopLevelDestination.Flags, FlagsListRoute)
    else -> null
}

private fun resetStack(
    backStack: NavBackStack<NavKey>,
    root: RedfaceNavKey,
    route: RedfaceNavKey,
) {
    backStack.clear()
    backStack.add(root)
    if (route != root) backStack.add(route)
}
```

Le `TopicScreen` reçoit le `scrollTo` (numreponse cible) via la `TopicRoute` et scroll jusqu'au bon post après chargement de la page.

> **Politique de back stack sur deep link** : on **réinitialise** le back stack de l'onglet cible (`resetStack`) plutôt qu'on n'empile sur l'historique courant. Rationale : un deep link entrant doit poser un état de navigation **prévisible** — back ramène à la racine de l'onglet, pas à un mélange d'écrans visités avant le deep link. Cf. § Back Stack ci-dessous.

### Predictive back

Nav 3 intègre `PredictiveBackHandler` via `NavDisplay` — aucun code custom requis pour les écrans standards. Seuls les écrans à interaction custom (ex : éditeur avec draft) ajoutent leur propre handler ; Phase 2B-A livre `PostEditorScreen` sans cette confirmation (pas encore de draft persistant) — l'exemple ci-dessous reste le pattern cible quand la persistance arrivera :

```kotlin
@Composable
fun PostEditorScreen(state: PostEditorState, onIntent: (PostEditorIntent) -> Unit) {
    var showDiscardDialog by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = state.draft.text.isNotEmpty()) { progress ->
        progress.collect { /* animation personnalisée si besoin */ }
        showDiscardDialog = true  // à la fin, on demande confirmation
    }

    // ... rest of the screen
}
```

Manifest requis : `android:enableOnBackInvokedCallback="true"` sur `<application>`.

### Multi-pane adaptatif (tablette, foldables)

> **Statut Phase 5+** — multi-pane n'est pas livré en Phase 1. Dans le snippet ci-dessous :
>
> - le **pattern de composition** (`NavDisplay` + `ListDetailPaneScaffold` sur le même back stack, switch `WindowSizeClass`) est **illustratif** — c'est ce qui sera implémenté Phase 5+ ;
> - les **signatures de screens** appelées (`FlagsRoute(onOpenFlag, onLoginRequested)`, `MessagesScreen(versionName, versionCode, onLoginRequested, onOpenDiagnostics)`, `SearchScreen()`, `TopicScreen(request: TopicRequest, onReply: (subcat, page) -> Unit, onOpenPage)`, `PostEditorScreen(request: PostEditorRequest, onSubmitSucceeded: (targetPage?) -> Unit)`, `TopicFormScreen(mode, cat?, subcat?, topicId?)`) sont les signatures **réelles** livrées dans le repo (cf. `feature/topic/.../TopicScreen.kt`, `feature/flags/.../FlagsRoute.kt`, `feature/messages/.../MessagesScreen.kt`, `feature/search/.../SearchScreen.kt`, `feature/editor/.../PostEditorScreen.kt`, `feature/editor/.../TopicFormScreen.kt`).
>
> Le call-site `onOpenFlag = { flag -> backStack.add(TopicRoute(flag.cat, flag.topicId, flag.lastReadPage, scrollTo = ...)) }` passe désormais le topic concerné — Phase 1B.4 a remplacé le placeholder mock par la liste réelle des drapeaux.

```kotlin
@Composable
fun AdaptiveNavHost(backStack: NavBackStack<NavKey>) {
    val isExpanded = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass !=
        WindowWidthSizeClass.COMPACT

    if (isExpanded) {
        ListDetailPaneScaffold(
            listPane = {
                FlagsRoute(
                    onOpenFlag = { flag ->
                        backStack.add(
                            TopicRoute(
                                cat = flag.cat,
                                post = flag.topicId,
                                page = flag.lastReadPage,
                                scrollTo = flag.lastPostReadId
                                    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                                    ?.toInt(),
                            ),
                        )
                    },
                    onLoginRequested = { backStack.add(LoginRoute) },
                )
            },
            detailPane = {
                when (val current = backStack.lastOrNull()) {
                    is TopicRoute -> TopicScreen(
                        request = TopicRequest(
                            cat = current.cat,
                            post = current.post,
                            page = current.page,
                            scrollTo = current.scrollTo,
                        ),
                        onReply = { subcat, page ->
                            backStack.add(PostEditorRoute(PostEditorMode.Reply, current.cat, topicId = current.post, page = page, subcat = subcat))
                        },
                        onOpenPage = { targetPage ->
                            backStack.removeAt(backStack.lastIndex)
                            backStack.add(current.copy(page = targetPage, scrollTo = null))
                        },
                    )
                    is PostEditorRoute -> PostEditorScreen(
                        request = PostEditorRequest(current.mode, current.cat, current.topicId, current.numreponse, current.page, current.subcat),
                        onSubmitSucceeded = { /* multi-pane refresh handled by parent observer */ },
                    )
                    is TopicFormRoute -> TopicFormScreen(
                        mode = current.mode,
                        cat = current.cat,
                        subcat = current.subcat,
                        topicId = current.topicId,
                    )
                    else -> Text("Select a topic")
                }
            },
        )
    } else {
        RedfaceNavHost(backStack = backStack)
    }
}
```

Phase 1B.4 a livré `FlagsRoute` (dans `:feature:flags`) avec le vrai modèle `Flag` ; en Phase 1D-1 le scroll anchor est passé de `firstUnreadPostId` à `lastPostReadId` (REST `last_post_read_id`) : `backStack.add(TopicRoute(flag.cat, flag.topicId, flag.lastReadPage, scrollTo = flag.lastPostReadId?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()))`. Phase 1C-A a ensuite remplacé les placeholders Forum/Category par `ForumScreen` + `ForumCategoryScreen` alimentés par `ForumRepository` REST. Le polish pré-Phase 2 (#154) a retiré les constantes `DEMO_TOPIC_*` et leurs callbacks : `SearchScreen()` n'expose plus de bouton de navigation, et `MessagesScreen(versionName, versionCode, onLoginRequested, onOpenDiagnostics)` accueille temporairement les actions compte (login/logout) et outils alpha (Diagnostics, signalement, version) jusqu'à ce que Phase 3 livre la vraie liste de MPs.

---

## Back Stack

Nav 3 expose le back stack comme un `NavBackStack<NavKey>` observable, puis `NavDisplay` le rend directement entry par entry. Règles Redface 2 :

- **Bottom nav** : chaque onglet conserve son propre back stack (un `rememberNavBackStack(...)` par onglet, le `NavDisplay` actif reçoit celui de la `currentDestination`). Quand l'utilisateur change d'onglet, le back stack précédent reste en mémoire et reprend où il en était.
- **Retour depuis un topic** : retour à la liste (drapeaux, forum, recherche) à la même position de scroll — l'entrée précédente est conservée dans la liste tant qu'elle est dans le back stack.
- **Retour depuis reply/edit** : retour au topic à la même page.
- **Deep link** : on identifie l'onglet cible et on **réinitialise** son back stack via `resetStack(root, route)` (cf. § Cas particulier : lien vers un post spécifique). Conséquence prévisible : back depuis le deep link ramène à la racine de l'onglet (drapeaux, forum, …), pas à un état pré-deep-link arbitraire.

```mermaid
graph LR
    A["Drapeaux"] --> B["Topic (page 3)"]
    B --> C["Quote → Reply"]
    C -->|"Back"| B
    B -->|"Back"| A
    A -->|"Back"| EXIT["Quitter l'app"]
```
