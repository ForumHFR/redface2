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

## v14 — `0.1.0-phase1b.0` — 2026-04-27

**Statut** : `local`
**Commit** : à venir (PR feature/1b-1-auth)
**Fichier** : `redface2-v14-20260427-<sha>.aab`

Phase 1B.1 livrée : login HFR utilisable de bout en bout.

### Added
- **Login HFR fonctionnel** — `LoginScreen` (`:feature:auth`) appelle `AuthRepository.login()`, qui POSTe `login_validation.php?config=hfr.inc` via le `@AuthenticatedClient`. Le cookie `md_user` retourné est persisté par `PersistentCookieJar` ↔ `DataStoreCookieStore`, donc la session survit kill/restart de l'app.
- **`AuthState` global** — `FlagsScreen` affiche maintenant `Connecté en tant que <pseudo> · Se déconnecter` ou un CTA `Se connecter à HFR`, alimenté par `FlagsHomeViewModel.observeAuthState()`.
- **Erreurs typées** — `LoginError.{InvalidCredentials, RateLimited, Network, Unknown}` mappées en bandeaux français localisés dans `LoginScreen`.
- **`:core:auth` non créé** — l'architecture spec place le backbone auth dans `:core:network` (login + cookies) et `:core:data` (repository impl). Le module `:feature:auth` (déjà bootstrap Phase 0) ajoute juste l'UI.
- **Sécurité au repos** — `android:allowBackup="false"` + `fullBackupContent="false"` dans `AndroidManifest.xml` pour exclure les cookies des backups Google Drive (cf. ADR-002 amendé).
- **Konsist @AnonymousClient** (closes #42) — règle architecturale qui interdit aux fichiers sous `/auth/` d'importer le qualifier `@AnonymousClient`. Catch le mismatch silencieux (cookies pas envoyés → session vue comme déconnectée) au build.
- **DataStore Preferences 1.2.1** — ajouté au version catalog. Persiste les cookies non chiffrés (cf. ADR-002 amendé : password en plaintext POST → chiffrement local redondant face à un attaquant runtime).

### Changed
- **ADR-002 amendé** — alignement avec la décision originale issue [#24 thème 13](https://github.com/ForumHFR/redface2/issues/24#issuecomment-3526003625) : DataStore non chiffré + FBE plateforme, sans clé Keystore custom (la rédaction initiale avait dérivé en réintroduisant AES/GCM Keystore).
- `InMemoryCookieJar` n'est plus dans le graph Hilt production — gardé comme utilitaire de test.

### Tests
- 5 tests `:core:data.auth.DataStoreCookieStore` (Robolectric, persist + filter expired)
- 6 tests `:core:network.cookie.PersistentCookieJar` (cache snapshot + merge + deletion-marker)
- 6 tests `:core:network.auth.AuthRemoteDataSource` (MockWebServer, success + 4 erreurs typées + identity mismatch)
- 7 tests `:core:data.auth.DefaultAuthRepository` (MockK + fake CookieStore)
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
