---
title: Contribuer
nav_order: 10
---

# Contribuer
{: .fs-8 }

Comment participer au projet.
{: .fs-5 .fw-300 }

---

## Phase actuelle : Specifications

Le projet est en phase de spec. Le code n'est pas encore ecrit. Les contributions les plus utiles en ce moment :

- **Commenter les issues** : donner son avis sur les choix techniques, proposer des alternatives
- **Proposer des features** : ouvrir une issue avec le label `feature`
- **Signaler des oublis** : quelque chose manque dans les specs ? Dites-le
- **Proposer un nom** : voir la [page nommage]({% link naming.md %})

---

## Quand le dev commencera

### Prerequis

- Android Studio (derniere version stable)
- JDK 21+
- Un appareil ou emulateur Android API 29+
- Un compte HFR (pour tester)

### Structure du projet

```
redface2/
  app/                    # Point d'entree, DI, navigation
  core/
    model/                # Modeles domaine
    network/              # OkHttp, session HFR
    parser/               # Jsoup, HTML → modeles
    database/             # Room, cache, MPStorage
    ui/                   # Theme, composants partages, PostRenderer
  feature/
    forum/                # Categories, topics
    topic/                # Lecture de topic
    editor/               # Reply, edit, FP, creation topic
    messages/             # MPs, MultiMPs
    auth/                 # Login
    settings/             # Preferences
```

### Convention par feature

Chaque feature suit la meme organisation :

```
feature/topic/
  TopicScreen.kt          # @Composable, collecte state + effects
  TopicContent.kt         # @Composable stateless, previewable
  TopicViewModel.kt       # MVI ViewModel
  TopicState.kt           # State + Intent + Effect
  TopicRepository.kt      # Interface repository
```

### Style de code

- **Kotlin** : suivre les [conventions officielles](https://kotlinlang.org/docs/coding-conventions.html)
- **Compose** : suivre les [guidelines API](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md)
- **Nommage** : anglais pour le code, francais pour les issues et la documentation
- **Pas de code commente** : si c'est supprime, c'est supprime
- **Pas de TODO dans le code** : ouvrir une issue a la place

### Workflow Git

- Branche principale : `main`
- Branches feature : `feature/nom-court`
- Branches fix : `fix/nom-court`
- Commits : [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`)
- PR obligatoire pour merger dans `main`
- Review par au moins un mainteneur

### Tests

- Tests unitaires pour les ViewModels (intents → state)
- Tests unitaires pour les parsers (HTML → modeles)
- Tests d'integration pour les repositories
- Tests UI pour les ecrans critiques (Compose testing)

---

## Communication

- **Issues GitHub** : pour les bugs, features, questions techniques
- **Topic HFR** : pour les discussions generales avec la communaute (lien a venir)

---

## Attribution

Les contributions sont reconnues dans le CHANGELOG et les release notes. Merci a tous ceux qui participent.
