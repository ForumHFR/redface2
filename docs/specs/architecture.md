---
title: Architecture
parent: Spécifications
nav_order: 3
permalink: /specs/architecture
mermaid: true
---

# Architecture
{: .fs-8 }

Modules Gradle, couches, data flow et stratégie de cache.
{: .fs-5 .fw-300 }

---

## Couches

L'application suit une architecture en 3 couches strictes. Chaque couche ne peut dépendre que de la couche en dessous. Les frontières sont **enforces par les modules Gradle** — pas de convention implicite.

```mermaid
graph TB
    subgraph "Presentation"
        direction LR
        S["Screens (Compose)"]
        VM["ViewModels (MVI)"]
        S --> VM
    end
    subgraph "Domaine"
        RI["Repository interfaces"]
        M["Modèles domaine"]
    end
    subgraph "Données"
        direction LR
        IMPL["Repository implémentations"]
        NET["HfrClient (OkHttp)"]
        PARSE["HfrParser (Jsoup)"]
        DB["Room Database"]
        IMPL --> NET
        IMPL --> PARSE
        IMPL --> DB
    end
    VM --> RI
    RI --> M
    IMPL -.->|implémente| RI
```

- **Presentation** (`:feature:*`) : Compose UI + ViewModels MVI. Ne connait que les interfaces de repositories et les modèles domaine.
- **Domaine** (`:core:domain` + `:core:model`) : Interfaces de repositories + modèles purs. Aucune dépendance framework. Frontière de compilation.
- **Données** (`:core:data` + `:core:network` + `:core:parser` + `:core:database`) : Implémentations concrètes. Les features ne dépendent jamais de cette couche directement — Hilt injecte les implémentations.

---

## Modules Gradle

```mermaid
graph TB
    APP[":app"] --> FFL[":feature:flags"]
    APP --> FF[":feature:forum"]
    APP --> FT[":feature:topic"]
    APP --> FE[":feature:editor"]
    APP --> FM[":feature:messages"]
    APP --> FA[":feature:auth"]
    APP --> FS[":feature:settings"]
    APP --> FSR[":feature:search"]
    APP --> CDATA[":core:data"]

    FFL --> CDOM[":core:domain"]
    FFL --> CU[":core:ui"]

    FF --> CDOM
    FF --> CU

    FT --> CDOM
    FT --> CU
    FT --> CEXT[":core:extension"]

    FE --> CDOM
    FE --> CU
    FE --> CEXT

    FM --> CDOM
    FM --> CU

    FA --> CDOM
    FA --> CU

    FS --> CDOM
    FS --> CU

    FSR --> CDOM
    FSR --> CU

    CDOM --> CM[":core:model"]
    CEXT --> CM
    CU --> CM

    CDATA --> CDOM
    CDATA --> CN[":core:network"]
    CDATA --> CP[":core:parser"]
    CDATA --> CD[":core:database"]

    CP --> CM
    CD --> CM

    style APP fill:#e74c3c,color:#fff
    style CM fill:#f39c12,color:#fff
    style CDOM fill:#e67e22,color:#fff
    style CDATA fill:#16a085,color:#fff
    style CN fill:#2ecc71,color:#fff
    style CP fill:#27ae60,color:#fff
    style CD fill:#3498db,color:#fff
    style CU fill:#9b59b6,color:#fff
    style CEXT fill:#8e44ad,color:#fff
```

### Modules core

| Module | Responsabilité | Dépend de |
|--------|---------------|-----------|
| `:core:model` | Modèles domaine purs (`Topic`, `Post`, `PostContent`, `Category`, `Flag`, `MP`). Aucune dépendance Android. | rien |
| `:core:domain` | Interfaces de repositories (`TopicRepository`, `FlagRepository`, `AuthRepository`...) et règles métier partagées. Aucune dépendance framework. | `:core:model` |
| `:core:data` | Implémentations des repositories. Orchestre réseau (HTML + REST JSON), parser HTML et cache. Porte les DTO `@Serializable` REST et leurs mappers vers `:core:model`. Fournit les bindings Hilt. | `:core:domain`, `:core:network`, `:core:parser`, `:core:database` |
| `:core:network` | Deux clients : `HfrClient` (HTML brut sur `forum*.php`, login, MPs, mutations) et `HfrApiClient` (JSON REST sur `/webservices/rest_api.php`, browsing). Encapsulent OkHttp. Aucun type domaine exposé — du `String` brut ou des erreurs typées. Le helper de rewrite HATEOAS `HfrApiClient.rewriteHateoasHref(href)` vit ici. Cf. [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride). | rien |
| `:core:parser` | `HfrParser` : transforme le HTML HFR et, à partir de l'éditeur Phase 2, le BBCode HFR en modèles domaine, dont l'AST `PostContent`. Pas de parser JSON REST ici — les DTO REST vivent dans `:core:data`. | `:core:model` |
| `:core:database` | Room DB, DAOs, entities, mappers entity↔model. Cache locale + cache MPStorage. | `:core:model` |
| `:core:ui` | Thème Material 3 (`theme/`) et `PostRenderer` (`post/`, `PostContent` → Compose). D'autres sous-packages (`components/`, `adaptive/`, `semantics/`, `util/`, `extensions/`) sont prévus mais n'apparaîtront qu'au fur et à mesure de l'arrivée des features qui les justifient — pas de module vide en avance. Seul module autorisé à instancier `ColorScheme`, `Typography`, `Shapes`. | `:core:model` |
| `:core:extension` | Interfaces d'extension : `PostDecorator`, `TopicToolbarContributor`, `EditorToolbarContributor`. | `:core:model` |

### Modules feature (base)

Les features ne dépendent que de `:core:domain` (interfaces) et `:core:ui` (composants partagés). Exception volontaire : `:feature:topic` et `:feature:editor` peuvent aussi dépendre de `:core:extension`, car ce sont les deux points d'intégration des contributeurs (`PostDecorator`, `TopicToolbarContributor`, `EditorToolbarContributor`). Elles ne connaissent jamais la couche données — Hilt injecte les implémentations depuis `:core:data`.

| Module | Écrans | Dépend de |
|--------|--------|-----------|
| `:feature:flags` | Drapeaux (accueil) — onglets rouge/cyan/favoris, footer auth + MP | `:core:domain`, `:core:ui` |
| `:feature:forum` | Catégories, sous-catégories, liste de topics | `:core:domain`, `:core:ui` |
| `:feature:topic` | Lecture de topic, pagination | `:core:domain`, `:core:ui`, `:core:extension` |
| `:feature:editor` | Reply, edit, edit FP, preview BBCode, création topic | `:core:domain`, `:core:ui`, `:core:extension` |
| `:feature:messages` | MPs classiques, MultiMPs, création MP/MultiMP | `:core:domain`, `:core:ui` |
| `:feature:auth` | Login HFR | `:core:domain`, `:core:ui` |
| `:feature:search` | Recherche dans les topics et posts, filtres | `:core:domain`, `:core:ui` |
| `:feature:settings` | Préférences, thème, gestion cache | `:core:domain`, `:core:ui` |

### Modules feature (extensions communautaires — Phase 4)

Les 8 modules extension arrivent en **Phase 4** uniquement. En Phases 0 à 3, le projet compte 15 modules (8 core + 7 features base). Les extensions sont des modules Gradle isolés qui s'enregistrent via Hilt `@IntoSet` — ajouter une extension ne demande aucune modification du code existant. La décision de découpage v1 est formalisée dans [ADR-001]({{ site.baseurl }}/adr/001-modules-gradle-v1).

> **État réel des modules en Phase 1** : tous les modules core et feature de base sont déclarés dans `settings.gradle.kts`, mais certains ne contiennent encore que le squelette Gradle (`build.gradle.kts`) sans code Kotlin — `:core:extension` et `:feature:settings` notamment. `:core:network` et `:core:database` ont reçu leur backbone Phase 1A (`HfrClient`, `TopicRepositoryImpl` cache-aside, schema Room v1). `:feature:auth` contient le login HFR Phase 1B.1 (`LoginScreen` / `LoginViewModel`). C'est volontaire : le découpage est fixé dès le bootstrap (ADR-001) pour figer les frontières, mais le code arrive feature par feature. La prose ci-dessus décrit le **contrat cible** ; la réalité courante est trackée par la roadmap.

| Module | Fonction | Dépend de |
|--------|----------|-----------|
| `:feature:bookmarks` | Sauvegarder des posts | `:core:extension`, `:core:model`, `:core:database` |
| `:feature:blacklist` | Masquer des utilisateurs | `:core:extension`, `:core:model`, `:core:database` |
| `:feature:qualitay` | Signaler un post remarquable | `:core:extension`, `:core:model`, `:core:network` |
| `:feature:redflag` | Alertes intelligentes (via CF Worker) | `:core:extension`, `:core:model`, `:core:network` |
| `:feature:colortag` | Colorer et annoter les pseudos | `:core:extension`, `:core:model`, `:core:database` |
| `:feature:imagehost` | Upload et bibliothèque d'images | `:core:model`, `:core:network`, `:core:ui` |
| `:feature:gifpicker` | Recherche et insertion de GIFs | `:core:model`, `:core:network`, `:core:ui` |
| `:feature:stats` | Statistiques utilisateur | `:core:model`, `:core:network` |

### Module app

`:app` est le point d'entrée. Il :
- Configure Hilt (DI) — inclut `:core:data` pour le wiring des implémentations
- Définit la navigation globale (`RedfaceApp` + `NavDisplay`)
- Contient `MainActivity`
- Dépend de tous les modules feature (base + extensions)

> **Note Phase 1B.4 — `:feature:flags` livré** : l'écran d'accueil (Drapeaux) vit désormais dans `:feature:flags` avec `FlagsViewModel` (Hilt) + `FlagRepository` + 3 onglets (`FlagType.CYAN` = mes sujets, `RED` = lus uniquement, `FAVORITE`). `:app` ne fait plus que la navigation (`FlagsRoute(versionName, versionCode, onOpenFlag, onLoginRequested, onOpenDiagnostics)`) et passe `BuildConfig.VERSION_NAME/VERSION_CODE` en paramètres pour que l'écran puisse afficher le footer "Redface 2 — vX.Y (build N)" sans dépendre de la BuildConfig de `:app`.

---

## Séparation des responsabilités

### `:core:domain` — interfaces

Les interfaces de repositories vivent dans le module domaine. Aucune dépendance framework.

> **Note Phase 1** : ces interfaces sont le **contrat cible**. `TopicRepository` est livré (cf. [#88](https://github.com/ForumHFR/redface2/pull/88)) — `TopicScreen` lit du vrai HFR via cache-aside Room. `AuthRepository` est livré en Phase 1B.1 pour le login HFR et l'observation de session. Les autres interfaces (`FlagRepository`, etc.) arrivent feature par feature avec leur implémentation `:core:data`.

```kotlin
// Dans :core:domain — le contrat
interface TopicRepository {
    /**
     * Émet d'abord la page en cache si elle existe, puis la version réseau fraîchement
     * fetchée et persistée. Cache-aside : la deuxième émission peut être identique à la
     * première si le réseau confirme le cache.
     */
    fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic>

    /** Force un fetch réseau ignorant le cache, et persiste le résultat. */
    suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic
}

interface FlagRepository {
    /**
     * Émet le succès en cache pour la session courante si disponible ; sinon `Loading`,
     * puis le résultat d'un fetch network (`Success(flags)` ou `Failure`). Les abonnés
     * reçoivent ensuite chaque [refresh] explicite via le SharedFlow par type.
     */
    fun observe(type: FlagType): Flow<FlagsResult>
    suspend fun refresh(type: FlagType)
    fun clearSessionCache()
}

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>
    suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated>
    suspend fun logout()
}

interface MessagesRepository {
    /**
     * Compteur de MPs non lus : `null` quand anonyme ou avant la première résolution,
     * Int sinon. Phase 1B.1 livre uniquement ce compteur (preuve d'auth sur l'écran d'accueil) ;
     * la liste complète des MPs et la lecture de threads MP arrivent en Phase 1C.
     */
    fun observeUnreadMpCount(): Flow<Int?>
}
```

`TopicRepository` est livré en Phase 1A (cf. [#88](https://github.com/ForumHFR/redface2/pull/88), [#89](https://github.com/ForumHFR/redface2/pull/89)). `prefetchNextPage` documenté dans la roadmap arrivera en Phase 1B sur `HfrClient` directement (avec `useAuth = false`), puis sera relayé par `TopicRepository.prefetchTopicPage(...)`. `MessagesRepository` est livré en Phase 1B.1 en bonus du login : `:core:data DefaultMessagesRepository` combine l'observation de `AuthState` avec un fetch de `forum1.php?cat=prive` (parser dédié `:core:parser/messages/PrivateMessageListParser`) déclenché à chaque transition vers `Authenticated`. Le full pipeline messagerie (liste + threads) viendra en Phase 1C.

`FlagRepository` + UI sont livrés en Phase 1B.2 → 1B.5, **migrés en REST en Phase 1D-1** (cf. ADR-003 et issue #110). `:core:data DefaultFlagRepository` itère sur les catégories publiques fournies par `ForumRepository.observeCategories()` (filtrant `id > 0` pour éviter le 403 sur d'éventuels `cat=0` modos) et appelle pour chacune `HfrApiClient.getCategoryFlagTopics(cat, bucket = HfrRestFlagBucket.{PARTICIPATED,READ,FAVORITES}, page, resultsPerPage, useAuth = true)`. Chaque page est désérialisée en `RestListEnvelope<RestTopic>` (forme plate, contrat prouvé par la fixture `rest_cat23_participated.json`) puis mappée via `RestFlagMappers.toFlags(envelope, defaultType, fallbackCat)` pour produire `List<Flag>` (cf. [`models.md`]({{ site.baseurl }}/specs/models)). Les résultats des N catégories sont concaténés puis triés globalement par `lastReplyAt` (REST `YYYY-MM-DD HH:mm`, donc tri lexicographique = chronologique inverse) ; sans ce tri global la liste serait groupée par cat au lieu d'être triée par activité comme l'attend l'écran. La voie globale `forums/hardwarefr/topics/{bucket}/` n'est **pas** exposée par `HfrApiClient` en Phase 1D-1 : son enveloppe groupée par catégorie n'a pas de fixture capturée et garder une API morte est un anti-pattern (cf. § "Le noyau avant l'écosystème" de l'AGENTS.md). Une PR de suivi pourra rajouter le helper et basculer la consommation une fois le payload capturé. Le scrape HTML `forum1f.php?owntopic=N` + `FlagsListParser` ont été retirés ; `getFlagsPage()` n'existe plus côté `HfrClient`. La séquence de flux reste identique : `flow { emit(Loading); emit(fetch); emitAll(refreshes) }` cold-collected — un `MutableSharedFlow<FlagsResult>` par `FlagType` rebroadcast les résultats des `refresh()` explicites. Cache mémoire par onglet pour la session HFR courante : revenir sur un onglet déjà chargé ne refetch pas implicitement, mais `refresh(type)` force toujours le réseau et `clearSessionCache()` vide tout au logout / changement de session. `FlagItem` rend une ligne dans `:core:ui`, et `:feature:flags FlagsRoute` compose les 3 onglets plus le footer auth + MP count + version + signalement CSAE. Pas de cache Room en 1D-1 — la persistance des drapeaux arrive avec #26.

### `:core:network` — HfrClient + HfrApiClient

La couche réseau ne parse rien. Elle retourne du `String` (HTML ou JSON) ou des confirmations d'action. Deux clients coexistent depuis Phase 1C-A (cf. [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride)) :

- **`HfrClient`** — endpoints HTML `forum*.php` : login, lecture posts, MPs, mutations drapeaux (`addflag.php` / `delflag.php`). La lecture des drapeaux est passée en REST en Phase 1D-1 — `getFlagsPage()` retiré.
- **`HfrApiClient`** — endpoints REST JSON `/webservices/rest_api.php?uri=…` : browsing (catégories, sous-catégories, topic listings, metadata topic). Fournit aussi le helper validant `rewriteHateoasHref(href)` qui transforme une URL HATEOAS `/api/…` en URL callable côté HFR.

```kotlin
@Singleton
class HfrClient @Inject constructor(
    @AuthenticatedClient private val authenticated: OkHttpClient,
    @AnonymousClient private val anonymous: OkHttpClient,
    @HfrBaseUrl private val baseUrl: HttpUrl,
) {
    // Phase 1A — livrée
    suspend fun getTopicPage(cat: Int, post: Int, page: Int, useAuth: Boolean = true): String

    // Phase 1B.1 — livrée
    suspend fun getPrivateMessageListPage(page: Int = 1): String

    // Phase 2+ — à implémenter
    suspend fun postReply(cat: Int, post: Int, content: String): Result<Unit>
    suspend fun editPost(cat: Int, post: Int, numreponse: Int, content: String): Result<Unit>
    // ...
}
```

Le login HFR est isolé dans `:core:network.auth.AuthRemoteDataSource`, pas dans `HfrClient` : il POSTe `login_validation.php` avec un cookie jar de staging, classe la réponse, puis commite explicitement les cookies dans le `@AuthenticatedClient` seulement si la réponse est `Authenticated`.

`@AnonymousClient` (cookie jar = `CookieJar.NO_COOKIES`) permet à un caller — typiquement le prefetch de la page suivante — d'aller chercher du HTML sans que HFR ne marque les drapeaux comme lus côté serveur. Les écrans qui doivent honorer la lecture (lecture utilisateur) appellent avec `useAuth = true` (default).

### `:core:parser` — HfrParser

Le parser transforme le HTML HFR et, à partir de l'éditeur Phase 2, le BBCode HFR en modèles domaine. Isolé de toute logique réseau et UI.

> **Statut Phase 1D-1** : `parseTopicPage` (Phase 1A) reste actif, `PrivateMessageListParser` (Phase 1B.1) reste actif. Le parser drapeaux HTML `FlagsListParser` (Phase 1B.2) a été retiré avec la migration REST des drapeaux (#110, ADR-003). `PostContentParser` et `TopicPageParser` existent comme classes internes derrière `HfrParser`. Les autres méthodes ci-dessous arrivent feature par feature : `parseEditPage` Phase 2, `parseMessageList` Phase 3, `parsePostContentFromBbcode` Phase 2 (parser BBCode pour preview éditeur).

```kotlin
class HfrParser @Inject constructor() {
    fun parseTopicPage(html: String): Topic                 // Phase 1 — livrée
    fun parsePostContentFromHtml(html: String): PostContent // Phase 1 — livrée (interne au module)
    fun parsePostContentFromBbcode(bbcode: String): PostContent // Phase 2 éditeur
    fun parseCategories(html: String): List<Category>       // Phase 1 fin
    fun parseEditPage(html: String): EditInfo               // Phase 2
    fun parseMessageList(html: String): List<PrivateMessage> // Phase 3
    // ...
}

// Le parser drapeaux HTML a été retiré en Phase 1D-1 (#110) au profit
// de la lecture REST via `HfrApiClient.getCategoryFlagTopics(...)` + `RestFlagMappers`.
```

### `:core:data` — implémentations

Les implémentations de repositories orchestrent réseau, parser et cache. Elles vivent dans `:core:data`, jamais dans les features.

```kotlin
// Dans :core:data — l'implémentation (Phase 1A)
@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val topicDao: TopicDao,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicRepository {

    override fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic> = flow {
        // 1. Si on a une copie en cache, l'émettre tout de suite (UI réactive)
        val cached = withContext(ioDispatcher) { loadFromCache(cat, post, page) }
        if (cached != null) emit(cached)

        // 2. Re-fetch + re-parse + cache, puis émettre la version fraîche
        emit(refreshTopicPage(cat, post, page))
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic =
        withContext(ioDispatcher) {
            val html = client.getTopicPage(cat, post, page, useAuth = true)
            val topic = parser.parseTopicPage(html)
            val (topicEntity, postEntities) = TopicMappers.toEntities(topic, clock.instant())
            topicDao.upsertTopicPageWithPosts(topicEntity, postEntities)
            topic
        }
}
```

Le binding Hilt connecte l'interface à l'implémentation :

```kotlin
// Dans :core:data
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindTopicRepository(impl: TopicRepositoryImpl): TopicRepository

    @Binds
    abstract fun bindFlagRepository(impl: DefaultFlagRepository): FlagRepository

    @Binds
    abstract fun bindForumRepository(impl: DefaultForumRepository): ForumRepository
}
```

`ForumRepository` (Phase 1C-A) consomme `HfrApiClient` et expose `observeCategories()` / `observeSubcategories(cat)` / `observeTopicList(cat, subcat?, page)` (et leurs `refresh*` jumeaux) en `Flow<ForumResult<…>>`. Cache mémoire pour catégories et sous-catégories ; pas de Room en 1C-A (les listings de topics ne sont pas mis en cache disque). Cf. [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride) pour la décision REST-first sur ces trois domaines.

Les ViewModels dans les features ne connaissent que l'interface :

```kotlin
// Dans :feature:topic — ne dépend que de :core:domain
@HiltViewModel
class TopicViewModel @Inject constructor(
    private val topicRepository: TopicRepository,  // interface, pas impl
) : ViewModel() { ... }
```

---

## Stratégie de cache

| Donnée | Stratégie | TTL |
|--------|-----------|-----|
| Pages topic lues | Cache Room (`topic_pages` + `posts`), cache-affichable + refresh BG si stale, anti-écrasement par prefetch anonyme via `authMode` | 60 s (`CachePolicy.topicPage`) |
| Listings topics REST | Mémoire processus, replay synchrone du dernier succès, refresh manuel = bypass TTL | 30 s (`CachePolicy.topicList`) |
| Drapeaux REST (par compte) | Cache Room (`flag_topics`) avec `userId` dans la clé primaire ; purge au logout / changement de compte par `CacheInvalidator` | 30 s (`CachePolicy.flags`) |
| Catégories | Cache mémoire HFR-public, replay sur stale puis refresh | 24 h (`CachePolicy.categories`) |
| Sous-catégories | Cache mémoire par `cat`, même sémantique stale-replay | 6 h (`CachePolicy.subcategories`) |
| Smileys | Cache Coil, ne changent jamais | Infini |
| Avatars | Cache Coil, ETag | 1 h |
| MultiMP flags | Room, jamais expire (donnée locale) | Permanent |
| Préférences | DataStore | Permanent |

### Sémantique fresh / stale

`observeTopicPage(cat, post, page)` :

1. Cache `AUTHENTICATED` + fresh → émet une seule fois, pas de réseau. C'est le cas snappy back-nav d'un utilisateur connecté.
2. Cache `ANONYMOUS` (fresh ou stale) → émet le cache pour un affichage immédiat puis lance un fetch authentifié pour upgrader la row vers `AUTHENTICATED` (champs per-user `isOwnPost`, `isEditable`, drapeau de lecture). Le `@Transaction` anti-écrasement décrit ci-dessous garantit que ce remplacement ne perd jamais une row `AUTHENTICATED` plus riche entre-temps.
3. Cache `AUTHENTICATED` + stale → émet le cache puis tente un refresh ; si le refresh échoue (offline, 502), l'erreur est swallowed et le stale reste à l'écran.
4. Cache absent → fetch direct ; les erreurs propagent au flow.

Le `refreshTopicPage` explicite **bypasse** le TTL et renvoie systématiquement du frais. Même règle pour `refreshCategories` / `refreshSubcategories` / `refreshTopicList`.

### Isolation par compte

Les drapeaux sont strictement scopés par pseudo (lowercase) dans `flag_topics.userId`. À la transition `Authenticated(A) → Anonymous` ou `Authenticated(A) → Authenticated(B)`, `CacheInvalidator` :

- vide les rows `flag_topics` pour l'ancien `userId` ;
- appelle `FlagRepository.clearSessionCache()` pour purger le cache mémoire.

Les pages topic (`topic_pages` / `posts`) ne sont pas purgées : le HTML est partagé entre lecteurs, et la TTL courte de 60 s + le garde-fou `authMode` rendent la fuite de champs per-user (`isOwnPost`, `isEditable`) bornée.

### Anti-écrasement auth ↔ anonyme

Le prefetch anonyme (Phase 1D PR 4) ne doit **pas** remplacer un row authentifié plus riche. Chaque ligne persistée porte un `authMode` (`AUTHENTICATED` ou `ANONYMOUS`) ; un upsert anonyme sur une row authentifiée existante est silencieusement ignoré côté `TopicRepositoryImpl.persist`. À l'inverse, un fetch authentifié écrase toujours, pour garantir la fraîcheur des champs per-user.

### Prefetch intelligent

Pour donner l'impression que le forum est local :

```
Utilisateur lit la page 3 d'un topic
  → Prefetch page 4 en arrière-plan (anonyme, écrit en cache ANONYMOUS)
  → Quand il scroll vers le bas, la page 4 est déjà prête
```

Implémentation Phase 1D PR 4 (#108) :

- **Topic page** : `TopicViewModel` déclenche `topicRepository.prefetch(cat, post, page+1)` après chaque émission `Loaded`. Le job est rattaché à `viewModelScope` ; sortir de l'écran ou changer de page propage le `cancel()` jusqu'à OkHttp.
- **Topic listing** : `CategoryViewModel` déclenche `forumRepository.prefetchTopicList(cat, subcat, page+1)` à chaque transition vers `Content`. Le job est annulé à chaque changement de `(subcat, page)` via le pattern `combine + onEach + cancel` standard.
- **Une seule page d'avance** par déclencheur. Pas de chaîne `n+2, n+3` — le coût ne paie pas le bénéfice et grossirait la facture pour des comportements rares.
- **Échecs swallowed** : un prefetch flaky ne doit jamais perturber l'affichage. Erreurs loguées en `Log.w`, puis avalées.

#### Règle critique : prefetch non-authentifié

Les requêtes de prefetch ne doivent **jamais** inclure les cookies de session — sinon HFR marque silencieusement les topics comme lus. Implémentation avec deux instances `OkHttpClient` (`@AuthenticatedClient` / `@AnonymousClient`) et test Konsist d'enforcement : voir [protocol-hfr.md § Règle critique prefetch non-authentifié]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié).

Côté cache disque, l'entrée est tagguée `authMode = ANONYMOUS` et **ne remplace pas** une row existante taguée `AUTHENTICATED` (cf. § Stratégie de cache).

---

## Gestion de session

HFR utilise des cookies de session. Le flow d'authentification :

```mermaid
sequenceDiagram
    participant App
    participant OkHttp
    participant HFR

    App->>OkHttp: login(user, pass)
    OkHttp->>HFR: POST /login_validation.php (cookie jar staging, no redirect)
    HFR-->>OkHttp: Set-Cookie md_user, md_pass
    OkHttp->>OkHttp: classify Authenticated
    OkHttp->>OkHttp: commit cookies dans PersistentCookieJar

    Note over App,HFR: Toutes les requêtes suivantes incluent les cookies

    App->>OkHttp: fetchFlags()
    OkHttp->>HFR: GET /webservices/rest_api.php?uri=forums/hardwarefr/categories/{cat}/topics/{bucket}/ + cookies
    HFR-->>OkHttp: JSON drapeaux
    OkHttp-->>App: JSON brut
```

Les cookies sont persistés via un `PersistentCookieJar` adossé à un DataStore non chiffré (voir § Stockage sécurisé ci-dessous) pour éviter de se re-logguer à chaque lancement.

### Stockage sécurisé des credentials

**Option A retenue** (cycle [#24](https://github.com/ForumHFR/redface2/issues/24) thème 13, formalisée dans [ADR-002]({{ site.baseurl }}/adr/002-credentials-option-a)) : stack minimaliste **DataStore non chiffré**, protection au repos déléguée à **File-Based Encryption (FBE)** d'Android, **pas de password stocké**.

**Ce qui est stocké** : uniquement les **cookies de session HFR** (`md_user`, `md_pass`) — nécessaires pour rester connecté entre deux lancements de l'app.

**Ce qui n'est pas stocké** : le mot de passe en clair de l'utilisateur. À l'expiration de session (cookies invalidés côté HFR), l'app redirige vers l'écran de login — l'utilisateur ré-entre son mot de passe. Pas de re-login transparent silencieux.

**Protection au repos** :

- minSdk 29 garantit FBE active : `/data/data/<pkg>` est chiffré tant que le device est locké, avec une clé dérivée du PIN/pattern utilisateur.
- `android:allowBackup="false"` exclut les cookies du backup Google Drive.
- la sandbox d'app empêche les autres apps non-root d'y accéder.

> **Note** : `EncryptedSharedPreferences` (AndroidX Security) est déprécié à partir de `security-crypto 1.1.0-beta01` (04/06/2025), puis marqué deprecated en `1.1.0`. La release note officielle demande de préférer les APIs plateforme — la décision Option A va plus loin en supprimant la couche crypto custom redondante avec FBE.

**Rationale Option A (vs chiffrement custom envisagé initialement)** :
- Le **password transite en clair** dans le POST `login_validation.php` (HFR ne supporte pas le hash côté client). Tout chiffrement local du cookie reste **redondant face à un attaquant runtime** : il verrait le password lors du prochain login.
- FBE + sandbox + `allowBackup="false"` couvrent les menaces réalistes (app tierce, adb sur device locké, backup, forensic device locké).
- Tink est overkill pour un seul secret (rotation, AEAD streaming, multi-keyset — aucun n'est utile ici).
- Pas de clé Keystore custom = pas de gestion "clé invalidée par rotation système / restauration backup / perte StrongBox".
- Pas de biométrie : forum ≠ banque, complexité UX disproportionnée pour le scope v1.

---

## Gestion des erreurs

### Session expirée

Les fetchers authentifiés lèvent `SessionExpiredException` quand HFR renvoie la page de login à la place du payload demandé (URL finale `/login.php` / `/login_validation.php`, ou formulaire login en HTTP 200). L'écran concerné affiche un état session expirée et propose la reconnexion via la route de login livrée par `:feature:auth`. L'utilisateur ré-entre son mot de passe (Option A, pas de re-login transparent : le password n'est pas stocké).

### HFR indisponible

Le repository retourne `Result.failure` → le ViewModel affiche les données du cache Room + une bannière "HFR indisponible, données en cache". Retry automatique avec backoff exponentiel (2s, 4s, 8s, max 60s).

### Rate limiting

Interceptor OkHttp avec détection des réponses HTTP 429 et des patterns de blocage HFR. File d'attente côté client avec rate limit (max 2 req/s vers HFR). Backoff automatique sur 429.

### Breakage du parser

`HfrParser` wrappe chaque méthode dans `runCatching`. Sur échec, le HTML brut est loggé en mode debug pour diagnostic. Un smoke test CI hebdomadaire vérifie que les sélecteurs CSS critiques (`HfrSelectors`) matchent toujours sur une vraie page HFR publique.

---

## Enforcement architecture au build

Les règles d'architecture décrites plus haut (3 couches strictes, features → `:core:domain` + `:core:ui` uniquement, tokens M3 centralisés dans `:core:ui`) sont **enforcées mécaniquement** par **[Konsist](https://docs.konsist.lemonappdev.com/)** (Kotlin-first, AST parsing) — pas par une convention markdown.

Choix Konsist plutôt que ArchUnit :
- Konsist voit les spécificités Kotlin : `sealed`/`data`/`internal`/`object`, extensions, expect/actual KMP.
- ArchUnit lit le bytecode (post-`javac`/`kotlinc`) et perd la sémantique Kotlin.
- Konsist est Kotlin-first, intègre plus simplement avec la stack Redface 2.

Règles implémentées dans `app/src/test/kotlin/fr/forumhfr/redface2/ArchitectureKonsistTest.kt` (la surface réelle est plus stricte que les exemples ci-dessous, qui restent illustratifs des invariants visés) :

```kotlin
class ArchitectureTest {
    @Test fun `features n'importent pas :core:data`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.path.contains("/feature/") }
            .imports
            .assertFalse { it.name.startsWith("redface.core.data.") }
    }

    @Test fun `ColorScheme Typography Shapes instantiés uniquement dans :core:ui`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.path.contains("/core/ui/") }
            .functions()
            .assertFalse { func ->
                func.hasReturnType { it.name in setOf("ColorScheme", "Typography", "Shapes") }
            }
    }

    // Activée Phase 1+ avec :core:network — cf. contributing.md § Konsist.
    // Tant qu'aucun code prefetch n'existe, le test n'a pas de surface à scanner
    // et ferait échouer Konsist sur scope vide.
    @Test fun `prefetch utilise AnonymousClient`() {
        Konsist.scopeFromProject()
            .functions()
            .filter { it.name.startsWith("prefetch") }
            .assertTrue { fn ->
                fn.parameters.any { it.hasAnnotationOf(AnonymousClient::class) }
            }
    }
}
```

Les tests Konsist tournent en CI dès Phase 0 et bloquent les PR qui violent les règles.

---

## Protocole HFR

HFR n'a pas d'API publique. Redface 2 fait du scraping HTML et doit respecter plusieurs invariants (CSRF `hash_check`, anti-bot `verifrequet`, `numreponse` par catégorie, cookies de session, prefetch non-authentifié).

**Source de vérité** : [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) — endpoints (`forum1.php`, `forum2.php`, `bddpost.php`, …), form fields par endpoint, `hash_check`, `verifrequet`, `numreponse`, `listenumreponse`, sessions, smileys, edge cases (posts supprimés, emails obfusqués, pagination, `cryptlink`), fixtures.
