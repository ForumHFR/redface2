# Changelog — application

Suivi des AAB générés (`./gradlew :app:bundleRelease`) avec le format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

À ne pas confondre avec [`CHANGELOG.md`](../CHANGELOG.md) à la racine, qui suit les versions des **specs**. Ici on suit les versions binaires (`versionCode` / `versionName`) et leur statut de distribution.

Statuts possibles d'une release :

- `local` — l'AAB existe sur une machine de dev, pas distribué
- `internal` — uploadé sur le canal Play Console *internal testing*
- `closed` — uploadé sur le canal Play Console *closed testing* (alpha / beta)
- `production` — disponible publiquement sur Play Store

Workflow : bumper `versionCode` + `versionName` dans `app/build.gradle.kts`, ajouter une entrée ici, builder. Quand l'AAB part vers un canal Play Console, mettre à jour le statut de la version concernée.

---

## v20 — `0.1.0-phase1b.6` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v20-<date>-<sha>.aab`

Phase 1B.2-1B.5 livrée d'un trait : liste réelle des drapeaux HFR (parser + repository + UI + module feature).

### Added
- **`FlagsListParser`** dans `:core:parser` — parse `forum1f.php?owntopic={1,2,3}` (mes sujets cyan / lus uniquement rouges / favoris). Détection unread canonique sur `td.sujetCase1` (`closedb*` vs `closed`), classification via icône `td.sujetCase5` (`flag1` → cyan, `flag0` → rouge, `favoris` → favori) avec fallback sur le `defaultType` du listing. 6 tests, 3 fixtures HTML capturées sur HFR réel avec données sensibles nettoyées.
- **`Flag` data class** dans `:core:model` (remplace l'ancien placeholder `FlaggedTopic`) + `FlagType { CYAN, RED, FAVORITE }`. Champs `totalPages` (`td.sujetCase4`), `replyCount` (`td.sujetCase7`), `views` (`td.sujetCase8`) — colonnes alignées sur les headers HFR « Dern. page » / « Rép. » / « Lues ».
- **`FlagRepository`** dans `:core:domain` (`observe(type)` / `refresh(type)`) + `DefaultFlagRepository` dans `:core:data` (network-only, broadcast refresh via `MutableSharedFlow` par `FlagType`). 4 tests Robolectric+MockK.
- `DefaultFlagRepository` garde un cache mémoire par onglet après le premier succès : changer d'onglet puis revenir sur Favoris ne refetch pas implicitement et ne fait pas bouger l'état lu/non-lu sans action utilisateur.
- **`HfrClient.getFlagsPage(owntopic: Int)`** sur `@AuthenticatedClient` (les drapeaux sont une vue par utilisateur, owntopic ∈ 1..3 enforced par `require`).
- **`FlagItem` composable** dans `:core:ui` — pastille couleur (cyan/rouge/jaune, dimmed à 35% si lu), titre semi-bold si unread, footer `metadata: String` reçu pré-formaté par le caller (i18n boundary clean : `:core:ui` n'a pas de `strings.xml`).
- **Module `:feature:flags`** avec `FlagsRoute` + `FlagsViewModel` :
  - `flatMapLatest(authState)` pour ne montrer la liste que quand authentifié, retour anti-flicker en attendant `authState ≠ null`.
  - 3 onglets `PrimaryTabRow` (« Mes sujets » = `owntopic=1` cyan / « Lus uniquement » = `owntopic=2` rouge / « Favoris » = `owntopic=3`), source flow change avec `selectTab`.
  - Footer auth (pseudo, MP unread, version, bouton CSAE, bouton logout).
  - Bouton « Réessayer » sur état d'erreur (pas de `PullToRefreshBox` en 1B — viendra en 1D quand un cas d'usage le justifie).
  - Footer `FlagItem` formaté côté `:feature:flags` via `stringResource` (`flags_item_metadata_with_author` / `_no_author`).
  - 5 tests Turbine couvrant `flatMapLatest` + `selectTab` + `refresh` + `logout`.
- Navigation `:app` migre `entry<FlagsListRoute>` du placeholder `FlagsScreen` (supprimé) vers `FlagsRoute`. Lambda `onOpenFlag` pousse la vraie `TopicRoute(flag.cat, flag.topicId, flag.lastReadPage, scrollTo = flag.firstUnreadPostId.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt())` au lieu du `DEMO_TOPIC` hardcodé.

### Changed
- `docs/specs/architecture.md` documente `:feature:flags` (mermaid + tableau des modules).
- `docs/specs/models.md` remplace le placeholder `FlaggedTopic` par le vrai `Flag` + drift `lastReplyAt: String` justifiée (HFR renvoie une chaîne FR pré-formatée).
- `docs/specs/navigation.md` réécrit le snippet `entry<FlagsListRoute>` autour de `FlagsRoute(...)`.
- `docs/specs/roadmap.md` coche l'item « Écran Drapeaux ».

### Removed
- `app/src/main/kotlin/.../FlagsScreen.kt` (placeholder Phase 0).
- `app/src/main/kotlin/.../FlagsHomeViewModel.kt` (placeholder ViewModel à 1 string).
- Strings du placeholder dans `app/src/main/res/values/strings.xml` (déplacées dans `:feature:flags`).

### Notes
- L'API HFR `forum1f.php?owntopic=N` est **par utilisateur** : chaque rafraîchissement marque les topics vus côté HFR. Pas d'`@AnonymousClient` ici, c'est intentionnel — le prefetch non-authentifié est réservé à la pagination des topics.
- `Flag.firstUnreadPostId` est un `Long` côté domain mais `TopicRoute.scrollTo` un `Int` (limite Compose Navigation 3) ; le narrowing `takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()` est sûr en pratique (HFR `numreponse` plafonne ~10M).
- Konsist : architecture rules toujours vertes après ajout de `:feature:flags`.

---

## v19 — `0.1.0-phase1b.5` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v19-<date>-<sha>.aab`

In-app reporting channel pour la conformité Google Play CSAE.

### Added
- **Bouton "Signaler un contenu"** sur `FlagsScreen` — `Intent.ACTION_SENDTO` avec `mailto:xat@azora.fr` + sujet pré-rempli `Redface 2 — Signalement`. Catch `ActivityNotFoundException` → Toast français quand aucun client mail n'est dispo.
- 3 strings : `report_content_cta`, `report_email_subject`, `report_no_email_client`.

### Notes
- La page CSAE (`docs/legal/csae/index.html`, déployée Phase 1B.1 sur GitHub Pages) **claim** explicitement ce mécanisme de signalement in-app — sans cette livraison, la déclaration au Play Console serait factuellement incohérente avec l'app.
- Reste sur `FlagsScreen` (point d'entrée toujours visible) en attendant un écran `:feature:settings` réel.

---

## v18 — `0.1.0-phase1b.4` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v18-<date>-<sha>.aab`

Polish post-review : on traite la liste des findings encore ouverts (non-bloquants flaggés par superpowers + Codex + nouveaux surgis avec le feature MP).

### Added
- 5 tests `:core:data.messages.DefaultMessagesRepositoryTest` (Anonymous→null, Authenticated→count, network error→null, logout→null, refetch on re-Authenticated)
- 1 test `:core:network.cookie.PersistentCookieJarTest` `saveFromResponse with expired non-empty cookie removes the entry` (defensive complément à la deletion-marker)

### Changed
- **`PersistentCookieJar.loadForRequest`** — guard JVM-safe : refuse de bloquer le Main thread si un Looper est dispo. La Mutex `storeMutex` sérialise les écritures `save()` et `clear()` côté DataStore pour éliminer la race logout-vs-save sur disque
- **`PersistentCookieJarTest.loadForRequest before first store emission blocks until cookies arrive`** — `Thread.sleep(100ms)` fragile remplacé par `CountDownLatch` (supplier started) + boucle de poll (isDone stays false). Déterministe même sur runner lent
- **`DefaultMessagesRepository`** — log `Log.w` sur échec de fetch au lieu de swallow silencieux. Une ligne "MPs non lus" manquante dans FlagsScreen est maintenant diagnostiquable
- **`DefaultAuthRepository.observeAuthState`** — `distinctUntilChanged()` final, plus d'émissions Anonymous→Anonymous redondantes
- **Konsist anti-leak `@AnonymousClient`** — scope étendu à `/auth/` + `/messages/` (toutes deux authenticated-by-construction). Hardened contre star-import et FQN annotation usage
- **`docs/specs/protocol-hfr.md:40`** — URL canonique "Liste des MPs" corrigée : `forum1.php?cat=prive&...` (pas `message.php` qui ouvre le composer). Note ajoutée
- **`docs/specs/architecture.md`** — `MessagesRepository` documentée dans le bloc des interfaces `:core:domain`, paragraphe sur le pipeline 1B.1
- **`docs/guides/contributing.md`** — section "Dogfood : installer en parallèle d'une release Play" décrit l'overlay `.gradle-user/dogfood.init.gradle` (gitignored)

### Notes
- Aucun changement de comportement utilisateur observable vs v17
- L'overlay dogfood reste gitignored — pas de scénario `:app:bundleRelease` qui expose `applicationIdSuffix=.dogfood` à Play Console

---

## v17 — `0.1.0-phase1b.3` — 2026-04-28

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v17-<date>-<sha>.aab`

Bonus Phase 1B.1 : compteur de MPs non lus sur l'écran d'accueil, comme preuve « réellement loggé HFR » au-delà de la simple présence du cookie `md_user`.

### Added
- **`MessagesRepository.observeUnreadMpCount(): Flow<Int?>`** dans `:core:domain` — `null` quand anonyme ou avant la première résolution, non-null Int sinon. Sur logout, retourne à `null` à l'émission `Anonymous` suivante.
- **`PrivateMessageListParser`** dans `:core:parser/messages/` — Jsoup parse `tr.sujet img[src]`, compte les filenames `closedbp` (icône HFR « MP non lu »). Convention extraite du legacy v1 `HTMLToPrivateMessageList.java:31-32`, prouvée en prod sur ~10 ans.
- **`HfrClient.getPrivateMessageListPage(page = 1)`** — fetch authentifié `forum1.php?config=hfr.inc&cat=prive&page=1&...` (URL canonique du legacy v1, pas `message.php` que la spec citait par erreur — `protocol-hfr.md:40` à corriger plus tard).
- **`DefaultMessagesRepository`** dans `:core:data/messages/` — combine `AuthState` avec le fetch via `transformLatest` : seul un état `Authenticated` déclenche un fetch ; un échec réseau emit `null` (pas d'affichage spéculatif).
- **`FlagsHomeViewModel.unreadMpCount: StateFlow<Int?>`** + ligne `MPs non lus : N` rendue dans `FlagsScreen` sous le pseudo connecté.

### Tests
- 4 tests `:core:parser.messages.PrivateMessageListParserTest`
  - fixture HFR réelle (50 MPs, tous lus → 0 non lus)
  - HTML synthétique mixed read/unread (validation positive)
  - inbox vide
  - rows non-`tr.sujet` ignorés (anti-faux-positif)
- Fixture `private_messages_list_all_read.html` (122 KB) reprise du legacy `ForumHFR/Redface` (origine HFR prod, 2015 — DOM identique aujourd'hui).

### Notes
- Pas de pagination des MPs : seule la page 1 est fetchée (50 MPs/page côté HFR ; les non-lus sont triés en tête, donc cette page suffit pour l'UX « est-ce qu'il y a du nouveau ? »). La pagination sera traitée si une vraie liste UI atterrit (Phase 1C ou plus tard).
- Pas de pull-to-refresh : la valeur est rafraîchie au prochain login / kill+relance d'app. Suffisant pour preuve d'auth ; un refresh manuel viendra avec l'écran Messages dédié.

---

## v16 — `0.1.0-phase1b.2` — 2026-04-28

**Statut** : `local`
**Commit** : `15c6c34`
**Fichier** : `redface2-v16-20260427-15c6c34.aab` *(le stamp date utilise l'UTC du runner Docker — la build a été lancée le 28 avril ~00:12 Paris, soit encore le 27 en UTC)*

Rebuild administratif de Phase 1B.1 — `versionCode` 15 brûlé côté Play Console, nouveau code `16` requis. Aucun changement code vs v15.

### Notes
- Voir entrées v15 et v14 ci-dessous pour le contenu Phase 1B.1.

---

## v15 — `0.1.0-phase1b.1` — 2026-04-27

**Statut** : `local`
**Commit** : à venir (rebuild Phase 1B.1)
**Fichier** : `redface2-v15-20260427-<sha>.aab`

Rebuild administratif de Phase 1B.1 — `versionCode` 14 déjà uploadé sur Play Console, nouveau code `15` requis pour pouvoir réuploader. Aucun changement fonctionnel vs v14 ; le seul écart code est un polish post-review superpowers.

### Changed
- `AuthRemoteDataSource.classify()` — `LoginError.Unknown` distingue maintenant `"expected md_user cookie not set"` (cookie absent) de `"md_user cookie value mismatched the submitted pseudo"` (cookie présent mais valeur ≠ pseudo soumis). Auparavant les deux cas retournaient le même message « not set » menteur. Diagnostic logs côté dev plus précis ; comportement utilisateur identique (bandeau `LoginError.Unknown` localisé).

### Notes
- Voir entrée v14 ci-dessous pour le contenu Phase 1B.1 complet (login HFR + cookies persistants + AuthState global + Konsist anti-leak).

---

## v14 — `0.1.0-phase1b.0` — 2026-04-27

**Statut** : `local`
**Commit** : à venir (PR feature/1b-1-auth)
**Fichier** : `redface2-v14-20260427-<sha>.aab`

Phase 1B.1 livrée : login HFR utilisable de bout en bout.

### Added
- **Login HFR fonctionnel** — `LoginScreen` (`:feature:auth`) appelle `AuthRepository.login()`, qui POSTe `login_validation.php?config=hfr.inc` via le `@AuthenticatedClient`. Le cookie `md_user` retourné est persisté par `PersistentCookieJar` ↔ `DataStoreCookieStore`, donc la session survit kill/restart de l'app.
- **`AuthState` global** — `FlagsScreen` affiche maintenant `Connecté en tant que <pseudo> · Se déconnecter` ou un CTA `Se connecter à HFR`, alimenté par `FlagsHomeViewModel.authState`.
- **Erreurs typées** — `LoginError.{InvalidCredentials, RateLimited, Network, Unknown}` mappées en bandeaux français localisés dans `LoginScreen`.
- **`:core:auth` non créé** — l'architecture spec place le backbone auth dans `:core:network` (login + cookies) et `:core:data` (repository impl). Le module `:feature:auth` (déjà bootstrap Phase 0) ajoute juste l'UI.
- **Sécurité au repos** — `android:allowBackup="false"` + `fullBackupContent="false"` dans `AndroidManifest.xml` pour exclure les cookies des backups Google Drive (cf. ADR-002 amendé).
- **Konsist @AnonymousClient** (Refs #42, auth-side seulement) — règle architecturale qui interdit aux fichiers sous `/auth/` d'importer le qualifier `@AnonymousClient`. Catch le mismatch silencieux (cookies pas envoyés → session vue comme déconnectée) au build. Le pendant prefetch-side (assertion que `HfrClient.prefetch*` utilise `@AnonymousClient`) reste à activer quand le code prefetch atterrira — `#42` doit donc rester ouvert.
- **DataStore Preferences 1.2.1** — ajouté au version catalog. Persiste les cookies non chiffrés (cf. ADR-002 amendé : password en plaintext POST → chiffrement local redondant face à un attaquant runtime).

### Changed
- **ADR-002 amendé** — alignement avec la décision originale issue [#24 thème 13](https://github.com/ForumHFR/redface2/issues/24#issuecomment-3526003625) : DataStore non chiffré + FBE plateforme, sans clé Keystore custom (la rédaction initiale avait dérivé en réintroduisant AES/GCM Keystore).
- `InMemoryCookieJar` supprimé (aucun consumer prod ni test). Si un futur test a besoin d'un CookieJar isolé en mémoire, il sera réintroduit sous `src/test/`.

### Tests
- 6 tests `:core:data.auth.DataStoreCookieStore` (Robolectric, persist + filter expired + payload corrompu fail-closed)
- 10 tests `:core:network.cookie.PersistentCookieJar` (cache snapshot + merge + deletion-marker + init cold-start)
- 6 tests `:core:network.auth.AuthRemoteDataSource` (MockWebServer, success + 4 erreurs typées + identity mismatch)
- 7 tests `:core:data.auth.DefaultAuthRepository` (MockK + vrai `PersistentCookieJar` + fake CookieStore)
- 3 tests `:core:data.auth.AuthChainIntegrationTest` (MockWebServer + chaîne auth complète)
- 11 tests `:feature:auth.LoginViewModel` (Idle → Submitting → Authenticated/Error, debounce, error mapping)
- 1 test Konsist nouveau (anti-leak `@AnonymousClient` sur les paquets `/auth/`)

### Notes
- Drapeaux réels (parseFlags + `:feature:flags`) attendus en 1B.2 + 1B.3
- Détection session expirée (Interceptor sur 302 → `/login.php`) reportée — Phase 1B.3 ou plus tard
- Pas de biométrie / pas de relogin transparent (Option A actée dans ADR-002)

---

## v13 — `0.1.0-phase1a.1` — 2026-04-27

**Statut** : `local`
**Commit** : `7e42d89` (avant merge PR [#89](https://github.com/ForumHFR/redface2/pull/89))
**Fichier** : `redface2-v13-20260427-7e42d89.aab`

Premier AAB qui affiche sa propre version dans l'UI.

### Added
- `BuildConfig.VERSION_NAME` / `VERSION_CODE` exposés à Kotlin via `buildFeatures.buildConfig = true` ([4d469b8](https://github.com/ForumHFR/redface2/commit/4d469b8))
- `FlagsScreen` rend un footer `Redface 2 — v0.1.0-phase1a.1 (build 13)` (style `labelSmall`, `onSurfaceVariant`) — temporaire jusqu'à ce que `:feature:settings` exporte un About screen

### Changed
- `versionCode` / `versionName` migrent de l'init-script de signing (gitignored) vers `app/build.gradle.kts` (tracké), pour qu'ils soient lisibles, diffables, et accessibles à `BuildConfig`

---

## v12 — `0.1.0-phase1a` — 2026-04-26

**Statut** : `local`
**Commit** : `e749dbf` (avant footer version)
**Fichier** : `redface2-v12-20260427-e749dbf.aab`

Premier AAB Phase 1A complète : la lecture topic passe par le vrai pipeline réseau au lieu d'une fixture embarquée.

### Added
- `:core:network` complet : `HfrClient.getTopicPage(cat, post, page, useAuth)`, `InMemoryCookieJar` host-keyed, qualifiers `@AuthenticatedClient` / `@AnonymousClient` / `@HfrBaseUrl` (PR [#88](https://github.com/ForumHFR/redface2/pull/88))
- `:core:database` v1 : `TopicEntity` (PK `cat,post,page`) + `PostEntity` (PK `cat,numreponse`) + `TopicDao` transactionnel + `PostContent` JSON converter via kotlinx.serialization (PR [#88](https://github.com/ForumHFR/redface2/pull/88))
- `:core:domain.TopicRepository` interface (`Flow<Topic>` cache-aside) + impl `:core:data.TopicRepositoryImpl` (PR [#88](https://github.com/ForumHFR/redface2/pull/88))
- `TopicScreen` lit le vrai topic HFR via `TopicRepository.observeTopicPage(...)` au lieu de `TopicFixtureRepository` (PR [#89](https://github.com/ForumHFR/redface2/pull/89))
- 3 tests d'intégration `:core:data` (`MockWebServer` + Robolectric, fixture `topic_page_single.html`)
- 10 tests `:core:ui` (`parseColor`, `buildInlineText`, `collectInlineMedia` — invariant `MediaCounter` + récursion sur les 6 containers)
- 5 tests `:feature:topic` (cache+fresh, fail-no-cache, fail-after-cache → cache préservé, retry)

### Changed
- `Mode.Placeholder` retiré de `TopicUiState` — toute paire `(cat, post)` est légitime
- `availablePages` dérivé de `topic.totalPages` à chaque émission
- UX cache-first : un échec réseau **après** un cache hit garde le contenu visible

### Removed
- `TopicFixtureRepository`, `FixedTopicFixtures`, `AssetTopicFixtureRepository`, `provideTopicFixtureRepository` (mort code après le rebind)
- 3 fixtures HTML embarquées dans `app/src/main/assets/topic_khakha/` (~415 KB)

### Notes
- Pull-to-refresh, Snackbar pour le silent fail réseau, snapshot test quote depth ≥ 3 sont déférés à Phase 1D Polish
- Konsist `@AnonymousClient` rule (issue [#42](https://github.com/ForumHFR/redface2/issues/42)) déférée à Phase 1B (besoin du vrai code prefetch à scanner)

---

## v11 — `0.1.0-conventions` — 2026-04-26

**Statut** : `local`
**Commit** : `2f86051` (avant les 1A backbone + bind)
**Fichier** : `redface2-v11-20260426-2f86051.aab`

Premier AAB signé reproduit-iblement via `--init-script` Gradle. Phase 0 finie + slice topic fixe.

### Added
- Slice topic fixe : `TopicScreen` rend une fixture HFR réelle (`topic_khakha_page_146.html`) via `:core:parser` → AST `PostContent` → `:core:ui` `PostRenderer` (PRs [#78](https://github.com/ForumHFR/redface2/pull/78), [#80](https://github.com/ForumHFR/redface2/pull/80))
- Bottom-nav 4 onglets, navigation Compose Navigation 3, thème Material 3 clair / sombre / AMOLED
- Pipeline build signed AAB stamping `redface2-v<N>-<YYYYMMDD>-<sha>.aab`

### Notes
- Pas de réseau réel — `TopicFixtureRepository` charge un asset embarqué
- Login HFR / Drapeaux réels / Forum réel arrivent en Phase 1B / 1C
