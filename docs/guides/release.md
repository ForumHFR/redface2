---
layout: default
title: Release et publication Play Console
parent: Guides
nav_order: 5
---

# Release et publication Play Console

Comment construire les artefacts signés et les publier sur le bon canal depuis GitHub Actions. **Depuis #304, un seul déclencheur : `workflow_dispatch` avec un input `channel` (`beta` | `dev`).** Plus de GitHub Release à publier à la main. Deux modèles de distribution distincts car **Play et F-Droid sont des stores indépendants** (artefacts séparés) :

- **Play** : **une seule app** (`fr.forumhfr.redface2`, un seul applicationId), distribuée via **tracks**. Le **label du lanceur** change par build (`-PappLabel`), pas l'applicationId.
- **F-Droid** : **un applicationId par canal** (`.beta`/`.dev`) → des **apps distinctes coexistantes** (F-Droid indexe par package, pas de tracks).

| Input `channel` | Play | F-Droid |
|---|---|---|
| **`beta`** | `fr.forumhfr.redface2`, label « Redface 2 β », track **open testing** | package `fr.forumhfr.redface2.beta` (« Redface 2 β ») |
| **`dev`** | `fr.forumhfr.redface2`, label « Redface 2 dev », track **internal testing** | package `fr.forumhfr.redface2.dev` (« Redface 2 dev ») |

Le canal **prod/stable** est **mis de côté pour l'instant** (#302/#304) : il n'est plus câblé dans le workflow. Sa conception (gate d'approbation `production` + track production) reste récupérable dans l'historique git.

**Chaque dispatch crée lui-même une Release pre-release `app-v<N>`** portant les artefacts signés (AAB Play + APK F-Droid du canal), pour que le publisher F-Droid ait une source. Comme le workflow n'a plus de trigger `on: release`, cette Release auto-créée ne re-déclenche rien.

### versionCode — registre canonique partagé (le cœur du modèle)

Le `versionCode` est un **compteur unique, monotone, partagé par beta ET dev** : l'ensemble des tags git **`app-v<N>`** EST le registre. À chaque ship, la CD calcule :

```
versionCode = max( plus grand tag app-v<N> , plancher versionCode de build.gradle.kts ) + 1
```

Conséquences :
- **Aucun bump manuel** : un dispatch beta ou dev alloue automatiquement le code suivant et tague `app-v<N>`.
- **Pas d'inversion beta↔dev** : les deux canaux tirent du **même** registre, donc la suite est globalement croissante quel que soit l'ordre des ships. (Play partage le namespace `versionCode` entre tracks d'une même app → c'est exactement ce qu'il faut.)
- **Plancher de sécurité** : le `versionCode` de `app/build.gradle.kts` sert de borne basse — si le fetch des tags échouait, on n'émet jamais un code inférieur au dernier shippé (qui ferait rejeter Play / bloquer F-Droid).
- Le `versionName` (`app/build.gradle.kts`) reste la version « marketing » humaine, **découplée** du `versionCode`.

Workflow source : [`.github/workflows/release.yml`](https://github.com/ForumHFR/redface2/blob/main/.github/workflows/release.yml).

## Conventions

- **Tag = registre** : chaque ship (beta ou dev) crée un tag `app-v<versionCode>` (ex: `app-v73`). Cet ensemble de tags est la source canonique du compteur. (Les `v0.x.0` du site/specs et les anciens `app-dev-v*` ne matchent pas `^app-v[0-9]+$` et sont ignorés du calcul.)
- **Routing (`resolve-target`)** : `channel=beta` → Play track `beta` + F-Droid `.beta` ; `channel=dev` → Play track `internal` + F-Droid `.dev`. Le **label** (`-PappLabel`) et le **versionCode** (`-PversionCodeOverride`, calculé depuis le registre) sont injectés au build des deux artefacts ; un **check CI** (`aapt2 dump badging`) vérifie package + label + **versionCode** de chaque APK avant publication.
- **Sérialisation** : le `concurrency: group: release` (sans `cancel-in-progress`) sérialise les runs pour que deux dispatches n'allouent jamais le même code.

## Pré-requis (à faire une fois)

### 1. Service account Play Console (côté GCP IAM + Play Console)

Le seul flux supporté en 2026 par Google pour les uploads CI est via **GCP IAM**. Il n'y a plus de "service account natif Play Console".

1. Créer un projet GCP dédié au repo (ou réutiliser celui du compte développeur Play).
2. **GCP Console → IAM → Service accounts** → créer `redface2-play-publisher`. Aucun rôle GCP n'est nécessaire — la création du compte de service suffit. Les droits applicatifs (publication AAB, gestion tracks) sont accordés exclusivement côté Play Console à l'étape 1.5. Le rôle GCP `Service Account User` n'est utile **que si** d'autres principals doivent impersonner ce SA via la CLI `gcloud` ; pour un upload CI direct depuis GitHub Actions avec la clé JSON, on peut le laisser vide.
3. Onglet **Keys → Add Key → JSON** → télécharger le fichier `redface2-play-publisher.json`. **Ne le commit nulle part.**
4. **Play Console → Settings → Developer API → API access** → **Link existing project** (le projet GCP de l'étape 1) → **Grant access** au service account.
5. Permissions Play Console — à accorder sur l'app **`fr.forumhfr.redface2`** (un seul applicationId, tous les tracks sont dessus) :
   - `View app information`
   - `Manage testing track and edit drafts`
   - `Manage testing track releases` (couvre `internal` + `beta` = open testing)
   - `Release apps to production` (pour le track production)

Référence Google : [Use the Play Developer API with a service account](https://developers.google.com/android-publisher/getting_started).

### 2. Premier upload manuel (obligatoire, une fois)

Play Console exige **un premier AAB uploadé manuellement** sur l'app avant que l'API service account puisse pousser (sinon l'API répond `Package not found` — l'applicationId n'est fixé qu'au premier upload). Comme on n'a **qu'une seule app**, c'est à faire **une seule fois** (déjà fait historiquement : l'app `fr.forumhfr.redface2` a reçu les builds alpha v14…v71). Pour mémoire, l'AAB prod se génère localement (keystore sous `.gradle-user/signing/`, gitignored) :

```bash
./scripts/docker-dev.sh ./gradlew :app:bundleProdRelease \
  --init-script .gradle-user/signing/signing.init.gradle
# AAB : app/build/outputs/bundle/prodRelease/*.aab
```

Puis Play Console : **App → Test → \<track\> → Create new release** → upload manuel. Ensuite la CD prend le relais sur tous les tracks.

### 3. Secrets GitHub Actions

À provisionner dans **Settings → Secrets and variables → Actions** du repo `ForumHFR/redface2` :

| Secret | Source / valeur |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | depuis le repo cloné : `base64 -w0 .gradle-user/signing/upload.jks` puis copier le contenu (sans retours à la ligne) |
| `UPLOAD_KEYSTORE_PASSWORD` | mot de passe du keystore configuré localement |
| `UPLOAD_KEY_ALIAS` | alias de la clé dans le keystore (`upload` par défaut) |
| `UPLOAD_KEY_PASSWORD` | mot de passe de la clé (souvent identique à celui du keystore) |
| `PLAY_SERVICE_ACCOUNT_JSON` | contenu intégral du fichier JSON téléchargé à l'étape 1.3 |

Le keystore (`.jks`) et son init-script Gradle vivent sous `.gradle-user/signing/`, **gitignored par construction** (cf. `.gitignore`). Ce dossier n'est pas versionné — chaque maintainer doit le posséder localement ou l'obtenir hors-repo (canal sécurisé).

Le workflow refuse de tourner si `UPLOAD_KEYSTORE_BASE64` est manquant (un build non signé n'a pas de sens pour la CD). En revanche `PLAY_SERVICE_ACCOUNT_JSON` peut être absent : la CD construira et signera l'AAB, l'attachera comme artefact GitHub, et **skippera l'upload Play** avec un warning. Pratique pour valider le workflow avant que la partie Play Console soit prête.

## Flux beta — open testing (public)

**GitHub → Actions → Release → Run workflow → `channel = beta`** (ou `gh workflow run release.yml -f channel=beta`). La CD :

- alloue le `versionCode` depuis le registre de tags (`max(app-v*, plancher)+1`) ;
- build `:app:bundleProdRelease -PappLabel="Redface 2 β" -PversionCodeOverride=<N>` (Play, base appId, label β) + `:app:assembleBetaRelease -PversionCodeOverride=<N>` (F-Droid, `fr.forumhfr.redface2.beta`) ;
- upload Play sur le **track `beta` (open testing)** en `completed` ;
- crée la Release pre-release `app-v<N>` avec AAB+APK Play **et** l'APK F-Droid `.beta` ;
- notifie F-Droid (package `.beta`).

Plus besoin de bumper `versionCode` à la main ni de publier une Release : le dispatch fait tout. (Mettre à jour `app/CHANGELOG.md` + `versionName` reste utile pour une vraie montée de version marketing.)

## Flux dev — Play internal + F-Droid `.dev` (rapide)

**GitHub → Actions → Release → Run workflow → `channel = dev`** (défaut ; ou `gh workflow run release.yml -f channel=dev -f ref=<branche>`). Identique au flux beta mais :

- Play : track **`internal`** au lieu de `beta`, label « Redface 2 dev ».
- F-Droid : `:app:assembleDevRelease` → package `fr.forumhfr.redface2.dev`.

Inputs : `channel` (`dev` par défaut) et `ref` (défaut = ref courant, pour builder une branche). Les artefacts sont aussi téléchargeables depuis le run Actions (30 j) pour le sideload.

## Flux prod — production (différé)

**Mis de côté pour l'instant** (#302/#304). Le workflow ne propose plus que `beta`/`dev`. Quand on voudra (re)brancher la production (gate d'approbation `production` + track `production` en `draft`), repartir de la conception conservée dans l'historique git du workflow et dans le draft `drafts/claude-cd-channels-labels-fdroid-design.md`.

## Bump de version : convention

Le `versionCode` est strictement croissant et **géré automatiquement** par le registre de tags `app-v<N>` (cf. § versionCode ci-dessus). Play Console rejette tout AAB dont le `versionCode` a déjà été uploadé (sur n'importe quel track) — le registre garantit qu'on n'en réutilise jamais un.

| Cas | Action |
|---|---|
| Ship beta ou dev | **rien à faire** côté `versionCode` : la CD alloue le suivant depuis le registre et tague `app-v<N>`. |
| Montée de version marketing (Phase N+1, milestone) | bumper le `versionName` dans `app/build.gradle.kts` + `app/CHANGELOG.md` via PR. Le `versionCode` reste piloté par le registre. |
| Plancher `versionCode` de `build.gradle.kts` | ne le baisser jamais ; il sert de borne basse de sécurité. Le bumper est inutile en régime normal (le registre est au-dessus). |

## Promotion entre tracks

Comme tout est l'app unique `fr.forumhfr.redface2`, **la promotion native Play fonctionne** entre ses tracks (internal → open testing → production). Le canal prod étant différé, en pré-1.0 on reste sur `internal` (dev) et `open testing` (beta), tous deux via dispatch.

- **Promote dans Play Console** (internal → open testing, ou open testing → production) reste possible *côté Play uniquement*, mais ⚠️ **bypasse l'automatisation** : **F-Droid n'est pas notifié** et la Release GitHub n'est pas mise à jour. À réserver à un cas Play urgent ; sinon re-dispatcher sur le bon canal garde Play + F-Droid + GitHub **alignés** (le binaire peut être identique — le registre alloue juste un nouveau `versionCode`).
- Le `workflow_dispatch` est désormais l'**unique** porte d'entrée de la CD (canal choisi par l'input `channel`).

## Pourquoi `r0adkll/upload-google-play` plutôt que `gradle-play-publisher`

[`gradle-play-publisher`](https://github.com/Triple-T/gradle-play-publisher) (GPP) a longtemps été l'option canonique pour intégrer la publication Play Console directement dans le build Gradle. La version `4.0.0` (janvier 2026) ajoute le support AGP 9 mais accumule des problèmes de compatibilité avec AGP 9.x plus récent ([issue #1185](https://github.com/Triple-T/gradle-play-publisher/issues/1185) closed not planned, projet déclaré en **maintenance mode** depuis avril 2026 — [issue #1188](https://github.com/Triple-T/gradle-play-publisher/issues/1188)). Sur le projet actuel (AGP 9.2.0 + Kotlin 2.3.21), appliquer GPP 4.0.0 lève `Could not create plugin of type 'PlayPublisherPlugin' > com/android/build/api/variant/ApplicationVariant`.

L'action [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play) consomme directement l'AAB produit par `:app:bundleRelease` et parle à l'API Play Developer sans passer par un plugin Gradle. Elle ne dépend pas d'AGP, n'introduit aucune surface de friction sur les prochains bumps de stack, et garde Gradle focalisé sur ce qu'il fait bien (build + sign).

## Rotation du keystore

L'upload key (PKCS12 RSA 4096 stockée dans `upload.jks`) est ce que **nous** signons. Play Store re-signe avec la **App Signing key** côté Google, donc compromission de l'upload key ne casse pas les installs existants — il suffit de générer une nouvelle key et de la swapper dans Play Console + le secret `UPLOAD_KEYSTORE_BASE64`.

Procédure : voir [docs Play Console — Reset upload key](https://support.google.com/googleplay/android-developer/answer/9842756).

## Récupérer un AAB si l'upload Play Console échoue

Si l'étape `Publish to Play Console` du workflow échoue (auth Play, quota, track invalide…) **après** que la signature ait réussi, l'AAB et l'APK signés sont déjà stagés. Deux façons de les récupérer sans relancer le build :

1. **Workflow artefacts** : aller sur la page Actions → run en échec → en bas du job `build`, télécharger l'archive `redface2-<canal>-v<N>-<sha>` (rétention 30 jours). Chemin standard pour les deux canaux (beta/dev).
2. **GitHub Release** : le step `Create GitHub Release` crée la Release pre-release `app-v<N>` (`always()` + garde sur la signature) **même si l'upload Play a échoué** ensuite — les artefacts signés y sont attachés. Après avoir corrigé la cause (permissions Play, premier upload manuel du listing, etc.), l'upload manuel via la Play Console UI depuis l'AAB téléchargé évite de consommer un nouveau `versionCode`.

Si la signature elle-même échoue (`keytool -list` ou `jarsigner -verify`), aucun artefact n'est produit — refixer le secret keystore avant de retenter.

## Dépannage

| Symptôme | Cause probable | Fix |
|---|---|---|
| `versionCode XX has already been used` | Un build local/manuel a consommé un code hors registre, ou un tag `app-v<N>` a été supprimé | Vérifier le plus grand tag `app-v<N>` ; au besoin bumper le plancher `versionCode` de `app/build.gradle.kts` au-dessus du code consommé |
| `403 Service account does not have permission` | Permissions Play Console pas accordées | Refaire l'étape 1.5 du pré-requis |
| `INVALID_ARGUMENT: package fr.forumhfr.redface2 not found` | Premier upload manuel pas fait | Faire l'étape 2 du pré-requis |
| AAB non signé en sortie de CD | Secret `UPLOAD_KEYSTORE_BASE64` manquant ou corrompu | Re-provisionner avec `base64 -w0` (pas de retours à la ligne) |
| `keytool -list` échoue dans le job CI | Mauvais password ou base64 mangled | Vérifier `UPLOAD_KEYSTORE_PASSWORD` et regénérer le secret base64 |
| Upload Play KO mais AAB OK | Voir § « Récupérer un AAB si l'upload Play Console échoue » | Télécharger l'artefact GH Actions, finir l'upload depuis Play Console |

## Note sur la signing config locale

Le init-script Gradle `.gradle-user/signing/signing.init.gradle` (gitignored, hors arbre versionné) reste utilisable en local pour signer un AAB de test sans toucher aux secrets CI. Il ne s'active que quand on passe `--init-script` explicitement.

La CD, elle, lit les variables d'environnement `UPLOAD_KEYSTORE_PATH`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` que le workflow remplit depuis les secrets GitHub. Ces variables sont prises en compte par `app/build.gradle.kts` qui pose alors un `signingConfigs.create("upload")` ad hoc — sans contaminer le flow dev local.
