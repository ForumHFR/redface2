---
title: Contribuer
nav_order: 10
---

# Contribuer
{: .fs-8 }

Comment participer au projet.
{: .fs-5 .fw-300 }

---

## Phase actuelle : Spécifications

Le projet est en phase de spec. Le code n'est pas encore écrit. Les contributions les plus utiles en ce moment :

- **Commenter les issues** : donner son avis sur les choix techniques, proposer des alternatives
- **Proposer des features** : ouvrir une issue avec le label `feature`
- **Signaler des oublis** : quelque chose manque dans les specs ? Dites-le
- **Proposer un nom** : voir la [page nommage]({{ site.baseurl }}/naming)

---

## Quand le dev commencera

### Prérequis

- Android Studio (dernière version stable)
- JDK 21+
- Un appareil ou émulateur Android API 29+
- Un compte HFR (pour tester)

### Structure du projet

```
redface2/
  app/                    # Point d'entrée, DI, navigation
  core/
    model/                # Modèles domaine
    network/              # OkHttp, session HFR
    parser/               # Jsoup, HTML → modèles
    database/             # Room, cache, MPStorage
    ui/                   # Thème, composants partagés, PostRenderer
  feature/
    forum/                # Catégories, topics
    topic/                # Lecture de topic
    editor/               # Reply, edit, FP, création topic
    messages/             # MPs, MultiMPs
    auth/                 # Login
    settings/             # Préférences
```

### Convention par feature

Chaque feature suit la même organisation :

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
- **Nommage** : anglais pour le code, français pour les issues et la documentation
- **Pas de code commenté** : si c'est supprimé, c'est supprimé
- **Pas de TODO dans le code** : ouvrir une issue à la place

### Workflow Git

- Branche principale : `main`
- Branches feature : `feature/nom-court`
- Branches fix : `fix/nom-court`
- Commits : [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`)
- PR obligatoire pour merger dans `main`
- Review par au moins un mainteneur

### Tests

- Tests unitaires pour les ViewModels (intents → state)
- Tests unitaires pour les parsers (HTML → modèles)
- Tests d'intégration pour les repositories
- Tests UI pour les écrans critiques (Compose testing)

---

## Communication

- **Issues GitHub** : pour les bugs, features, questions techniques
- **Topic HFR** : pour les discussions générales avec la communauté (lien à venir)

---

## Attribution

Les contributions sont reconnues dans le CHANGELOG et les release notes. Merci à tous ceux qui participent.
