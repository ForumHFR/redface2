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

## Unreleased

### Added
- **Retirer un drapeau (#99, Phase 2 finish)** : action « Retirer » sur chaque ligne de la liste des drapeaux, avec **dialog de confirmation Material 3 obligatoire** (titre du topic + type de drapeau) avant tout appel réseau, puis snackbar de succès/échec. Suppression unitaire via `GET /user/delflag.php` authentifié (mapping `FlagType`→`owntopic` : CYAN=1, RED=2, FAVORITE=3), classée sur le texte « Drapeau effacé avec succès ». En cas de succès, le drapeau est retiré des caches mémoire et Room et la liste se met à jour immédiatement ; en cas d'échec, aucun cache n'est touché. Pas d'undo optimiste. Action désactivée pendant l'appel (anti double-tap). Suppression en masse hors scope.

---

## v62 — `0.3.22` — 2026-05-27

**Statut** : `local`
**Commit** : `156a858` sur `feature/phase2-finish-ui-polish-198-199-201-202` avant merge PR #207
**Fichier** : à produire via `workflow_dispatch` du job `release.yml` (ou push d'un tag `app-v62`)

Correctif du bug de citations invisibles en mode connecté — la vraie cause, trouvée via la boucle de feedback émulateur.

### Fixed
- Citations (`PostBlock.Quote`) cassées en mode **authentifié** : HFR sert `<table class="oldcitation">` pour un compte connecté utilisant le style de citation classique, vs `<table class="citation">` en anonyme. Le parser ne connaissait que `citation`/`quote` → la citation était avalée et rendue en texte brut côté connecté uniquement (rendu OK en anonyme). `PostContentParser` reconnaît désormais `oldcitation` aux 3 points de classification + le sélecteur d'auteur. Test de régression avec fragment HTML réel capturé en mode connecté. Limitation connue tracée (TODO Phase 2) : le href de citation loggé `forum2.php?...#tM` n'est pas matché par `CITATION_HREF_REGEX`, donc le « aller au message cité » reste inactif en connecté (la citation s'affiche correctement).

### Changed
- `app/build.gradle.kts` : `versionCode = 62`, `versionName = "0.3.22"`.

---

## v61 — `0.3.21` — 2026-05-25

**Statut** : `closed`
**Commit** : `workflow_dispatch` sur `feature/phase2-finish-ui-polish-198-199-201-202` (run #26388655525, success)
**Fichier** : AAB uploadé sur le canal Play closed alpha

Slice maintenance alpha sur la PR #207 — réponse à la régression bordure invisible AMOLED v60 et bug quote stale persisté.

### Added
- Paramètres alpha : carte « Maintenance alpha » avec une action « Vider le cache des topics » (dialogue de confirmation Material 3, feedback inline succès / échec, indicateur de progression M3 pendant le wipe). Wipe les tables Room `posts` + `topic_pages` au sein d'une `@Transaction` ; **ne touche pas** aux drapeaux, à la session HFR, aux préférences proxy ni à la base de données globale (pas de `clearAllTables()`). Escape hatch pour forcer un reparse au prochain affichage quand le `PostContent` AST persisté est devenu obsolète.
- Paramètres alpha : switch « Ignorer le cache topic » dans la carte « Maintenance alpha ». Quand actif, `TopicRepositoryImpl.observeTopicPage` saute la lecture Room et part directement sur le réseau (le résultat est toujours persisté pour rester cohérent avec le parser courant), et `prefetch()` devient no-op. Outil de dogfood alpha uniquement — les drapeaux, l'authentification, le proxy et les préférences non liées sont intacts. Préférence persistée dans DataStore (`ignore_topic_cache`, default `false`).

### Fixed
- Settings : race d'hydratation du toggle « Ignorer le cache topic ». Quand l'utilisateur flippait le switch avant que la coroutine d'init n'ait fini de lire la valeur DataStore initiale, l'hydration tardive écrasait le flip optimiste avec la valeur stale (le toggle pouvait afficher `false` alors que DataStore était à `true`). Guard ajouté : `ignoreTopicCacheTouchedLocally` empêche l'hydratation d'écraser une modification locale, et `onSuccess` ré-affirme `ignoreTopicCache = desired` pour une cohérence finale quel que soit l'interleaving.

### Changed
- `app/build.gradle.kts` : `versionCode = 61`, `versionName = "0.3.21"`.

---

## v60 — `0.3.20` — 2026-05-24

**Statut** : `closed`
**Commit** : workflow_dispatch sur `feature/phase2-finish-ui-polish-198-199-201-202` avant merge PR #207
**Fichier** : artefact CD `dispatch-v60`

Codex rereview corrections appliquées au polish v59 — pas de nouvelle feature, uniquement des fixes ciblés.

### Fixed
- QuoteFrame : la bordure verticale d'accent est désormais dessinée via `Modifier.drawBehind` sur la Column (largeur hard-codée en pixels), au lieu d'un `Box.matchParentSize().width(4.dp)` qui risquait de peindre l'accent sur toute la largeur du card selon l'ordre de résolution des contraintes Compose. Aucune mesure intrinsèque ni enfant match-parent — sans danger pour les quotes contenant `[img]` (SubcomposeLayout).
- A11y avatar : la branche image chargée annonce maintenant « Avatar de <pseudo> » comme la branche placeholder standalone (avant : pseudo brut sans préfixe « Avatar de »). Une seule string localisée `R.string.avatar_content_description` utilisée pour les 2 modes.
- KDoc `BADGE_SIZE` : retiré la mention erronée que `Surface(onClick = ...)` injecte automatiquement le 48dp interactif. C'est `Modifier.minimumInteractiveComponentSize()` appliqué explicitement qui fait le travail.

### Changed
- `app/build.gradle.kts` : `versionCode = 60`, `versionName = "0.3.20"`.

---

## v59 — `0.3.19` — 2026-05-24

**Statut** : `closed`
**Commit** : tag `app-v59` après merge PR #207
**Fichier** : artefact CD `app-v59`

Phase 2 finish UI polish (#198 / #199 / #201 / #202).

### Added
- Menu compte global accessible depuis Drapeaux, Forum, Recherche et Messages : avatar / login-logout / paramètres alpha / diagnostics / signalement / version. Sortie unique de l'onglet `Messages` qui devient un placeholder Phase 3 sobre (#198).
- Avatars des auteurs HFR dans chaque post du topic (carré à coins arrondis, placeholder initiale quand l'URL est nulle / erreur, partagé via `:core:ui/RedfaceUserAvatar`) (#201).

### Changed
- Drapeaux : refresh manuel déplacé dans le header compact (`TextButton` à côté du menu compte) au lieu d'un bouton pleine largeur en fin de liste. Toggle « cyans déjà lus » passé en `FilterChip` Material 3 sous le tab row CYAN (#199).
- Citations : `QuoteBlock` et `CollapsedQuoteBlock` gagnent une bordure verticale d'accent (4dp, `primary`/`tertiary` alterné par profondeur) sur le `surfaceContainerHighest` existant — la régression d'invisibilité AMOLED est résolue, les quotes restent identifiables sur les 3 thèmes (clair/sombre/AMOLED). La règle `MAX_VISIBLE_QUOTE_DEPTH = 3` et le collapse au-delà sont préservés (#202).
- `app/build.gradle.kts` : `versionCode = 59`, `versionName = "0.3.19"`.

### Fixed
- A11y : badge compte expose `Role.Button` + `Modifier.minimumInteractiveComponentSize()` (48dp touch target sur 40dp visuel), `semantics(mergeDescendants=true)` empêche la double annonce TalkBack (review round 2 PR #207).
- A11y : avatar utilisateur announce « Avatar de <pseudo> » dans les deux modes (image chargée et placeholder initiale standalone), au lieu de rester muet.
- QuoteFrame : la bordure verticale d'accent est dessinée directement via `Modifier.drawBehind` (sans mesure intrinsèque ni enfant `matchParentSize`), ce qui évite le crash `IllegalStateException` "Asking for intrinsic measurements of SubcomposeLayout" qui touchait les citations contenant un `[img]` et garantit une bordure 4dp exacte indépendamment de l'ordre de résolution des contraintes Compose.

### Removed
- `MessagesViewModel` + son test (logique compte/logout déplacée dans `AppAccountViewModel` côté `:app`).
- Strings devenues mortes dans `:feature:messages` : 13 strings `messages_section_account`, `messages_auth_loading`, `messages_anonymous_intro`, `messages_login_cta`, `messages_logged_in_as`, `messages_logout_cta`, `messages_section_alpha_tools`, `messages_app_version_footer`, `messages_diagnostics_cta`, `messages_settings_cta`, `messages_report_content_cta`, `messages_report_email_subject`, `messages_report_no_email_client`. Les versions globales équivalentes vivent dans `:core:ui/account_menu_*`.
- String `flags_show_read_participated_toggle` (remplacée par `flags_show_read_participated_chip` pour le FilterChip).

---

## v58 — `0.3.18` — 2026-05-24

**Statut** : `closed`
**Commit** : tag `app-v58` après merge PR #204
**Fichier** : artefact CD `app-v58`

Phase 2 finish — rechargement du topic après publication.

### Fixed
- Reply / quote / edit / edit FP : après une soumission acceptée par HFR, l'écran topic force maintenant le rafraîchissement de la page cible au lieu de réafficher un cache stale.
- Reply simple : retour en bas de la page fraîchement rechargée quand HFR renvoie l'ancre `#bas`.
- Quote / edit / edit FP : extraction de `#t{numreponse}` depuis l'URL de succès HFR pour revenir directement au post créé ou modifié.
- Échec de rafraîchissement post-submit : feedback utilisateur explicite via Toast, avec fallback sur le cache existant plutôt qu'un écran cassé.

### Changed
- `app/build.gradle.kts` : `versionCode = 58`, `versionName = "0.3.18"`.

---

## v57 — `0.3.17` — 2026-05-24

**Statut** : `closed`
**Commit** : à venir (workflow_dispatch sur branche PR #194 avant merge / tag)
**Fichier** : artefact CD `dispatch-v57`

Hardening review PR #194 : proxy HFR-only, `isSaving` non-zombie, dropdown EditFP M3, charset UTF-8 credentials proxy.

### Fixed
- Paramètres proxy : un échec DataStore ne laisse plus le bouton Enregistrer bloqué en chargement.
- Edit FP : le dropdown sous-catégorie utilise le composant Material 3 `ExposedDropdownMenuBox` pour fiabiliser le tap sur champ read-only.
- Proxy : retrait du champ `scheme` non livré pour éviter de promettre un proxy HTTPS natif alors que le MVP supporte le proxy HTTP classique avec `CONNECT`.
- Proxy : le proxy utilisateur est désormais limité aux domaines HFR (`hardware.fr` / `*.hardware.fr`) pour éviter de casser les images externes lorsque le proxy ne route que HFR.
- Proxy : credentials Basic encodées en UTF-8 (plutôt que ISO-8859-1) pour éviter une boucle 407 silencieuse sur mots de passe proxy accentués.

### Docs
- ADR-012 ajoutée pour cadrer le stockage local des credentials proxy.
- Specs architecture réalignées : `:feature:settings` contient maintenant le `SettingsScreen` alpha.

### Changed
- `app/build.gradle.kts` : `versionCode = 57`, `versionName = "0.3.17"`.

---

## v56 — `0.3.16` — 2026-05-23

**Statut** : `closed`
**Commit** : build de test avant merge PR #194
**Fichier** : artefact CD `dispatch-v56`

Phase 2 close-out — réglage proxy alpha, polish recherche / Edit FP et helper image URL éditeur.

### Added
- Paramètres alpha accessibles depuis l’onglet Messages, avec proxy HTTP utilisateur : hôte, port, auth Basic optionnelle, persistance DataStore et application au réseau OkHttp + images Coil après redémarrage.
- Guide utilisateur `docs/guides/proxy.md` pour configurer et dépanner le proxy.
- Dialog d’insertion d’image par URL dans la toolbar BBCode partagée : génère `[img]https://...[/img]` depuis Reply / Quote / Edit / Edit FP / New topic.

### Fixed
- Recherche : les pivots de catégories restent sur une seule ligne horizontale avec ellipsis, évitant les libellés verticaux sur mobile.
- Edit FP : la sous-catégorie est modifiable via le même dropdown que la création de topic.

### Changed
- `app/build.gradle.kts` : `versionCode = 56`, `versionName = "0.3.16"`.

---

## v55 — `0.3.15` — 2026-05-23

**Statut** : `closed`
**Commit** : `8691c69`
**Fichier** : artefact CD `app-v55`

Phase 2G-B — release finale recherche après rebase sur `main`, hotfix release workflow et publication `app-v55`.

### Fixed
- Le changement de mode Titres + messages / Titres / Messages conserve maintenant la catégorie HFR déjà sélectionnée via le pivot au lieu de repartir silencieusement sur toutes les catégories.
- KDoc restante `SearchUiState` alignée sur Phase 2G-A/B.

### Changed
- `app/build.gradle.kts` : `versionCode = 55`, `versionName = "0.3.15"`.
- Release publiée via tag `app-v55` depuis `main`, pour éviter de réutiliser le `versionCode=54` déjà consommé par la build Alpha de test.

---

## v54 — `0.3.14` — 2026-05-23

**Statut** : `closed`
**Commit** : `04d8944` (build de test avant rebase/sync PR #183)
**Fichier** : artefact CD `dispatch-v54`

Phase 2G-B — polish final recherche avant test Alpha.

### Fixed
- Le texte d'accueil de la recherche reflète maintenant le comportement réel : ouverture du message correspondant quand HFR fournit un `numreponse`, sinon ouverture du topic.
- Les libellés Phase 2G-A/B et la roadmap sont réalignés après l'ajout des modes Titres + messages / Titres / Messages.

### Changed
- `app/build.gradle.kts` : `versionCode = 54`, `versionName = "0.3.14"`.
- Notes Play Console conservées sur le périmètre recherche 2G-B.

---

## v53 — `0.3.13` — 2026-05-23

**Statut** : `closed`
**Commit** : `0ae8d75` (build de test avant merge PR #183)
**Fichier** : artefact CD `dispatch-v53`

Phase 2G-B — polish recherche avant nouvelle alpha.

### Added
- Recherche par défaut en mode « Titres + messages » (`titre=3`), avec choix explicite Titres + messages / Titres seuls / Messages seuls.
- Affichage de l'extrait « Dernier message correspondant » quand HFR le fournit pour une recherche dans le contenu.
- Navigation vers le post exact depuis un résultat de recherche contenu quand le lien HFR porte `numreponse`.
- Indication sobre des filtres auteur/date/pagination à venir.

### Fixed
- Le pivot catégories HFR est affiché comme un sélecteur horizontal de périmètre, pas comme une liste de résultats à ouvrir.
- Les cartes de résultats affichent leur catégorie/sous-catégorie pour clarifier le contexte.

### Changed
- `app/build.gradle.kts` : `versionCode = 53`, `versionName = "0.3.13"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v52 — `0.3.12` — 2026-05-22

**Statut** : `closed`
**Commit** : à venir (tag `app-v52` après merge de la PR de release)
**Fichier** : artefact CD `app-v52`

Phase 2G-A — recherche réelle de topics HFR par titre.

### Added
- Onglet Recherche branché sur l'endpoint HFR réel `forum1.php?recherches=1`.
- Recherche par titre en mode toutes catégories, avec pivots de catégories quand HFR renvoie plusieurs familles de résultats.
- Recherche scoped par catégorie depuis les pivots HFR.
- Fixtures réelles et tests parser / repository / ViewModel pour les quatre formes observées : aucun résultat, pivot unique, pivot multiple, catégorie explicite.

### Fixed
- Les résultats d'une recherche en vol ne peuvent plus remplacer l'état après saisie d'une nouvelle query.
- Les lignes de résultats malformées échouent explicitement au parser au lieu d'être silencieusement ignorées.
- La construction de l'URL `searchTopics()` est maintenant couverte par MockWebServer, y compris le mode anonyme.

### Changed
- `app/build.gradle.kts` : `versionCode = 52`, `versionName = "0.3.12"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v51 — `0.3.11` — 2026-05-22

**Statut** : `closed`
**Commit** : à venir (build de test avant merge PR #174)
**Fichier** : artefact CD `app-v51`

Phase 2F-B — premier picker de smileys dans l'éditeur BBCode.

### Added
- Bouton « Smileys » dans l'éditeur Reply / Quote / Edit.
- Bottom sheet Material 3 avec onglet Standard (25 smileys HFR intégrés) et onglet Wiki.
- Recherche live des smileys perso via l'endpoint HFR `message-smi-mp-aj.php`, avec debounce 300 ms et seuil de 3 caractères comme le composer web HFR.
- Insertion du token BBCode brut dans le texte (`:jap:`, `;)`, `[:haha jap]`, variantes `[:name:N]`) en conservant la convention HFR d'espaces autour.

### Fixed
- Les diagnostics du picker ne loggent plus la query complète ni l'identifiant numérique HFR.
- La recherche wiki en vol est annulée quand un smiley est sélectionné ou quand le picker est fermé.

### Changed
- `app/build.gradle.kts` : `versionCode = 51`, `versionName = "0.3.11"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v50 — `0.3.10` — 2026-05-22

**Statut** : `closed`
**Commit** : à venir (tag `app-v50` après merge de la PR de release)
**Fichier** : `redface2-v50-<sha>.aab`

Hotfix alpha — amélioration du remplissage automatique sur l'écran de connexion.

### Fixed
- `LoginScreen` expose les hints Android Autofill corrects : pseudo en `ContentType.Username`, mot de passe en `ContentType.Password`.
- Proton Pass, Bitwarden, Google Password Manager et les autres services Autofill ont maintenant un contrat explicite pour distinguer les deux champs.

### Changed
- `app/build.gradle.kts` : `versionCode = 50`, `versionName = "0.3.10"`.
- Notes Play Console mises à jour pour le track alpha.

---

## v49 — `0.3.9` — 2026-05-21

**Statut** : `closed`
**Commit** : `c79789b`
**Fichier** : artefact CD `app-v49`

Phase 2E — création de topic depuis l'app. Un compte authentifié peut ouvrir le composer depuis une liste de catégorie, saisir un titre + contenu BBCode, choisir la sous-catégorie et poster via `bddpost.php` sans navigateur.

### Added
- FAB « Nouveau topic » dans `ForumCategoryScreen`, visible uniquement quand `AuthState.Authenticated`.
- `TopicFormMode.New` fonctionnel dans `TopicFormScreen` : titre, dropdown sous-catégorie obligatoire, toolbar BBCode, preview locale et options HFR.
- `NewTopicContext` / `NewTopicSubmitResult`, `TopicFormParser.parseNewTopic`, `HfrClient.getNewTopicForm()` / `submitNewTopic()`, et `TopicFormRepository.fetchNewTopicForm()` / `submitNewTopic()`.
- Fallback honnête après succès : navigation directe si un futur parser extrait `newTopicId`; sinon retour sur la sous-catégorie cible avec Toast.

### Changed
- `app/build.gradle.kts` : `versionCode = 49`, `versionName = "0.3.9"`.
- Notes Play Console mises à jour pour le track alpha.

### Fixed
- Documentation/KDoc nettoyées : `TopicFormMode.New` n'est plus décrit comme placeholder ou futur.
- `protocol-hfr.md` aligne la limite restante : le POST création est livré, seule la fixture de réponse succès dédiée manque encore pour extraire les ids du nouveau topic.

---

## v48 — `0.3.8` — 2026-05-21

**Statut** : `closed`
**Commit** : `265877c`
**Fichier** : artefact CD `app-v48`

Stabilisation post-review de l'édition du premier post avant d'attaquer la création de topic.

### Changed
- Hydratation `subject` et `draft` indépendante dans `TopicFormViewModel`.
- Parser FP durci : aucun fallback silencieux quand le `<select name=subcat>` n'a pas de sélection valide.
- Champs sondage sortis de `hiddenFields`; `TopicPollForm.fields` devient la source unique de passthrough.

---

## v47 — `0.3.7` — 2026-05-21

**Statut** : `closed`
**Commit** : `9256298`
**Fichier** : artefact CD `app-v47`

Phase 2D-B — édition du premier post d'un topic owned.

### Added
- `TopicFormScreen` réel pour `TopicFormMode.EditFirstPost` : sujet, contenu BBCode, options HFR et sous-catégorie.
- Parser topic-level `TopicFormParser` avec préservation des champs sondage et filtrage `password` / `delete`.
- Bouton « Modifier le premier message » quand HFR expose l'action sur le premier post.

---

## v46 — `0.3.6` — 2026-05-20

**Statut** : `closed`
**Commit** : `11ae858`
**Fichier** : artefact CD `app-v46`

Phase 2D-A — édition d'un post existant appartenant au compte authentifié.

### Added
- `PostEditorMode.Edit` fonctionnel via `EditPostRepository`.
- Détection `Post.isEditable` / `Post.isOwnPost` depuis la toolbar HFR.
- Submit edit via `bdd.php`, avec refresh topic + scroll vers le post édité.

---

## v45 — `0.3.5` — 2026-05-19

**Statut** : `closed`
**Commit** : `3e2a350`
**Fichier** : artefact CD `app-v45`

Correctif options HFR et radios/checkboxes du formulaire d'écriture.

### Fixed
- `ReplyFormParser` respecte la sémantique browser : radio/checkbox non cochée absente du POST.
- `MsgIcon` ne dérive plus vers la dernière radio du formulaire.

### Added
- Toggles signature, smilies et notification email dans `PostEditorScreen`.

---

## v44 — `0.3.4` — 2026-05-18

**Statut** : `closed`
**Commit** : `577c6b6`
**Fichier** : artefact CD `app-v44`

Phase 2C complète — reply + quote MVP.

### Added
- Bouton « Citer » par post quand HFR expose `numrep` + `ref`.
- Hydratation du draft avec le `[quotemsg=…]` prérempli par HFR.
- POST quote via le même `ReplyRepository` que la réponse simple.

---

## v43 — `0.3.3` — 2026-05-18

**Statut** : `closed`
**Commit** : `59667a3`
**Fichier** : artefact CD `app-v43`

Hotfix `NetworkOnMainThreadException` sur le flow reply.

### Fixed
- `DefaultReplyRepository.fetchReplyForm()` et `submitReply()` passent par le dispatcher IO injecté avant les appels OkHttp bloquants.

---

## v42 — `0.3.2` — 2026-05-18

**Statut** : `closed`
**Commit** : `7929ff8`
**Fichier** : artefact CD `app-v42`

Instrumentation alpha du flow reply.

### Added
- Diagnostics transport et mapping ViewModel autour du GET/POST reply.
- Logs sans `hash_check`, utiles pour qualifier les échecs alpha sans `adb`.

---

## v41 — `0.3.1` — 2026-05-18

**Statut** : `closed`
**Commit** : `9edfc21`
**Fichier** : artefact CD `app-v41`

Première instrumentation diagnostics du flow reply.

### Added
- `DefaultReplyRepository` écrit dans `DiagnosticsLog` sur GET, parse, POST et erreurs classifiées.
- Extraits HTML redacted sur échec parser.

---

## v40 — `0.3.0` — 2026-05-18

**Statut** : `closed`
**Commit** : `9679a51`
**Fichier** : artefact CD `app-v40`

Premier flux de mutation HFR réelle : réponse à un topic depuis l'app.

### Added
- `PostEditorMode.Reply` branché sur HFR : GET `message.php`, POST `bddpost.php`.
- `ReplyRepository`, `ReplyFormParser`, `ReplySubmitResponseParser` et erreurs HFR typées.
- Anti-double-submit et refresh de la page topic après succès.

### Changed
- `versionName` passe à `0.3.0` pour marquer la première mutation réelle.

---

## v39 — `0.2.0` — 2026-05-18

**Statut** : `closed`
**Commit** : `2ffdc39`
**Fichier** : artefact CD `app-v39`

Passage Phase 2 : protocole d'écriture HFR cartographié et socle éditeur local livré.

### Added
- Fixtures Phase 2A pour reply, quote, edit, création topic, anonyme, topic fermé, succès et erreurs HFR.
- `PostEditorScreen` / `PostEditorViewModel` local-only avec toolbar BBCode, preview locale et sélection préservée.
- `BbcodeContentParser` + `BbcodePreviewParser` pour la preview BBCode locale.

### Changed
- Convention app : `versionName` passe au semver pur (`0.2.0`), distinct de la version des specs/site.

---

## v38 — `0.1.0-phase1.7` — 2026-05-11

**Statut** : `closed`
**Commit** : `b5ef0b8`
**Fichier** : `redface2-v38-b5ef0b8.aab`

Build de polish pré-Phase 2 pour le track alpha Play. L'objectif est de présenter une app de lecture cohérente pendant la revue manuelle Play, sans faux boutons laissant croire que Recherche ou Messages sont déjà livrés.

### Changed
- Écran Drapeaux recentré sur la lecture : footer alpha retiré, liste + refresh + login/reconnect gardés.
- Les sujets CYAN déjà lus (`hasUnread = false`) sont masqués par défaut, avec un toggle « Afficher les sujets participés déjà lus ».
- Écran Messages transformé en surface temporaire « Compte + Outils alpha » : login/logout, version, diagnostics et signalement.
- Écran Recherche remplacé par une annonce sobre de la future recherche HFR Phase 2, sans bouton de topic démo.

### Docs
- Specs `architecture.md`, `mvi.md`, `navigation.md` et `roadmap.md` alignées avec le polish #154.

---

## v37 — `0.1.0-phase1.6` — 2026-05-10

**Statut** : `closed`
**Commit** : `1832ed1`
**Fichier** : `redface2-v37-1832ed1.aab`

Build de finalisation Phase 1. Pas de changement fonctionnel visible utilisateur — uniquement de l'instrumentation perf et des tests qui figent les invariants du `PostRenderer` pour Phase 2.

### Added
- `androidx.tracing` 1.3.0 (#143, closes #117) : 7 sections `rf2.topic.*` couvrent le parcours « ouvrir un topic et commencer à lire ». 4 sections sync (`network`, `body_read`, `parse_html`, `map_domain`) + 3 async (`room_read`, `room_write`, `first_content`). Catalogue stable dans [`docs/guides/profiling.md`](https://github.com/ForumHFR/redface2/blob/main/docs/guides/profiling.md), prêt à être consommé par un `TraceSectionMetric` macrobenchmark futur (#142).
- Test `core/ui` qui fige le contrat de profondeur de quote ≥ 3 collapsable (#138, closes #83).
- Test `core/ui` qui fige la symétrie d'ensemble du `MediaCounter` sur un AST non-trivial (#140, closes #139).

### Closed-out
- Phase 1 marquée ✅ livrée dans [`docs/specs/roadmap.md`](https://github.com/ForumHFR/redface2/blob/main/docs/specs/roadmap.md).
- Issues finalisation Phase 1 fermées : #28 (référence behaviors HFR — repris dans #81), #51 (primitives UI — `FlagItem` livré, `TopicRow`/`PostCard` reportés au 2e usage réel), #117 (tracing).
- Follow-ups Phase 2 ouverts : #141 (microbench parser), #142 (macrobench parcours topic), #131 (validation visuelle smileys dogfood), #130 (test Robolectric `fillMaxSize`).

---

## v36 — `0.1.0-phase1.5` — 2026-05-10

**Statut** : `closed`
**Commit** : `100038d`
**Fichier** : `redface2-v36-<date>-<sha>.aab`

Premier build CD avec auto-publish sur le track alpha. Pas de changement fonctionnel de l'app — c'est l'AAB qui valide bout-en-bout le nouveau pipeline avec `status: completed` (par défaut sur les tracks de test), pour ne plus avoir à activer le draft manuellement dans la Play Console après chaque upload.

### Changed
- `.github/workflows/release.yml` : ajout d'un input `play_release_status` au `workflow_dispatch` et d'un défaut intelligent (`completed` pour testing tracks, `draft` pour production).
- `app/build.gradle.kts` bump `versionCode = 36`, `versionName = "0.1.0-phase1.5"`. Le slot `v35` est marqué `closed` (uploadé manuellement sur le track alpha avant la mise en place du push API).

---

## v35 — `0.1.0-phase1.4` — 2026-05-08

**Statut** : `closed`
**Commit** : `4bc6210`
**Fichier** : `redface2-v35-4bc6210.aab`

Patch dogfood après extraction exhaustive du wikismilies HFR : le bucket carré `56sp × 56sp` de v34 rendait les petits smileys lisibles, mais ne respectait pas la forme dominante réelle du corpus. Sur 34 139 smileys perso, la première taille est `70×50` (8047 occurrences), suivie de `50×50` (2811), `67×50` (1142), puis de nombreuses variantes `W×50`.

### Changed
- `PostMediaDisplayPolicy.persoSmiley` : `56sp × 56sp` → `70sp × 50sp`.
- `ContentScale.Fit` reste la règle des smileys, mais le bucket cible devient corpus-first :
  - `15×15` devient `50×50`, lisible sans réserver une ligne carrée de 56sp.
  - `39×15` devient `70×27`, ratio préservé.
  - `50×50` reste `50×50`, taille native dominante.
  - `70×50` reste `70×50`, taille la plus fréquente du wikismilies.
  - `200×150` devient `67×50`, borne haute conservée.
- Les images inline `[img]` restent en `ContentScale.Inside` dans le bucket `240×180`.
- Invariant typographique resserré : `persoSmiley.placeholderHeight ≤ 2.5 × bodyMedium.lineHeight`.

### Tests
- `PostMediaDisplayPolicyTest` : dimensions 70×50sp, corpus `Fit` aligné sur wikismilies, séparation `smileyContentScale` / `inlineImageContentScale`, ratios extrêmes `1×100` / `100×1`, invariant `2.5×`.
- `PostRendererInlineTest` : bucket perso 70×50sp explicitement distinct du builtin 18sp.

---

## v34 — `0.1.0-phase1.3` — 2026-05-05

**Statut** : `burnt`
**Commit** : `21e04d6`
**Fichier** : `redface2-v34-20260505-21e04d6.aab`

Patch dogfood après retour visuel sur v32/v33 : les smileys perso en bucket `40sp` corrigent le chevauchement, mais sont trop petits sur smartphone. v34 garde le correctif clé de #129 (`fillMaxSize()` dans le placeholder `sp`), remonte le bucket perso à `56sp`, et repasse les smileys en `ContentScale.Fit` pour restaurer leur lisibilité.

Slot remplacé par v35 après analyse du crawl exhaustif wikismilies : `56×56` est lisible, mais la distribution réelle justifie un bucket `70×50`.

### Changed
- `PostMediaDisplayPolicy.persoSmiley` : `40sp × 40sp` → `56sp × 56sp`.
- Corpus attendu à density 1 avec `ContentScale.Fit` côté smileys :
  - `15×15` devient `56×56`, lisible sur smartphone.
  - `39×15` devient `56×22`, ratio préservé.
  - `50×50` devient `56×56`, léger upscale assumé.
  - `70×50` devient `56×40`, ratio préservé.
  - `200×150` devient `56×42`, borne haute conservée.
- Les images inline `[img]` restent en `ContentScale.Inside` pour ne pas agrandir une petite image arbitraire dans le bucket `240×180`.
- Invariant typographique assoupli de `2.5×` à `2.8× bodyMedium.lineHeight` : on privilégie la lisibilité des perso HFR sans revenir au bucket cassé `64sp`.

### Tests
- `PostMediaDisplayPolicyTest` : dimensions 56sp, corpus `Fit`, séparation `smileyContentScale` / `inlineImageContentScale`, ratios extrêmes `1×100` / `100×1`, invariant `2.8×`.
- `PostRendererInlineTest` : bucket perso 56sp explicitement distinct du builtin 18sp.

---

## v33 — `0.1.0-phase1.2` — 2026-05-05

**Statut** : `burnt`
**Commit** : `a55453a` puis `535b839`
**Fichier** : `redface2-v33-20260505-a55453a.aab`, `redface2-v33-20260505-535b839.aab`

Slot brûlé pendant le dogfood du correctif smileys perso. La trajectoire finale passe par v35 avec un nouveau `versionCode` pour éviter tout conflit Play Console / distribution interne.

---

## v32 — `0.1.0-phase1.1` — 2026-05-05

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v32-<date>-<sha>.aab`

Release Phase 1 close-out après merge de [#126](https://github.com/ForumHFR/redface2/pull/126) (rendu Compose des images et smileys HFR avec Coil 3) **et** [#129](https://github.com/ForumHFR/redface2/pull/129) (correctif visuel sur les perso smileys inline). Ferme [#109](https://github.com/ForumHFR/redface2/issues/109) et donc l'umbrella Phase 1 [#87](https://github.com/ForumHFR/redface2/issues/87).

Le `versionName` perd le suffixe `-phase1d` parce que toutes les sous-phases 1A → 1D sont désormais sur `main` ; on entre dans la stabilisation Phase 1 avant ouverture du canal Play Console internal testing ([#72](https://github.com/ForumHFR/redface2/issues/72)). Note sur la trajectoire : `versionCode 31` (`0.1.0-phase1.0`) a été buildé localement avec le bucket perso 64×64 + `ContentScale.Fit` issu de #126 ; un bug visuel a été reproduit en dogfood sur le post HFR #74625731 (perso smileys oversize, lignes de texte intrudées). Pas de v31 distribuée — on saute directement à v32 avec le fix.

### Added
- **`PostMediaDisplayPolicy`** (`:core:ui`) : politique de buckets pure JVM-testable pour les médias inline. Builtin smiley `18×18`, perso smiley `40×40`, inline image `240×180`, block image `min 160dp / max 480dp`. `ContentScale.Inside` (downscale only, **jamais d'upscale**) pour les médias inline — un perso 70×50 est ramené à un ratio préservé (≈ 40×29 à density 1), un perso 15×15 reste à 15×15 centré avec padding visible (pas de pixelisation par 4× upscale).
- **`SingletonImageLoader.Factory`** sur `RedfaceApplication` avec `AnimatedImageDecoder.Factory()` (`coil-gif`) : autoplay GIFs builtin (`:bounce:`, `:pt1cable:`) ET perso sans configuration par-call-site. minSdk 29 → pas de fallback `GifDecoder` legacy.
- **`SubcomposeAsyncImage`** sur `PostRenderer.ImageBlock` : slots loading / error visibles pendant le fetch ou si l'host (rehost.diberie.com, super-h.fr…) est offline. `defaultMinSize(160dp)` réserve une hauteur de placeholder pour éviter un layout jump quand la bitmap résout (cf. review Codex sur PR #126).
- **3 strings FR** pour les états image : `post_image_loading`, `post_image_error`, `post_image_error_with_alt`.
- **Aliases libs** `coil-core`, `coil-gif`, `coil-network-okhttp` exposés au module `:app` pour mettre le décodeur GIF + le fetcher OkHttp sur le classpath du `SingletonImageLoader`.
- **Fonction pure `insideScaledMediaSize(source, bucket)`** miroir de `ContentScale.Inside`, exposée pour tester le corpus HFR réel sans Compose runtime. `coerceAtLeast(1)` sur les sorties pour éviter qu'un ratio extrême (1×100) ne collapse une dimension à 0.

### Fixed
- **Smileys perso inline oversize** ([#129](https://github.com/ForumHFR/redface2/pull/129)) : trois facteurs cumulés diagnostiqués via arbitrage Codex et corrigés ensemble.
  1. Bucket perso `64sp × 64sp` dans un paragraphe `bodyMedium` avec `lineHeight = 20.sp` explicite : le placeholder faisait 3.2× la hauteur de ligne, le `LineHeightStyleSpan` figé contraignait l'expansion automatique du `PlaceholderSpan` → débordement vertical sur les lignes adjacentes. Bucket réduit à `40sp × 40sp` (`≤ 2.5 × bodyMedium.lineHeight`, invariant pinned dans les tests).
  2. `ContentScale.Fit` upscalait les petits sprites (`tinostar` 15×15 → 64×64 = 4× upscale pixelisé). Remplacé par `ContentScale.Inside` (downscale only) pour les trois call-sites inline.
  3. `Modifier.size(64.dp)` côté `AsyncImage` figé en `dp` pendant que le placeholder est en `sp` → divergence sous `fontScale ≠ 1`. Remplacé par `Modifier.fillMaxSize()` : l'image suit le placeholder en `sp`, robuste sous `fontScale ≠ 1` (accessibility).

### Tests
- `PostMediaDisplayPolicyTest` (pure JVM) : pin les dimensions des 4 buckets, invariant typographique `persoSmiley.placeholderHeight ≤ 2.5 × bodyMedium.lineHeight` (lecture dynamique via `RedfaceTypography`), invariant `inlineMediaContentScale === ContentScale.Inside`. Test corpus HFR réel `[(15,15), (39,15), (40,40), (50,50), (70,50), (200,150)]` via `insideScaledMediaSize`. Test ratios extrêmes `1×100` / `100×1` (garde anti-collapse via `coerceAtLeast(1)`).
- `PostRendererInlineTest` (pure JVM) : assert `PlaceholderVerticalAlign.Center` sur les trois chemins (builtin, perso, inline image). Vérifie que le bucket perso est bien `40sp` et **pas** le builtin (garde anti-collapse).

---

## v30 — `0.1.0-phase1d.2` — 2026-05-04

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v30-<date>-<sha>.aab`

Release Phase 1D après merge de [#123](https://github.com/ForumHFR/redface2/pull/123) : support natif des blocs monospace HFR `[fixed]` / `[code]`.

### Added
- **Parser PostContent** : `PostBlock.Fixed(text)` et `PostBlock.CodeBlock(text, language?)` sont produits depuis `<table class="fixed">` / `<table class="code">`, y compris quand les blocs sont imbriqués dans une citation.
- **Hints langue `[code lang]`** : la classe `<pre class="<lang>">` est exposée via `CodeBlock.language` (`cpp`, `java`, etc.). La coloration syntaxique HFR est volontairement aplatie en texte brut en Phase 1.
- **PostRenderer** : rendu Compose natif en `Card` monospace avec scroll horizontal et `softWrap = false`.

### Fixed
- **Indentation monospace** : le parser ne fait plus de `trim()` global sur les blocs `[fixed]` / `[code]`; seules les lignes vides structurelles en bordure sont retirées.
- **Scroll horizontal** : le conteneur monospace ne clamp plus sa largeur interne avant `horizontalScroll`.

### Tests
- Tests parser sur les fixtures réelles `topic_page_multipage.html` et `topic_redface2_p16.html`.
- Test de sérialisation JSON pour les nouveaux variants `Fixed` / `CodeBlock`.

---

## v24 — `0.1.0-phase1b.10` — 2026-05-01

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v24-<date>-<sha>.aab`

Durcissement final Phase 1B avant 1C : login failure, cookies, session expirée et spec HFR.

### Changed
- **Login HFR** : le POST `login_validation.php` utilise maintenant un cookie jar de staging avec redirects désactivés. Les `Set-Cookie` reçus sur une réponse 200 ou une redirection 302 ne sont commités dans le `PersistentCookieJar` qu'après classification `Authenticated`, donc `InvalidCredentials`, `RateLimited` ou `Unknown` ne peuvent plus installer une session par effet de bord.
- **Cookies persistants** : `PersistentCookieJar` sérialise les écritures avec une version de mutation ; un `clear()` plus récent gagne contre un `saveFromResponse()` plus ancien qui n'aurait pas encore atteint le store. `DataStoreCookieStore.observe()` fail-close sur payload corrompu sans terminer le flow, afin qu'une future écriture valide soit observée.
- **Session expirée** : `HfrClient.getFlagsPage()`, `getPrivateMessageListPage()` et `getTopicPage(useAuth = true)` lèvent maintenant `SessionExpiredException` si HFR redirige vers `/login.php` ou renvoie un formulaire login en HTTP 200. `getTopicPage(useAuth = false)` garde le passthrough anonyme pour le prefetch. L'écran Drapeaux affiche un message dédié avec action de reconnexion au lieu d'une liste vide trompeuse.
- **`protocol-hfr.md`** : documente le cookie `md_user` form-url-encoded (`Colonel MythO` -> `Colonel+MythO`), distingue absence de cookie et mismatch après décodage, et acte le staging cookie du login.

### Tests
- Tests MockWebServer sur login failure + `Set-Cookie` non persisté, login success + commit explicite, session expirée flags/MP/topic authentifié, passthrough topic anonyme, vraie page vide non confondue avec login.
- Tests cookie store sur `clear()` vs save stale et recovery après payload DataStore corrompu.

---

## v23 — `0.1.0-phase1b.9` — 2026-04-29

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v23-<date>-<sha>.aab`

Hotfix login pour les pseudos avec espace ou caractères spéciaux.

### Fixed
- **`AuthRemoteDataSource.classify`** décode maintenant la valeur du cookie `md_user` via `URLDecoder` avant comparaison avec le pseudo soumis. HFR pose le pseudo URL-form-encodé dans le cookie (espace → `+`, accents → `%XX`), donc un pseudo comme `Colonel MythO` matchait `Colonel+MythO` octet-à-octet → `LoginError.Unknown` alors que la session était en réalité valide. Confirmé sur l'alpha grâce au trail diagnostics. URLDecoder est wrappé dans `runCatching` pour fall-back sur la valeur brute si l'encodage est malformé. Test ajouté : `pseudo with space matches md_user cookie URL-form-encoded`.

### Notes
- v22 est restée locale (jamais distribuée) — bumpée à v23 directement pour livrer ce hotfix avec les diagnostics login + bouton « Copier ».

---

## v22 — `0.1.0-phase1b.8` — 2026-04-29

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v22-<date>-<sha>.aab`

Diagnostics login plus utiles : trace de la requête HTTP envoyée + bouton « Copier ».

### Added
- **`AuthRemoteDataSource`** logue maintenant le wire-form body envoyé à HFR (`Log.d login request: POST <url> body=pseudo=...&password=<redacted>`). Permet au testeur alpha de voir comment son pseudo est URL-encodé (espace → `+`, `@` → `%40`, accents → `%XX%XX`) avant qu'il n'arrive au PHP HFR — utile quand un pseudo « spécial » est rejeté et qu'on suspecte un désaccord d'encoding entre `FormBody` et le décodeur côté serveur. Le password est masqué via regex sur le buffer dumpé avant tout `Log.d` ou `diagnostics.record`.
- **Bouton « Copier »** dans `DiagnosticsScreen` : copie tout le ring buffer dans le presse-papiers en plain text (`HH:mm:ss.SSS  L  TAG  message` par ligne). Désactivé buffer vide. Toast de confirmation sur Android < 13 (Android 13+ affiche déjà l'overlay système « copié dans le presse-papiers »).

### Notes
- v21 buildée localement mais non distribuée — bumpée à v22 directement.

---

## v21 — `0.1.0-phase1b.7` — 2026-04-29

**Statut** : `local`
**Commit** : à venir
**Fichier** : `redface2-v21-<date>-<sha>.aab`

Diagnostics in-app + corrections post-review round 2.

### Added
- **`DiagnosticsLog`** dans `:core:domain` — ring buffer 200 entrées en mémoire, exposé via `StateFlow<List<Entry>>`. In-memory only par design, pas de persistance disque.
- **`DiagnosticsScreen`** dans `:feature:flags` — viewer in-app accessible via le bouton « Diagnostics (alpha) » dans le footer. Auto-scroll vers la dernière entrée, code couleur par niveau (I vert, D bleu, W orange), boutons « Vider » / « Fermer ».
- **`DiagnosticsRoute`** dans la navigation `:app`.
- **Trail logcat + in-app** sur `AuthRemoteDataSource` : `Log.i` à chaque tentative (pseudo + length + codepoints, jamais le password), `Log.d` après réponse (HTTP code, body length, cookie names, présence de md_user en length seulement), `Log.w` sur chaque échec classifié.

### Changed
- **`LoginError.Unknown` mismatch cookie/pseudo** : embed maintenant un diagnostic factuel (`submitted len=X vs cookie len=Y, sameLength=true/false, caseInsensitiveMatch=true/false`) sans jamais embed la valeur du cookie.
- **`LoginUiState.Mode.Error`** gagne un champ `detail: String?` propagé depuis `LoginError.Unknown.detail` et `LoginError.Network.cause`.
- **`LoginScreen.ErrorBanner`** rend le détail en monospace sous le message localisé — un testeur diagnostique sans `adb logcat`.
- **`FlagsListParser`** : `replyCount` et `views` strippent maintenant tout char non-numérique via `digitsOnly()` au lieu de juste l'espace ASCII (robust contre NBSP `U+00A0` que HFR utilise pour grouper les milliers).
- **`FlagsListParser.totalPages`** : `coerceAtLeast(1)` au niveau parser, élimine "p.X/0" sur topic neuf.
- **`FlagsListParser.lastReadPage`** : fallback `totalPages` quand le row est lu et n'a pas d'anchor (drapeau lu sur favorisn).
- **`FlagRepository` cache mémoire** : le cache par onglet est maintenant explicitement borné à la session HFR courante (`clearSessionCache()` au logout / retour anonyme). Un nouvel accès avec un autre compte ne peut plus réémettre les drapeaux du compte précédent.
- **`FlagRepository.refresh()`** : émet `Loading` puis le résultat réseau frais, met à jour le cache session, et alimente le bouton « Actualiser » affiché sur les listes en succès.
- **`FlagItem`** dans `:core:ui` reçoit maintenant `metadata: String` pré-formaté par le caller — i18n boundary clean (plus de littéral français hardcodé dans le module partagé).
- **`FlagsRoute` `LazyColumn`** ajoute `Modifier.weight(1f)` pour que `FooterSlot` reste visible même avec 127 drapeaux (cyan tab) — `AuthenticatedBody` devient extension `ColumnScope`.
- **`FlagsRoute` `LazyColumn` `key`** passe de `flag.topicId` à `"${cat}-${topicId}"` — élimine le crash latent `IllegalArgumentException` si HFR retourne le même topicId dans deux cats.
- **Onglets HFR mapping** corrigé via fixtures réelles : `FlagType.CYAN` = `owntopic=1` = sujets participés (« Mes sujets »), `FlagType.RED` = `owntopic=2` = lus uniquement, `FlagType.FAVORITE` = `owntopic=3`.
- **`DefaultFlagRepository`** : cache mémoire par onglet après le premier succès — changer d'onglet puis revenir ne refetch pas et ne fait pas bouger l'état lu/non-lu.

### Fixed
- Tests `:core:network` activent `testOptions.unitTests.isReturnDefaultValues = true` pour mocker `android.util.Log` en JVM unit tests.
- **`DiagnosticsScreen`** : clés `LazyColumn` basées sur un `Entry.id` monotone au lieu de `timestampMillis + hash(message)`, supprimant le crash théorique sur deux logs identiques dans la même milliseconde.

### Notes
- v20 a été uploadée Play Console — versionCode 21 obligatoire pour cette release. CHANGELOG v20 existant conservé pour historique.

---

## v20 — `0.1.0-phase1b.6` — 2026-04-28

**Statut** : `internal`
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
