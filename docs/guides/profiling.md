---
layout: default
title: Profiling et tracing
parent: Guides
nav_order: 6
---

# Profiling et tracing

Comment instrumenter et mesurer les performances du parcours « ouvrir un topic et commencer à lire ». Cette page documente le **catalogue stable des sections de trace** et la procédure pour les visualiser dans Android Studio Profiler ou Perfetto.

Issue tracker : [#117](https://github.com/ForumHFR/redface2/issues/117) (instrumentation initiale, livrée), suites éventuelles à ouvrir si une trace montre un goulot.

## Catalogue des sections `rf2.topic.*`

Toutes les sections sont préfixées `rf2.topic.` pour faciliter le filtrage côté Perfetto / `TraceSectionMetric`. Le préfixe est stable : un bump de phase ne le renomme pas. Tout changement de nom doit être reflété simultanément ici et dans la (future) configuration `TraceSectionMetric` du macrobenchmark.

| Section | Type | Module / fichier | Phase mesurée |
|---|---|---|---|
| `rf2.topic.network` | sync | `core/network/.../HfrClient.kt` | Appel OkHttp `execute()` jusqu'aux headers (DNS + connect + TLS + send + receive headers). Émise sur les deux paths du parcours topic (auth + anon) — `executeAuthenticatedHtml` reçoit le préfixe `rf2.topic` uniquement quand `getTopicPage(useAuth=true)` l'appelle, donc `getPrivateMessageListPage` et tout autre appelant restent hors namespace. |
| `rf2.topic.body_read` | sync | `core/network/.../HfrClient.kt` | `response.body.string()` — lecture des bytes du body. Mesure isolée du téléchargement, distincte du handshake. Même conditionnel d'émission que `network`. |
| `rf2.topic.parse_html` | sync | `core/data/.../TopicRepositoryImpl.kt` | `HfrParser.parseTopicPage(html)` — coût CPU pur du parsing Jsoup → AST `PostContent`. |
| `rf2.topic.map_domain` | sync | `core/data/.../TopicRepositoryImpl.kt` | `TopicMappers.toEntities(...)` — conversion modèles domaine → entités Room. |
| `rf2.topic.room_read` | async | `core/data/.../TopicRepositoryImpl.kt` | `loadFromCache(...)` — lecture cache-aside (hit ou miss). Wrap `traceAsync` car les `suspend` DAO Room peuvent reprendre sur un thread différent de celui qui a émis le begin. |
| `rf2.topic.room_write` | async | `core/data/.../TopicRepositoryImpl.kt` | `persist(...)` — écriture transactionnelle Room (auth ou anon prefetch). Async pour la même raison que `room_read`. |
| `rf2.topic.first_content` | async | `feature/topic/.../TopicViewModel.kt` | Commence quand `loadCurrentPage()` démarre, finit au premier emit `Mode.Loaded` ou `Mode.Error`. Mesure le ressenti utilisateur entre intent et premier contenu, qu'il vienne du cache ou du réseau. |

Sections **synchrones** (`Trace.beginSection` / `Trace.endSection` via le helper `trace { … }`) : begin et end doivent se produire sur le même thread. C'est garanti ici parce que `network`, `body_read`, `parse_html` et `map_domain` enrobent du code non-`suspend` qui ne peut pas changer de thread pendant son exécution.

Sections **asynchrones** (`Trace.beginAsyncSection` / `Trace.endAsyncSection` via le helper `traceAsync(name, cookie) { … }`) : tolèrent les changements de thread (begin sur thread A, end sur thread B). Utilisées pour tout code qui suspend ou qui peut être resumé sur un dispatcher différent — `room_read`, `room_write` (DAO Room `suspend`) et `first_content` (UI → IO → UI). Le cookie est un entier monotone (un par scope : compteur process-wide pour les sections Room, compteur par-VM pour `first_content`) — il garantit qu'un retry mid-load ferme la section précédente avant qu'une nouvelle commence.

## Visualiser les sections en local

### Android Studio Profiler — System Trace (recommandé pour le dev quotidien)

1. Lancer l'app debug sur device ou émulateur (API ≥ 29 — minSdk du projet).
2. **View → Tool Windows → Profiler**.
3. Sélectionner le process `fr.forumhfr.redface2`.
4. **CPU** → choisir l'onglet **System Trace** → Record.
5. Ouvrir un topic depuis l'app pendant que l'enregistrement tourne.
6. Stopper l'enregistrement, chercher `rf2.topic.*` dans la barre de recherche : les sections apparaissent dans la lane du process.

### Perfetto (pour analyse plus fine)

1. Activer le développeur trace : `adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/trace.pb` avec une config personnalisée (ou utiliser le UI Perfetto sur https://ui.perfetto.dev/ qui sait piloter `record_android_trace`).
2. Reproduire le scénario topic.
3. Pull le trace : `adb pull /data/misc/perfetto-traces/trace.pb`.
4. Drag-and-drop dans https://ui.perfetto.dev/ → search bar → `rf2.topic`.

Perfetto sait grouper les sections par nom et calculer p50 / p95 sur plusieurs runs, ce que System Trace ne fait pas.

## Conventions

- **Toujours préfixer `rf2.topic.`** pour les nouvelles sections du parcours topic. Pour d'autres parcours (forum, drapeaux, MPs), utiliser `rf2.<feature>.` afin de garder un namespace lisible.
- **Pas de format / pas d'emoji** dans les noms de sections — ils sont consommés par des outils qui comparent par chaîne exacte.
- **Sections instantanées (durée ≈ 0)** : préférer un nom qui finit par `.tick` ou `.event` (e.g. `rf2.topic.cache_hit_event`) pour les distinguer des phases de durée. Aucun de ce type n'est utilisé aujourd'hui.
- **Modifier un nom de section = casser un consommateur potentiel** (macrobenchmark, dashboard externe). Toute modification doit mettre à jour ce fichier dans la même PR.

## Hors scope de cette première instrumentation

- **Microbenchmark parser** (`androidx.benchmark.junit4`) — module dédié pour mesurer `HfrParser.parseTopicPage` en isolation. Suivi sous une issue fille ouverte avec `#117`. Déclencheur : une trace montre `rf2.topic.parse_html` dominant le temps avant premier contenu, ou un changement structurel du parser.
- **Macrobenchmark parcours topic** (`androidx.benchmark.macro.junit4`) — module séparé pour mesurer `FrameTimingMetric` et `TraceSectionMetric` sur un scénario complet. Suivi sous une issue fille. Déclencheur : optimisation envisagée sur le parcours, ou ralentissement ressenti remonté en dogfood.
- **OkHttp `EventListener` debug** — séparation fine DNS / connect / TLS / request / response / body. Pas livré ici, peut être ajouté à `HfrClient` si une trace montre que `rf2.topic.network` mérite d'être décomposée plus finement.

## Référence

- AndroidX Tracing 1.3.0 (catégorie `androidx.tracing:tracing`) — la version 1.3 a fusionné `tracing-ktx` dans le module principal. La 2.0.0-alpha introduit `TraceSink` / `TraceDriver` ; non consommée tant qu'elle n'est pas stable.
- [Documentation officielle Android — System tracing overview](https://developer.android.com/topic/performance/tracing) pour le format `Trace.beginSection` / `endSection` et les async sections.
- [Documentation `TraceSectionMetric`](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/TraceSectionMetric) pour la consommation de ces sections par un macrobenchmark.
