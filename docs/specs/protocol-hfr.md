---
title: Protocole HFR
parent: Spécifications
nav_order: 8
permalink: /specs/protocol-hfr
---

# Protocole HFR
{: .fs-8 }

Endpoints, form fields, constantes et edge cases du protocole HFR utilisés par Redface 2.
{: .fs-5 .fw-300 }

---

## Préambule

HFR expose deux surfaces consommables côté client :

1. **HTML sur les endpoints `forum*.php`** — le scraping historique, toujours en place pour la lecture des posts, les MPs, le login et toutes les mutations en v1 (`bddpost.php`, `addflag.php`, `delflag.php`).
2. **JSON sur `/webservices/rest_api.php`** — une API REST partielle exposée par MesDiscussions (le moteur du forum), retrouvée et instrumentée fin avril 2026, qui couvre la portion **browsing** : catégories, sous-catégories, listings de topics, drapeaux personnels, metadata d'un topic. Décision de stratégie hybride : [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride).

Cette page documente les invariants des deux surfaces — constantes, form fields, anti-CSRF, anti-bot, optimisations JS inline, contrat REST — que **le LLM qui écrit le parser, le client réseau ou les mappers REST doit respecter**.

La documentation HTML est issue de la rétro-ingénierie du code de [Redface v1](https://github.com/ForumHFR/Redface) (Java + Retrofit 1.9) et de fixtures HTML réelles capturées depuis [forum.hardware.fr](https://forum.hardware.fr). La documentation REST est issue de tests live 2026-05-01 sur le contrat MesDiscussions V1 (doc Confluence retrouvée sur [Wayback Machine](https://web.archive.org/web/2018/help.mesdiscussions.net/pages/viewpage.action?pageId=5013586)) et des 6 fixtures JSON capturées au même moment.

---

## Endpoints par fonctionnalité

| Fonctionnalité | Méthode | Endpoint | Auth requise |
|---|---|---|---|
| Page d'accueil (catégories) | GET | `/hfr/` ou `/` | non |
| Liste de topics d'une sous-catégorie | GET | `/forum2.php?config=hfr.inc&cat={cat}&subcat={subcat}&page={page}` | non (logué ↔ lu/non-lu visible) |
| Liste topics (rewrite SEO) | GET | `/hfr/{cat_slug}/{subcat_slug}/liste_sujet-{page}.htm` | non |
| Lecture d'un topic | GET | `/forum2.php?config=hfr.inc&cat={cat}&post={post}&page={page}` | non |
| Drapeaux (accueil Redface 2) — REST | GET | `/webservices/rest_api.php?uri=forums/hardwarefr/topics/{participated,read,favorites}/&page={page}&results_per_page={n}` | **oui** |
| Drapeaux par catégorie — REST | GET | `/webservices/rest_api.php?uri=forums/hardwarefr/categories/{cat}/topics/{participated,read,favorites}/&page={page}&results_per_page={n}` | **oui** |
| Login | POST | `/login_validation.php?config=hfr.inc&redirect=&url=` | — |
| Formulaire reply | GET | `/message.php?config=hfr.inc&cat={cat}&post={post}&page={page}&p=1&subcat={subcat}&sondage=0&owntopic=0&new=0` | **oui** |
| Formulaire quote | GET | `/message.php?config=hfr.inc&cat={cat}&post={post}&numrep={numreponse}&ref={ref}&page={page}&p=1&subcat={subcat}&sondage=0&owntopic=0&new=0#formulaire` | **oui** |
| Formulaire edit post | GET | `/message.php?config=hfr.inc&cat={cat}&post={post}&page={page}&p=1&subcat={subcat}&sondage=0&owntopic=0&new=0&numreponse={numreponse}` | **oui** |
| Formulaire nouveau topic | GET | `/message.php?config=hfr.inc&cat={cat}&subcat={subcat}&sondage=0&owntopic=0&new=0` | **oui** |
| Reply / quote (post) | POST | `/bddpost.php?config=hfr.inc` | **oui** |
| Edit (post) | POST | `/bdd.php?config=hfr.inc` | **oui** |
| Edit FP (premier post) | POST | `/bdd.php?config=hfr.inc` avec champs spécifiques | **oui** |
| Suppression post/topic owned | POST | `/bdd.php?config=hfr.inc` avec `delete=1` | **oui** |
| Nouveau topic | POST | `/bddpost.php?config=hfr.inc` | **oui** |
| MP (envoi) | POST | `/bddpost.php?config=hfr.inc&cat=prive&pseudo={dest}` | **oui** |
| Conversation MP | GET | `/message.php?config=hfr.inc&cat=prive&post={mp_id}&page={page}` | **oui** |
| Liste des MPs | GET | `/forum1.php?config=hfr.inc&cat=prive&page={page}&subcat=&sondage=0&owntopic=0&trash=0&trash_post=0&moderation=0&new=0&nojs=0&subcatgroup=0` | **oui** |
| Ajouter aux drapeaux | GET | `/user/addflag.php?config=hfr.inc&cat={cat}&post={post}&numreponse={numreponse}` | **oui** |
| Retirer des drapeaux | GET | `/user/delflag.php?config=hfr.inc&cat={cat}&post={post}&p=1&sondage=0&owntopic={0,1}&new=0` | **oui** |
| Profil public | GET | `/hfr/profil-{user_id}.htm` | non |
| Paramètres utilisateur | GET | `/editprofil.php?config=hfr.inc&page={1..7}` | **oui** |
| Modération (alerte) | GET/POST | `/modo.php?config=hfr.inc&cat={cat}&post={post}&numreponse={numreponse}` | **oui** |
| Recherche | GET | `/search.php?config=hfr.inc&search={query}&cat={cat}&...` | non |

> **Note sur `PRIVATE_MESSAGE_CAT_ID`** : la catégorie des MPs est la **chaîne** `"prive"` et non un entier. Attention lors du typage côté Kotlin — `cat: String` pour les endpoints MP ou sentinel dédié.

> **Note sur l'URL "Liste des MPs"** : l'endpoint canonique est `forum1.php?config=hfr.inc&cat=prive&...`, **pas** `message.php?config=hfr.inc` (qui ouvre le composer d'un MP isolé). Vérifié dans le legacy v1 (`HFREndpoints.PRIVATE_MESSAGES_URL`, prouvé en prod ~10 ans) et reproduit dans `:core:network HfrClient.getPrivateMessageListPage()` de Phase 1B.1. Toute la chaîne de query params (`subcat=`, `sondage=0`, `owntopic=0`, etc.) est conservée à l'identique du legacy par défensif — HFR pourrait accepter une URL plus courte mais ce n'est pas testé.

---

## Form fields critiques

### POST `bddpost.php` (reply, quote ou nouveau topic)

Contrat recapturé sur HFR réel le 2026-05-17 avec le compte de test `XaTelitte`, topic Redface 2 `cat=23`, `post=35395`, `subcat=550`, post de test `numreponse=2784595`. Fixtures de référence : `write_reply_form_open_topic.html`, `write_quote_form_test_post.html`, `write_create_topic_form_android_cat.html`, `write_reply_success_response.html`, `write_empty_message_error.html`, `write_invalid_token_error.html`, `write_antiflood_error.html`, `write_locked_topic_page.html`, `write_reply_locked_topic_forced_form.html`, `write_locked_topic_error.html`.

| Field | Valeur | Obligatoire | Description |
|---|---|---|---|
| `hash_check` | `<token>` extrait de la page GET précédente | **oui** | Anti-CSRF. Voir section dédiée. |
| `verifrequet` | `"1100"` | **oui** | Constante anti-bot. String, pas entier. |
| `cat` | ID catégorie | oui | ou `"prive"` pour MP |
| `post` | ID topic | oui si reply | Vide si nouveau topic. |
| `subcat` | ID sous-catégorie | oui | Observé `550` pour Android. |
| `numreponse` | `""` | oui | Vide sur reply/quote/create. Ne pas confondre avec `numrep`. |
| `numrep` | `""` ou numreponse cité | oui | Vide en reply simple. Renseigné en quote. |
| `MsgIcon` | `"1"` | conventionnel | Icône du message (1 = défaut) |
| `signature` | `"1"` | conventionnel | Inclure la signature |
| `wysiwyg` | `"0"` | conventionnel | Mode BBCode brut |
| `new` | `"0"` observé | oui | Même le formulaire de création topic observé expose `new="0"`. Ne pas supposer `1` sans nouvelle capture. |
| `page` | page topic courante | oui | Observé `20` sur reply/quote/edit ; `1` sur nouveau topic. |
| `p` | `"1"` | conventionnel | |
| `sondage` | `"0"` ou `"1"` | oui si topic | `"1"` si nouveau topic avec sondage |
| `sond` | `"0"` | oui | Présent dans les formulaires observés. |
| `cache` | `"cache"` | oui | Présent dans les formulaires observés. |
| `owntopic` | `"0"` ou `"1"` | oui | Topic favori ? |
| `config` | `"hfr.inc"` | oui | Toujours `hfr.inc` pour HFR |
| `sujet` | titre topic | oui | Champ réel observé. Pas `subject`. En reply/quote il reprend le titre existant. |
| `content_form` | contenu BBCode | oui | Le corps du message |
| `from_subcat` | ID sous-catégorie | nouveau topic | Présent sur le formulaire de création topic. |
| `toread1..5` | options visibles | nouveau topic | Présents sur le formulaire de création topic, à traiter comme opaques avant implémentation sondage/options. |

#### Reply simple

Le formulaire reply est obtenu par GET `message.php`. Le POST part vers `bddpost.php?config=hfr.inc`.

Champs observés sur le topic Redface 2 page 20 :

- `post=35395`
- `cat=23`
- `subcat=550`
- `page=20`
- `numreponse=""`
- `numrep=""`
- `sujet="Redface 2 — PHASE 2 @ ALPHA"`
- `content_form` vide avant saisie utilisateur

#### Quote

Le lien `quote+` utilise `numrep`, pas `numreponse` :

```text
GET /message.php?...&post=35395&numrep=2784595&ref=0&page=20&...
```

HFR préremplit ensuite `content_form` :

```bbcode
[quotemsg=2784595,768,1214571]...[/quotemsg]
```

Le premier paramètre est le `numreponse` cité. Le troisième paramètre observé correspond à l'ID utilisateur de l'auteur cité. Le second paramètre (`768` dans la capture du post `2784595`, `640` dans une capture antérieure page 16) est une position/index HFR à traiter comme **opaque** tant que son calcul exact n'a pas été confirmé. Pour le MVP, Redface 2 doit récupérer le formulaire quote côté HFR et réutiliser le `content_form` prérempli, au lieu de reconstruire `[quotemsg=...]` localement.

#### Succès et erreurs reply

Réponse succès observée après POST `bddpost.php?config=hfr.inc` :

```text
Votre réponse a été postée avec succès !
```

La réponse succès ne contient pas le message posté. Le client doit recharger la page topic et localiser le nouveau `numreponse` dans le topic. Lors du test anti-flood, les trois réponses consécutives acceptées ont créé `numreponse=2784599`, `2784600`, puis `2784601`.

Erreurs observées :

| Cas | Réponse HFR | Impact client |
|---|---|---|
| `content_form` vide avec `hash_check` valide | `Vous devez remplir tous les champs avant de poster ce message` | Rester dans l'éditeur, afficher l'erreur. |
| `hash_check` invalide avec contenu non vide | `Une erreur est survenue lors de l'envoi des données. Essayez de vider le cache de votre navigateur` | Recharger le formulaire avant retry ; ne pas rejouer le POST tel quel. |
| Plus de 3 réponses consécutives en 10 minutes | `Afin de prevenir les tentatives de flood, vous ne pouvez poster plus de 3 réponses consécutives dans un intervalle de 10 minutes` | Bloquer temporairement l'envoi et proposer de réessayer plus tard. |
| Topic fermé | `Désolé ce sujet a été fermé...` | Bloquer l'éditeur et revenir au topic. |

> **Note anonyme** : GET `message.php` en session anonyme ne redirige pas immédiatement vers le login. HFR sert le même composer avec champs `pseudo` et `password` visibles (`write_reply_anonymous_form.html`, `write_create_topic_anonymous_form.html`). Redface 2 ne doit pas exposer ce mode legacy : l'app passe par login HFR explicite avant tout POST.

> **Note topic fermé** : la page topic fermée ne contient pas de lien reply (`write_locked_topic_page.html`). Si l'utilisateur force l'URL `message.php?...&post=14227`, HFR sert quand même un composer (`write_reply_locked_topic_forced_form.html`) mais le POST `bddpost.php` refuse (`write_locked_topic_error.html`). Le client ne doit donc pas se contenter de l'existence du formulaire pour conclure qu'un topic est éditable.

### POST `bdd.php` (edit)

Contrat recapturé sur HFR réel le 2026-05-17 avec le post de test `numreponse=2784595` sur le topic Redface 2, puis complété avec un topic temporaire owned en Programmation / Divers (`cat=10`, `post=148749`, `subcat=388`). Fixtures de référence : `write_edit_form_test_post.html`, `write_edit_success_response.html`, `write_created_owned_topic_page.html`, `write_edit_first_post_form.html`, `write_edit_first_post_success_response.html`, `write_delete_post_form.html`, `write_delete_post_success_response.html`, `write_delete_topic_form.html`, `write_delete_topic_success_response.html`, `write_deleted_topic_404.html`.

| Field | Valeur | Description |
|---|---|---|
| `hash_check` | `<token>` | Anti-CSRF |
| `verifrequet` | `"1100"` | Anti-bot |
| `cat` | ID catégorie | |
| `post` | ID topic | |
| `numreponse` | ID post | Post à éditer |
| `numrep` | `""` | Présent mais vide dans le formulaire d'édition observé. |
| `page` | page topic courante | Observé `20`. |
| `subcat` | ID sous-catégorie | Observé `550`. |
| `sujet` | titre topic | Champ réel observé. Pas `subject`. |
| `content_form` | nouveau contenu BBCode | |
| `pollsondage` | données sondage | Seulement si edit FP avec sondage |
| `delete` | `"1"` | Présent uniquement si l'utilisateur coche la suppression dans le formulaire d'édition. |

Le fait qu'une édition concerne le **premier post** (FP) vs un post normal est déduit côté client (`isFirstPostOwner`) puis pris en compte dans la construction du form. HFR expose aussi des champs supplémentaires sur le FP : `sujet` éditable en input texte, `subcat` en `<select>`, options sondage, et champs `toread1..5`.

Réponse succès observée après POST `bdd.php?config=hfr.inc` :

```text
Votre message a été édité avec succès !
```

La réponse succès ne contient pas le contenu édité. Le client doit recharger la page topic ou mettre à jour son cache local après succès.

#### Suppression post/topic

La suppression utilise le **même formulaire d'édition** que `bdd.php` :

- post normal owned : le formulaire expose `<input name="delete" value="1">` avec le libellé `Effacer ce message` ;
- premier post owned : le formulaire expose `<input name="delete" value="1">` avec le libellé `Effacer l'intégralité du sujet`.

Le POST conserve les champs d'édition (`hash_check`, `cat`, `post`, `numreponse`, `sujet`, `content_form`, etc.) et ajoute `delete=1`. HFR répond dans les deux cas avec le même message générique :

```text
Message effacé avec succès !
```

Dans la capture du topic temporaire `post=148749`, la suppression du FP a supprimé le topic complet : l'URL `forum2.php?...&post=148749` répond ensuite HTTP `404`, et la réponse succès contient un refresh vers la liste de sous-catégorie. Redface 2 doit donc :

1. traiter `delete=1` sur FP comme une action destructrice distincte "supprimer le topic" ;
2. demander une confirmation UI forte avant d'envoyer le POST ;
3. après succès, revenir à la liste de topics plutôt que tenter de recharger le topic supprimé.

### POST `login_validation.php`

URL complète : `POST https://forum.hardware.fr/login_validation.php?config=hfr.inc`

Form-encoded body (`application/x-www-form-urlencoded`) :

| Field | Valeur | Description |
|---|---|---|
| `pseudo` | username | |
| `password` | password (plaintext) | **Attention** : HFR attend le password en clair dans le form POST (over HTTPS). Ne pas hasher côté client. Pas de `hash_check` ni de GET préalable pour le login lui-même. |

Détection de la réponse (mirror de l'impl `:core:network/auth/AuthRemoteDataSource` Phase 1B.1) :

| Cas | Marqueur | Action client |
|---|---|---|
| Succès | cookie `Set-Cookie: md_user=<pseudo form-url-encoded>` présent et décodable vers le pseudo soumis | `AuthState.Authenticated(pseudo)`, cookies commités dans `PersistentCookieJar` |
| Mauvais identifiants | body contient `Votre mot de passe ou nom d'utilisateur n'est pas valide` | `LoginError.InvalidCredentials` |
| Anti-flood | body contient `Afin de prévenir les tentatives de flood` | `LoginError.RateLimited` (l'utilisateur attend quelques minutes et retente) |
| Cookie `md_user` absent | aucun cookie d'identité exploitable | `LoginError.Unknown("expected md_user cookie not set")` |
| Cookie `md_user` présent mais valeur décodée ≠ pseudo soumis | défensif : `AuthRemoteDataSource` refuse de revendiquer une autre identité | `LoginError.Unknown("md_user cookie does not match requested pseudo (...)")` |
| Tout autre format | aucun marqueur reconnu | `LoginError.Unknown(detail)` |

HFR encode la valeur du cookie `md_user` comme un form body (`application/x-www-form-urlencoded`) : espace `+`, accents `%XX`, etc. Le client doit donc décoder `md_user` avant comparaison (`Colonel MythO` ↔ `Colonel+MythO`). Ce contrat est couvert par le test `pseudo with space matches md_user cookie URL-form-encoded`.

Le POST login utilise un cookie jar de staging avec redirects désactivés : les `Set-Cookie` posés par une réponse 200 ou par la redirection 302 de login restent visibles pour classification, puis ne sont commités dans le `PersistentCookieJar` qu'après classification `Authenticated`. Une réponse classée `InvalidCredentials`, `RateLimited` ou `Unknown` ne doit jamais installer une session par effet de bord.

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

- **Source de vérité côté lecture** : c'est l'attribut `src` de l'`<img>` produit par HFR qui pilote le rendu, pas une table embarquée client-side. Le parser pose `PostInline.Smiley(kind, imageUrl)` directement à partir de l'`alt`/`title` (pour le `kind`) et du `src` (pour `imageUrl`) — `:core:ui` consomme `imageUrl` tel quel via `AsyncImage`. Pas de reconstruction d'URL à partir du nom (les chemins perso peuvent contenir des sous-dossiers numérotés `images/perso/<N>/`, des espaces encodés, et des variantes — la source HTML est la seule fiable).
- Cache Coil agressif (les smileys ne changent jamais) : `CachePolicy.ENABLED` + disque infini.
- **GIFs animés** : builtins comme perso peuvent être des `.gif` animés (`:bounce:`, `:pt1cable:`, majorité des perso). Le décodeur `coil-gif` (`AnimatedImageDecoder.Factory`, API 28+) est enregistré sur le `SingletonImageLoader` côté `:app` pour autoplay sans configuration par-call-site.
- **Tailles différentes** : les smileys ne sont pas tous 16×16. Le crawl exhaustif `wikismilies.php` réalisé pendant le dogfood a trouvé **34 139** smileys perso, avec une distribution très concentrée sur une ligne HFR de **50 px** : `70×50` (8047), `50×50` (2811), `67×50` (1142), puis beaucoup de variantes `W×50`; les micro-smileys existent aussi (`15×15` 701, `19×19` 399, `16×16` 206). Phase 1 utilise une politique de buckets (`:core:ui` `PostMediaDisplayPolicy`) : builtin **bucket fixe 18×18**, perso **bucket fixe 70×50**. Les smileys utilisent `ContentScale.Fit` pour rester lisibles sur smartphone et préserver le ratio : un sprite `15×15` devient `50×50`, un `39×15` devient `70×27`, un `50×50` reste `50×50`, un `70×50` reste `70×50`, un `200×150` descend à `67×50` (à density/fontScale = 1). Les images inline `[img]` gardent `ContentScale.Inside` pour éviter d'agrandir une petite image arbitraire dans le bucket `240×180`. L'enfant `AsyncImage` du `Placeholder` utilise `Modifier.fillMaxSize()` au lieu d'un `Modifier.size(.dp)` figé — l'image suit ainsi le placeholder en `sp` même quand `fontScale ≠ 1` (accessibility). `PlaceholderVerticalAlign.Center`. Invariant pinned dans les tests : `persoSmiley.placeholderHeight ≤ 2.5 × bodyMedium.lineHeight` (lecture dynamique sur `RedfaceTypography`, `20.sp` aujourd'hui). Mesure intrinsèque via `ImageLoader.execute()` ou catalogue embarqué complet reportés Phase 2/4 si le bucket fixe reste insuffisant.
- Extraire le code smiley (`:jap:`, `:bounce:`) depuis l'attribut `alt`/`title` de l'`<img>` pour pouvoir le re-saisir côté éditeur — c'est un sujet **éditeur Phase 2**, pas le chemin principal de lecture.
- Les smileys custom d'un utilisateur sont exposés dans son profil (section `perso`).
- Un catalogue "wiki smileys" est disponible via `message-smi-mp-aj.php` (recherche de smileys) — utile pour l'éditeur Phase 2, pas en lecture.

---

## Sessions, cookies et 403

### Cookies HFR

| Cookie | Rôle | Durée |
|---|---|---|
| `md_user` | Pseudo utilisateur — **indicateur de session active** | 1 an |
| `md_pass` | Token de session | 1 an |
| `md_forum` | Identifiant de forum | session |
| Cookies divers (tracking interne HFR) | — | variable |

**Indicateur de session active** : présence du cookie `md_user`. Si une réponse HTTP redirige vers la page de login ou si le DOM contient le formulaire login à la place du payload demandé, la session a expiré.

### Détection et recovery de session expirée

Les fetchers authentifiés doivent distinguer une vraie liste vide d'une page login servie en HTTP 200. Phase 1B hardening : `HfrClient` lève `SessionExpiredException` au minimum quand :

1. l'URL finale après redirection pointe vers `/login.php` ou `/login_validation.php` ;
2. le HTML contient un formulaire login HFR (`login_validation.php`, champ `pseudo`, champ `password`).

Ce signal est branché sur les endpoints authentifiés `HfrApiClient.getCategoryFlagTopics()` (REST per-cat, fan-out 1 appel par catégorie publique), `HfrClient.getPrivateMessageListPage()` (HTML) et `HfrClient.getTopicPage(useAuth = true)` (HTML). Le chemin `getTopicPage(useAuth = false)` garde son passthrough anonyme pour le prefetch : il ne peut pas avoir de session expirée et le caller veut le HTML brut retourné par HFR. L'UI drapeaux affiche alors un état “session expirée” avec action de reconnexion, au lieu de parser la page login comme une liste vide.

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
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton @HfrBaseUrl
    fun provideHfrBaseUrl(): HttpUrl = HfrConstants.BASE_URL.toHttpUrl()

    @Provides @Singleton @AuthenticatedClient
    fun provideAuthClient(base: OkHttpClient, jar: CookieJar): OkHttpClient =
        base.newBuilder().cookieJar(jar).build()

    @Provides @Singleton @AnonymousClient
    fun provideAnonymousClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
}

@Singleton
class HfrClient @Inject constructor(
    @AuthenticatedClient private val authenticated: OkHttpClient,
    @AnonymousClient private val anonymous: OkHttpClient,
    @HfrBaseUrl private val baseUrl: HttpUrl,
) {
    suspend fun getTopicPage(
        cat: Int,
        post: Int,
        page: Int,
        useAuth: Boolean = true,  // false ⇒ @AnonymousClient ⇒ pas de mise à jour des drapeaux
    ): String { /* ... */ }
}
```

Le contrat se ramène à un **flag de booléen** côté caller : `useAuth = true` (default) pour la lecture explicite, `useAuth = false` pour tout appel de prefetch / pré-chauffage de cache. Un test Konsist enforcera la règle Phase 1A — toute fonction dont le nom commence par `prefetch*` (ou tout call-site marqué prefetch dans le repository) doit passer `useAuth = false`. La règle est trackée dans [#42](https://github.com/ForumHFR/redface2/issues/42) et reste désactivée tant qu'aucun call-site prefetch n'existe — elle s'active avec la PR `1A-bind`.

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

## API REST HFR — endpoints et contrat (Phase 1C-A, ADR-003)

Toutes les URLs REST passent par le même point d'entrée :

```
GET https://forum.hardware.fr/webservices/rest_api.php?uri=<URI>[&page=N&results_per_page=M]
```

### Endpoints retenus en v1

| Domaine | URI | Auth | Notes |
|---|---|---|---|
| Liste des catégories publiques | `forums/hardwarefr/categories/` | non (auth ajoute des liens drapeaux) | 19 catégories ; `cat=24` Blabla est conditionnelle, exclue de la nav publique |
| Sous-catégories d'une catégorie | `forums/hardwarefr/categories/{cat}/subcategories/` | non | Ordre éditorial préservé |
| Liste des topics — catégorie entière | `forums/hardwarefr/categories/{cat}/topics/last/` | non / oui | Auth ajoute `is_read`, `flag_owntopic`, `last_position`, `last_post_read_id` |
| Liste des topics — sous-catégorie | `forums/hardwarefr/categories/{cat}/subcategories/{sub}/topics/last/` | non / oui | idem, plus `links.subcategory.href` |
| Metadata d'un topic | `forums/hardwarefr/categories/{cat}/topics/{topic}/` | non / oui | 1 KB vs 220 KB pour la même page HTML |
| Drapeaux par catégorie | `forums/hardwarefr/categories/{cat}/topics/{participated,read,favorites}/` | **oui** | Format plat (resources = topics). **Voie consommée en Phase 1D-1 (#110)** : `DefaultFlagRepository` itère sur les catégories publiques pour reconstituer la liste complète des drapeaux. |
| Drapeaux globaux | `forums/hardwarefr/topics/{participated,read,favorites}/` | **oui** | Format groupé par catégorie (resources = list of category groups). **Non consommé en Phase 1D-1** : forme groupée non capturée, à valider via fixture live + DTO dédié dans une PR de suivi (perf : remplacerait N requêtes par-cat par 1 requête globale). |

### Endpoints HTML-only (pas de REST disponible côté HFR)

- `…/posts/` (lecture posts d'un topic) → HTTP 500 inconditionnel côté serveur, vérifié exhaustivement le 2026-05-02 (18+ variantes CSRF testées). Reste HTML : `forum2.php?cat=N&post=M&page=P`.
- MPs (liste, lecture, envoi) → aucun endpoint REST exposé (300+ variantes scannées). Reste HTML : `forum1.php?cat=prive`, `forum2.php?cat=prive&post=N`, `bddpost.php`.
- Mutations drapeaux (`PUT topics/{id}/`) → routé mais sémantique métier opaque (downgrade refusé, no-op refusé, hors-bornes silently ignored). Reste HTML : `addflag.php` / `delflag.php`.
- Création topic / réponse (`POST topics/last/`, `POST topics/{id}/posts/`) → routé et fonctionnel (testé live), mais **reporté à v2** : `DELETE` REST = 501, donc rollback de test impossible côté CI sans cycle sandbox.

### Helper de réécriture HATEOAS — obligatoire

Tout `href` retourné dans un payload REST pointe sur `https://forum.hardware.fr/api/<…>`. Apache n'a jamais activé le rewrite `/api/` côté HFR, ces URLs renvoient 404 telles quelles. Le client doit réécrire :

```text
https://forum.hardware.fr/api/<path>          (HATEOAS, non callable)
        →
https://forum.hardware.fr/webservices/rest_api.php?uri=<path>   (callable)
```

Implémenté de façon validante (host + scheme + préfixe `/api/` enforcés) dans `:core:network HfrApiClient.rewriteHateoasHref(href)`. Les query params HATEOAS (`page`, `results_per_page`) sont copiés comme params top-level — ils ne sont jamais bakés dans la valeur `uri`.

### Contraintes serveur

- Verbes : GET / POST / PUT supportés. PATCH renvoie 501 ; DELETE renvoie 501 sur les ressources existantes.
- `hash_check` (CSRF) requis pour les POST / PUT, à extraire d'une page HTML auth.
- Pagination : `?page=N&results_per_page=M`. Défaut HATEOAS = 25 ; cible Redface 2 = 50 (limite les requêtes sur les sous-cats actives).
- Limite parallélisme : ≤ 10 connexions TCP simultanées vers HFR (saturation Apache mesurée à 20). OkHttp `maxRequestsPerHost = 5` par défaut reste sous le seuil.
- `name` / `title` peuvent contenir des entités HTML non décodées (`&amp;`) — décoder côté mapper.

### Champs présents en JSON / absents en HTML

- `views` (compteur de vues) : absent du JSON. Si on a besoin du chiffre, c'est HTML uniquement.
- `total_pages` (topic) : déductible via `ceil(links.posts.count / N)`, où `N` est la valeur du query param `results_per_page` exposé par `links.posts.href` du payload (typiquement 40 dans les fixtures actuelles, mais ne **jamais** le hardcoder — c'est l'API qui décide). C'est différent du réglage HTML utilisateur `postsPerPage` (cf. § *postsPerPage configurable*) qui s'applique au rendu de `forum2.php`, pas à la liste de topics REST.
- `last_position` ≠ page : `last_position` est l'**index/offset du dernier post lu dans le topic global** (et non un numéro de page). La page de reprise est exposée séparément, encodée dans `links.posts.href?page=N` du même topic auth ; c'est cette valeur qu'il faut consommer pour `TopicSummary.lastReadPage`.
- `flag_owntopic` (auth uniquement) : numéro de bucket drapeau côté HFR — `1` = sujets participés (cyan), `2` = lus uniquement (rouge), `3` = favoris (jaune). Mapping canonique :

  | `flag_owntopic` | `FlagType` (côté Redface 2) | Bucket HFR (legacy `forum1f.php?owntopic=N`) |
  |---|---|---|
  | `1` | `CYAN` | « Mes sujets » (sujets participés) |
  | `2` | `RED` | « Lus uniquement » |
  | `3` | `FAVORITE` | « Favoris » |
  | `null` / absent | `null` | payload anonyme |
  | autre (`0`, `4`, négatif…) | `null` | bucket inconnu — l'UI dégrade silencieusement, pas de crash |

  Indépendant de `is_read` : un sujet drapeau cyan peut être lu (`is_read = true`, `hasUnread = false`) ou non lu — les deux axes coexistent dans `TopicSummary`.

- `last_post_read_id` ≠ « premier non lu » : `last_post_read_id` est l'**id du dernier post lu** par l'utilisateur dans ce topic. Le legacy HTML `forum1f.php` exposait au contraire un `#t{numreponse}` pointant le **premier post non lu**. Redface 2 (Phase 1D-1) consomme `last_post_read_id` tel quel via `Flag.lastPostReadId` et l'utilise comme ancre de scroll en deep link — re-ancrer le lecteur sur le dernier post lu est suffisamment proche de l'UX « où je m'étais arrêté » sans inférer un premier-non-lu que le payload REST n'expose pas.
- `tns3` filename avatar : nom du fichier, le préfixe d'URL est à reconstituer côté UI.

---

## Fixtures HTML

Les fixtures de test du parser vivent dans `core/parser/src/test/resources/fixtures/` (à créer en Phase 0). Chaque fixture doit être :

- **Capturée depuis HFR réel** (jamais fabriquée par une IA ou à la main)
- **Nettoyée** des données sensibles avant commit : cookies, `hash_check`, emails, identifiants réels, URLs signées
- **Annotée** avec sa source HFR (URL ou `cat=X, post=Y, numreponse=Z`) dans un fichier `.source.txt` frère ou en commentaire en tête du HTML

Catalogue complet : voir [`contributing.md#fixtures-html-pour-le-parser`](contributing.md#fixtures-html-pour-le-parser).

Pour capturer une fixture : utiliser le MCP `hfr-mcp` avec `hfr_read output=path/to/fixture.html` (écrit le HTML brut), puis appliquer le skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) pour générer l'analyse structurée.

## Fixtures REST

Les fixtures JSON de test des mappers REST vivent dans `core/data/src/test/resources/fixtures/` (Phase 1C-A — capturées 2026-05-01). Mêmes règles que les fixtures HTML : capturées live, nettoyées des données sensibles avant commit, accompagnées d'un `.source.txt` qui documente la commande curl d'origine et les caveats.

Catalogue initial :
- `rest_categories.json` / `rest_categories_auth.json` — 19 catégories anonymes / authentifiées (avec liens drapeaux).
- `rest_subcategories_cat13.json` — 15 sous-catégories de Discussions (cat=13).
- `rest_topics_cat23_subcat550_p1.json` — page 1 des 25 derniers topics Tech Mobiles / Android.
- `rest_topic_meta_35395.json` — metadata du topic communauté Redface 2.
- `rest_cat23_participated.json` — un topic en mode authentifié (drapeau « participé »).

---

## Sources

- [Redface v1 code](https://github.com/ForumHFR/Redface/tree/master/app/src/main/java/com/ayuget/redface/data/api/hfr)
- [Redface v1 fixtures](https://github.com/ForumHFR/Redface/tree/master/app/src/test/resources)
- [MesDiscussions SDK (Wayback Machine)](https://web.archive.org/web/*/mesdiscussions.net) — ancienne doc partielle des paramètres URL HFR
- Skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md) pour l'analyse d'une fixture
- MCP [`hfr-mcp`](https://github.com/XaaT/hfr-mcp) pour interagir avec forum.hardware.fr depuis les agents LLM
