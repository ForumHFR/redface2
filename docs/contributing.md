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

**Stack de tests :**
- **JUnit 4** — framework de test (standard Android instrumenté)
- **MockK** — mocking Kotlin-first
- **Robolectric** — tests Android sans émulateur (quand on ne peut pas mocker les composants Android)
- **Turbine** — test des `Flow` et `StateFlow` (assertions sur les émissions)
- **Compose Testing** — tests UI pour les écrans critiques

**Couverture :**
- **100%** sur les modules métier : parser, database, ViewModels
- Tests d'intégration pour les repositories (network + parser + cache)
- Tests UI pour les écrans critiques et les interactions clés

**Stratégie :**
- Tests unitaires pour les ViewModels : intent → state (fonction pure)
- Tests unitaires pour les parsers : HTML fixture → modèle attendu
- Tests d'intégration pour les repositories : cache-then-network, retry, erreurs
- Tests UI pour les écrans critiques (Compose testing)

### Héritage Redface v1

Le code de [Redface v1](https://github.com/ForumHFR/Redface) contient ~10 transformers de parsing, 17 fixtures HTML et 13 tests. Cette base est reprise comme point de départ :
- Les **fixtures HTML** servent de référence pour les edge cases du parser HFR
- La **logique de parsing** (gestion des posts supprimés, pages instables, encoding) est analysée pour ne pas réinventer la roue
- Les edge cases identifiés dans les tests v1 deviennent des cas de test dans v2

### Fixtures HTML pour le parser

Le parser HTML est testé contre des **fixtures capturées depuis de vraies pages HFR** (jamais fabriquées par une IA). Chaque page nécessitant une authentification est capturée en version logué **et** non-logué quand la distinction est pertinente (contenu différent, champs manquants, redirections).

```
core/parser/src/test/resources/fixtures/
```

**Reprises de Redface v1** (17 fixtures, `app/src/test/resources/` dans ForumHFR/Redface) :

| Fixture | Page HFR | Auth ? |
|---------|----------|--------|
| `topic_multipage.html` | Topic multi-pages | logué + non-logué |
| `topic_singlepage.html` | Topic 1 seule page | non-logué |
| `topic_posts.html` | Posts d'un topic (40/page) | logué + non-logué |
| `edit_post.html` | Page d'édition d'un post | logué uniquement |
| `quote.html` | Contenu de citation BBCode | logué uniquement |
| `categories.html` | Page d'accueil (catégories) | non-logué |
| `topic_list.html` | Liste de topics d'une sous-catégorie | logué + non-logué |
| `profile_standard.html` | Profil utilisateur standard | non-logué |
| `profile_admin.html` | Profil admin/modo | non-logué |
| `mp_list.html` | Liste des MPs classiques | logué uniquement |
| `mp_conversation.html` | Conversation MP | logué uniquement |
| `smiley_search.html` | Résultats recherche de smileys | non-logué |
| `rehost_response.html` | Réponse reho.st | non-logué |

**Nouvelles fixtures v2** (pages non couvertes par v1) :

| Fixture | Page HFR | Auth ? | Pourquoi |
|---------|----------|--------|----------|
| `flags_page.html` | `/forum1f.php` (drapeaux) | logué uniquement | Écran d'accueil, pas dans v1 |
| `flags_page_empty.html` | Drapeaux vides | logué uniquement | Cas edge : aucun drapeau |
| `search_results.html` | `/search.php` | logué + non-logué | Recherche |
| `login_success.html` | Réponse login OK | — | Détection succès auth |
| `login_failure.html` | Réponse login échoué | — | Détection échec auth |
| `edit_fp.html` | Édition First Post (sujet + sondage) | logué uniquement | Distinct de l'édition normale |
| `new_topic.html` | Page de création de topic | logué uniquement | Formulaire avec sous-catégories |
| `multimp_conversation.html` | MultiMP | logué uniquement | Différent des MPs classiques |
| `topic_with_poll.html` | Topic avec sondage | logué + non-logué | Parsing du sondage |
| `topic_last_page.html` | Dernière page (< 40 posts) | non-logué | Pagination edge case |
| `topic_deleted_posts.html` | Page avec posts supprimés | non-logué | Décalage de numérotation |
| `modo_not_flagged.html` | modo.php — formulaire d'alerte | logué uniquement | Redflag : post pas encore alerté |
| `modo_flagged.html` | modo.php — déjà alerté | logué uniquement | Redflag : post alerté |
| `modo_join.html` | modo.php — rejoindre une alerte | logué uniquement | Redflag : alerte en cours |

**Total : ~30 fixtures** (13 reprises de v1 + 14 nouvelles, certaines en double logué/non-logué).

**Règles :**
- Les fixtures sont capturées depuis le vrai site HFR, jamais fabriquées
- Quand un bug de parsing est corrigé, le HTML problématique est ajouté aux fixtures avec un test de non-régression
- Un **smoke test CI hebdomadaire** vérifie que les sélecteurs CSS critiques matchent toujours sur une vraie page HFR publique
- Les fixtures logué ne doivent **jamais** contenir de cookies, tokens ou identifiants réels — nettoyer avant commit

---

## Communication

- **Issues GitHub** : pour les bugs, features, questions techniques
- **Topic HFR** : pour les discussions générales avec la communauté (lien à venir)

---

## Attribution

Les contributions sont reconnues dans le CHANGELOG et les release notes. Merci à tous ceux qui participent.
