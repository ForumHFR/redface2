# #603 — Rapport des 4 spikes bloquants (PR0)

> Levés avant implémentation, 2026-06-24. Décisions actées dans
> [ADR-017](../docs/adr/017-refonte-vue-drapeaux.md). Document non normatif (le réel a été vérifié
> contre le code de prod et les sources REST HFR).

## Spike 1 — `quotedMe` (« on a été cité ») dispo côté serveur ? → **No-Go → DIFFÉRÉ**

**Question** : peut-on alimenter un indicateur « cité » depuis une source réelle (API/REST) ?

**Vérifications** :
- `core/model/.../Flag.kt` : aucun champ quote/cité/mention (15 champs, tous métier topic).
- DTO REST `RestTopic` (`core/network` / `RestForumDtos`) : aucun champ quote ; les seuls champs
  auth-only sont `isRead`, `flagOwntopic`, `lastPosition`, `lastPostReadId`.
- API REST HFR (référence vérifiée) : pas d'endpoint « notifications / mentions / cité » exposé
  (`notifications/` → 404). Les seules occurrences de « quote » dans le repo concernent la **citation
  pour poster** (`quotedNumreponse`, `Post.quotedAuthors`), pas un drapeau « je suis cité ».

**Verdict** : **non exposé par les modèles/API actuels** (= No-Go pour l'état présent). Aucune source
réelle connue. Conformément au défaut acté et à la charte « pas de simulation de données absentes »,
l'indicateur est **retiré du MVP**. Les composants peuvent porter le paramètre `quotedMe`, mais il
vaut **toujours `false`** en prod. Réintroduction si une source serveur réelle apparaît.

## Spike 2 — Signal fiable du refresh **automatique** pour la barre de progression ? → **GO**

**Question** : existe-t-il un signal distinct/fiable du refresh auto (sinon booléen fragile) ?

**Vérifications** (`feature/flags/.../FlagsViewModel.kt`) :
- `isRefreshing: StateFlow<Boolean>` est levé au début de **tout** refresh : `refresh()` (manuel) et
  `maybeAutoRefresh()` (auto landing/tab/resume, throttle 15 s interne).
- Le chargement initial est `FlagsListUiState.Loading`.
- Distinction interne auto vs manuel existante via `recallListToTop` (one-shot, auto-only) — non
  nécessaire ici.

**Verdict** : **GO.** La barre se pilote par `state is Loading || isRefreshing` : couvre chargement
initial + refresh manuel + refresh auto, sans nouveau booléen. C'est exactement le « visuel au refresh
auto qui n'en avait pas » demandé. → PR4 débloqué (livré en queue de run).

## Spike 3 — App bar translucide au scroll + contenu dessous (Nav3 + M3) sans casser le swipe d'onglets ? → **GO** (hide-on-scroll bottom bar différé)

**Question** : peut-on faire l'app bar translucide « contenu glissant dessous » sans casser le swipe
inter-onglets déjà acté ?

**Vérifications** :
- L'idiome existe déjà en prod : `RedfaceSearchAppBar` (shell Réglages, #494) avec param `elevated`
  → `surface` au repos, `surfaceContainer` translucide (`alpha 0.94`) + ombre au scroll. Le shell
  superpose la barre à la liste (Box + `contentPadding` haut), `elevated = listState.canScrollBackward`.
- Le swipe d'onglets (`FlagsTabSwipe`) est **horizontal** ; le scroll de liste et le pull-to-refresh
  sont **verticaux** : axes orthogonaux, pas de conflit (déjà le cas en prod aujourd'hui).
- `FlagsRoute` héberge déjà sa propre colonne sous `statusBarsPadding()` ; le `Scaffold` externe
  n'ancre que le `SnackbarHost`. La bottom bar est au niveau app (`navigation/RedfaceNavigation.kt`).

**Verdict** : **GO** pour l'app bar translucide (pattern éprouvé, réutilisable). Le **hide-on-scroll
de la bottom bar** reste **différé** (sensible insets/scroll, et la bottom bar est app-level) — exclu
du run, confirmé par la feuille de route (PR6 sans hide-on-scroll).

## Spike 4 — Mapping catégorie → drawable Material Symbols (sans import Material Icons) ? → **GO**

**Question** : peut-on mapper ~20 catégories HFR vers des icônes sans violer l'interdiction detekt
`ForbiddenImport` (`androidx.compose.material.*`) ?

**Vérifications** :
- Convention établie ([ADR-015]) : icônes = **vector drawables locaux** `core/ui/res/drawable/ic_ms_*`
  rendus via `RedfaceVectorIcon(@DrawableRes …)`. Aucun `Icons.*` Material. Detekt `ForbiddenImport`
  actif (`config/detekt/detekt.yml`).
- Déjà présents et utilisables comme icônes de catégorie : `ic_ms_forum`, `ic_ms_memory`,
  `ic_ms_sports_esports` (les 2 derniers ajoutés en exploration).
- Catégories cibles (19 publiques + Blabla) connues via l'API REST : Hardware, Hardware Périphériques,
  Ordinateurs Portables, Overclocking/Cooling/Modding, Électronique/Domotique/DIY, GSM/GPS/PDA, Apple,
  Vidéo/Son, Photo numérique, Jeux Vidéo, Windows & Software, Réseaux perso/SOHO, Systèmes & Réseaux
  Pro, OS Alternatifs, Programmation, Graphisme, Achats/Ventes, Emploi & Études, Discussions (+ Blabla).

**Verdict** : **GO.** L'idiome (vector drawables locaux + `RedfaceVectorIcon`) est éprouvé et
`ic_ms_forum` existe déjà (fallback légitime). **Cible décidée pour PR3** (non encore réalisée) :
compléter le set `ic_ms_*` (estimé ~17 drawables à créer pour couvrir les 19 cats + Blabla) + une
**fonction pure de mapping** `categoryIcon(catId): Int` avec fallback `ic_ms_forum`. PR1 peut poser la
fonction de mapping (testable) avec les drawables disponibles + fallback ; le set complet est livré en
PR3 (là où l'en-tête de catégorie les consomme). Le nombre exact de drawables reste à confirmer au
moment du mapping réel.

---

## Synthèse

| Spike | Verdict | Impact run |
|---|---|---|
| 1. `quotedMe` | No-Go serveur | Différé, jamais simulé (défaut acté respecté) |
| 2. Signal refresh auto | GO | `Loading \|\| isRefreshing` → PR4 débloqué |
| 3. App bar translucide / swipe | GO (hide-on-scroll différé) | PR2/PR3 réutilisent l'idiome `elevated` |
| 4. Icônes catégories | GO | Set + mapping en PR3, fonction posable en PR1 |

**Aucun No-Go bloquant** : le seul No-Go (`quotedMe`) correspond au comportement pré-acté (différer,
ne pas simuler). Le run d'implémentation se poursuit intégralement.
