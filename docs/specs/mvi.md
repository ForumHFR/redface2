---
title: Pattern MVI
parent: Spécifications
nav_order: 6
permalink: /specs/mvi
mermaid: true
---

# Pattern MVI
{: .fs-8 }

Model-View-Intent : le pattern d'architecture UI de Redface 2.
{: .fs-5 .fw-300 }

---

## Principe

MVI impose un **flux de données unidirectionnel** (UDF — Unidirectional Data Flow). L'utilisateur émet des Intents, le ViewModel produit un nouveau State, Compose dessine le State.

```mermaid
graph LR
    A["UI (Compose)"] -->|"Intent"| B["ViewModel"]
    B -->|"State"| A
    B -->|"Effect"| C["Navigation / Toast / ..."]
```

Trois concepts :

- **State** : l'état complet de l'écran. Immutable. Un seul objet `data class`.
- **Intent** : une action de l'utilisateur. `sealed interface`. Pur, sans logique.
- **Effect** : un événement one-shot (navigation, snackbar, vibration). Ne fait pas partie du state car il ne doit pas être rejoué à la recomposition.

### Note terminologique

Ce que ce document appelle "MVI" est techniquement du **MVVM + UDF** — le pattern recommandé par Google pour Compose. La distinction est principalement terminologique :

- **MVVM classique** : ViewModel expose des `LiveData`/`StateFlow`, la View observe. Le flux peut être bidirectionnel.
- **MVI / MVVM+UDF** : le flux est strictement unidirectionnel. Les actions passent par des Intents (ou Events), le ViewModel produit un nouveau State immutable. C'est ce que fait ce projet.

Le code est le même. On utilise le terme "MVI" dans ce projet par convention, mais un développeur habitué au MVVM Android retrouvera ses repères.

### Méthodologie MVI hybride

 Conformément à la [méthodologie triple-hybride]({{ site.baseurl }}/specs/methodology) (SDD + Prototype + TDD) :

- **Spec les contrats** (types `State`, `Intent`, `Effect`) — c'est le contrat public du ViewModel, utile pour le Screen et les tests. Ces types sont documentés ci-dessous pour chaque écran.
- **TDD les helpers purs** (`matchesFilter`, `comparatorFor`, mappers, reducers déterministes). Red → Green → Refactor, testables isolément.
- **Prototype le Screen Compose**. L'UI émerge du code, pas de la spec — l'exemple complet ci-dessous montre les *patterns* (`send(intent)`, `ObserveAsEvents`, `PullToRefreshBox`) mais la mise en page réelle est itérée à partir de la Phase 1.

Les exemples ViewModel ci-dessous sont **des squelettes illustratifs** — certains détails (timer 5 s, rollback, mutex) sont documentés parce qu'ils encodent des patterns non-triviaux, pas parce qu'ils sont figés dans la pierre.

---

## Écran Drapeaux (accueil)

> **Statut Phase 1B.4 → 1B.5 livré** : le `FlagsViewModel` réel expose plusieurs `StateFlow` séparés (auth, MP, onglet courant, liste de drapeaux du tab) plutôt qu'un seul `FlagsState` agrégé, et un nombre limité d'actions (`selectTab`, `refresh`, `logout`). Pas de tri, pas de filtre, pas de `RemoveFlag`/`UndoRemoveFlag`, pas de pull-to-refresh : ces capacités sont **hors scope Phase 1B** et arriveront au plus tôt en Phase 1D / Phase 2 quand un cas d'usage le justifie. Un bouton « Actualiser » force néanmoins un fetch réseau explicite sur l'onglet courant, pour éviter que le cache mémoire de session ne bloque le dogfood. Le squelette illustratif Phase 1+ ci-dessous a été remplacé par la forme actuellement shippée.

### ViewModel — forme livrée

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FlagsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val flagRepository: FlagRepository,
) : ViewModel() {

    private var observedPseudo: String? = null

    private val _selectedTab = MutableStateFlow(FlagType.CYAN)
    val selectedTab: StateFlow<FlagType> = _selectedTab.asStateFlow()

    // #154 polish — CYAN-only client filter, default hides `hasUnread = false` rows.
    private val _showReadParticipatedTopics = MutableStateFlow(false)
    val showReadParticipatedTopics: StateFlow<Boolean> = _showReadParticipatedTopics.asStateFlow()

    val authState: StateFlow<AuthState?> =
        authRepository.observeAuthState()
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    val flagsState: StateFlow<FlagsResult?> = authState
        .onEach(::clearFlagsCacheIfSessionChanged)
        .flatMapLatest { state ->
            when (state) {
                null -> flowOf<FlagsResult?>(null)
                AuthState.Anonymous -> flowOf<FlagsResult?>(null)
                is AuthState.Authenticated -> selectedTab.flatMapLatest { type ->
                    combine(
                        flagRepository.observe(type),
                        _showReadParticipatedTopics,
                    ) { result, showRead -> filterReadParticipatedIfNeeded(result, type, showRead) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    fun selectTab(type: FlagType) { _selectedTab.value = type }
    fun setShowReadParticipatedTopics(value: Boolean) { _showReadParticipatedTopics.value = value }

    fun refresh() {
        viewModelScope.launch { flagRepository.refresh(_selectedTab.value) }
    }

    fun logout() {
        viewModelScope.launch {
            // Order matters: drop the private cache before flipping AuthState to
            // Anonymous so the Flags tab can't redraw the previous user's rows
            // for a frame after logout fires.
            flagRepository.clearSessionCache()
            authRepository.logout()
        }
    }

    private fun filterReadParticipatedIfNeeded(
        result: FlagsResult,
        type: FlagType,
        showReadParticipated: Boolean,
    ): FlagsResult {
        if (type != FlagType.CYAN || showReadParticipated) return result
        return when (result) {
            is FlagsResult.Success -> result.copy(flags = result.flags.filter { it.hasUnread })
            else -> result
        }
    }

    private fun clearFlagsCacheIfSessionChanged(state: AuthState?) {
        when (state) {
            null -> Unit
            AuthState.Anonymous -> {
                observedPseudo = null
                flagRepository.clearSessionCache()
            }
            is AuthState.Authenticated -> {
                if (observedPseudo != state.pseudo) {
                    flagRepository.clearSessionCache()
                }
                observedPseudo = state.pseudo
            }
        }
    }
}
```

> **Polish #154** — `MessagesRepository` n'est plus injectée dans `FlagsViewModel` : l'ancien `unreadMpCount` était surfacé dans le footer Drapeaux mais ce footer (pseudo, logout, version, signalement, Diagnostics) a été déplacé sur `MessagesScreen` en attendant Phase 3. Le compteur MP reviendra à côté de la liste réelle des MPs, pas en tant qu'overlay temporaire sur Drapeaux.

`FlagRepository` est livrée comme un contrat à deux verbes (cf. `core/domain/.../FlagRepository.kt`) :

```kotlin
interface FlagRepository {
    fun observe(type: FlagType): Flow<FlagsResult>
    suspend fun refresh(type: FlagType)
    fun clearSessionCache()
}

sealed class FlagsResult {
    data object Loading : FlagsResult()
    data class Success(val flags: List<Flag>) : FlagsResult()
    data class Failure(val cause: Throwable) : FlagsResult()
}
```

Les noms de champs (`Flag.cat`, `Flag.topicId`, `Flag.type`, `Flag.replyCount`, `Flag.totalPages`, `Flag.lastReadPage`, …) suivent strictement [`models.md`]({{ site.baseurl }}/specs/models#drapeaux). Pas de `topic.postId` ni `topic.flagType` ni `topic.lastDate` — ces noms n'existent pas dans le modèle.

### Cible future (Phase 1D / Phase 2)

Quand le besoin arrive, on pourra élargir le contrat :

- ajout d'intents `RemoveFlag` / `UndoRemoveFlag` (avec timer `delay(5_000)` + rollback réseau, pattern documenté dans `:feature:topic`),
- pré-calcul UI `filteredFlags` derived avec un `SortMode` / `FlagFilter`,
- `PullToRefreshBox` Material 3 sur le `LazyColumn` (l'API Phase 1B se contente d'un bouton « Réessayer » sur état d'erreur).

Quand cette extension arrive, `FlagsState` agrégé peut redevenir préférable au triplet de `StateFlow` actuel ; ce sera un changement scope au moment du chantier, documenté ici à ce moment-là — pas avant.

### Screen (Compose) — forme livrée

`feature/flags/src/main/kotlin/.../FlagsRoute.kt` est l'entrée stateful (récupère `FlagsViewModel` via `hiltViewModel()`, collecte ses `StateFlow` via `collectAsStateWithLifecycle()`). Depuis le polish #154, `FlagsRoute` se concentre sur la liste : 3 onglets, toggle « afficher les sujets participés déjà lus » (CYAN uniquement), bouton Actualiser, branche login si anonyme, branche reconnect si `SessionExpiredException`. Pas de footer alpha — les actions compte (pseudo / logout) et outils (Diagnostics, signalement, version) vivent sur `MessagesScreen` jusqu'à Phase 3. Le découpage `<Name>Screen` / `<Name>Content` reste l'objectif quand la complexité justifie le coût (filtre, tri, undo) — cf. cible Phase 2.

---

## Écran Topic (lecture)

> **Statut Phase 1A** : le `TopicUiState` réellement exposé par `feature/topic/.../TopicUiState.kt` est aujourd'hui `(request: TopicRequest, mode: Mode, availablePages: List<Int>)` avec `Mode = Loading | Loaded(topic) | Error(message)`, et l'unique intent est `Retry`. Le ViewModel collecte `TopicRepository.observeTopicPage(...)` (cache-aside : émet le cache puis le fresh) et calcule `availablePages = (1..topic.totalPages).toList()` à chaque émission. Le contrat ci-dessous est la **cible Phase 1 fin / Phase 2** quand pull-to-refresh, edit FP, flag et image viewer arriveront. La forme actuelle reflète qu'il n'y a pas encore d'actions sur posts ni de pagination explicite — cohérent avec la méthodologie hybride (squelette illustratif, pas figé).
>
> **Statut Phase 1D-2 (#107)** : seul `TopicEffect.ScrollToPost` est livré ; il pilote le scroll one-shot vers un `numreponse` quand un deep link arrive avec `?scrollTo=N`. Les autres effets listés ci-dessous (`NavigateToReply`, `NavigateToEdit`, `NavigateToEditFirstPost`, `NavigateToImage`, `Error`) restent du **contrat cible Phase 2** — ils ne sont ni émis ni câblés tant que les actions correspondantes (réponse, édition, viewer image, surface d'erreur) n'arrivent pas.

```kotlin
data class TopicUiState(
    val title: String = "",
    val posts: List<Post> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoading: Boolean = false,
    val isFirstPostOwner: Boolean = false,
    val poll: Poll? = null,
    val error: String? = null,
)

sealed interface TopicIntent {
    data class LoadPage(val page: Int) : TopicIntent
    data object NextPage : TopicIntent
    data object PrevPage : TopicIntent
    data object Refresh : TopicIntent
    data class QuotePost(val numreponse: Int) : TopicIntent
    data class EditPost(val numreponse: Int) : TopicIntent
    data object EditFirstPost : TopicIntent
    data class FlagTopic(val type: FlagType) : TopicIntent
    data class OpenImage(val url: String) : TopicIntent
}

sealed interface TopicEffect {
    /** Phase 1D-2 (#107) — one-shot scroll demand consumed by `LaunchedEffect(Unit)`. */
    data class ScrollToPost(val numreponse: Int) : TopicEffect
    data class NavigateToReply(val cat: Int, val post: Int, val quote: String?) : TopicEffect
    data class NavigateToEdit(val cat: Int, val post: Int, val numreponse: Int) : TopicEffect
    data class NavigateToEditFirstPost(val cat: Int, val post: Int) : TopicEffect
    data class NavigateToImage(val url: String) : TopicEffect
    data class Error(val message: String) : TopicEffect
}
```

---

## Écran Editor (post-level reply / edit) + formulaire de topic

Phase 2B-A (#86 + #144) a séparé l'éditeur en **deux familles** plutôt qu'en un mode unique. Phase 2C ajoute les deux mutations HFR principales : le submit reply (#145, via `bddpost.php`) et la quote MVP (#146, même endpoint POST mais GET form via `numrep={cited}&ref={N}`). Phase 2D ajoute deux écrans complémentaires : `PostEditorMode.Edit` (#147) édite un post arbitraire via `message.php?…&numreponse={N}` + POST `bdd.php` ; `TopicFormMode.EditFirstPost` (#148) édite le premier post d'un topic, exposant en plus `sujet` et `subcat` (le formulaire topic-level vit dans `TopicFormScreen`). Création de topic (#149) reste en attente.

```kotlin
// Post-level editor — édition de niveau post (contenu BBCode seulement)
enum class PostEditorMode { Reply, Edit }

// Topic-level form — sujet + cat/subcat + contenu + sondage
enum class TopicFormMode { New, EditFirstPost }

data class PostEditorState(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int?,
    val numreponse: Int?,
    val page: Int?,                       // (Phase 2C) page topic en cours
    val subcat: Int?,                     // (Phase 2C) sous-cat HFR, requis pour reply
    val quotedNumreponse: Int? = null,    // (Phase 2C #146) numreponse cité ; null = reply, non-null = quote
    val quoteRef: Int? = null,            // (Phase 2C #146) ref opaque parsé depuis le href quote
    val draft: TextFieldValue = TextFieldValue(),
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
    val isLoadingForm: Boolean = false,   // GET message.php avant submit
    val isSubmitting: Boolean = false,    // POST bddpost.php (reply/quote) ou bdd.php (edit) en cours
    val submitError: SubmitError? = null,
    val draftHydratedFromForm: Boolean = false, // (Phase 2C #146) draft initialisé une fois depuis ReplyForm.initialContent
)

sealed interface PostEditorIntent {
    data class ContentChanged(val value: TextFieldValue) : PostEditorIntent
    data class ToolbarActionClicked(val action: BbcodeAction) : PostEditorIntent
    data object TogglePreview : PostEditorIntent
    data object SubmitClicked : PostEditorIntent       // Phase 2C (Reply / Quote) + Phase 2D (Edit)
    data object ErrorDismissed : PostEditorIntent      // (Phase 2C)
}

// Effets one-shot bypassant le state (jamais rejoués sur recomposition)
sealed interface PostEditorEffect {
    data class SubmitSucceeded(val targetPage: Int?) : PostEditorEffect
}
```

> **Statut Phase 2C+2D — Reply + Quote + Edit post + Edit FP MVP livrés** : deux ViewModels coexistent — `PostEditorViewModel` (post-level : Reply via `ReplyRepository`, Edit post via `EditPostRepository`) et `TopicFormViewModel` (topic-level : Edit FP via `TopicFormRepository`). Tous trois repositories partagent `HfrClient`, le parser de réponse (`ReplySubmitResponseParser`), et la sémantique d'erreurs (`ReplyFailureReason`). Côté contrat : reply/quote POSTent `bddpost.php`, edit post et edit FP POSTent `bdd.php` ; `TopicFormScreen` ajoute en plus un champ `sujet` modifiable et exposera la sous-catégorie (re-catégorisation autorisée). Anti-clobber par champ : côté post-level `draftHydratedFromForm` / `optionsHydratedFromForm` ; côté topic-level `subjectHydratedFromServer` / `draftHydratedFromServer` / `optionsHydratedFromForm` (hydratation indépendante par champ pour qu'un fetch lent puisse compléter ce que l'utilisateur n'a pas touché). Ces flags empêchent aussi un refetch silencieux sur `InvalidHashCheck` d'écraser le travail utilisateur. Sur succès, `SubmitSucceeded(targetPage, scrollTo?)` — la navigation pop l'éditeur et recharge la page topic ; pour edit post et edit FP, `scrollTo = numreponse` cible le post édité. Sondage : Phase 2D #148 préserve les champs verbatim sans muter (édition active reportée à une future fixture). Création de topic (#149) reste en placeholder.
>
> Les deux écrans partagent leurs **capacités** via composables `:core:ui` (`BbcodeTextField`, `BbcodeToolbar`, `BbcodePreview`, et plus tard `PollEditor`, `CatSubcatPicker`). `BbcodePreview` reçoit un `PostContent` déjà parsé — il **ne parse pas lui-même**, ce qui garde `:core:ui` libre de toute logique métier. Le parsing BBCode reste une responsabilité `:core:parser` (`parsePostContentFromBbcode`) exposée aux features via une interface `BbcodePreviewParser` (`:core:domain`) injectée par Hilt, afin de préserver la frontière `:feature:*` → `:core:domain` + `:core:ui` (les ViewModels appellent le use case et passent le `PostContent` résultant à `BbcodePreview`). Pas de duplication, juste deux contrats de formulaire distincts. Rationale : l'endpoint HFR n'est pas une bonne frontière UI (`Reply` et `NewTopic` passent tous deux par `bddpost.php` mais leurs formulaires diffèrent ; `EditFirstPost` et `NewTopic` partagent presque toute la structure malgré des endpoints différents). La frontière utile est **post-level** vs **topic-level**.

> **Phase 2B-B (#144) — polish toolbar** : `BbcodeAction` est devenu un `sealed interface` (au lieu d'`enum class`) pour porter `Color(colorHex)`. La toolbar expose désormais une palette couleur Material 3 (chip + `DropdownMenu` 5 swatches). Contrat HFR : `[#RRGGBB]…[/#RRGGBB]`, la balise fermante reprend le hex — pas de `[/color]`. Preview locale toujours synchrone et non bloquante ; preview serveur `apercu.php` reportée tant qu'aucune divergence HFR n'apparaît. Listes BBCode (`[list]/[*]`) restent hors AST mais survivent en texte brut (`BbcodeContentParserTest::full list block survives`).

---

## Écran Messages

```kotlin
data class MessagesState(
    val activeTab: MessageTab = MessageTab.CLASSIC,
    val classicMPs: List<PrivateMessage> = emptyList(),
    val multiMPs: List<PrivateMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class MessageTab { CLASSIC, MULTI }

sealed interface MessagesIntent {
    data class SwitchTab(val tab: MessageTab) : MessagesIntent
    data object Refresh : MessagesIntent
    data class OpenMP(val mp: PrivateMessage) : MessagesIntent
    data object NewMP : MessagesIntent
    data object NewMultiMP : MessagesIntent
}
```

---

## Convention

Chaque feature suit la même structure de fichiers (source set : `src/main/kotlin/`, cf. [`contributing.md`]({{ site.baseurl }}/guides/contributing#convention-par-feature) pour les règles de nommage détaillées) :

```
feature/topic/src/main/kotlin/fr/forumhfr/redface2/feature/topic/
  ├── TopicScreen.kt        // @Composable, collecte state + effects
  ├── TopicContent.kt       // @Composable stateless, previewable (si extrait)
  ├── TopicViewModel.kt     // MVI ViewModel (Hilt-injected via @HiltViewModel)
  ├── TopicUiState.kt       // État UI + Intents (consolidés tant que court)
  └── TopicRequest.kt       // Paramètre d'entrée du screen (DTO dérivé de TopicRoute)

feature/topic/src/test/kotlin/fr/forumhfr/redface2/feature/topic/
  └── TopicViewModelTest.kt // JUnit 4 + Turbine, fixture-driven
```

La `NavKey` (`TopicRoute`) ne vit **pas** dans le module feature : elle est déclarée côté `:app` dans `app/src/main/kotlin/.../navigation/RedfaceNavigation.kt` sous le sealed interface `RedfaceNavKey`. C'est la convention canonique pour Redface 2 — les routes `@Serializable` sont centralisées dans `:app` pour éviter les dépendances circulaires entre features. Détails dans [`contributing.md`]({{ site.baseurl }}/guides/contributing#convention-par-feature).

Cette convention garantit la cohérence et facilite l'onboarding des contributeurs.

---

## Utilitaire : ObserveAsEvents

Helper lifecycle-aware pour collecter les effects sans les traiter en arrière-plan. Vit dans `:core:ui` et est utilisé par tous les screens.

```kotlin
@Composable
fun <T> ObserveAsEvents(
    flow: Flow<T>,
    onEvent: (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect(onEvent)
        }
    }
}
```

Sans ce helper, les effects émis pendant que l'app est en arrière-plan seraient traités immédiatement (navigation fantôme, snackbars invisibles). `repeatOnLifecycle(STARTED)` garantit que les effects ne sont consommés que quand l'écran est au premier plan.
