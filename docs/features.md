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

La communauté HFR a développé de nombreux userscripts qui enrichissent l'expérience du forum. Ces scripts ajoutent des fonctionnalités que le forum ne propose pas nativement : alertes qualité, bookmarks sur des posts, blacklist d'utilisateurs, et plus encore.

Redface 2 a l'ambition d'intégrer les plus populaires de ces features **nativement dans l'app**, sans que l'utilisateur ait besoin d'installer quoi que ce soit.

---

## Features identifiées

### Alertes Qualitay

Permettre aux utilisateurs de signaler un post qui mérite d'être lu. Un forumeur peut lancer une "alerte qualitay" sur un post remarquable.

**Intégration dans l'app :**
- Bouton sur chaque post pour lancer/voir une alerte
- Indicateur visuel sur les posts signalés
- Vue dédiée pour retrouver les posts signalés

### Bookmarks

Sauvegarder des posts spécifiques pour y revenir plus tard. Équivalent d'un marque-page sur un post précis.

**Intégration dans l'app :**
- Bouton bookmark sur chaque post
- Stockage local (Room)
- Vue dédiée avec tri par date/topic
- Synchronisation possible via compte GitHub (futur)

### Blacklist

Masquer les posts d'un utilisateur. L'expérience de lecture est améliorée sans les messages indésirables.

**Intégration dans l'app :**
- Action "blacklister" sur le profil/post d'un utilisateur
- Posts masqués avec option "afficher quand même"
- Gestion de la liste dans les paramètres
- Stockage local (Room)

### Redflag

Système d'alertes intelligent sur les topics suivis. Dépasse les simples drapeaux HFR avec des notifications configurables.

**Intégration dans l'app :**
- Détection de nouveaux messages sur les topics suivis
- Notifications push (via WorkManager + polling)
- Filtres : par auteur, par mot-clé, par topic
- Intégration avec le système de drapeaux natif

### MPStorage natif

Tracking lu/non-lu des MultiMPs. HFR ne gère pas cette information nativement.

**Intégration dans l'app :**
- Stockage local dans Room (remplace l'approche userscript via localStorage ou worker Cloudflare)
- Vue MultiMP avec indicateurs lu/non-lu
- Marquage automatique à la lecture

---

## Architecture d'extensions

Pour supporter ces features (et les futures contributions de la communauté), Redface 2 utilise une architecture modulaire.

Chaque feature communautaire est un **module Gradle isolé** :

```
:feature:bookmarks
:feature:blacklist
:feature:redflag
:feature:qualitay
```

### Points d'extension

Les features communautaires interagissent avec l'app via des interfaces standardisées :

```kotlin
// Enrichir le rendu d'un post
interface PostDecorator {
    fun decorate(post: Post): PostDecoration
}

data class PostDecoration(
    val badges: List<Badge> = emptyList(),     // badges affichés sur le post
    val actions: List<PostAction> = emptyList(), // actions dans le menu du post
    val hidden: Boolean = false,                // post masqué (blacklist)
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

Le système collecte automatiquement toutes les contributions via `@IntoSet` — ajouter une feature ne demande aucune modification du code existant.

---

## Features non-userscript

En plus des features inspirées des userscripts, Redface 2 ajoutera :

### Mode offline

- Cache complet des topics lus
- Lecture sans connexion
- File d'attente pour les réponses (envoi quand le réseau revient)

### Thème dynamique

- Material You (couleurs dynamiques selon le wallpaper)
- Thème sombre AMOLED (noir pur)
- Thème "HFR classique" (pour les nostalgiques)

### Notifications

- Polling configurable (5min, 15min, 30min, 1h)
- Notification par topic/auteur/mot-clé
- Mode "ne pas déranger" par plage horaire

### Partage intelligent

- Partager un lien vers un post spécifique
- Capture d'écran d'un post avec mise en forme
- Copie du BBCode brut

---

## Proposer une feature

Le topic HFR recensant les userscripts et addons communautaires sera utilisé comme référence pour identifier les features à intégrer.

Pour proposer une nouvelle feature :
1. Ouvrir une issue sur ce repo avec le label `feature`
2. Décrire le use case et l'UX souhaitée
3. Si c'est une adaptation d'un userscript existant, lier le script original
4. Discussion ouverte avec la communauté avant implémentation
