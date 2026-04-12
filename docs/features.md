---
title: Features communautaires
nav_order: 7
---

# Features communautaires
{: .fs-8 }

Les meilleurs ajouts des userscripts HFR, intégrés nativement.
{: .fs-5 .fw-300 }

---

## Contexte

La communauté HFR a développé **126 userscripts** recensés sur le [topic dédié](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=13&subcat=432&post=116015) maintenu par roger21. Ces scripts, accumulés sur des années, ajoutent des fonctionnalités essentielles que le forum ne propose pas nativement.

Redface 2 intègre les plus populaires et les plus utiles **nativement dans l'app**. Ce catalogue est basé sur le sondage de popularité du topic (77 répondants) et les retours de la communauté.

---

## Lecture et affichage

### Indicateurs lu/non-lu

*Inspiré de : Last Post Highlight (pop. 22), New Page Number (pop. 33)*

- Distinction visuelle claire entre posts lus et non lus dans un topic
- Nombre de pages non lues affiché sur chaque drapeau
- Ligne de séparation entre le dernier post lu et les nouveaux
- Ouverture directe au premier post non lu

### Ego Quote — "On parle de vous"

*Inspiré de : Ego Quote (pop. 32), Ego Posts (pop. 12)*

- Posts qui vous citent mis en évidence (couleur, badge)
- Vos propres posts visuellement distincts
- Notification optionnelle quand quelqu'un vous cite (dans les drapeaux)

### Color Tag — Colorer les pseudos

*Inspiré de : Color Tag (pop. 21)*

- Attribuer une couleur et/ou une note à un pseudo
- Coloration visible partout : posts, drapeaux, MPs
- Catégoriser les utilisateurs (ami, expert, troll…)
- Stockage Room, synchronisable via MPStorage

### Infos profil rapides

*Inspiré de : Infos rapides mod_r21 (pop. 32)*

- Tap sur un pseudo → popup avec infos profil (statut, inscription, avatar, stats)
- Accès rapide aux actions : MP, blacklist, color tag
- Pas besoin de quitter le topic pour voir un profil

### Liens explicites

*Inspiré de : Liens explicites mod_r21 (pop. 26)*

- Les liens internes HFR affichent un texte descriptif au lieu de l'URL brute
- Exemple : `forum1.php?cat=13&post=116015` → "Topic des userscripts (Programmation)"
- Les liens externes affichent le titre de la page quand disponible

### Spoilers

*Inspiré de : Spoiler Reductor (pop. 7), No Spoiler (pop. 2)*

- Spoilers repliés par défaut (tap pour révéler)
- Option dans les préférences : toujours afficher les spoilers
- Animation fluide à l'ouverture/fermeture

---

## Éditeur et rédaction

### Preview live

*Inspiré de : Aperçu rapide mod_r21 (pop. 32)*

- Prévisualisation en temps réel du BBCode pendant la rédaction
- Rendu identique à ce que les autres verront
- Toggle preview / éditeur

### Smart paste

*Inspiré de : Copie/Colle (pop. 41) — le script le plus populaire*

- Coller une image → upload automatique via le provider d'images configuré, insertion du BBCode `[img]`
- Coller une URL YouTube/Twitch → proposition d'embed ou insertion du lien
- Coller une URL d'image → proposition de rehost + insertion
- Coller du texte formaté → conversion en BBCode
- Fonctionne aussi en drag & drop

### Smileys favoris

*Inspiré de : Vos smileys favoris mod_r21 (pop. 26), Wiki smileys & raccourcis (pop. 28)*

- Liste personnelle de smileys favoris (accès rapide dans l'éditeur)
- Raccourcis clavier pour les smileys fréquents
- Recherche de smileys par mot-clé (wiki smileys HFR)
- Statistiques d'utilisation (les plus utilisés remontent)
- Synchronisation via MPStorage

### Suppression rapide de posts

*Inspiré de : Suppression rapide de posts (pop. 25)*

- Supprimer son propre post en un tap (avec confirmation)
- Pas besoin de passer par la page d'édition

---

## Médias

### Embed vidéo

*Inspiré de : Video Link Replacer mod_r21 (pop. 40) — 2e script le plus populaire*

- Les liens YouTube, Dailymotion, Twitch, Vimeo, Streamable, Coub sont remplacés par un lecteur intégré
- Lecture inline sans quitter l'app
- Miniature + bouton play (pas d'autoplay)

### Lecteur audio/vidéo HTML5

*Inspiré de : HTML5 Media Link Replacer (pop. 29)*

- Les liens vers des fichiers média (mp3, wav, ogg, mp4, webm) deviennent des lecteurs natifs
- Lecture inline dans le post

### Hébergement d'images

HFR ne propose aucun hébergement d'images natif. Historiquement, la communauté utilisait [reho.st](https://reho.st) — un service qui n'accepte plus les uploads manuels mais qui continue de servir les images existantes et permet le "rehost" (préfixer une URL avec `https://reho.st/` pour en créer une copie permanente).

Deux hébergeurs communautaires HFR sont actifs :
- **Rehost by dib** (diberie) — hébergeur et rehost dédié à la communauté HFR
- **super-h.fr** — hébergeur/rehost alternatif

En fallback ou au choix de l'utilisateur : **Imgur**.

**Intégration dans l'app :**
- Upload d'images directement depuis l'éditeur (appareil photo, galerie, presse-papiers)
- Choix du provider : diberie, super-h.fr, imgur (configurable dans les préférences)
- **Rehost** : coller une URL d'image → l'app la rehost automatiquement via le provider choisi et insère le BBCode `[img]`
- **Bibliothèque d'images** : historique de tous les uploads avec miniatures, dates, et liens
- **Tokens de suppression** : chaque upload conserve le token de suppression du provider, permettant de supprimer l'image plus tard depuis l'app
- Stockage de la bibliothèque dans Room, synchronisable via MPStorage pour retrouver ses images sur un autre appareil
- Compression/redimensionnement automatique avant upload (configurable)
- Copie du lien BBCode en un tap (`[img]url[/img]` ou `[url=original][img]thumb[/img][/url]`)

### Recherche et insertion de GIFs

*Inspiré de : [HFR] Giphy (pop. 12)*

Poster un GIF sur HFR demande aujourd'hui de quitter l'app, chercher le GIF ailleurs, copier l'URL et revenir coller le BBCode. Redface 2 intègre la recherche de GIFs directement dans l'éditeur.

**Providers :**
- [**DieudoGifs**](https://dieudogifs.be/) — moteur de GIFs communautaire HFR avec tags, votes et recherche. Source privilégiée car les GIFs sont déjà adaptés à la culture du forum.
- **Giphy** — le plus grand catalogue de GIFs au monde
- **Tenor** — intégré dans Google, bon catalogue

**Intégration dans l'app :**
- Bouton GIF dans la toolbar de l'éditeur → ouvre un panneau de recherche
- Recherche par mots-clés avec preview animé
- Choix du provider (DieudoGifs par défaut, Giphy et Tenor en alternatives)
- Navigation par tags/catégories (DieudoGifs) ou trending (Giphy/Tenor)
- Tap sur un GIF → insère automatiquement le BBCode `[img]url[/img]`
- Historique des GIFs récemment utilisés (accès rapide)
- Favoris de GIFs (stockage Room, synchronisable via MPStorage)

---

## Drapeaux et navigation

### Drapeaux compacts

*Inspiré de : Sujets compacts (pop. 18)*

- Option pour masquer les catégories sans nouveaux messages
- Vue condensée qui ne montre que l'activité récente

### Real New Answer — détection de citations

*Inspiré de : Real New Answer (pop. 19)*

- Indicateur sur la page des drapeaux quand un de vos posts a été cité depuis votre dernière visite
- Distinction entre "nouveau message dans le topic" et "quelqu'un vous a répondu"

### Permaliens

*Inspiré de : Permalien (pop. 10)*

- Lien permanent sur chaque post (partage facile)
- Copie en un tap
- Deep link vers le post exact dans l'app

### Navigation au clavier (tablettes)

*Inspiré de : [HFR] Navigation (pop. 5)*

- Raccourcis clavier pour naviguer : page suivante/précédente, drapeaux, retour
- Utile sur tablettes avec clavier

---

## Filtrage

### Blacklist avancée

*Inspiré de : Bloque liste mod_r21 (pop. 16), Black List (pop. 15)*

- Masquer les posts d'utilisateurs avec configuration fine
- Filtrage par catégorie, par topic, ou global
- Posts masqués avec option "afficher quand même"
- Notes sur les pseudos bloqués (pourquoi bloqué)
- Gestion des citations de bloqués dans les posts d'autres utilisateurs

### Filtre de contenu

*Inspiré de : Anti HS mod_r21 (pop. 11)*

- Règles configurables pour filtrer les messages indésirables
- Filtrage par mots-clés, nombre de citations, présence de vidéos/GIFs
- Par topic (masquer le hors-sujet sur un topic technique par exemple)

---

## Social

### Alertes Qualitay

*Inspiré de : Alerte Qualitay mod_r21 (pop. 19)*

Signaler un post qui mérite d'être lu. Un forumeur peut lancer une "alerte qualitay" sur un post remarquable.

- Bouton sur chaque post pour lancer/voir une alerte
- Indicateur visuel sur les posts signalés
- Vue dédiée pour retrouver les posts signalés
- Intégration du [flux RSS des AQ](https://aq.super-h.fr/rss.php)

### Bookmarks

*Inspiré de : [HFR] Bookmarks (pop. 7)*

- Sauvegarder des posts spécifiques pour y revenir plus tard
- Stockage Room, synchronisable via MPStorage (compatibilité avec le script existant)
- Vue dédiée avec tri par date/topic

### Stats utilisateur

*Inspiré de : [HFR] Stats (pop. 15)*

- Statistiques d'un membre : nombre de posts, topics, ancienneté, activité
- Accessible depuis le popup profil rapide

---

## Système

### Redflag — alertes intelligentes

Système d'alertes intelligent sur les topics suivis. Dépasse les simples drapeaux HFR avec des notifications configurables.

- Détection de nouveaux messages sur les topics suivis
- Notifications push (via WorkManager + polling)
- Filtres : par auteur, par mot-clé, par topic
- Intégration avec le système de drapeaux natif

L'app s'appuie sur l'**API du worker Cloudflare** du projet [hfr-redflag](https://github.com/XaaT/hfr-redflag) existant pour récupérer les statuts d'alerte (crowdsourced). L'app est un client HTTP du worker, comme le userscript :
- `GET /check?cat={cat}&ids=...` → statuts connus
- `POST /report` → remonter les résultats de scan

### Intégration MPStorage

MPStorage est une bibliothèque cross-plateforme (issue de hfr-redkit) qui utilise un MP HFR dédié comme backend de stockage : les données sont sérialisées en JSON dans le corps d'un message privé spécialement créé pour l'occasion. Cela permet de synchroniser des préférences et métadonnées (drapeaux MultiMP, bookmarks, etc.) entre navigateurs, appareils et scripts sans serveur tiers.

**Intégration dans l'app :**
- Lire et écrire dans le même MP de stockage que les userscripts (compatibilité cross-plateforme)
- Cache Room locale pour les accès rapides, synchronisation périodique avec le MP
- Vue MultiMP avec indicateurs lu/non-lu calculés depuis les données MPStorage

### Résumé quotidien par IA

*Inspiré de : [HFR] Résumé quotidien par topic (pop. 2, mais innovant)*

- Résumé automatique des topics suivis généré par IA (Claude)
- Configurable : quels topics résumer, fréquence, longueur
- Utile pour les topics très actifs (100+ posts/jour)
- **Expérimental / futur** — nécessite un backend dédié ou un appel API direct (clé utilisateur). Non inclus dans la roadmap active.

---

## Architecture d'extensions

Pour supporter ces features (et les futures contributions de la communauté), Redface 2 utilise une architecture modulaire.

Chaque feature communautaire est un **module Gradle isolé** :

```
:feature:bookmarks
:feature:blacklist
:feature:redflag
:feature:qualitay
:feature:imagehost
:feature:gifpicker
:feature:colortag
:feature:stats
```

### Points d'extension

Les features communautaires interagissent avec l'app via des interfaces standardisées, définies dans `:core:extension` :

```kotlin
// Contexte fourni à chaque décorateur (informations par post)
data class DecorationContext(
    val post: Post,
    val currentUser: String,   // pseudo connecté
    val cat: Int,
    val topicId: Int,
)

// Enrichir le rendu d'un post
interface PostDecorator {
    fun decorate(context: DecorationContext): PostDecoration
}

data class PostDecoration(
    val badges: List<Badge> = emptyList(),     // badges affichés sur le post
    val actions: List<PostAction> = emptyList(), // actions dans le menu du post
    val hidden: Boolean = false,                // post masqué (blacklist)
    val highlighted: Boolean = false,           // post mis en évidence (ego quote, qualitay)
    val highlightColor: Color? = null,          // couleur de mise en évidence
)

// Enrichir la toolbar d'un topic
interface TopicToolbarContributor {
    fun actions(topic: Topic): List<ToolbarAction>
}

// Enrichir la toolbar de l'éditeur
interface EditorToolbarContributor {
    fun actions(): List<EditorAction>
}
```

Chaque décorateur est un `@Singleton` injecté par Hilt avec ses propres dépendances. L'enregistrement se fait via `@IntoSet` — ajouter une extension ne demande **aucune modification du code existant** :

```kotlin
// Dans :feature:blacklist — exemple concret
@Singleton
class BlacklistDecorator @Inject constructor(
    private val blacklistDao: BlacklistDao,
) : PostDecorator {
    override fun decorate(context: DecorationContext): PostDecoration {
        val isBlocked = blacklistDao.isBlocked(context.post.author)
        return PostDecoration(hidden = isBlocked)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BlacklistModule {
    @Binds @IntoSet
    abstract fun bindDecorator(impl: BlacklistDecorator): PostDecorator
}
```

Le `TopicViewModel` collecte tous les décorateurs et les applique en séquence. Les résultats sont mergés — un post peut être highlighted par Ego Quote **et** avoir un badge Qualitay simultanément.

---

## Features non-userscript

En plus des features inspirées des userscripts, Redface 2 ajoutera :

### Mode offline

- Cache complet des topics lus
- Lecture sans connexion
- File d'attente pour les réponses (envoi quand le réseau revient)

### Thèmes dynamiques et customisables

- Material You (couleurs dynamiques selon le wallpaper)
- Thème sombre AMOLED (noir pur)
- Thème "HFR classique" (pour les nostalgiques)

**Thèmes YAML personnalisés** (proposé par la communauté) :

Les utilisateurs peuvent créer et partager des thèmes via un fichier YAML. Jetpack Compose rend l'intégration de thèmes dynamiques triviale grâce à `MaterialTheme` et `CompositionLocal`.

```yaml
name: "HFR Classique"
colors:
  primary: "#CC0000"
  background: "#F0F0F0"
  surface: "#FFFFFF"
  flagCyan: "#00BCD4"
  flagFavorite: "#FFD700"
spacing:
  postPadding: 8
  compact: true
icons: "material"  # material | outline | rounded
```

- Import/export de thèmes en un tap (coller un YAML depuis le forum, c'est facile)
- Galerie de thèmes communautaires (les presets Material You, AMOLED et HFR classique sont des thèmes YAML intégrés)
- Potentiel pour migrer les utilisateurs HFR4droid attachés à leur customisation

### Notifications

- Polling configurable (5min, 15min, 30min, 1h)
- Notification par topic/auteur/mot-clé
- Mode "ne pas déranger" par plage horaire

### Partage intelligent

- Partager un lien vers un post spécifique
- Capture d'écran d'un post avec mise en forme
- Copie du BBCode brut

---

## Source

Ce catalogue est basé sur l'analyse du [topic "Pimp my HFR"](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=13&subcat=432&post=116015) maintenu par roger21, qui recense 126 userscripts de 15+ auteurs. Les popularités indiquées proviennent du sondage intégré au topic (77 répondants).

Auteurs principaux de la scène userscript HFR : **roger21** (~40 scripts), **toyonos** (~15), **PetitJean** (~10), **DdsT** (5), **garath_** (5), **WirIpse** (4 + MPStorage), **XaTriX** (2).

## Proposer une feature

Pour proposer une nouvelle feature :
1. Ouvrir une issue sur ce repo avec le label `feature`
2. Décrire le use case et l'UX souhaitée
3. Si c'est une adaptation d'un userscript existant, lier le script original
4. Discussion ouverte avec la communauté avant implémentation
