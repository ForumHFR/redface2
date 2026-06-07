# Prompt d'implémentation — #282 swipe gauche/droite pour changer de page de topic (Option A)

> Non normatif (draft). Issu de l'analyse #282 + avis Codex gpt-5.5 xhigh (2026-06-06).

## Objectif

Permettre de changer de page d'un topic par **geste horizontal** (gauche = page suivante, droite = page précédente), **sans remonter en haut pour atteindre les boutons**. MVP **Option A** : le geste appelle le chemin de navigation EXISTANT `onOpenPage(page±1)` — aucun nouveau modèle de page, aucune modif repo/VM/nav.

## Contraintes (à NE PAS violer)

- **Pas de fetch authentifié de page voisine** (règle prefetch non-auth) → donc PAS de `HorizontalPager` avec VM voisin. Le geste ne fait que déclencher `onOpenPage`.
- **Ne pas voler le scroll vertical** du `LazyColumn` ni le `horizontalScroll` de la grille de pages (`TopicPageNavigation`, item header du LazyColumn).
- detekt : `LongParameterList` ≤ 5 params ; `ForbiddenImport` interdit `androidx.compose.material.*` et `androidx.lifecycle.LiveData`/`GlobalScope` (utiliser `androidx.compose.foundation.*` / `material3`).
- Pas de claim « testé » sans exécution réelle.

## Implémentation

### 1. Helper pur et testable — `feature/topic/.../TopicSwipe.kt` (nouveau)

```kotlin
internal enum class HorizontalSwipe { LEFT, RIGHT }

/**
 * Page cible d'un swipe horizontal en lecture topic, ou null si le geste est bloqué (bord).
 * LEFT = page suivante, RIGHT = page précédente. Pure → testée unitairement.
 */
internal fun swipeTargetPage(
    currentPage: Int,
    totalPages: Int,
    swipe: HorizontalSwipe,
): Int? = when (swipe) {
    HorizontalSwipe.LEFT -> (currentPage + 1).takeIf { it <= totalPages }
    HorizontalSwipe.RIGHT -> (currentPage - 1).takeIf { it >= 1 }
}
```

### 2. Détecteur de geste — `feature/topic/.../TopicScreen.kt`

> ⚠️ La grille de pages (`TopicPageNavigation`, avec `Modifier.horizontalScroll`) est l'**item header DANS** le `LazyColumn`. On ne peut donc pas « ne pas couvrir » la grille en posant le détecteur sur un conteneur dédié. Le détecteur est posé sur le `Box`/`LazyColumn` de `TopicLoadedContent` MAIS, étant **bas niveau et respectueux de la consommation**, il laisse la grille (enfant) consommer ses gestes horizontaux en premier (passe Main enfant→parent) et le `LazyColumn` gérer le scroll vertical.

- API (validée Codex, **à confirmer Context7 « stable release » au moment de coder**) : `Modifier.pointerInput(currentPage, totalPages) { awaitEachGesture { ... } }` avec
  `awaitFirstDown(requireUnconsumed = false)` → `awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> /* ne consume QUE si on prend le geste */ }` → boucle `awaitHorizontalDragOrCancellation(pointerId)` en accumulant `totalDx` et en alimentant un `VelocityTracker` (`androidx.compose.ui.input.pointer.util.VelocityTracker`).
  - `awaitHorizontalTouchSlopOrCancellation` ne se déclenche que sur **slop HORIZONTAL** → un drag vertical-dominant (scroll) ne l'active jamais (désambiguïsation d'axe gratuite). S'il renvoie null (geste annulé / consommé par un enfant comme la grille) → on abandonne sans naviguer.
- **Décision en fin de geste** : commit si `abs(totalDx) >= max(72.dp.toPx(), size.width * 0.20f)` **ou** `abs(velocity.x) >= 900.dp.toPx()` (px/s ; `size` et `Density` dispo dans `PointerInputScope`). Sinon no-op.
  - `totalDx < 0` (drag vers la gauche) → `HorizontalSwipe.LEFT` → `swipeTargetPage(...)` → si non-null `onOpenPage(target)`.
  - `totalDx > 0` → `HorizontalSwipe.RIGHT` → idem.
- **Un seul** `onOpenPage` par geste. Aux bords (`swipeTargetPage == null`) : pas de navigation, pas de flash.

### 3. Câblage

- `onOpenPage: (Int) -> Unit` est déjà disponible dans `TopicScreen`/`TopicLoadedContent` (remonte à `RedfaceNavigation.kt:758`, pop+push `TopicRoute(scrollTo=null)`). Réutiliser tel quel.
- **Source unique** : `currentPage = mode.topic.page` (ou `state.request.page`) et `totalPages = mode.topic.totalPages` — la MÊME source que `canGoPrevious`/`canGoNext` (`TopicUiState`), pour zéro divergence. `swipeTargetPage` borne donc exactement comme les boutons.
- Le geste n'est actif **qu'en mode `Content`** (sinon rien à paginer) → le `Modifier` n'est appliqué que dans `TopicLoadedContent`.

### 4. Hors scope (à NE PAS faire)

- Pas de drag continu / peek / `HorizontalPager`.
- Pas d'animation de transition (slide) : elle nécessiterait une transition au niveau `NavDisplay` (`:app`) car le changement de page recrée l'écran ; follow-up séparé si désiré.
- Pas de prefetch du sens précédent ni de préservation d'offset vertical entre pages (reset en haut = comportement attendu).

## Tests

- `feature/topic/src/test/.../TopicSwipeTest.kt` : `swipeTargetPage` — LEFT depuis page<totalPages → +1 ; LEFT depuis dernière page → null ; RIGHT depuis page>1 → -1 ; RIGHT depuis page 1 → null ; cas totalPages=1 → null des deux côtés.
- Vérif émulateur : swipe horizontal change de page ; scroll vertical intact ; la grille de pages garde son scroll horizontal ; pas de double-navigation ; bords no-op.

## Validation locale (obligatoire avant push)

```
cd /work/xaat/redface2 && ./scripts/docker-dev.sh ./gradlew \
  detektAll lintDebug test testDebugUnitTest :app:lintProdDebug :app:assembleProdDebug
```

## Livrable

Branche `feat/282-swipe-page`, commit(s) Conventional + `Co-Authored-By: Claude Opus 4.8`. PR `Closes #282`.
