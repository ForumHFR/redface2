---
layout: default
title: Release et publication Play Console
parent: Guides
nav_order: 5
---

# Release et publication Play Console

Comment construire un AAB signé et le publier sur le canal de tests Play Console depuis GitHub Actions. Le workflow couvre deux flux :

1. **Tag git `app-v<N>`** — release officielle alignée sur le `versionCode`. Build → AAB + APK signés → upload Play Console (track `internal` par défaut, statut `DRAFT`) → GitHub Release avec les artefacts attachés.
2. **`workflow_dispatch` manuel** — release intermédiaire / dogfood en cours de dev. Choix de la branche, du track Play (`internal` / `alpha` / `beta` / `production` / nom de closed track custom / `none`) et de l'attachement à une GitHub Release. Track `none` = build + sign uniquement, pas d'upload Play.

Workflow source : [`.github/workflows/release.yml`](https://github.com/ForumHFR/redface2/blob/main/.github/workflows/release.yml).

## Conventions

- **Tag namespace** : les releases app utilisent `app-v<versionCode>` (ex: `app-v32`, `app-v33`). Cela évite de collisionner avec les tags `v0.x.0` du site / des specs.
- **Track Play Console** : la CD utilise par défaut `internal` (track standard, toujours présent). Les **closed testing tracks** ont un nom **custom** défini par le maintainer dans Play Console UI (ex: `qa`, `beta-ferme`, `dogfood`). Pour cibler un closed track depuis le `workflow_dispatch`, passer le nom **exact** tel qu'il apparaît dans la Play Console. La validation de l'existence du track est faite côté Play API au moment de l'upload — un nom invalide fera échouer l'action avec une erreur claire.

## Pré-requis (à faire une fois)

### 1. Service account Play Console (côté GCP IAM + Play Console)

Le seul flux supporté en 2026 par Google pour les uploads CI est via **GCP IAM**. Il n'y a plus de "service account natif Play Console".

1. Créer un projet GCP dédié au repo (ou réutiliser celui du compte développeur Play).
2. **GCP Console → IAM → Service accounts** → créer `redface2-play-publisher`. Pas de rôle GCP nécessaire au-delà de `Service Account User` (c'est Play qui lui assignera ses droits).
3. Onglet **Keys → Add Key → JSON** → télécharger le fichier `redface2-play-publisher.json`. **Ne le commit nulle part.**
4. **Play Console → Settings → Developer API → API access** → **Link existing project** (le projet GCP de l'étape 1) → **Grant access** au service account.
5. Permissions Play Console minimales pour l'app `fr.forumhfr.redface2` :
   - `View app information`
   - `Manage testing track and edit drafts`
   - `Manage closed testing release` (et `internal`, `alpha`, `beta`, `production` selon les tracks que la CD doit pouvoir cibler)

Référence Google : [Use the Play Developer API with a service account](https://developers.google.com/android-publisher/getting_started).

### 2. Premier upload manuel (obligatoire)

Play Console exige **un premier AAB uploadé manuellement** avant que l'API service account puisse pousser sur un track. Faire ça avec l'AAB le plus récent généré localement (le keystore vit sous `.gradle-user/signing/`, qui est gitignored — il faut soit le posséder déjà localement soit l'obtenir hors-repo) :

```bash
./scripts/docker-dev.sh ./gradlew :app:bundleRelease \
  --init-script .gradle-user/signing/signing.init.gradle
# AAB produit : app/build/outputs/bundle/release/redface2-v<N>-<date>-<sha>.aab
```

Puis dans Play Console : **App → Test → \<le track ciblé\> → Create new release** → upload manuel. Une fois ce premier draft créé, la CD prend le relais.

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

## Flux 1 — Release officielle par tag

```bash
# 1. Bumper versionCode + versionName dans app/build.gradle.kts (sur main)
# 2. Mettre à jour app/CHANGELOG.md et CHANGELOG.md (specs)
# 3. Merger la PR de release
git switch main && git pull --ff-only

# 4. Tag aligné sur versionCode et push (namespace `app-v<N>`)
git tag app-v32 -m 'v32 — Phase 1 close-out'
git push --tags
```

La CD démarre automatiquement. Output :
- AAB signé attaché à un nouvel objet **Releases** GitHub `app-v32`
- APK release signé attaché également (utile pour sideload, F-Droid, dogfood manuel)
- Upload Play Console **track `internal`** par défaut, **statut `DRAFT`** — aller dans Play Console pour activer le draft une fois testé en interne

## Flux 2 — Build intermédiaire / dogfood manuel

**GitHub → Actions → Release → Run workflow** :

| Input | Choix typique |
|---|---|
| `ref` | `feat/ma-branche-en-cours` (vide = ref actuel) |
| `play_track` | `internal` (par défaut, track standard) ; nom exact du closed track Play Console (ex: `qa`, `beta-ferme`) ; ou `none` pour ne pas pousser sur Play |
| `attach_release` | `false` (artefacts uniquement comme Workflow artefacts, pas de GitHub Release) |

Output :
- Artefacts AAB+APK téléchargeables depuis l'onglet Actions du run pendant 30 jours
- Si `play_track ≠ none` : upload Play Console en `DRAFT` sur le track choisi
- Si `attach_release = true` : crée une **draft GitHub Release** `dispatch-v<N>-<sha>` (utile pour partager un build par lien)

## Bump de version : convention

Le `versionCode` est strictement croissant. Play Console rejette tout AAB dont le `versionCode` a déjà été uploadé (sur n'importe quel track), même si la release a été archivée. Un slot consommé est consommé.

| Cas | Action |
|---|---|
| Release officielle Phase N+1 | bump majeur du `versionName` (ex: `0.1.0-phase1.4` → `0.2.0-phase2.0`) |
| Release intermédiaire dans la même phase | bump du suffixe (ex: `0.1.0-phase1.1` → `0.1.0-phase1.2`) |
| Build dogfood non distribué | bumper malgré tout, marquer comme `burnt` dans `app/CHANGELOG.md` si jamais distribué |

## Promotion entre tracks

L'action n'auto-promote pas — c'est un choix de design pour ne pas livrer en prod par accident. Pour promouvoir un draft d'un track à l'autre :

- **Manuellement via Play Console** : Test → `<track source>` → release concernée → **Promote release** → choisir le track cible. C'est le flux nominal.
- **Via la CD** : relancer `workflow_dispatch` avec le même `ref` et un nouveau `play_track`. **Attention** : le `versionCode` doit rester unique — Play Console rejette si une release du même `versionCode` est déjà sur le track cible. Cette voie sert surtout à "rejouer" la CD si le draft initial a été supprimé côté UI.

## Pourquoi `r0adkll/upload-google-play` plutôt que `gradle-play-publisher`

[`gradle-play-publisher`](https://github.com/Triple-T/gradle-play-publisher) (GPP) a longtemps été l'option canonique pour intégrer la publication Play Console directement dans le build Gradle. La version `4.0.0` (janvier 2026) ajoute le support AGP 9 mais accumule des problèmes de compatibilité avec AGP 9.x plus récent ([issue #1185](https://github.com/Triple-T/gradle-play-publisher/issues/1185) closed not planned, projet déclaré en **maintenance mode** depuis avril 2026 — [issue #1188](https://github.com/Triple-T/gradle-play-publisher/issues/1188)). Sur le projet actuel (AGP 9.2.0 + Kotlin 2.3.21), appliquer GPP 4.0.0 lève `Could not create plugin of type 'PlayPublisherPlugin' > com/android/build/api/variant/ApplicationVariant`.

L'action [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play) consomme directement l'AAB produit par `:app:bundleRelease` et parle à l'API Play Developer sans passer par un plugin Gradle. Elle ne dépend pas d'AGP, n'introduit aucune surface de friction sur les prochains bumps de stack, et garde Gradle focalisé sur ce qu'il fait bien (build + sign).

## Rotation du keystore

L'upload key (PKCS12 RSA 4096 stockée dans `upload.jks`) est ce que **nous** signons. Play Store re-signe avec la **App Signing key** côté Google, donc compromission de l'upload key ne casse pas les installs existants — il suffit de générer une nouvelle key et de la swapper dans Play Console + le secret `UPLOAD_KEYSTORE_BASE64`.

Procédure : voir [docs Play Console — Reset upload key](https://support.google.com/googleplay/android-developer/answer/9842756).

## Dépannage

| Symptôme | Cause probable | Fix |
|---|---|---|
| `versionCode XX has already been used` | Slot consommé sur un build local précédent | Bumper `versionCode` dans `app/build.gradle.kts` |
| `403 Service account does not have permission` | Permissions Play Console pas accordées | Refaire l'étape 1.5 du pré-requis |
| `INVALID_ARGUMENT: package fr.forumhfr.redface2 not found` | Premier upload manuel pas fait | Faire l'étape 2 du pré-requis |
| AAB non signé en sortie de CD | Secret `UPLOAD_KEYSTORE_BASE64` manquant ou corrompu | Re-provisionner avec `base64 -w0` (pas de retours à la ligne) |
| `keytool -list` échoue dans le job CI | Mauvais password ou base64 mangled | Vérifier `UPLOAD_KEYSTORE_PASSWORD` et regénérer le secret base64 |

## Note sur la signing config locale

Le init-script Gradle `.gradle-user/signing/signing.init.gradle` (gitignored, hors arbre versionné) reste utilisable en local pour signer un AAB de test sans toucher aux secrets CI. Il ne s'active que quand on passe `--init-script` explicitement.

La CD, elle, lit les variables d'environnement `UPLOAD_KEYSTORE_PATH`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` que le workflow remplit depuis les secrets GitHub. Ces variables sont prises en compte par `app/build.gradle.kts` qui pose alors un `signingConfigs.create("upload")` ad hoc — sans contaminer le flow dev local.
