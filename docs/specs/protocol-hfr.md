---
title: Protocole HFR
parent: Spécifications
nav_order: 9
permalink: /specs/protocol-hfr
---

# Protocole HFR
{: .fs-8 }

Endpoints, form fields, constantes et edge cases du protocole HFR utilisés par Redface 2.
{: .fs-5 .fw-300 }

---

## Préambule

HFR n'expose **aucune API publique**. Le client Redface 2 fait du **scraping HTML** sur les pages du forum, avec gestion de session par cookies. Cette page documente les invariants du protocole — constantes, form fields, anti-CSRF, anti-bot, optimisations JS inline — que **le LLM qui écrit le parser ou le client réseau doit respecter**.

Cette documentation est issue de la rétro-ingénierie du code de [Redface v1](https://github.com/ForumHFR/Redface) (Java + Retrofit 1.9) et de fixtures HTML réelles capturées depuis [forum.hardware.fr](https://forum.hardware.fr).

---

## Endpoints par fonctionnalité

| Fonctionnalité | Méthode | Endpoint | Auth requise |
|---|---|---|---|
| Page d'accueil (catégories) | GET | `/hfr/` ou `/` | non |
| Liste de topics d'une sous-catégorie | GET | `/forum2.php?config=hfr.inc&cat={cat}&subcat={subcat}&page={page}` | non (logué ↔ lu/non-lu visible) |
| Liste topics (rewrite SEO) | GET | `/hfr/{cat_slug}/{subcat_slug}/liste_sujet-{page}.htm` | non |
| Lecture d'un topic | GET | `/forum2.php?config=hfr.inc&cat={cat}&post={post}&page={page}` | non |
| Drapeaux (accueil Redface 2) | GET | `/forum1f.php?config=hfr.inc&owntopic={filter_id}` | **oui** |
| Login | POST | `/login_validation.php?config=hfr.inc&redirect=&url=` | — |
| Reply (post) | POST | `/bddpost.php?config=hfr.inc&cat={cat}` | **oui** |
| Edit (post) | POST | `/bdd.php?config=hfr.inc&cat={cat}` | **oui** |
| Edit FP (premier post) | POST | `/bdd.php?config=hfr.inc&cat={cat}` avec champ spécifique | **oui** |
| Nouveau topic | POST | `/bddpost.php?config=hfr.inc&cat={cat}&subcat={subcat}&new=1` | **oui** |
| MP (envoi) | POST | `/bddpost.php?config=hfr.inc&cat=prive&pseudo={dest}` | **oui** |
| Conversation MP | GET | `/message.php?config=hfr.inc&cat=prive&post={mp_id}&page={page}` | **oui** |
| Liste des MPs | GET | `/message.php?config=hfr.inc` | **oui** |
| Page d'édition d'un post | GET | `/message.php?config=hfr.inc&cat={cat}&post={post}&numreponse={numreponse}` | **oui** |
| Ajouter aux drapeaux | GET | `/user/addflag.php?config=hfr.inc&cat={cat}&post={post}&numreponse={numreponse}` | **oui** |
| Retirer des drapeaux | GET | `/user/delflag.php?config=hfr.inc&cat={cat}&post={post}&p=1&sondage=0&owntopic={0,1}&new=0` | **oui** |
| Profil public | GET | `/hfr/profil-{user_id}.htm` | non |
| Paramètres utilisateur | GET | `/editprofil.php?config=hfr.inc&page={1..7}` | **oui** |
| Modération (alerte) | GET/POST | `/modo.php?config=hfr.inc&cat={cat}&post={post}&numreponse={numreponse}` | **oui** |
| Recherche | GET | `/search.php?config=hfr.inc&search={query}&cat={cat}&...` | non |

> **Note sur `PRIVATE_MESSAGE_CAT_ID`** : la catégorie des MPs est la **chaîne** `"prive"` et non un entier. Attention lors du typage côté Kotlin — `cat: String` pour les endpoints MP ou sentinel dédié.

---

## Form fields critiques

### POST `bddpost.php` (reply ou nouveau topic)

| Field | Valeur | Obligatoire | Description |
|---|---|---|---|
| `hash_check` | `<token>` extrait de la page GET précédente | **oui** | Anti-CSRF. Voir section dédiée. |
| `verifrequet` | `"1100"` | **oui** | Constante anti-bot. String, pas entier. |
| `cat` | ID catégorie | oui | ou `"prive"` pour MP |
| `post` | ID topic | oui si reply | Absent si nouveau topic |
| `MsgIcon` | `"1"` | conventionnel | Icône du message (1 = défaut) |
| `signature` | `"1"` | conventionnel | Inclure la signature |
| `wysiwyg` | `"0"` | conventionnel | Mode BBCode brut |
| `new` | `"0"` ou `"1"` | oui | `"1"` pour nouveau topic |
| `page` | `"1"` | conventionnel | |
| `p` | `"1"` | conventionnel | |
| `sondage` | `"0"` ou `"1"` | oui si topic | `"1"` si nouveau topic avec sondage |
| `owntopic` | `"0"` ou `"1"` | oui | Topic favori ? |
| `config` | `"hfr.inc"` | oui | Toujours `hfr.inc` pour HFR |
| `content_form` | contenu BBCode | oui | Le corps du message |
| `subject` | titre | oui si nouveau topic | Absent si reply |

### POST `bdd.php` (edit)

| Field | Valeur | Description |
|---|---|---|
| `hash_check` | `<token>` | Anti-CSRF |
| `verifrequet` | `"1100"` | Anti-bot |
| `cat` | ID catégorie | |
| `post` | ID topic | |
| `numreponse` | ID post | Post à éditer |
| `content_form` | nouveau contenu BBCode | |
| `subject` | nouveau sujet | Seulement si edit FP |
| `pollsondage` | données sondage | Seulement si edit FP avec sondage |

Le fait qu'une édition concerne le **premier post** (FP) vs un post normal est déduit côté client (`isFirstPostOwner`) puis pris en compte dans la construction du form.

### POST `login_validation.php`

| Field | Valeur | Description |
|---|---|---|
| `pseudo` | username | |
| `password` | password (plaintext) | **Attention** : HFR attend le password en clair dans le form POST (over HTTPS). Ne pas hasher côté client. |

Détection du succès : cookie `md_user` présent dans la réponse. Détection de l'échec : pattern `Votre mot de passe ou nom d'utilisateur n'est pas valide` dans le body HTML.

---

## Constantes anti-CSRF et anti-bot

### `hash_check` — anti-CSRF

Chaque page HFR qui autorise un POST (édition, création, action sur le profil) **embarque** un champ caché dans le DOM :

```html
<input type="hidden" name="hash_check" value="<token>" />
```

**Règle** : avant tout POST, le client doit :

1. Faire un GET sur la page d'édition/création appropriée.
2. Extraire `hash_check` via la regex ou sélecteur CSS `input[name="hash_check"]`.
3. Injecter la valeur dans le form POST.
4. Si `hash_check` est absent du DOM → **fail fast visible** (erreur explicite, pas silencieuse). Le POST ne doit **jamais** partir sans.

En v1, le code `HashcheckExtractor.java` utilisait la regex suivante — à reproduire ou équivalent Jsoup :

```kotlin
val hashCheck = document.select("input[name=hash_check]").attr("value")
require(hashCheck.isNotBlank()) { "hash_check absent — le POST serait silencieusement rejeté par HFR" }
```

### `verifrequet = "1100"`

Constante anti-bot statique, présente dans **tous** les POST vers HFR. Valeur littérale `"1100"` (string, pas un nombre dynamique).

En Kotlin, à constanter dans `:core:network` :

```kotlin
object HfrConstants {
    const val VERIF_REQUET = "1100"
    const val CONFIG = "hfr.inc"
    const val PRIVATE_MESSAGE_CAT = "prive"
}
```

---

## `numreponse` — unique par catégorie, pas globalement

Le `numreponse` d'un post est unique **au sein d'une catégorie** (`cat=X`). Deux posts dans deux catégories différentes peuvent avoir le **même** `numreponse`. Le triplet `(cat, post, numreponse)` est unique globalement.

### Conséquences pour le code

- **Base Room** : `numreponse` seul **n'est pas** une clé primaire valide. Utiliser une clé composite :

  ```kotlin
  @Entity(
      tableName = "posts",
      primaryKeys = ["cat", "numreponse"],
      indices = [Index(value = ["cat", "post"])],
  )
  data class PostEntity(
      val cat: Int,
      val post: Int,         // topic ID
      val numreponse: Int,
      // ...
  )
  ```

- **Deep linking** : toujours inclure `cat` ET `numreponse` (optionnellement `post` pour la page). Un deep link qui ne fournit qu'un `numreponse` est ambigu.

- **Recherche** : les résultats contiennent toujours `(cat, post, numreponse)` — ne pas perdre `cat` quand on stocke un résultat pour navigation ultérieure.

---

## `listenumreponse` — optimisation JS inline

Chaque page topic HFR embarque un script inline du type :

```html
<script type="text/javascript">
var listenumreponse = new Array();
listenumreponse[0] = 1234567;
listenumreponse[1] = 1234570;
// ...
listenumreponse[39] = 1234999;
</script>
```

Ce tableau contient les `numreponse` des posts **de la page courante**. Redface v1 **ne l'utilisait pas**. Opportunité pour v2 :

- Identifier rapidement quels posts sont sur une page sans parser tous les blocs HTML
- Détecter si un `numreponse` attendu (ex : après un reply) est présent dans la réponse
- Optimiser le prefetch et la réactualisation

Extraction recommandée :

```kotlin
val listeNumreponse: List<Int> = Regex("""listenumreponse\[\d+\]\s*=\s*(\d+)""")
    .findAll(html)
    .map { it.groupValues[1].toInt() }
    .toList()
```

Voir le skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) (étape 3) pour la procédure d'extraction des variables JS inline.

---

## `cryptlink` — protection des URLs externes

HFR peut "crypter" certaines URLs externes (anti-scraping, tracking). Redface v1 ne gère pas explicitement ce cas (pas de transformer dédié) — les URLs sont simplement relayées au navigateur.

**Décision v2** : idem v1 — ne pas déchiffrer, relayer en l'état. Si l'UX en pâtit, revoir cette décision en Phase 2 via un transformer dédié.

---

## Smileys

Deux sources distinctes :

| Type | URL de base | Exemple |
|---|---|---|
| Smileys built-in HFR | `https://forum-images.hardware.fr/icones/` | `:jap:` → `/icones/smilies/jap.gif` |
| Smileys custom utilisateur | `https://forum-images.hardware.fr/images/perso/{user_id}/` | Upload par l'utilisateur via `editprofil.php?page=5` |

**Règles de rendu** :

- Cache Coil agressif (les smileys ne changent jamais) : `CachePolicy.ENABLED` + disque infini
- Extraire le code smiley (`:jap:`, `:bounce:`) depuis l'attribut `alt` de l'`<img>` pour pouvoir le re-saisir côté éditeur
- Les smileys custom d'un utilisateur sont exposés dans son profil (section `perso`)
- Un catalogue "wiki smileys" est disponible via `message-smi-mp-aj.php` (recherche de smileys)

---

## Sessions, cookies et 403

### Cookies HFR

| Cookie | Rôle | Durée |
|---|---|---|
| `md_user` | ID utilisateur — **indicateur de session active** | 1 an |
| `md_pass` | Token de session | 1 an |
| `md_forum` | Identifiant de forum | session |
| Cookies divers (tracking interne HFR) | — | variable |

**Indicateur de session active** : présence du cookie `md_user`. Si une réponse HTTP redirige vers la page de login ou si le DOM ne contient plus le pseudo de l'utilisateur connecté, la session a expiré.

### Détection et recovery de session expirée

Un `Interceptor` OkHttp :

1. Détecte HTTP 302 vers `/login.php` ou absence du pseudo dans la réponse.
2. Émet un événement `SessionExpired`.
3. `RedfaceApp` (`NavDisplay`) réinitialise le back stack courant sur la route `Auth` et efface le cache Room.

L'utilisateur ré-entre son mot de passe (Option A : pas de re-login transparent, le password n'est pas stocké — voir [architecture.md#stockage-sécurisé-des-credentials](architecture.md#stockage-sécurisé-des-credentials)).

### 403 / rate limiting

HFR n'expose pas officiellement de header `Retry-After`. En pratique :

- HTTP 429 ou 403 sur des requêtes répétées → backoff exponentiel (2s, 4s, 8s, max 60s)
- Rate limit client-side : max 2 requêtes/s sur les endpoints POST (reply, edit, flag)
- Aucune information officielle sur les seuils HFR — ces valeurs sont empiriques

---

## Règle critique : prefetch **non-authentifié**

Les requêtes de prefetch (pages suivantes d'un topic, drapeaux préchargés) **ne doivent jamais** inclure les cookies de session HFR.

**Raison** : HFR met à jour les drapeaux (topics marqués comme lus) sur **toute** requête authentifiée. Un prefetch avec session marquerait silencieusement les topics comme lus, ce qui est exactement le bug que Redface v1 présentait.

**Implémentation** :

```kotlin
class NetworkModule {
    @Provides @AuthenticatedClient
    fun provideAuthClient(@UserCookieJar jar: CookieJar): OkHttpClient = /* ... */

    @Provides @AnonymousClient
    fun provideAnonymousClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)  // ou un CookieJar vide dédié
        .build()
}

class HfrClient @Inject constructor(
    @AuthenticatedClient private val auth: OkHttpClient,
    @AnonymousClient private val anon: OkHttpClient,
) {
    suspend fun fetchTopicPage(cat: Int, post: Int, page: Int): String = /* auth */
    suspend fun prefetchTopicPage(cat: Int, post: Int, page: Int): String = /* anon */
}
```

Un test Konsist enforce la règle : tout appel à `prefetch*` doit utiliser `@AnonymousClient`.

Confirmé par Corran Horn sur le topic HFR Redface 2 : *« en utilisant un cookie d'un compte anonyme pour pas péter les drapeaux »*.

---

## Autres edge cases documentés

### Posts édités

Pattern dans le HTML des posts : `Message édité par <auteur> le DD-MM-YYYY à HH:MM:SS`. Extraire côté parser en champ `Post.editedAt: Instant?`.

### Posts supprimés / modérés

Structure HTML altérée : le `<table class="messagetable">` peut ne plus contenir que le bandeau d'auteur + une mention de suppression. Le parser doit gérer ce cas sans crasher — `Post.content` devient un `PostContent` vide ou un bloc sentinel `"Message supprimé"`.

### Emails obfusqués

HFR obfusque les emails dans les profils publics. Le texte visible est souvent `"Vous n'avez pas accès à cette information"` ou un email brouillé. **Ne pas** tenter de déobfusquer — conserver la string brute.

### Pagination edge case

Si la meta description HTML `Pages : N` est absente ou malformée, utiliser `UNKNOWN_PAGES_COUNT = -1` (sentinel) et recalculer côté client en naviguant. Fixture `topic_last_page.html` couvre ce cas (page partielle avec moins de 40 posts).

### `postsPerPage` configurable

Le nombre de posts par page est un **réglage utilisateur HFR** (`editprofil.php?page=3`), pas une constante. Ne **jamais** hardcoder 40 dans le code. Le parser lit la valeur depuis la page de paramètres à la connexion et la stocke dans `UserSettings.postsPerPage`.

---

## Fixtures HTML

Les fixtures de test du parser vivent dans `core/parser/src/test/resources/fixtures/` (à créer en Phase 0). Chaque fixture doit être :

- **Capturée depuis HFR réel** (jamais fabriquée par une IA ou à la main)
- **Nettoyée** des données sensibles avant commit : cookies, `hash_check`, emails, identifiants réels, URLs signées
- **Annotée** avec sa source HFR (URL ou `cat=X, post=Y, numreponse=Z`) dans un fichier `.source.txt` frère ou en commentaire en tête du HTML

Catalogue complet : voir [`contributing.md#fixtures-html-pour-le-parser`](contributing.md#fixtures-html-pour-le-parser).

Pour capturer une fixture : utiliser le MCP `hfr-mcp` avec `hfr_read output=path/to/fixture.html` (écrit le HTML brut), puis appliquer le skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) pour générer l'analyse structurée.

---

## Sources

- [Redface v1 code](https://github.com/ForumHFR/Redface/tree/master/app/src/main/java/com/ayuget/redface/data/api/hfr)
- [Redface v1 fixtures](https://github.com/ForumHFR/Redface/tree/master/app/src/test/resources)
- [MesDiscussions SDK (Wayback Machine)](https://web.archive.org/web/*/mesdiscussions.net) — ancienne doc partielle des paramètres URL HFR
- Skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) pour l'analyse d'une fixture
- MCP [`hfr-mcp`](https://github.com/XaaT/hfr-mcp) pour interagir avec forum.hardware.fr depuis les agents LLM
