# Material 3 — Rapport UI/UX et plan d'enforcement pour Redface 2

> **Statut** : DRAFT — non publié dans les specs. À valider avec la communauté avant intégration en `docs/material3.md`.
> **Auteur** : Claude Opus 4.6 (demandé par XaaT)
> **Date** : 2026-04-15
> **Cible** : Phase 0 — avant le premier commit de code applicatif

## Sommaire

1. [Contexte et objectifs](#1-contexte-et-objectifs)
2. [Fondamentaux Material 3](#2-fondamentaux-material-3)
3. [Système de couleurs — appliqué au forum](#3-système-de-couleurs-appliqué-au-forum)
4. [Typographie — appliquée au forum](#4-typographie-appliquée-au-forum)
5. [Shapes et elevation](#5-shapes-et-elevation)
6. [Motion et transitions](#6-motion-et-transitions)
7. [Layout adaptatif et canonical layouts](#7-layout-adaptatif-et-canonical-layouts)
8. [Navigation](#8-navigation)
9. [Composants clés pour un forum](#9-composants-clés-pour-un-forum)
10. [Accessibilité](#10-accessibilité)
11. [Edge-to-edge et predictive back (Android 15+)](#11-edge-to-edge-et-predictive-back-android-15)
12. [Application écran par écran à Redface 2](#12-application-écran-par-écran-à-redface-2)
13. [Structure `:core:ui` — fichiers et contenus](#13-structure-coreui-fichiers-et-contenus)
14. [Cadre d'enforcement via LLM — 10 couches](#14-cadre-denforcement-via-llm-10-couches)
15. [Plan d'exécution](#15-plan-dexécution)
16. [Questions ouvertes](#16-questions-ouvertes)
17. [Annexes](#17-annexes)

---

## 1. Contexte et objectifs

Redface 2 est un client Android pour forum.hardware.fr. La stack est verrouillée : **Kotlin + Jetpack Compose + Material 3**. Ce document définit comment Material 3 s'applique concrètement à Redface 2 et **comment forcer un LLM à produire du M3 conforme** pendant le développement.

**Hypothèses retenues** :
- Version Material 3 : `androidx.compose.material3:material3:1.3.x+`
- Version Material 3 Adaptive : `androidx.compose.material3.adaptive:adaptive-navigation-suite:1.0.x+`
- minSdk 29 → pas de backport Material You pre-12 à gérer pour le dynamic color (on reste simple : Android 12+ → dynamic color possible, < 12 → palette custom)
- Edge-to-edge forcé sur Android 15+ (obligatoire)
- Predictive back geste disponible

**Objectifs du rapport** :
1. Produire une référence complète que le LLM lit avant chaque génération d'UI
2. Définir les règles non négociables (enforced au build)
3. Définir les règles floues (style, à discuter humainement)
4. Préparer le plan d'exécution en Phase 0 et Phase 1

---

## 2. Fondamentaux Material 3

### 2.1 Trois sous-systèmes du theming

| Sous-système | Token central | Expose |
|---|---|---|
| Color | `ColorScheme` | 30+ rôles de couleur |
| Typography | `Typography` | 15 styles de texte |
| Shapes | `Shapes` | 5 tailles d'arrondis |

Accès unifié via `MaterialTheme.colorScheme`, `MaterialTheme.typography`, `MaterialTheme.shapes`. **Jamais de valeur hardcodée en dehors du module qui définit ces tokens (`:core:ui`).**

### 2.2 Elevation tonale (changement majeur vs M2)

M3 remplace les ombres par des **surface containers** à teinte variable :

| Rôle | Usage |
|---|---|
| `surfaceContainerLowest` | Niveau 0 — background le plus bas |
| `surfaceContainerLow` | Niveau 1 — cartes légèrement élevées |
| `surfaceContainer` | Niveau 2 — AppBar, background par défaut de card |
| `surfaceContainerHigh` | Niveau 3 — dialogs, bottom sheets |
| `surfaceContainerHighest` | Niveau 4 — chips, search bar |

`tonalElevation` sur `Surface` existe mais **préférer les surface containers explicites** (plus prévisible, meilleur dark mode).

### 2.3 Dynamic color (Android 12+)

Extraction de palette depuis le wallpaper utilisateur. API Compose :

```kotlin
val colorScheme = when {
    dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
    dynamicColor && !darkTheme -> dynamicLightColorScheme(context)
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}
```

**Décision Redface 2** : dynamic color **opt-in** (toggle dans settings, défaut OFF). Palette custom HFR par défaut pour préserver le branding.

---

## 3. Système de couleurs appliqué au forum

### 3.1 Les 30 rôles M3 et leur usage Redface 2

| Rôle | Light par défaut | Usage Redface 2 | Obligatoire avec |
|---|---|---|---|
| `primary` | Rouge HFR ~#A62C2C | FAB, CTA "Publier", active indicator | `onPrimary` |
| `onPrimary` | Blanc | Texte/icônes sur `primary` | - |
| `primaryContainer` | Rouge clair | État sélectionné (topic actif, filtre actif) | `onPrimaryContainer` |
| `onPrimaryContainer` | Rouge foncé | Texte sur `primaryContainer` | - |
| `secondary` | Teinte complémentaire | Accents moins emphatiques | `onSecondary` |
| `onSecondary` | - | Texte sur `secondary` | - |
| `secondaryContainer` | Teinte claire | Chips sélectionnés, badges secondaires | `onSecondaryContainer` |
| `onSecondaryContainer` | - | Texte sur `secondaryContainer` | - |
| `tertiary` | Teinte contrastante | Drapeaux, notifications, mentions | `onTertiary` |
| `onTertiary` | - | Texte sur `tertiary` | - |
| `tertiaryContainer` | Teinte claire | Badges tertiaires, highlights | `onTertiaryContainer` |
| `onTertiaryContainer` | - | Texte sur `tertiaryContainer` | - |
| `error` | Rouge Material | Erreurs, modération active, posts signalés | `onError` |
| `onError` | Blanc | Texte sur `error` | - |
| `errorContainer` | Rouge clair | Background d'alerte non critique | `onErrorContainer` |
| `onErrorContainer` | Rouge foncé | Texte sur `errorContainer` | - |
| `surface` | Presque blanc / noir | Background d'écran par défaut | `onSurface` |
| `onSurface` | Noir / presque blanc | Texte principal, corps de post | - |
| `surfaceVariant` | Gris très clair / sombre | Background de card secondaire | `onSurfaceVariant` |
| `onSurfaceVariant` | Gris moyen | Texte secondaire, métadonnées, icônes inactives | - |
| `surfaceContainerLowest` | Presque `surface` | Background le plus profond | - |
| `surfaceContainerLow` | Légèrement teinté | Background de post card | - |
| `surfaceContainer` | Plus teinté | AppBar non scrollé | - |
| `surfaceContainerHigh` | Encore plus | Dialogs, bottom sheets, menus |  - |
| `surfaceContainerHighest` | Le plus teinté | Chips, search bar | - |
| `surfaceBright` | Clair/uniforme | Variante uniforme claire (rare forum) | - |
| `surfaceDim` | Dim/foncé | Variante uniforme foncée (rare forum) | - |
| `surfaceTint` | Teinte de superposition | Auto via `tonalElevation` | - |
| `outline` | Gris moyen | Borders de OutlinedCard, dividers forts | - |
| `outlineVariant` | Gris clair | Dividers discrets entre posts | - |
| `scrim` | Noir alpha | Overlay de modal, drawer | - |
| `inverseSurface` | Inverse de `surface` | Snackbar background | `inverseOnSurface` |
| `inverseOnSurface` | Inverse de `onSurface` | Texte dans snackbar | - |
| `inversePrimary` | Inverse de `primary` | Actions dans snackbar | - |

### 3.2 Règles d'usage

1. **Jamais de couleur sans son pair** : `primary` → `onPrimary`, `surface` → `onSurface`, etc.
2. **Texte long de post** : `onSurface` sur `surface` (ou `surfaceContainerLow` si card)
3. **Métadonnées (date, auteur en sous-titre)** : `onSurfaceVariant`
4. **Liens cliquables dans post** : `primary`
5. **Mentions (@user)** : `tertiary`
6. **Quote block** : `surfaceContainerLow` + border gauche `outline`, texte `onSurfaceVariant`
7. **Code block** : `surfaceContainerHighest` + font monospace, texte `onSurface`
8. **Spoiler** : `surfaceContainer` collapsed, révélé au tap, texte `onSurfaceVariant`
9. **Drapeau actif / non lu** : marqueur `tertiary` + icône (jamais color-only)
10. **Modération** : `error` pour actions destructives, `errorContainer` pour info

### 3.3 Palette HFR — à générer

**Seed color proposée** : `#A62C2C` (rouge brique HFR, à valider avec Ayuget).

**Outil** : https://m3.material.io/theme-builder + export Kotlin, ou Material Kolor (lib Kotlin) pour génération programmatique.

**Output attendu** : 64 couleurs (32 light + 32 dark), stockées dans `:core:ui/theme/Color.kt`.

### 3.4 Contraste WCAG

| Élément | Ratio minimum |
|---|---|
| `bodyLarge` sur `surface` | 4.5:1 (AA normal) |
| `titleMedium` sur `surface` | 3:1 (AA large) |
| Icônes fonctionnelles | 3:1 |
| CTA primaire | 4.5:1 |
| Texte désactivé | 3:1 (à vérifier avec design, sinon incompatible AA) |

Vérification automatisable via règle custom ou manuelle par screenshot tests + outil (TalkBack Accessibility Scanner).

---

## 4. Typographie appliquée au forum

### 4.1 Type scale M3 complète (15 rôles)

| Rôle | Font size | Line height | Letter spacing | Weight | Usage Redface 2 |
|---|---|---|---|---|---|
| `displayLarge` | 57sp | 64sp | -0.25 | Regular | ❌ Pas mobile |
| `displayMedium` | 45sp | 52sp | 0 | Regular | Empty state principal |
| `displaySmall` | 36sp | 44sp | 0 | Regular | Splash / erreur pleine page |
| `headlineLarge` | 32sp | 40sp | 0 | Regular | Titre d'écran sans AppBar |
| `headlineMedium` | 28sp | 36sp | 0 | Regular | `LargeTopAppBar` expanded |
| `headlineSmall` | 24sp | 32sp | 0 | Regular | `MediumTopAppBar` expanded |
| `titleLarge` | 22sp | 28sp | 0 | Regular | `TopAppBar` titre collapsed |
| `titleMedium` | 16sp | 24sp | 0.15 | Medium | Titre de topic en liste, headline ListItem |
| `titleSmall` | 14sp | 20sp | 0.1 | Medium | Auteur de post, sous-titre de section |
| **`bodyLarge`** | **16sp** | **24sp** | **0.5** | **Regular** | **Corps de post — lecture** |
| `bodyMedium` | 14sp | 20sp | 0.25 | Regular | Texte secondaire, supportingText ListItem |
| `bodySmall` | 12sp | 16sp | 0.4 | Regular | Métadonnées (date, compteurs, "éditer il y a 5min") |
| `labelLarge` | 14sp | 20sp | 0.1 | Medium | Texte de Button |
| `labelMedium` | 12sp | 16sp | 0.5 | Medium | Chips, tags, overline |
| `labelSmall` | 11sp | 16sp | 0.5 | Medium | Badges, meta-meta |

### 4.2 Règles pour forum

1. **Corps de post = `bodyLarge`**. Jamais `bodyMedium` pour le contenu principal.
2. **Line-height généreuse** : 24sp pour du 16sp = ratio 1.5x, optimal pour la lecture.
3. **Largeur de ligne max** : 65–75 caractères en Expanded (`Modifier.widthIn(max = 680.dp)`). En Compact, largeur écran - 32dp padding.
4. **Overline** (`labelMedium` all-caps) pour indicateurs de section : "CATÉGORIE • PAGE 3/12".
5. **Date/heure** : `bodySmall` + `onSurfaceVariant`.
6. **Pseudo auteur** : `titleSmall` + `onSurface`, cliquable (couleur `primary` au hover/press).
7. **Citations nested** : descendre d'un cran (`bodyMedium` pour une quote de quote). Maximum 2 niveaux visibles, les niveaux 3+ collapsent.
8. **Code inline** (`[fixed]` HFR est block, mais `[b]` monospaced en inline selon conventions projet) : `bodyMedium` font monospace + `surfaceContainerHighest` background.
9. **Code block** : `bodyMedium` font monospace + `surfaceContainerHighest` background + scroll horizontal si trop large.
10. **Spoiler** : `bodyLarge` normal, fond collapsed = `surfaceContainer`.

### 4.3 Font families

| Famille | Usage | Licence |
|---|---|---|
| Google Sans Text (=Roboto flex) | UI par défaut | Apache 2.0 |
| Google Sans Display | Display et headline | Apache 2.0 |
| JetBrains Mono ou Roboto Mono | Code inline et block | OFL |
| Optionnel : Inter | Alternative UI si Roboto jugé daté | OFL |

**Décision** : Roboto flex par défaut (inclus dans Compose), JetBrains Mono embarqué pour le monospace.

### 4.4 Font scaling

Support obligatoire jusqu'à 200% système. Impacts :
- Jamais de `height` fixe sur composables contenant du texte
- Toujours `wrapContentHeight` ou layouts flexibles
- Tester avec `fontScale = 2.0f` dans les previews

---

## 5. Shapes et elevation

### 5.1 Tokens Shapes M3

| Token | Taille | Usage Redface 2 |
|---|---|---|
| `extraSmall` | 4dp | Badges, chips |
| `small` | 8dp | TextField outlined, chips |
| `medium` | 12dp | Card de post, card de topic |
| `large` | 16dp | FAB, dialog, bottom sheet |
| `extraLarge` | 28dp | Card héro, modal large |

`RoundedCornerShape` privilégié. Shapes asymétriques (`RoundedCornerShape(topStart = 16, topEnd = 16)`) pour bottom sheets.

### 5.2 Elevation

**Pas de shadow custom dans :feature:\***. Utiliser les surface containers. Seul `:core:ui` peut définir des elevations via tokens.

```kotlin
// OK dans :core:ui
Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))

// INTERDIT dans :feature:*
Card(modifier = Modifier.shadow(4.dp))
```

---

## 6. Motion et transitions

### 6.1 Tokens M3

**Durées** :

| Token | Valeur | Usage |
|---|---|---|
| `short1` | 50ms | Micro-feedback (ripple) |
| `short2` | 100ms | Fade small |
| `short3` | 150ms | Slide small |
| `short4` | 200ms | Transition intra-écran |
| `medium1` | 250ms | Transition d'écran |
| `medium2` | 300ms | Bottom sheet |
| `medium3` | 350ms | Shared element |
| `medium4` | 400ms | Container transform |
| `long1` | 450ms | Motion emphatic entrant |
| `long2` | 500ms | Drawer |
| `long3` | 550ms | - |
| `long4` | 600ms | Max recommandé |

**Easings** :
- `emphasized` : standard pour la plupart des transitions UI
- `emphasizedDecelerate` : entrée (element qui apparaît)
- `emphasizedAccelerate` : sortie (element qui disparaît)
- `standard` : micro-animations

### 6.2 Patterns appliqués à Redface 2

| Transition | Pattern M3 | API Compose |
|---|---|---|
| Liste topics → Topic detail | Container transform | `SharedTransitionLayout` + `sharedElement` |
| Switch d'onglet NavigationBar | Fade through | `AnimatedContent` avec `fadeIn + scaleIn` |
| Long-press → bottom sheet | Slide up | `ModalBottomSheet` (automatique) |
| Dialog open | Fade + scale | `AlertDialog` (automatique) |
| FAB hide on scroll | Slide vertical | `AnimatedVisibility` avec `slideOutVertically` |
| PullToRefresh | Progress custom | `PullToRefreshBox` (automatique) |
| Post card press | Ripple | `clickable` (automatique M3) |

### 6.3 Règles motion

1. Respecter `isReducedMotionEnabled()` (Android 14+) — désactiver les grosses animations si actif
2. Durée max 600ms, sauf loaders indéfinis
3. Pas d'animation bloquante (l'utilisateur doit pouvoir interagir pendant)
4. Respecter les easings M3, ne pas custom sans raison

---

## 7. Layout adaptatif et canonical layouts

### 7.1 Window Size Classes

| Classe | Largeur | Hauteur | Devices cibles |
|---|---|---|---|
| Compact | < 600dp | < 480dp | Téléphone portrait, foldable fermé |
| Medium | 600–840dp | 480–900dp | Phone landscape, tablette portrait, foldable ouvert |
| Expanded | ≥ 840dp | ≥ 900dp | Tablette landscape, desktop, ChromeOS |

API : `calculateWindowSizeClass(activity)` dans `MainActivity`.

Pour Redface 2 : stocker le `WindowSizeClass` dans une `CompositionLocal` custom (`LocalWindowSize`) pour accès partout.

### 7.2 Canonical layouts M3

| Layout | Cas | API Compose |
|---|---|---|
| **List-detail** | Liste + détail (forum !) | `ListDetailPaneScaffold` |
| **Feed** | Flux unique scrollable | `LazyColumn` simple |
| **Supporting pane** | Contenu principal + panneau annexe | `SupportingPaneScaffold` |
| **Three pane** | List + detail + extra | `ThreePaneScaffold` |

**Redface 2 = List-detail** :
- Topics list ↔ Topic detail = cas d'école
- Drapeaux list ↔ Topic detail = même schéma
- MP list ↔ MP conversation = même schéma

### 7.3 Comportement par WSC

| Écran | Compact | Medium | Expanded |
|---|---|---|---|
| Forum | Stack (list → detail) | Two-pane 40/60 | Two-pane 30/70 |
| Drapeaux | Stack | Two-pane | Two-pane |
| MP | Stack | Two-pane | Two-pane + extra (infos destinataire) |
| Settings | Full screen | Master-detail (catégories à gauche) | Master-detail |
| Profil | Stack | Stack (profil reste centré) | Stack avec largeur max 840dp centrée |

### 7.4 Foldables

`LocalFoldingFeature` via Jetpack WindowManager. Détection :
- Half-opened → adapter la UI (hinge-aware)
- Book posture → liste à gauche, post à droite

Implémentation Phase 3+. Pas critique Phase 1.

---

## 8. Navigation

### 8.1 Navigation root via NavigationSuiteScaffold

API unifiée M3 Adaptive :

```kotlin
NavigationSuiteScaffold(
    navigationSuiteItems = {
        item(selected = selectedTab == Tab.Forum, onClick = ..., icon = ..., label = { Text("Forum") })
        item(selected = selectedTab == Tab.Flags, onClick = ..., icon = ..., label = { Text("Drapeaux") }, badge = { Badge { ... } })
        item(selected = selectedTab == Tab.Messages, onClick = ..., icon = ..., label = { Text("MP") })
    }
) {
    // Contenu principal
}
```

S'adapte seule :
- Compact → `NavigationBar` (bottom, icônes + label)
- Medium → `NavigationRail` (côté, vertical)
- Expanded → `PermanentNavigationDrawer` (côté, ouvert)

### 8.2 TopAppBar — 4 variants

| Variant | Hauteur | Usage Redface 2 | Exemple |
|---|---|---|---|
| `TopAppBar` (small) | 64dp | Écrans secondaires standards | Liste drapeaux, settings |
| `CenterAlignedTopAppBar` | 64dp | Écran "top-level" avec titre court | Forum (liste de catégories) |
| `MediumTopAppBar` | 112dp → 64dp | Écran avec titre qui mérite emphase modérée | Topic detail |
| `LargeTopAppBar` | 152dp → 64dp | Écran "hero" | Profil utilisateur |

### 8.3 Scroll behaviors

```kotlin
val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

Scaffold(
    topBar = { MediumTopAppBar(..., scrollBehavior = scrollBehavior) },
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
)
```

Comportements :
- `pinned` : barre toujours visible (par défaut small)
- `enterAlways` : réapparaît au scroll up dès 1px
- `exitUntilCollapsed` : collapse la grande zone seulement, barre reste
- `enterAlwaysCollapsed` : cache tout, réapparaît au scroll up

**Choix Redface 2** :
- Topic detail : `exitUntilCollapsed` (Medium) pour avoir le titre visible au départ puis compacter
- Forum list : `enterAlways` (small) pour récupérer de l'espace au scroll

### 8.4 Actions dans TopAppBar

Ordre conventionnel :
1. Navigation icon (back / menu) en leading
2. Titre (1 ligne, ellipsize)
3. Actions trailing : 2 actions visibles max, autres dans overflow menu (IconButton "more")

---

## 9. Composants clés pour un forum

### 9.1 `ListItem` — unité de base

```kotlin
ListItem(
    headlineContent = { Text(topic.title, style = MaterialTheme.typography.titleMedium, maxLines = 2) },
    supportingContent = { Text("Dernier post : @${topic.lastAuthor} • ${topic.lastDate.relative()}") },
    overlineContent = { Text(topic.category.uppercase()) },
    leadingContent = { Icon(Icons.Rounded.Forum, contentDescription = null) },
    trailingContent = { Badge { Text("${topic.unreadCount}") } },
    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.clickable { onTopicClick(topic.id) }
)
```

**Règles** :
- Touch target hauteur mini 48dp (respecté par ListItem par défaut)
- `contentDescription = null` sur icône purement décorative
- `contentDescription = "Ouvrir topic"` sur icône porteuse d'action

### 9.2 Card de post

```kotlin
Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
) {
    Column(Modifier.padding(16.dp)) {
        // Header
        ListItem(
            headlineContent = { Text("@${post.author}", style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text(post.date.relative(), style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Avatar(post.avatarUrl) },
            trailingContent = { IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Plus d'actions") } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        // Body
        BBCodeText(post.content, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onQuote, label = { Text("Citer") }, leadingIcon = { Icon(Icons.Rounded.FormatQuote, null) })
            if (post.isOwn) AssistChip(onClick = onEdit, label = { Text("Éditer") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            AssistChip(onClick = onReport, label = { Text("Signaler") }, leadingIcon = { Icon(Icons.Rounded.Flag, null) })
        }
    }
}
```

### 9.3 Chips — 4 variants

| Chip | Action | Exemple Redface 2 |
|---|---|---|
| `AssistChip` | Action ponctuelle | "Citer", "Éditer", "Signaler" |
| `FilterChip` | Filtre togglable | "Non lus", "Lus", "Tous" |
| `InputChip` | Tag saisi | Destinataires MP, tags recherche |
| `SuggestionChip` | Suggestion | Autocomplétion @mention |

**Règle** : `selected = true` sur FilterChip → utilise `selectedContainerColor`.

### 9.4 Buttons — hiérarchie

```
FAB                 → Action flottante principale (Répondre, Nouveau topic)
Button (filled)     → CTA contextuel principal (Publier, Envoyer MP)
FilledTonalButton   → Action forte secondaire (Citer, Multicite)
ElevatedButton      → Rare, effet de profondeur voulu
OutlinedButton      → Action moyenne (Annuler, Retour)
TextButton          → Action mineure (Brouillon, Skip)
IconButton          → Action dans AppBar / ListItem trailing
FilledIconButton    → Icon button emphase forte
OutlinedIconButton  → Icon button emphase moyenne
```

### 9.5 FAB

```kotlin
// Liste topics : extended
ExtendedFloatingActionButton(
    onClick = onNewTopic,
    text = { Text("Nouveau sujet") },
    icon = { Icon(Icons.Rounded.Add, null) },
    expanded = !isScrolling  // collapse en Icon only pendant scroll
)

// Topic detail : regular icon FAB
FloatingActionButton(onClick = onReply) {
    Icon(Icons.Rounded.Reply, "Répondre")
}
```

### 9.6 SearchBar

```kotlin
// Compact : full-screen
SearchBar(
    query = query,
    onQueryChange = onQueryChange,
    onSearch = onSearch,
    active = active,
    onActiveChange = onActiveChange,
    placeholder = { Text("Rechercher dans HFR") },
    leadingIcon = { Icon(Icons.Rounded.Search, null) },
    trailingIcon = if (active) { { IconButton(onClick = clear) { Icon(Icons.Rounded.Close, "Effacer") } } } else null
) {
    // Résultats / historique
}

// Medium+ : docked
DockedSearchBar(...) { ... }
```

### 9.7 Bottom sheets

```kotlin
if (showSheet) {
    ModalBottomSheet(
        onDismissRequest = { showSheet = false },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Actions sur le post", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text("Citer") },
                leadingContent = { Icon(Icons.Rounded.FormatQuote, null) },
                modifier = Modifier.clickable { ...; showSheet = false }
            )
            ListItem(headlineContent = { Text("Multicite") }, leadingContent = { Icon(Icons.Rounded.AddCircle, null) }, ...)
            ListItem(headlineContent = { Text("Signaler") }, leadingContent = { Icon(Icons.Rounded.Flag, null) }, ...)
            ListItem(headlineContent = { Text("Voir profil") }, leadingContent = { Icon(Icons.Rounded.Person, null) }, ...)
        }
    }
}
```

### 9.8 Dialogs

```kotlin
AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Rounded.DeleteOutline, null) },
    title = { Text("Supprimer ce drapeau ?") },
    text = { Text("Cette action est irréversible.") },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Supprimer") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
)
```

Full-screen dialog pour éditeur : utiliser `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))`.

### 9.9 Snackbar

```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) }
) { padding ->
    // ...
}

// Afficher
scope.launch {
    val result = snackbarHostState.showSnackbar(
        message = "Post envoyé",
        actionLabel = "Annuler",
        duration = SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed) onUndo()
}
```

### 9.10 PullToRefreshBox

```kotlin
PullToRefreshBox(
    isRefreshing = state.isRefreshing,
    onRefresh = viewModel::refresh,
    modifier = Modifier.fillMaxSize()
) {
    LazyColumn { ... }
}
```

**Remplace définitivement Accompanist SwipeRefresh.**

### 9.11 Badges

```kotlin
BadgedBox(
    badge = {
        if (unreadCount > 0) Badge { Text("$unreadCount") }
    }
) {
    Icon(Icons.Rounded.Mail, null)
}
```

### 9.12 Progress indicators

- `LinearProgressIndicator` : en haut d'écran lors de chargement (remplace Snackbar "loading")
- `CircularProgressIndicator` : pour opérations locales (bouton chargement)
- **Ne jamais mettre de CircularProgressIndicator au centre plein écran** : préférer skeleton ou shimmer

### 9.13 TextField

```kotlin
OutlinedTextField(
    value = text,
    onValueChange = onChange,
    label = { Text("Votre message") },
    placeholder = { Text("Écrivez ici...") },
    supportingText = { Text("${text.length} / 10000") },
    isError = error != null,
    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
    modifier = Modifier.fillMaxWidth()
)
```

Pour édition longue : `OutlinedTextField` avec `minLines = 8, maxLines = Int.MAX_VALUE`.

### 9.14 Segmented buttons

Alternative aux FilterChips quand les options sont exclusives :

```kotlin
SingleChoiceSegmentedButtonRow {
    options.forEachIndexed { index, option ->
        SegmentedButton(
            selected = selected == option,
            onClick = { onSelect(option) },
            shape = SegmentedButtonDefaults.itemShape(index, options.size),
            label = { Text(option.label) }
        )
    }
}
```

---

## 10. Accessibilité

### 10.1 Checklist obligatoire par écran

1. [ ] Tous les `IconButton` ont un `contentDescription` non null
2. [ ] Toutes les `Image` porteuses d'info ont un `contentDescription`
3. [ ] Images décoratives : `contentDescription = null`
4. [ ] Touch targets ≥ 48dp × 48dp
5. [ ] Contraste WCAG AA validé (4.5:1 body, 3:1 large)
6. [ ] Pas d'information transmise par couleur seule (toujours doublée par icône ou texte)
7. [ ] Font scaling 200% testé, pas de texte tronqué
8. [ ] Headings marqués avec `Modifier.semantics { heading() }`
9. [ ] Groupes logiques avec `Modifier.semantics { contentDescription = "..." }` si composable composite
10. [ ] Swipe gestures ont un équivalent bouton (pour TalkBack)
11. [ ] `TextField` ont un `label` ou un `contentDescription`
12. [ ] Focus order cohérent (de haut en bas, gauche à droite)
13. [ ] Loading states annoncés (`stateDescription = "Chargement"`)
14. [ ] Erreurs annoncées (`LiveRegion.Polite`)

### 10.2 TalkBack test

Tester chaque écran avec TalkBack activé :
- Chaque élément interactif est annoncé
- L'ordre est logique
- Les états (sélectionné, désactivé, checked) sont annoncés
- Les actions disponibles sont claires

### 10.3 Font scaling

```kotlin
@Preview(fontScale = 2.0f, name = "Font 200%")
@Composable fun PreviewFontScale() { ... }
```

---

## 11. Edge-to-edge et predictive back (Android 15+)

### 11.1 Edge-to-edge obligatoire Android 15+

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RedfaceTheme { ... } }
    }
}
```

Impacts :
- Scaffold gère automatiquement les insets si on passe le `innerPadding`
- `NavigationBar` et `TopAppBar` consomment correctement les insets
- Attention aux FAB : positionner avec `Modifier.navigationBarsPadding()` si pas dans Scaffold
- Attention au clavier : `Modifier.imePadding()` sur TextField

### 11.2 Predictive back

Gesture "slide depuis le bord" avec aperçu. Gérer dans `NavHost` :
- Par défaut, les transitions M3 sont compatibles
- Pour animations custom : utiliser `PredictiveBackHandler`

---

## 12. Application écran par écran à Redface 2

### 12.1 Matrice écrans × composants

| Écran | Scaffold | TopAppBar | Body | Actions | Motion |
|---|---|---|---|---|---|
| Forum (catégories) | `NavigationSuiteScaffold` + `Scaffold` | `CenterAlignedTopAppBar` | `LazyColumn` + `ListItem` | `SearchBar` | Enter: fade |
| Liste topics | `Scaffold` | `TopAppBar` small + `enterAlways` | `LazyColumn` + `ListItem` + `PullToRefreshBox` | `ExtendedFAB` "Nouveau", filtre | List-to-detail: shared element |
| Topic detail | `ListDetailPaneScaffold` detail | `MediumTopAppBar` + `exitUntilCollapsed` | `LazyColumn` + `Card` posts | `FAB` "Répondre", long-press → `ModalBottomSheet` | ~ |
| Drapeaux | `NavigationSuiteScaffold` + `Scaffold` | `TopAppBar` small | `FilterChip` row + `LazyColumn` + `ListItem` | Swipe to dismiss, `PullToRefreshBox` | Slide dismiss |
| MP liste | `NavigationSuiteScaffold` + `Scaffold` | `TopAppBar` small | `LazyColumn` + `ListItem` | `ExtendedFAB` "Nouveau MP" | - |
| MP conversation | `Scaffold` | `MediumTopAppBar` | `LazyColumn` + `Card` messages | `OutlinedTextField` + `IconButton` send | - |
| Éditeur | Full-screen `Dialog` | `LargeTopAppBar` + action `Button` "Publier" | `OutlinedTextField` multi-line | `BottomAppBar` BBCode actions | Slide up |
| Profil | `Scaffold` | `LargeTopAppBar` + `exitUntilCollapsed` | Header Card + `LazyColumn` `ListItem` | `Button` "Envoyer MP" | - |
| Settings | `Scaffold` | `LargeTopAppBar` + `exitUntilCollapsed` | `LazyColumn` sections + `ListItem` | - | - |
| Recherche | `Scaffold` | `SearchBar` full-screen | `LazyColumn` résultats | FilterChips critères | - |
| Login | `Scaffold` | `CenterAlignedTopAppBar` | `OutlinedTextField` + `Button` | - | - |
| Nouveau topic | Full-screen `Dialog` | `LargeTopAppBar` + action "Publier" | `OutlinedTextField` titre + body + `SegmentedButton` catégorie | BottomAppBar BBCode | Slide up |

### 12.2 Écran Forum — détail

```kotlin
@Composable
fun ForumScreen(state: ForumState, onIntent: (ForumIntent) -> Unit) {
    NavigationSuiteScaffold(
        navigationSuiteItems = { ... 3 tabs ... }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Forum HFR") },
                    actions = {
                        IconButton(onClick = { onIntent(ForumIntent.OpenSearch) }) {
                            Icon(Icons.Rounded.Search, "Rechercher")
                        }
                    }
                )
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onIntent(ForumIntent.Refresh) },
                modifier = Modifier.padding(padding)
            ) {
                LazyColumn {
                    items(state.categories, key = { it.id }) { category ->
                        ListItem(
                            headlineContent = { Text(category.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text("${category.topicsCount} topics") },
                            leadingContent = { Icon(category.icon, null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.ChevronRight, null) },
                            modifier = Modifier.clickable { onIntent(ForumIntent.OpenCategory(category.id)) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
```

### 12.3 Écran Topic detail — détail

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(state: TopicState, onIntent: (TopicIntent) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(state.topic.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(TopicIntent.Back) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(TopicIntent.ToggleBookmark) }) {
                        Icon(
                            if (state.topic.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (state.topic.bookmarked) "Retirer des favoris" else "Ajouter aux favoris"
                        )
                    }
                    IconButton(onClick = { onIntent(TopicIntent.OpenMenu) }) {
                        Icon(Icons.Rounded.MoreVert, "Plus d'actions")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onIntent(TopicIntent.Reply) }) {
                Icon(Icons.AutoMirrored.Rounded.Reply, "Répondre")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.posts, key = { it.id }) { post ->
                PostCard(
                    post = post,
                    onQuote = { onIntent(TopicIntent.Quote(post.id)) },
                    onEdit = { onIntent(TopicIntent.Edit(post.id)) },
                    onLongPress = { onIntent(TopicIntent.OpenActions(post.id)) }
                )
            }
            item {
                PaginationControls(state.currentPage, state.totalPages) { page -> onIntent(TopicIntent.GoToPage(page)) }
            }
        }
    }

    if (state.showActionSheet) {
        PostActionsBottomSheet(
            post = state.currentActionPost,
            onAction = { action -> onIntent(TopicIntent.PerformAction(action)) },
            onDismiss = { onIntent(TopicIntent.CloseActions) }
        )
    }
}
```

### 12.4 Écran Éditeur — détail

```kotlin
@Composable
fun EditorDialog(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    Dialog(
        onDismissRequest = { onIntent(EditorIntent.Cancel) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                LargeTopAppBar(
                    title = { Text(if (state.isEdit) "Édition" else "Nouveau message") },
                    navigationIcon = {
                        IconButton(onClick = { onIntent(EditorIntent.Cancel) }) {
                            Icon(Icons.Rounded.Close, "Annuler")
                        }
                    },
                    actions = {
                        Button(
                            onClick = { onIntent(EditorIntent.Publish) },
                            enabled = state.canPublish,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(if (state.isEdit) "Enregistrer" else "Publier")
                        }
                    }
                )
            },
            bottomBar = {
                BBCodeToolbar(onAction = { onIntent(EditorIntent.BBCode(it)) })
            }
        ) { padding ->
            OutlinedTextField(
                value = state.content,
                onValueChange = { onIntent(EditorIntent.UpdateContent(it)) },
                label = { Text("Votre message") },
                placeholder = { Text("Écrivez...") },
                supportingText = { Text("${state.content.length} / 10000") },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                minLines = 8
            )
        }
    }
}
```

### 12.5 Previews obligatoires par écran

```kotlin
@Preview(name = "Light Compact", showBackground = true, device = "spec:width=360dp,height=640dp,dpi=480")
@Preview(name = "Dark Compact", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=360dp,height=640dp,dpi=480")
@Preview(name = "Light Medium", showBackground = true, device = "spec:width=800dp,height=1280dp,dpi=480")
@Preview(name = "Light Expanded", showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=480")
@Preview(name = "Font 200%", showBackground = true, fontScale = 2.0f)
@Preview(name = "Dynamic Color", showBackground = true, wallpaper = Wallpapers.RED_DOMINATED_EXAMPLE)
@Composable
fun TopicScreenPreview() { /* ... */ }
```

Au minimum : Light Compact + Dark Compact + Font 200% (3 previews) par écran.

---

## 13. Structure `:core:ui` — fichiers et contenus

```
:core:ui/
├── build.gradle.kts
└── src/main/kotlin/com/redface/ui/
    ├── theme/
    │   ├── Color.kt          # Palette HFR générée + rôles
    │   ├── Typography.kt     # Typography() complète 15 rôles
    │   ├── Shapes.kt         # Shapes() 5 tailles
    │   ├── Motion.kt         # Durations + Easings tokens
    │   ├── Dimens.kt         # Espacements custom (non-M3)
    │   └── RedfaceTheme.kt   # MaterialTheme wrapper
    ├── components/
    │   ├── Avatar.kt         # Avatar user avec fallback initiales
    │   ├── BBCodeText.kt     # Rendu BBCode en AnnotatedString
    │   ├── PostCard.kt       # Card de post réutilisable
    │   ├── TopicListItem.kt  # ListItem spécialisé topic
    │   ├── FlagListItem.kt   # ListItem spécialisé drapeau
    │   ├── SmileyPicker.kt   # Sélecteur de smileys HFR
    │   ├── BBCodeToolbar.kt  # Bottom toolbar éditeur
    │   ├── PaginationBar.kt  # Contrôles pagination
    │   └── EmptyState.kt     # État vide standardisé
    ├── adaptive/
    │   ├── LocalWindowSize.kt      # CompositionLocal pour WindowSizeClass
    │   └── AdaptiveScaffold.kt     # Helper si besoin
    ├── semantics/
    │   └── Headings.kt             # Helpers `heading()` etc.
    └── util/
        ├── RelativeDate.kt         # "il y a 2h"
        └── WindowInsets.kt         # Helpers insets
```

### 13.1 `Color.kt` (extrait attendu)

```kotlin
// Auto-généré par Material Theme Builder, seed = #A62C2C
// Ne pas modifier à la main — régénérer.

val primaryLight = Color(0xFF9F3B3B)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFFFDAD5)
val onPrimaryContainerLight = Color(0xFF410001)
// ... 60+ valeurs ...

val LightColorScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    // ... complet ...
)

val DarkColorScheme = darkColorScheme(
    primary = primaryDark,
    // ... complet ...
)
```

### 13.2 `Typography.kt`

```kotlin
private val RedfaceFontFamily = FontFamily.Default // Roboto flex

val RedfaceTypography = Typography(
    displayLarge = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp),
    displaySmall = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = RedfaceFontFamily, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
)

// Style monospace supplémentaire pour code
val MonospaceTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 20.sp
)
```

### 13.3 `Shapes.kt`

```kotlin
val RedfaceShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
```

### 13.4 `Motion.kt`

```kotlin
object RedfaceMotion {
    object Durations {
        val short1 = 50
        val short2 = 100
        val short3 = 150
        val short4 = 200
        val medium1 = 250
        val medium2 = 300
        val medium3 = 350
        val medium4 = 400
        val long1 = 450
        val long2 = 500
        val long3 = 550
        val long4 = 600
    }

    object Easings {
        val emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
        val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
        val emphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
        val standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    }
}
```

### 13.5 `Dimens.kt`

```kotlin
// Dimensions non couvertes par les tokens M3 natifs
object RedfaceDimens {
    val screenPaddingHorizontal = 16.dp
    val screenPaddingVertical = 8.dp
    val cardPadding = 16.dp
    val postSpacing = 8.dp
    val avatarSize = 40.dp
    val avatarLargeSize = 80.dp
    val maxContentWidth = 680.dp  // largeur de lecture optimale
    val dividerThickness = 1.dp
    val touchTargetMin = 48.dp
}
```

### 13.6 `RedfaceTheme.kt`

```kotlin
@Composable
fun RedfaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // opt-in défaut off
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RedfaceTypography,
        shapes = RedfaceShapes,
        content = content
    )
}
```

---

## 14. Cadre d'enforcement via LLM — 10 couches

### Couche 1 — Référence `docs/material3.md`

Ce document (ou sa version publiée) devient la source de vérité. Référencé depuis :
- `CLAUDE.md` (règles UI)
- `docs/architecture.md` (module `:core:ui`)
- `docs/mvi.md` (exemples Compose)
- `docs/features.md` (écrans)
- `docs/contributing.md` (UI checklist PR)

### Couche 2 — Règles `CLAUDE.md`

À ajouter en fin de `CLAUDE.md`, section "UI / Material 3" :

```markdown
### UI / Material 3

#### Règles absolues (enforced)
- Jamais d'import `androidx.compose.material.*` hors `material.icons`. Toujours `androidx.compose.material3.*`.
- Jamais de `Color(0x...)` ni `Color.Red` dans `:feature:*`. Toujours `MaterialTheme.colorScheme.*`.
- Jamais de `fontSize = X.sp` dans `:feature:*`. Toujours `MaterialTheme.typography.*`.
- Jamais de `RoundedCornerShape(...)` direct dans `:feature:*`. Toujours `MaterialTheme.shapes.*`.
- Jamais de `SwipeRefresh` (Accompanist). Toujours `PullToRefreshBox`.
- Jamais de `Chip` générique. Toujours `AssistChip`/`FilterChip`/`InputChip`/`SuggestionChip`.
- Jamais de `BottomNavigation`. Toujours `NavigationBar` via `NavigationSuiteScaffold`.
- Jamais de `TopAppBar` par défaut sans choix de variant.
- Touch target ≥ 48dp sur tout cliquable.
- `contentDescription` obligatoire sur `IconButton`, `Icon` cliquable, `Image` porteuse d'info.
- `contentDescription = null` explicite sur images purement décoratives.
- Pas de dimension hardcodée hors `:core:ui/Dimens.kt`. Exception : 0dp, 4dp, 8dp, 16dp courants.
- Dark mode fonctionnel obligatoire.
- Compact window size obligatoire, Medium/Expanded pour features principales (Forum, Topic, MP).

#### Règles souples (review humaine)
- Hiérarchie de boutons respectée (FAB → Button → FilledTonal → Outlined → Text).
- TopAppBar variant choisi selon nature de l'écran (cf. docs/material3.md).
- Motion tokens RedfaceMotion utilisés (pas de durée magique).
- Previews light + dark + font 200% présentes.
```

### Couche 3 — Konsist au build (issue #20)

Fichier : `build-logic/konsist-rules/src/test/kotlin/.../Material3Rules.kt`

```kotlin
class Material3Rules {

    @Test fun `no Material 2 imports`() {
        Konsist.scopeFromProduction()
            .imports
            .filter { it.name.startsWith("androidx.compose.material.") }
            .filter { !it.name.startsWith("androidx.compose.material.icons.") }
            .assertEmpty()
    }

    @Test fun `no hardcoded Color in feature modules`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.moduleName.startsWith("feature-") || it.moduleName.startsWith(":feature:") }
            .filter { it.text.contains(Regex("""Color\(0x[0-9A-Fa-f]{6,8}\)""")) }
            .assertEmpty()
    }

    @Test fun `no hardcoded fontSize in feature modules`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.moduleName.startsWith("feature-") }
            .filter { it.text.contains(Regex("""fontSize\s*=\s*\d+\.sp""")) }
            .assertEmpty()
    }

    @Test fun `no SwipeRefresh usage`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.text.contains("SwipeRefresh") }
            .assertEmpty()
    }

    @Test fun `no Chip generic usage`() {
        // Chip( sans variant → fail. AssistChip, FilterChip, etc. → OK.
        Konsist.scopeFromProduction()
            .files
            .filter { it.text.contains(Regex("""[^aA-zZ]Chip\s*\(""")) }
            .assertEmpty()
    }

    @Test fun `no BottomNavigation usage`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.text.contains("BottomNavigation") }
            .assertEmpty()
    }

    @Test fun `ColorScheme Typography Shapes defined only in core-ui`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.moduleName.contains("core-ui") }
            .filter { it.text.contains(Regex("""(lightColorScheme|darkColorScheme|Typography\s*\(|Shapes\s*\()""")) }
            .assertEmpty()
    }
}
```

### Couche 4 — Skill `.claude/commands/m3-check.md`

```markdown
# Vérification Material 3 sur un fichier ou un dossier Compose

Audite un fichier ou un dossier Compose et détecte les violations Material 3.

## Argument

$ARGUMENTS = chemin du fichier Kotlin/Compose ou dossier à auditer.

## Règles (par sévérité)

### Critique (bloque le merge)
1. Import `androidx.compose.material.*` hors icons → migrer M3
2. `Color(0xXXXXXXXX)` ou `Color.X` hardcodé dans `:feature:*` → MaterialTheme.colorScheme
3. `fontSize = N.sp` dans `:feature:*` → MaterialTheme.typography
4. `RoundedCornerShape(N.dp)` dans `:feature:*` → MaterialTheme.shapes
5. `SwipeRefresh` → PullToRefreshBox
6. `Chip(` générique → variant explicite
7. `BottomNavigation` → NavigationBar
8. `IconButton` avec Icon enfant sans `contentDescription` → ajouter

### Important (revoir avant merge)
9. `TopAppBar(` sans variant explicite
10. `FloatingActionButton` sans position bottom-end
11. Corps de post pas en `bodyLarge`
12. `Card` ElevatedCard par défaut (préférer Filled)
13. `Dialog` sans `onDismissRequest`
14. Dimension hardcodée (`X.dp`) non dans 0/4/8/16/24/32 → Dimens

### Moyen (nit)
15. Absence de `key =` dans `items(` de LazyColumn
16. Pas de preview dark mode
17. Pas de preview font 200%
18. `TextField` sans `label` ni `contentDescription`
19. Pas de `semantics { heading() }` sur en-tête d'écran

## Sortie

Rapport markdown par fichier :

```
# m3-check : <chemin>

## Critique (N)
- ligne M: description → fix proposé

## Important (N)
...

## Moyen (N)
...

## Résumé
- Violations critiques : N
- Violations importantes : N
- Violations moyennes : N
- Status global : FAIL / PASS / WARN
```

## Étapes

1. Lire `drafts/material3-ui-ux.md` ou `docs/material3.md` (référence)
2. Lire chaque fichier `.kt` du chemin donné
3. Parser les règles une par une
4. Générer le rapport structuré
5. Si invoqué en pre-commit : exit code 1 si critiques > 0
```

### Couche 5 — Skill `.claude/commands/m3-screen.md`

```markdown
# Générer un écran Compose conforme Material 3

Génère la structure Compose d'un écran Redface 2 en respectant Material 3.

## Argument

$ARGUMENTS = description fonctionnelle de l'écran (ex: "écran de sélection de smileys avec grid de 6 colonnes, search bar, catégories")

## Étapes

1. Lire `drafts/material3-ui-ux.md` ou `docs/material3.md`
2. Lire `docs/mvi.md` pour le pattern ViewModel/State/Intent
3. Lire `docs/navigation.md` pour les contraintes d'insertion dans la NavGraph
4. Identifier la catégorie de l'écran :
   - List (feed ou list-detail)
   - Detail (part de list-detail)
   - Form (éditeur, login, settings)
   - Dialog/sheet
5. Proposer la stack de composants :
   - Scaffold type (NavigationSuiteScaffold ? ListDetailPaneScaffold ? Scaffold ?)
   - TopAppBar variant + scroll behavior
   - Body (LazyColumn de ListItem ? LazyColumn de Card ? Grid ?)
   - Actions (FAB ? Button dans AppBar ? BottomAppBar ?)
   - Overlay (BottomSheet ? Dialog ?)
6. Générer :
   - Data class State
   - Sealed interface Intent
   - ViewModel stub avec reduce()
   - Composable Screen complet
   - Sub-composables si besoin
   - Previews : Light Compact, Dark Compact, Font 200%
7. Vérifier mentalement `/m3-check` sur la sortie :
   - Tous les Color via MaterialTheme
   - Tous les TextStyle via MaterialTheme
   - Tous les Shape via MaterialTheme
   - contentDescription partout où requis
   - Touch targets ≥ 48dp

## Format de sortie

Un fichier Kotlin par composable, structuré :
- `<Feature>State.kt`
- `<Feature>Intent.kt`
- `<Feature>ViewModel.kt`
- `<Feature>Screen.kt`

Plus un résumé :

```
Écran généré : <nom>
Scaffold : <type>
TopAppBar : <variant> + <scroll behavior>
Body : <composants>
Actions : <liste>
Accessibilité : <points clés>
Previews générés : <liste>
```

## Contraintes

- Tous les textes en français
- Commentaires uniquement si la logique n'est pas évidente
- Imports organisés (pas de wildcards sauf androidx.compose.*)
- Jamais de @OptIn sauf ExperimentalMaterial3Api si strictement nécessaire
- Respect strict de la règle "no hardcoded values"
```

### Couche 6 — Screenshot tests (Roborazzi)

`:build-logic` ajoute la convention `redface.screenshot-tests` qui applique :
- Plugin Roborazzi
- Dépendances tests
- Task `verifyRoborazzi` obligatoire en CI

Par écran (`:feature:topic`), un fichier `src/test/kotlin/.../TopicScreenScreenshotTest.kt` :

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicScreenScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun topicScreen_compact_light() = captureScreenshot("compact_light", compact = true, dark = false)
    @Test fun topicScreen_compact_dark() = captureScreenshot("compact_dark", compact = true, dark = true)
    @Test fun topicScreen_medium_light() = captureScreenshot("medium_light", compact = false, dark = false)
    @Test fun topicScreen_font200() = captureScreenshot("font200", compact = true, dark = false, fontScale = 2.0f)

    private fun captureScreenshot(name: String, compact: Boolean, dark: Boolean, fontScale: Float = 1.0f) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = fontScale)) {
                RedfaceTheme(darkTheme = dark) {
                    TopicScreen(state = fakeTopicState, onIntent = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("$name.png")
    }
}
```

Obligations :
- 1 screenshot test par écran principal minimum
- 4 variants : compact light / compact dark / medium light / font 200
- Diff en CI, rejet de PR si diff > seuil

### Couche 7 — Tokens centralisés

Contrainte : `ColorScheme`, `Typography`, `Shapes` ne peuvent être instanciés que dans `:core:ui`. Enforced par Konsist (Couche 3).

### Couche 8 — Palette via Material Theme Builder

Procédure :
1. Ouvrir https://m3.material.io/theme-builder
2. Seed color = `#A62C2C`
3. Preferences : neutral variant standard
4. Export → Jetpack Compose (.kt)
5. Copier le contenu dans `:core:ui/theme/Color.kt`
6. Commit dédié `chore(ui): regenerate HFR palette from Material Theme Builder (seed: #A62C2C)`
7. Interdiction de modifier manuellement les constantes. Changement = nouveau seed + re-export complet.

### Couche 9 — Pre-commit hook + CI

`.githooks/pre-commit` :
```bash
#!/bin/bash
# Fichiers Compose modifiés ?
files=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.(kt)$' | xargs grep -l '@Composable' 2>/dev/null)
if [ -n "$files" ]; then
    echo "Checking Material 3 compliance..."
    # Invoquer claude /m3-check $files en mode non-interactif
    # Exit 1 si critiques
fi
```

GitHub Actions `ci.yml` :
```yaml
jobs:
  verify:
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21 }
      - run: ./gradlew konsistTest        # règles archi + M3
      - run: ./gradlew verifyRoborazzi    # screenshots
      - run: ./gradlew lintDebug          # lint Android + Compose lint
      - run: ./gradlew testDebugUnitTest
```

### Couche 10 — Review PR automatisée

GHA qui invoque le skill `/m3-check` sur les fichiers modifiés d'une PR et poste un commentaire avec le rapport.

Commentaire type :
```
## /m3-check report

**feature-topic/src/main/kotlin/.../TopicScreen.kt**
- ❌ Critique : ligne 45 — `Color(0xFF1976D2)` hardcodé → utiliser `MaterialTheme.colorScheme.primary`
- ⚠️ Important : ligne 78 — `TopAppBar` sans variant → choisir explicitement small/centerAligned/medium/large

**Status : FAIL** (1 critique, 1 important)

_Rapport généré par Claude. Pour discuter ce report, utilisez le skill `/m3-check` localement._
```

---

## 15. Plan d'exécution

### Phase 0 — Specs (maintenant)

| Tâche | Owner | Sortie |
|---|---|---|
| 1. Valider ce draft | Humain | Décision publish ou iterate |
| 2. Publier en `docs/material3.md` | LLM | Nouvelle page nav_order 12 |
| 3. Mettre à jour `CLAUDE.md` section UI | LLM | Règles ajoutées |
| 4. Créer `.claude/commands/m3-check.md` | LLM | Skill opérationnel |
| 5. Créer `.claude/commands/m3-screen.md` | LLM | Skill opérationnel |
| 6. Valider seed color HFR | Humain (Ayuget) | Hex validé |
| 7. Mettre à jour `docs/architecture.md` section `:core:ui` | LLM | Structure alignée |
| 8. Ajouter check UI à `docs/contributing.md` | LLM | Checklist PR étendue |
| 9. Bump spec vers v0.4.0 | LLM | Footer config |

### Phase 0.5 — Setup projet

| Tâche | Dépendance |
|---|---|
| 10. Init Gradle + `:core:ui` vide | - |
| 11. Générer palette via Theme Builder | 6 |
| 12. Coder `Color.kt`, `Typography.kt`, `Shapes.kt`, `Motion.kt`, `Dimens.kt`, `RedfaceTheme.kt` | 11 |
| 13. Setup Konsist + règles M3 (issue #20) | 10 |
| 14. Setup Roborazzi convention | 10 |
| 15. Setup pre-commit hook `/m3-check` | 4, 10 |
| 16. Setup GHA CI avec verify steps | 13, 14 |
| 17. Screen-test exemple sur 1 écran factice | 12, 14 |

### Phase 1 — Premiers écrans

Par écran (Forum, Topic, Drapeaux, MP, Login) :
1. `/m3-screen "..."` → génération initiale
2. Implémentation + adaptations
3. Screenshot tests (4 variants minimum)
4. `/m3-check` sur le feature module
5. Konsist pass
6. Preview manuelle avec TalkBack
7. PR avec screenshots dans description

---

## 16. Questions ouvertes

1. **Seed color HFR exacte** : `#A62C2C` est une proposition. À valider avec Ayuget ou la communauté.
2. **Dynamic color défaut** : OFF (proposé) ou ON ? Impact branding vs intégration système.
3. **Font family** : Roboto flex (défaut) ou embarquer Inter ? Taille APK vs familiarité.
4. **Densité** : standard ou compact par défaut ? Power users tablette préfèrent souvent compact.
5. **Rendering BBCode** : `AnnotatedString` (simple) vs layout custom (gère quotes nested, spoilers interactifs). Décision Phase 1 parser UI.
6. **Édition BBCode** : raw texte + preview (simple) vs WYSIWYG partiel (complexe). M3 n'a pas de rich text natif.
7. **Material Expressive (2025)** : adopter formes morphing et motion expressives en Phase 4 ou attendre stabilisation ?
8. **Emojis HFR custom (`:jap:`, `:bounce:`)** : pixel art rendu dans `AnnotatedString` via `InlineTextContent`. Taille fixe 24dp.
9. **Rich tooltips** : activer sur long-press pour info sur badges, icônes ? Coût UX faible, intérêt modéré.
10. **Predictive back custom** : utiliser la transition par défaut (suffisant) ou custom avec `PredictiveBackHandler` ?

---

## 17. Annexes

### 17.1 Dépendances Gradle cibles

```kotlin
// libs.versions.toml
[versions]
compose-bom = "2024.12.01"  # ou + récent
material3 = "1.3.1"
material3-adaptive = "1.0.0"
material3-adaptive-navigation-suite = "1.3.1"
accompanist = "DELETED"  # ne plus utiliser
roborazzi = "1.30.0"
konsist = "0.17.3"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material3-adaptive = { module = "androidx.compose.material3.adaptive:adaptive" }
compose-material3-adaptive-layout = { module = "androidx.compose.material3.adaptive:adaptive-layout" }
compose-material3-adaptive-navigation = { module = "androidx.compose.material3.adaptive:adaptive-navigation" }
compose-material3-adaptive-nav-suite = { module = "androidx.compose.material3:material3-adaptive-navigation-suite" }
compose-material3-window-size = { module = "androidx.compose.material3:material3-window-size-class" }
compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
roborazzi = { module = "io.github.takahirom.roborazzi:roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
konsist = { module = "com.lemonappdev:konsist", version.ref = "konsist" }
```

### 17.2 Ressources officielles

- Material 3 Design : https://m3.material.io/
- Material Theme Builder : https://m3.material.io/theme-builder
- Compose Material 3 API : https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
- Compose Material 3 Adaptive : https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Now in Android (sample) : https://github.com/android/nowinandroid
- Reply M3 sample : https://github.com/android/compose-samples/tree/main/Reply
- Roborazzi : https://github.com/takahirom/roborazzi
- Konsist : https://docs.konsist.lemonappdev.com/

### 17.3 Outils de validation

- **Material Theme Builder web** : vérifier les contrastes avant export
- **Accessibility Scanner** (Android app) : scanner un écran déployé
- **TalkBack** : test manuel obligatoire par écran
- **Compose Preview (AS)** : light / dark / font 200% / WindowSize
- **Layout Inspector** : vérifier hiérarchie de composables
- **Lint Compose rules** : enabled via plugin Gradle

### 17.4 Glossaire

| Terme | Définition |
|---|---|
| Tonal elevation | Technique M3 : l'élévation se traduit par une teinte de surface, pas une ombre |
| Surface container | Variant de `surface` avec teinte incrémentale |
| Dynamic color | Palette extraite du wallpaper système (Android 12+) |
| Window Size Class | Catégorisation en Compact / Medium / Expanded selon la largeur |
| Canonical layout | Patron de layout officiel M3 (list-detail, feed, supporting pane) |
| Shared element | Transition où un élément persiste visuellement entre deux écrans |
| Predictive back | Geste système Android 14+ avec aperçu en cours de swipe |
| Edge-to-edge | UI qui s'étend sous les system bars (obligatoire Android 15+) |
| Reduce motion | Pref utilisateur qui désactive les grosses animations |

### 17.5 Checklist maître par écran (à copier dans chaque PR)

```markdown
## Material 3 checklist — <nom de l'écran>

### Theming
- [ ] Aucune couleur hardcodée (`MaterialTheme.colorScheme.*`)
- [ ] Aucun TextStyle ad-hoc (`MaterialTheme.typography.*`)
- [ ] Aucune shape hardcodée (`MaterialTheme.shapes.*`)

### Composants
- [ ] `androidx.compose.material3.*` uniquement (sauf icons)
- [ ] TopAppBar variant explicite
- [ ] FAB positionné correctement
- [ ] Chips avec variant explicite
- [ ] PullToRefreshBox (pas SwipeRefresh)

### Layout
- [ ] Scaffold approprié
- [ ] Preview Compact
- [ ] Preview Medium (si feature principale)
- [ ] Support edge-to-edge (innerPadding appliqué)

### Accessibilité
- [ ] contentDescription sur IconButton / Icon cliquable
- [ ] contentDescription = null sur icônes décoratives
- [ ] Touch targets ≥ 48dp
- [ ] Contraste WCAG AA validé
- [ ] Font scaling 200% testé
- [ ] TalkBack testé
- [ ] Pas d'info color-only

### Tests
- [ ] Screenshot tests (compact light + dark)
- [ ] `/m3-check` passé (0 critique)
- [ ] Konsist pass
- [ ] Lint pass
```

### 17.6 Mapping composants M3 → écrans Redface 2

| Composant M3 | Utilisations dans Redface 2 |
|---|---|
| `Scaffold` | Tous les écrans |
| `NavigationSuiteScaffold` | Top-level (Forum, Drapeaux, MP) |
| `ListDetailPaneScaffold` | Forum liste↔topic, Drapeaux↔topic, MP liste↔conversation |
| `TopAppBar` small | Settings, secondaires |
| `CenterAlignedTopAppBar` | Forum (liste cat) |
| `MediumTopAppBar` | Topic detail, MP conversation |
| `LargeTopAppBar` | Profil, Éditeur |
| `NavigationBar` | via NavigationSuiteScaffold compact |
| `NavigationRail` | via NavigationSuiteScaffold medium |
| `PermanentNavigationDrawer` | via NavigationSuiteScaffold expanded |
| `BottomAppBar` | Toolbar BBCode dans éditeur |
| `Card` (filled) | Post cards |
| `ListItem` | Partout (topics, drapeaux, MP, profil, settings) |
| `AssistChip` | Actions sur post |
| `FilterChip` | Filtres drapeaux |
| `InputChip` | Destinataires MP |
| `SuggestionChip` | Autocomplétion @mention |
| `SegmentedButton` | Catégorie dans nouveau topic |
| `Button` (filled) | CTA "Publier", "Envoyer" |
| `FilledTonalButton` | Actions secondaires fortes |
| `OutlinedButton` | "Annuler", "Retour" |
| `TextButton` | "Brouillon", actions mineures |
| `IconButton` | AppBar, trailing ListItem |
| `FloatingActionButton` | "Répondre" dans topic |
| `ExtendedFloatingActionButton` | "Nouveau topic", "Nouveau MP" |
| `SearchBar` | Recherche full-screen (compact) |
| `DockedSearchBar` | Recherche docked (medium+) |
| `ModalBottomSheet` | Actions long-press sur post |
| `AlertDialog` | Confirmations (supprimer drapeau) |
| `Dialog` full-screen | Éditeur, nouveau topic |
| `SnackbarHost` | Feedback transient (post envoyé) |
| `BadgedBox` | Compteurs non lus |
| `LinearProgressIndicator` | Top de liste pendant refresh |
| `CircularProgressIndicator` | Button en cours d'envoi |
| `OutlinedTextField` | Éditeur, login, settings |
| `Switch` / `Checkbox` / `RadioButton` | Settings |
| `Slider` | Settings (taille de texte ?) |
| `PullToRefreshBox` | Toutes les listes refresh |
| `Tooltip` | Hover long sur badges |
| `HorizontalDivider` | Entre posts, entre sections settings |

---

## Fin du draft

Ce document reste à iterer. Priorités de validation :
1. Accord sur le plan d'enforcement (couches 3, 4, 5, 6 surtout)
2. Seed color validée
3. Décision dynamic color opt-in/out
4. Valider que les skills `/m3-check` et `/m3-screen` méritent d'être créés en Phase 0

À partir de là, le plan est applicable :
- `/m3-screen "écran X"` génère du Compose conforme
- `/m3-check <fichier>` vérifie la conformité
- Konsist empêche les dérives au build
- Roborazzi détecte les régressions visuelles
- Review humaine valide l'esprit (hiérarchie, émotion, cohérence forum)
