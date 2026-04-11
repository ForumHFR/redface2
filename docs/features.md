---
title: Features communautaires
nav_order: 7
---

# Features communautaires
{: .fs-8 }

Les meilleurs ajouts des userscripts HFR, integres nativement.
{: .fs-5 .fw-300 }

---

## Contexte

La communaute HFR a developpe de nombreux userscripts qui enrichissent l'experience du forum. Ces scripts ajoutent des fonctionnalites que le forum ne propose pas nativement : alertes qualite, bookmarks sur des posts, blacklist d'utilisateurs, et plus encore.

Redface 2 a l'ambition d'integrer les plus populaires de ces features **nativement dans l'app**, sans que l'utilisateur ait besoin d'installer quoi que ce soit.

---

## Features identifiees

### Alertes Qualitay

Permettre aux utilisateurs de signaler un post qui merite d'etre lu. Un forumeur peut lancer une "alerte qualitay" sur un post remarquable.

**Integration dans l'app :**
- Bouton sur chaque post pour lancer/voir une alerte
- Indicateur visuel sur les posts signales
- Vue dediee pour retrouver les posts signales

### Bookmarks

Sauvegarder des posts specifiques pour y revenir plus tard. Equivalent d'un marque-page sur un post precis.

**Integration dans l'app :**
- Bouton bookmark sur chaque post
- Stockage local (Room)
- Vue dediee avec tri par date/topic
- Synchronisation possible via compte GitHub (futur)

### Blacklist

Masquer les posts d'un utilisateur. L'experience de lecture est amelioree sans les messages indesirables.

**Integration dans l'app :**
- Action "blacklister" sur le profil/post d'un utilisateur
- Posts masques avec option "afficher quand meme"
- Gestion de la liste dans les parametres
- Stockage local (Room)

### Redflag

Systeme d'alertes intelligent sur les topics suivis. Depasse les simples drapeaux HFR avec des notifications configurables.

**Integration dans l'app :**
- Detection de nouveaux messages sur les topics suivis
- Notifications push (via WorkManager + polling)
- Filtres : par auteur, par mot-cle, par topic
- Integration avec le systeme de drapeaux natif

### MPStorage natif

Tracking lu/non-lu des MultiMPs. HFR ne gere pas cette information nativement.

**Integration dans l'app :**
- Stockage local dans Room (remplace l'approche userscript via localStorage ou worker Cloudflare)
- Vue MultiMP avec indicateurs lu/non-lu
- Marquage automatique a la lecture

---

## Architecture d'extensions

Pour supporter ces features (et les futures contributions de la communaute), Redface 2 utilise une architecture modulaire.

Chaque feature communautaire est un **module Gradle isole** :

```
:feature:bookmarks
:feature:blacklist
:feature:redflag
:feature:qualitay
```

### Points d'extension

Les features communautaires interagissent avec l'app via des interfaces standardisees :

```kotlin
// Enrichir le rendu d'un post
interface PostDecorator {
    fun decorate(post: Post): PostDecoration
}

data class PostDecoration(
    val badges: List<Badge> = emptyList(),     // badges affiches sur le post
    val actions: List<PostAction> = emptyList(), // actions dans le menu du post
    val hidden: Boolean = false,                // post masque (blacklist)
)

// Enrichir la toolbar d'un topic
interface TopicToolbarContributor {
    fun actions(topic: Topic): List<ToolbarAction>
}
```

Chaque module feature enregistre ses decorators et contributors via Hilt :

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class BookmarkModule {
    @Binds
    @IntoSet
    abstract fun bindDecorator(impl: BookmarkDecorator): PostDecorator
}
```

Le systeme collecte automatiquement toutes les contributions via `@IntoSet` — ajouter une feature ne demande aucune modification du code existant.

---

## Features non-userscript

En plus des features inspirees des userscripts, Redface 2 ajoutera :

### Mode offline

- Cache complete des topics lus
- Lecture sans connexion
- File d'attente pour les reponses (envoi quand le reseau revient)

### Theme dynamique

- Material You (couleurs dynamiques selon le wallpaper)
- Theme sombre AMOLED (noir pur)
- Theme "HFR classique" (pour les nostalgiques)

### Notifications

- Polling configurable (5min, 15min, 30min, 1h)
- Notification par topic/auteur/mot-cle
- Mode "ne pas deranger" par plage horaire

### Partage intelligent

- Partager un lien vers un post specifique
- Capture d'ecran d'un post avec mise en forme
- Copie du BBCode brut

---

## Proposer une feature

Le topic HFR recensant les userscripts et addons communautaires sera utilise comme reference pour identifier les features a integrer.

Pour proposer une nouvelle feature :
1. Ouvrir une issue sur ce repo avec le label `feature`
2. Decrire le use case et l'UX souhaitee
3. Si c'est une adaptation d'un userscript existant, lier le script original
4. Discussion ouverte avec la communaute avant implementation
