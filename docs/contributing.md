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
    database/             # Room, cache, sync MPStorage
    ui/                   # Thème, composants partagés, PostRenderer
  feature/
    forum/                # Catégories, topics
    topic/                # Lecture de topic
    editor/               # Reply, edit, FP, création topic
    messages/             # MPs, MultiMPs
    auth/                 # Login
    settings/             # Préférences
```

### Gestion des dépendances

Le projet utilise un **Gradle version catalog** (`libs.versions.toml`) pour centraliser les versions de toutes les dépendances. Avec 14+ modules, c'est indispensable pour éviter la duplication et les conflits de versions.

```toml
# gradle/libs.versions.toml (extrait)
[versions]
kotlin = "2.1.0"
compose-bom = "2026.04.00"
hilt = "2.52"
room = "2.7.0"
okhttp = "4.12.0"
coil = "3.0.0"
jsoup = "1.18.0"
```

### Convention par feature

Chaque feature suit la même organisation :

```
feature/topic/
  TopicScreen.kt          # @Composable, collecte state + effects
  TopicContent.kt         # @Composable stateless, previewable
  TopicViewModel.kt       # MVI ViewModel
  TopicState.kt           # State + Intent + Effect
```

### Style de code

- **Kotlin** : suivre les [conventions officielles](https://kotlinlang.org/docs/coding-conventions.html)
- **Compose** : suivre les [guidelines API](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md)
- **Nommage** : anglais pour le code, français pour les issues et la documentation
- **Pas de code commenté** : si c'est supprimé, c'est supprimé
- **Pas de TODO dans le code** : ouvrir une issue à la place

### Accessibilité

- Tous les éléments interactifs : `contentDescription` ou `semantics { }` en Compose
- Touch targets minimum 48dp
- Contraste WCAG AA (4.5:1 texte, 3:1 gros texte)
- Support du scaling de police (pas de tailles en `dp` pour le texte, toujours `sp`)
- Navigation TalkBack : headings sémantiques, custom actions pour les posts
- Lint a11y activé en CI dès Phase 0

### Localisation

- Toutes les chaînes UI dans `strings.xml` dès Phase 0 (français par défaut)
- `values-en/strings.xml` pour le listing Play Store (anglais)
- Pas de strings hardcodées dans les Composables — détecté par lint
- L'app est conçue pour la communauté francophone HFR, mais la structure i18n est en place dès le départ

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

### Fixtures HTML pour le parser

Le parser HTML est testé contre des **fixtures capturées depuis HFR** (pas inventées) :

```
core/parser/src/test/resources/fixtures/
    topic_page.html
    flags_page.html
    edit_page.html
    login_success.html
    search_results.html
    mp_list.html
    categories.html
```

Quand un bug de parsing est corrigé, le HTML problématique est ajouté aux fixtures avec un test de non-régression. Un **smoke test CI hebdomadaire** vérifie que les sélecteurs CSS critiques matchent toujours sur une vraie page HFR publique.

---

## Communication

- **Issues GitHub** : pour les bugs, features, questions techniques
- **Topic HFR** : pour les discussions générales avec la communauté (lien à venir)

---

## Attribution

Les contributions sont reconnues dans le CHANGELOG et les release notes. Merci à tous ceux qui participent.
