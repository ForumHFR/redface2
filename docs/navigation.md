---
title: Navigation
nav_order: 4
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
- **Rouge** : marque de lecture (dernière position lue)

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
- **Editer le FP** (si c'est notre topic, post #0) → éditeur spécial avec sujet + sondage
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
- **Preview** : avant-première du rendu BBCode

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

| Pattern URL | Écran cible |
|-------------|-------------|
| `forum.hardware.fr/forum1.php?cat=X&post=Y&page=Z` | Topic page Z |
| `forum.hardware.fr/forum1.php?cat=X&post=Y` | Topic page 1 |
| `forum.hardware.fr/forum2.php?config=hfr.inc&cat=X&subcat=Y` | Liste topics |
| `forum.hardware.fr/forum1f.php` | Drapeaux |
| `forum.hardware.fr/forum1.php?cat=X&post=Y#t12345` | Post spécifique |

Implémentation via Compose Navigation deep links :

```kotlin
composable(
    route = "topic/{cat}/{post}/{page}",
    deepLinks = listOf(
        navDeepLink {
            uriPattern = "https://forum.hardware.fr/forum1.php?cat={cat}&post={post}&page={page}"
        }
    ),
) { backStackEntry ->
    TopicScreen(
        cat = backStackEntry.arguments?.getString("cat")?.toInt() ?: 0,
        post = backStackEntry.arguments?.getString("post")?.toInt() ?: 0,
        page = backStackEntry.arguments?.getString("page")?.toInt() ?: 1,
    )
}
```

---

## Back Stack

Le back stack est géré par Compose Navigation avec des règles spécifiques :

- **Bottom nav** : chaque onglet conserve son propre back stack
- **Retour depuis un topic** : retour à la liste (drapeaux, forum, recherche) à la même position de scroll
- **Retour depuis reply/edit** : retour au topic à la même page
- **Deep link** : back → écran d'accueil (drapeaux)

```mermaid
graph LR
    A["Drapeaux"] --> B["Topic (page 3)"]
    B --> C["Quote → Reply"]
    C -->|"Back"| B
    B -->|"Back"| A
    A -->|"Back"| EXIT["Quitter l'app"]
```
