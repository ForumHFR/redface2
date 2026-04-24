---
title: Architecture
parent: Spécifications
nav_order: 2
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
    APP[":app"] --> FF[":feature:forum"]
    APP --> FT[":feature:topic"]
    APP --> FE[":feature:editor"]
    APP --> FM[":feature:messages"]
    APP --> FA[":feature:auth"]
    APP --> FS[":feature:settings"]
    APP --> FSR[":feature:search"]
    APP --> CDATA[":core:data"]

    FF --> CDOM[":core:domain"]
    FF --> CU[":core:ui"]

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

    CN --> CM
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
| `:core:data` | Implémentations des repositories. Orchestre réseau, parser et cache. Fournit les bindings Hilt. | `:core:domain`, `:core:network`, `:core:parser`, `:core:database` |
| `:core:network` | `HfrClient` : requêtes HTTP, cookies, session, login. Encapsule OkHttp. | `:core:model` |
| `:core:parser` | `HfrParser` : transforme le HTML HFR et le BBCode HFR en modèles domaine, dont l'AST `PostContent`. | `:core:model` |
| `:core:database` | Room DB, DAOs, entities, mappers entity↔model. Cache locale + cache MPStorage. | `:core:model` |
| `:core:ui` | Thème Material 3 (6 sous-packages : `theme/`, `components/`, `adaptive/`, `semantics/`, `util/`, `extensions/`), composants partagés, `PostRenderer` (`PostContent` → Compose). Seul module autorisé à instancier `ColorScheme`, `Typography`, `Shapes`. | `:core:model` |
| `:core:extension` | Interfaces d'extension : `PostDecorator`, `TopicToolbarContributor`, `EditorToolbarContributor`. | `:core:model` |

### Modules feature (base)

Les features ne dépendent que de `:core:domain` (interfaces) et `:core:ui` (composants partagés). Elles ne connaissent pas la couche données — Hilt injecte les implémentations depuis `:core:data`.

| Module | Écrans | Dépend de |
|--------|--------|-----------|
| `:feature:forum` | Catégories, sous-catégories, liste de topics | `:core:domain`, `:core:ui` |
| `:feature:topic` | Lecture de topic, pagination | `:core:domain`, `:core:ui`, `:core:extension` |
| `:feature:editor` | Reply, edit, edit FP, preview BBCode, création topic | `:core:domain`, `:core:ui`, `:core:extension` |
| `:feature:messages` | MPs classiques, MultiMPs, création MP/MultiMP | `:core:domain`, `:core:ui` |
| `:feature:auth` | Login HFR | `:core:domain`, `:core:ui` |
| `:feature:search` | Recherche dans les topics et posts, filtres | `:core:domain`, `:core:ui` |
| `:feature:settings` | Préférences, thème, gestion cache | `:core:domain`, `:core:ui` |

### Modules feature (extensions communautaires — Phase 4)

Les 8 modules extension arrivent en **Phase 4** uniquement. En Phases 0 à 3, le projet compte 15 modules (8 core + 7 features base). Les extensions sont des modules Gradle isolés qui s'enregistrent via Hilt `@IntoSet` — ajouter une extension ne demande aucune modification du code existant. La décision de découpage v1 est formalisée dans [ADR-001]({{ site.baseurl }}/adr/001-modules-gradle-v1).

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
- Définit le `NavGraph` (navigation globale)
- Contient `MainActivity`
- Dépend de tous les modules feature (base + extensions)

---

## Séparation des responsabilités

### `:core:domain` — interfaces

Les interfaces de repositories vivent dans le module domaine. Aucune dépendance framework.

```kotlin
// Dans :core:domain — le contrat
interface TopicRepository {
    suspend fun getTopic(cat: Int, post: Int, page: Int): Result<Topic>
    suspend fun prefetchNextPage(cat: Int, post: Int, page: Int)
}

interface FlagRepository {
    suspend fun getFlags(): Result<List<FlaggedTopic>>
    suspend fun removeFlag(topic: FlaggedTopic): Result<Unit>
}

interface AuthRepository {
    suspend fun login(username: String, password: String): Result<Unit>
    suspend fun isLoggedIn(): Boolean
}
```

### `:core:network` — HfrClient

Le client HTTP ne parse rien. Il retourne du HTML brut ou des confirmations d'action.

```kotlin
class HfrClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    suspend fun fetchTopicPage(cat: Int, post: Int, page: Int): String
    suspend fun fetchFlags(): String
    suspend fun postReply(cat: Int, post: Int, content: String): Result<Unit>
    suspend fun editPost(cat: Int, post: Int, numreponse: Int, content: String): Result<Unit>
    suspend fun login(username: String, password: String): Result<Unit>
    // ...
}
```

### `:core:parser` — HfrParser

Le parser transforme le HTML HFR et le BBCode HFR en modèles domaine. Isolé de toute logique réseau et UI.

```kotlin
class HfrParser @Inject constructor() {
    fun parseTopicPage(html: String): Topic
    fun parsePostContentFromHtml(html: String): PostContent
    fun parsePostContentFromBbcode(bbcode: String): PostContent
    fun parseFlags(html: String): List<FlaggedTopic>
    fun parseCategories(html: String): List<Category>
    fun parseEditPage(html: String): EditInfo
    fun parseMessageList(html: String): List<PrivateMessage>
    // ...
}
```

### `:core:data` — implémentations

Les implémentations de repositories orchestrent réseau, parser et cache. Elles vivent dans `:core:data`, jamais dans les features.

```kotlin
// Dans :core:data — l'implémentation
class TopicRepositoryImpl @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val topicDao: TopicDao,
) : TopicRepository {

    override suspend fun getTopic(cat: Int, post: Int, page: Int): Result<Topic> {
        // 1. Vérifier le cache
        topicDao.getCached(cat, post, page)?.let { return Result.success(it) }

        // 2. Fetch + parse
        return runCatching {
            val html = client.fetchTopicPage(cat, post, page)
            val topic = parser.parseTopicPage(html)

            // 3. Mettre en cache
            topicDao.insert(topic.toEntity())

            topic
        }
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
    abstract fun bindFlagRepository(impl: FlagRepositoryImpl): FlagRepository
}
```

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

| Donnée | Stratégie | Durée |
|--------|-----------|-------|
| Topics lus | Cache Room, invalidation au refresh | Jusqu'au refresh |
| Drapeaux | Cache Room, refresh au lancement + pull-to-refresh | 5 min TTL |
| Catégories | Cache Room, rarement change | 24h TTL |
| Smileys | Cache Coil, ne changent jamais | Infini |
| Avatars | Cache Coil, ETag | 1h TTL |
| MultiMP flags | Room, jamais expire (donnée locale) | Permanent |
| Préférences | DataStore | Permanent |

### Prefetch intelligent

Pour donner l'impression que le forum est local :

```
Utilisateur lit la page 3 d'un topic
  → Prefetch page 4 en arrière-plan
  → Quand il scroll vers le bas, la page 4 est déjà prête

Utilisateur ouvre ses drapeaux
  → Prefetch les 3 premiers topics (ceux qu'il ouvre le plus souvent)
```

Le prefetch respecte les conditions réseau : désactivé en mode économie de données ou réseau lent.

#### Règle critique : prefetch non-authentifié

Les requêtes de prefetch ne doivent **jamais** inclure les cookies de session — sinon HFR marque silencieusement les topics comme lus. Implémentation avec deux instances `OkHttpClient` (`@AuthenticatedClient` / `@AnonymousClient`) et test Konsist d'enforcement : voir [protocol-hfr.md § Règle critique prefetch non-authentifié]({{ site.baseurl }}/specs/protocol-hfr#règle-critique--prefetch-non-authentifié).

---

## Gestion de session

HFR utilise des cookies de session. Le flow d'authentification :

```mermaid
sequenceDiagram
    participant App
    participant OkHttp
    participant HFR

    App->>OkHttp: login(user, pass)
    OkHttp->>HFR: POST /login_validation.php
    HFR-->>OkHttp: Set-Cookie md_user, md_pass
    OkHttp->>OkHttp: CookieJar stocke les cookies

    Note over App,HFR: Toutes les requêtes suivantes incluent les cookies

    App->>OkHttp: fetchFlags()
    OkHttp->>HFR: GET /forum1f.php + cookies
    HFR-->>OkHttp: HTML drapeaux
    OkHttp-->>App: HTML brut
```

Les cookies sont persistés via un `PersistentCookieJar` adossé au DataStore chiffré (voir § Stockage sécurisé ci-dessous) pour éviter de se re-logguer à chaque lancement.

### Stockage sécurisé des credentials

**Option A retenue** (cycle [#24](https://github.com/ForumHFR/redface2/issues/24) thème 13, formalisée dans [ADR-002]({{ site.baseurl }}/adr/002-credentials-option-a)) : stack minimaliste **DataStore + Android Keystore**, **pas de Tink**, **pas de password stocké**.

**Ce qui est stocké** : uniquement les **cookies de session HFR** (`md_user`, `md_pass`) — nécessaires pour rester connecté entre deux lancements de l'app.

**Ce qui n'est pas stocké** : le mot de passe en clair de l'utilisateur. À l'expiration de session (cookies invalidés côté HFR), l'app redirige vers l'écran de login — l'utilisateur ré-entre son mot de passe. Pas de re-login transparent silencieux.

> **Note** : `EncryptedSharedPreferences` (AndroidX Security) est déprécié à partir de `security-crypto 1.1.0-beta01` (04/06/2025), puis marqué deprecated en `1.1.0`. La release note officielle demande de préférer les APIs plateforme et l'usage direct d'Android Keystore ; les problèmes de StrictMode et de corruption de keyset observés sur le terrain restent des signaux supplémentaires, pas la formulation officielle canonique.

**Implémentation** :

```kotlin
// 1. Clé AES/GCM dans Android Keystore (non extractible, TEE/StrongBox si dispo)
private fun getOrCreateSessionKey(): SecretKey {
    val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply { init(spec) }
        .generateKey()
}

// 2. Chiffrement direct Cipher (pas de lib wrapper)
fun encrypt(plaintext: ByteArray): Ciphertext {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSessionKey())
    return Ciphertext(iv = cipher.iv, data = cipher.doFinal(plaintext))
}

// 3. DataStore pour la persistance (IV + ciphertext stockés ensemble)
@Serializable
data class SessionCookies(
    val mdUser: EncryptedBlob,
    val mdPass: EncryptedBlob,
)
```

**Rationale Option A (vs DataStore + Tink + Keystore envisagé initialement)** :
- Tink est **overkill pour un seul secret** (session cookie). Sa valeur est dans le keyset management, AEAD streaming, multi-secret rotation — aucun n'est utile ici.
- Moins de dépendances = moins de surface d'attaque et moins de maintenance.
- Pas de password stocké = pas de question "que faire si la clé Keystore est invalidée" (re-login = user input = zéro magie).
- Pas de biométrie : la clé Keystore est protégée par le TEE/StrongBox du device, suffisant pour un forum.

---

## Gestion des erreurs

### Session expirée

Un `Interceptor` OkHttp détecte la redirection vers la page de login (HTTP 302 ou absence du cookie `md_user` dans la réponse). Il émet un événement `SessionExpired`. Le `NavGraph` redirige vers l'écran de login — l'utilisateur ré-entre son mot de passe (Option A, pas de re-login transparent : le password n'est pas stocké).

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

Règles prévues dans `build-logic/src/main/kotlin/redface/Architecture.kt` (exemples) :

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
