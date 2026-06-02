---
layout: default
title: Release et publication Play Console
parent: Guides
nav_order: 5
---

# Release et publication Play Console

Comment construire un AAB signé et le publier sur le bon canal Play Console depuis GitHub Actions. **Depuis #233, le canal est déterminé par le déclencheur** (3 product flavors coexistants, applicationId distincts ; une seule source de vérité = la Release GitHub) :

1. **GitHub Release publiée + cochée « pre-release »** → canal **beta** (`fr.forumhfr.redface2.beta`) → Play **open testing** + F-Droid beta.
2. **GitHub Release publiée, normale (stable)** → canal **prod** (`fr.forumhfr.redface2`) → Play **production** (statut `draft`) **après approbation manuelle** via l'Environment GitHub `production` + F-Droid release.
3. **`workflow_dispatch` manuel** → canal **dev** (`fr.forumhfr.redface2.dev`) → Play **internal testing** (rapide, pas de F-Droid).

⚠️ **Le tag `app-v<N>` seul ne déclenche plus la CD** : il faut **publier une GitHub Release** (sur ce tag) — pre-release pour beta, stable pour prod. Une Release dont le tag ne commence pas par `app-v` (ex. les `v0.x` specs/site) est **ignorée** (gate dans `resolve-target`).

Workflow source : [`.github/workflows/release.yml`](https://github.com/ForumHFR/redface2/blob/main/.github/workflows/release.yml).

## Conventions

- **Tag namespace** : les releases app utilisent `app-v<versionCode>` (ex: `app-v32`, `app-v33`). Cela évite de collisionner avec les tags `v0.x.0` du site / des specs.
- **Canaux / tracks Play Console** : depuis #233, **le canal est dérivé du déclencheur** (cf. en-tête), pas d'input `play_track` libre. Mapping figé dans `resolve-target` : beta → `fr.forumhfr.redface2.beta` track **open testing** ; prod → `fr.forumhfr.redface2` track **production** ; dev → `fr.forumhfr.redface2.dev` track **internal**. Chaque `applicationId` est un listing Play distinct (coexistence sur l'appareil). L'alpha (closed testing de l'app unique) est **retiré** au profit de ces 3 canaux.

## Pré-requis (à faire une fois)

### 1. Service account Play Console (côté GCP IAM + Play Console)

Le seul flux supporté en 2026 par Google pour les uploads CI est via **GCP IAM**. Il n'y a plus de "service account natif Play Console".

1. Créer un projet GCP dédié au repo (ou réutiliser celui du compte développeur Play).
2. **GCP Console → IAM → Service accounts** → créer `redface2-play-publisher`. Aucun rôle GCP n'est nécessaire — la création du compte de service suffit. Les droits applicatifs (publication AAB, gestion tracks) sont accordés exclusivement côté Play Console à l'étape 1.5. Le rôle GCP `Service Account User` n'est utile **que si** d'autres principals doivent impersonner ce SA via la CLI `gcloud` ; pour un upload CI direct depuis GitHub Actions avec la clé JSON, on peut le laisser vide.
3. Onglet **Keys → Add Key → JSON** → télécharger le fichier `redface2-play-publisher.json`. **Ne le commit nulle part.**
4. **Play Console → Settings → Developer API → API access** → **Link existing project** (le projet GCP de l'étape 1) → **Grant access** au service account.
5. Permissions Play Console — à accorder sur **chacune** des 3 apps (`fr.forumhfr.redface2`, `fr.forumhfr.redface2.beta`, `fr.forumhfr.redface2.dev`), ou en accès **account-wide** (sinon les uploads beta/dev échouent en 403 / `package not found`) :
   - `View app information`
   - `Manage testing track and edit drafts`
   - `Manage testing track releases` (couvre `internal` + `beta` = open testing)
   - `Release apps to production` (pour le canal prod)

Référence Google : [Use the Play Developer API with a service account](https://developers.google.com/android-publisher/getting_started).

### 2. Premier upload manuel — **par package** (obligatoire)

Play Console exige **un premier AAB uploadé manuellement** par **`applicationId`** avant que l'API service account puisse pousser. Les 3 canaux étant 3 packages distincts, il faut le faire **pour chacun** (`…redface2`, `…redface2.beta`, `…redface2.dev`). Les AAB signés se génèrent localement (keystore sous `.gradle-user/signing/`, gitignored — à posséder/obtenir hors-repo) :

```bash
./scripts/docker-dev.sh ./gradlew :app:bundleProdRelease :app:bundleBetaRelease :app:bundleDevRelease \
  --init-script .gradle-user/signing/signing.init.gradle
# AAB : app/build/outputs/bundle/{prod,beta,dev}Release/app-{flavor}-release.aab
```

Puis dans **chaque** listing Play Console : **App → Test → \<track\> → Create new release** → upload manuel. Une fois ce premier upload fait par package, la CD prend le relais sur ce package.

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

1. Bumper `versionCode` + `versionName` dans `app/build.gradle.kts` (sur `main`), mettre à jour `app/CHANGELOG.md`, merger la PR de release.
2. Sur `main`, **publier une GitHub Release pre-release** sur un tag `app-v<N>` :

```bash
git switch main && git pull --ff-only
gh release create app-v72 --prerelease --title 'Redface 2 v72 (0.4.0-beta)' --notes '…'
```

La CD résout **beta** : build `:app:bundleBetaRelease` (`fr.forumhfr.redface2.beta`), upload Play **open testing** (statut `completed`), attache l'AAB+APK à la Release, notifie F-Droid (beta).

## Flux prod — production

Idem mais **Release stable** (case « pre-release » décochée) :

```bash
gh release create app-v73 --title 'Redface 2 v73 (1.0.0)' --notes '…'
```

La CD résout **prod** et **attend l'approbation** dans l'Environment GitHub `production` (required reviewer) avant tout build/upload. Upload Play **production** en **statut `draft`** (double garde-fou : approbation GitHub + activation manuelle Play). Notifie F-Droid (release).

## Flux dev — internal testing (rapide)

**GitHub → Actions → Release → Run workflow** (ou `gh workflow run release.yml -f ref=<branche>`). Pas de GitHub Release, pas de F-Droid. La CD résout **dev** : build `:app:bundleDevRelease` (`fr.forumhfr.redface2.dev`), upload Play **internal** (`completed`). Seul input : `ref` (défaut = ref courant). Artefacts du run téléchargeables 30 j.

## Bump de version : convention

Le `versionCode` est strictement croissant. Play Console rejette tout AAB dont le `versionCode` a déjà été uploadé (sur n'importe quel track), même si la release a été archivée. Un slot consommé est consommé.

| Cas | Action |
|---|---|
| Release officielle Phase N+1 | bump majeur du `versionName` (ex: `0.1.0-phase1.4` → `0.2.0-phase2.0`) |
| Release intermédiaire dans la même phase | bump du suffixe (ex: `0.1.0-phase1.1` → `0.1.0-phase1.2`) |
| Build dogfood non distribué | bumper malgré tout, marquer comme `burnt` dans `app/CHANGELOG.md` si jamais distribué |

## Promotion entre canaux

⚠️ **beta → prod n'est PAS une promotion Play.** beta est le package `…redface2.beta` et prod `…redface2` : Play Console ne promeut **qu'entre les tracks d'un MÊME package**, pas entre packages distincts. Donc :

- **beta → prod** = publier une **Release stable** (case pre-release décochée) sur un **nouveau tag `app-v<N>`** avec un `versionCode` neuf → la CD build/upload le package prod. C'est le seul chemin beta→prod.
- **Promote release (même package)** reste valable *à l'intérieur* d'un package : ex. au sein de prod, promouvoir d'un track de test interne vers `production`, ou faire un rollout progressif. Via Play Console : Test → `<track>` → release → **Promote release** (ne traverse jamais une frontière de package).
- Le `workflow_dispatch` ne sert qu'au canal **dev** (son seul input est `ref`) — pas un outil de promotion.

## Pourquoi `r0adkll/upload-google-play` plutôt que `gradle-play-publisher`

[`gradle-play-publisher`](https://github.com/Triple-T/gradle-play-publisher) (GPP) a longtemps été l'option canonique pour intégrer la publication Play Console directement dans le build Gradle. La version `4.0.0` (janvier 2026) ajoute le support AGP 9 mais accumule des problèmes de compatibilité avec AGP 9.x plus récent ([issue #1185](https://github.com/Triple-T/gradle-play-publisher/issues/1185) closed not planned, projet déclaré en **maintenance mode** depuis avril 2026 — [issue #1188](https://github.com/Triple-T/gradle-play-publisher/issues/1188)). Sur le projet actuel (AGP 9.2.0 + Kotlin 2.3.21), appliquer GPP 4.0.0 lève `Could not create plugin of type 'PlayPublisherPlugin' > com/android/build/api/variant/ApplicationVariant`.

L'action [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play) consomme directement l'AAB produit par `:app:bundleRelease` et parle à l'API Play Developer sans passer par un plugin Gradle. Elle ne dépend pas d'AGP, n'introduit aucune surface de friction sur les prochains bumps de stack, et garde Gradle focalisé sur ce qu'il fait bien (build + sign).

## Rotation du keystore

L'upload key (PKCS12 RSA 4096 stockée dans `upload.jks`) est ce que **nous** signons. Play Store re-signe avec la **App Signing key** côté Google, donc compromission de l'upload key ne casse pas les installs existants — il suffit de générer une nouvelle key et de la swapper dans Play Console + le secret `UPLOAD_KEYSTORE_BASE64`.

Procédure : voir [docs Play Console — Reset upload key](https://support.google.com/googleplay/android-developer/answer/9842756).

## Récupérer un AAB si l'upload Play Console échoue

Si l'étape `Publish to Play Console` du workflow échoue (auth Play, quota, track invalide…) **après** que la signature ait réussi, l'AAB et l'APK signés sont déjà stagés. Deux façons de les récupérer sans relancer le build :

1. **Workflow artefacts** : aller sur la page Actions → run en échec → en bas du job `build`, télécharger l'archive `redface2-<canal>-v<N>-<sha>` (rétention 30 jours). Chemin standard pour tous les canaux (beta/prod/dev).
2. **GitHub Release** (canaux beta/prod uniquement) : le step `Attach artefacts to the GitHub Release` tourne **après** l'upload Play mais indépendamment de son succès — si le build + la signature ont passé, l'AAB+APK sont attachés à la Release `app-v<N>` (que tu as publiée) même si Play a refusé. Après avoir corrigé la cause (permissions Play, premier upload manuel du listing, etc.), l'upload manuel via la Play Console UI depuis l'AAB téléchargé évite de re-bumper le `versionCode`. (Le canal **dev** n'a pas de Release → utiliser les workflow artefacts.)

Si la signature elle-même échoue (`keytool -list` ou `jarsigner -verify`), aucun artefact n'est produit — refixer le secret keystore avant de retenter.

## Dépannage

| Symptôme | Cause probable | Fix |
|---|---|---|
| `versionCode XX has already been used` | Slot consommé sur un build local précédent | Bumper `versionCode` dans `app/build.gradle.kts` |
| `403 Service account does not have permission` | Permissions Play Console pas accordées | Refaire l'étape 1.5 du pré-requis |
| `INVALID_ARGUMENT: package fr.forumhfr.redface2 not found` | Premier upload manuel pas fait | Faire l'étape 2 du pré-requis |
| AAB non signé en sortie de CD | Secret `UPLOAD_KEYSTORE_BASE64` manquant ou corrompu | Re-provisionner avec `base64 -w0` (pas de retours à la ligne) |
| `keytool -list` échoue dans le job CI | Mauvais password ou base64 mangled | Vérifier `UPLOAD_KEYSTORE_PASSWORD` et regénérer le secret base64 |
| Upload Play KO mais AAB OK | Voir § « Récupérer un AAB si l'upload Play Console échoue » | Télécharger l'artefact GH Actions, finir l'upload depuis Play Console |

## Note sur la signing config locale

Le init-script Gradle `.gradle-user/signing/signing.init.gradle` (gitignored, hors arbre versionné) reste utilisable en local pour signer un AAB de test sans toucher aux secrets CI. Il ne s'active que quand on passe `--init-script` explicitement.

La CD, elle, lit les variables d'environnement `UPLOAD_KEYSTORE_PATH`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` que le workflow remplit depuis les secrets GitHub. Ces variables sont prises en compte par `app/build.gradle.kts` qui pose alors un `signingConfigs.create("upload")` ad hoc — sans contaminer le flow dev local.
