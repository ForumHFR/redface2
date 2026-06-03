# Design CD — canaux beta/dev, label par canal, Play 1-fiche + F-Droid 2-apps

> Statut : **draft non normatif** (drafts/ ne gouverne rien tant que non promu). À faire relire par Codex gpt-5.5 xhigh avant implémentation.
> Auteur : Claude Opus 4.8 (demandé par @XaaT). Contexte : #233 (umbrella release/distrib).

## 1. Objectif

Distribuer Redface 2 sur **deux canaux pré-1.0** — **beta** et **dev** — avec, **selon le canal**, un **label de lanceur dédié** (« Redface 2 β », « Redface 2 dev »), sur **Play Store** et **F-Droid**, sans gérer encore les releases **stables/prod** (code conservé, déclencheur inactif jusqu'à la sortie de beta).

Exigences utilisateur explicites :
1. **Play : un seul applicationId / une seule fiche.** Pas 2 apps Play. Le label seul change selon le canal.
2. **F-Droid : beta ET dev disponibles**, idéalement comme 2 apps qui **coexistent** sur l'appareil.
3. **Le label change à la compilation** (CI/CD), et chaque artefact part sur le bon canal/store.
4. **Prod/stable différé** : garder le code CI/CD, ne pas déclencher.

## 2. Contrainte structurante (vérifiée)

- `applicationId` = identité de l'app **pour Play ET pour l'appareil** (doc officielle `developer.android.com/build/build-variants`). Deux installs ne peuvent pas partager un `applicationId`.
- **Play** : `applicationId` distinct ⟺ **fiche séparée**. Donc « 1 fiche » ⟹ **1 seul applicationId** sur Play. Le **label** (`android:label`) est indépendant de l'`applicationId` → on peut varier le label par build sans toucher l'`applicationId`.
- **F-Droid** : indexe par `applicationId` (package), **pas de notion de track**. Pour avoir beta **et** dev comme **2 apps coexistantes**, il faut **2 applicationId distincts** (pattern standard F-Droid : Tusky/Tusky Nightly, Bitwarden, etc.).
- **Clé de la solution** : Play et F-Droid sont **deux canaux indépendants, artefacts séparés**. Rien n'impose au build F-Droid d'avoir le même `applicationId` que le build Play. ⟹ Play en 1 `applicationId`, F-Droid en `applicationId` suffixés `.beta`/`.dev`, **simultanément**.

## 3. Schéma cible

| Canal | Déclencheur | Store | applicationId | Label lanceur | Destination |
|---|---|---|---|---|---|
| **beta** | GitHub Release *pre-release* | **Play** | `fr.forumhfr.redface2` | « Redface 2 β » | track **open testing** |
| **beta** | (même run) | **F-Droid** | `fr.forumhfr.redface2.beta` | « Redface 2 β » | package F-Droid « Redface 2 β » |
| **dev** | `workflow_dispatch` | **Play** | `fr.forumhfr.redface2` | « Redface 2 dev » | track **internal** |
| **dev** | (même run) | **F-Droid** | `fr.forumhfr.redface2.dev` | « Redface 2 dev » | package F-Droid « Redface 2 dev » |
| **prod** *(différé)* | GitHub Release stable | Play / F-Droid | `fr.forumhfr.redface2` | « Redface 2 » | production (gate) / package base |

Conséquences assumées :
- Sur **Play**, beta et dev sont des **tracks de la même fiche** `fr.forumhfr.redface2` ; le label diffère par build de track. Le binaire open-testing **n'est pas promu** vers prod (prod = build neuf plus tard) → graver « β » dans le label beta ne « fuit » pas en prod.
- Sur **F-Droid**, beta (`.beta`) et dev (`.dev`) sont **2 apps distinctes coexistantes**. À la sortie de prod, F-Droid prod = `fr.forumhfr.redface2` (base) : les utilisateurs F-Droid de `.beta` **ne migrent pas automatiquement** vers le package base (appId différent) — migration manuelle à la 1.0 (tradeoff standard des beta-apps F-Droid, à documenter dans `installation.md`).

## 4. Mécanisme de build (`app/build.gradle.kts`)

- **Label** = propriété Gradle injectée au build, source unique :
  ```kotlin
  // defaultConfig
  manifestPlaceholders["appLabel"] =
      providers.gradleProperty("appLabel").orNull ?: "@string/app_name"
  ```
  Le `debug` buildType continue de forcer « Redface 2 ADB » + `.debug` (dogfood adb local inchangé).
- **applicationId** = via flavors (dimension `channel`), **suffixe seulement** (on **retire les `manifestPlaceholders["appLabel"]` par flavor** — le label vient désormais de la propriété, source unique) :
  - `prod` : pas de suffixe → `fr.forumhfr.redface2` (artefact **Play**).
  - `beta` : `applicationIdSuffix = ".beta"` (artefact **F-Droid** beta).
  - `dev`  : `applicationIdSuffix = ".dev"` (artefact **F-Droid** dev).
- Conséquence : un canal produit **2 artefacts** (appId différent) → un build **prod** (base, pour Play) + un build du **flavor du canal** (suffixé, pour F-Droid), tous deux avec le même `-PappLabel`.

## 5. CD `redface2/.github/workflows/release.yml`

`resolve-target` (sorties par canal) :
- `channel` : beta | dev | prod
- `app_label` : « Redface 2 β » | « Redface 2 dev » | « Redface 2 »
- `play_package` : **toujours** `fr.forumhfr.redface2`
- `play_track` : beta | internal | production
- `play_status` : completed | completed | draft
- `fdroid_flavor` : Beta | Dev | Prod  (flavor Gradle pour l'APK F-Droid)
- `fdroid_package` : `fr.forumhfr.redface2.beta` | `.dev` | base
- `fdroid_channel` : beta | dev | none (none possible si on veut couper F-Droid sur un canal)
- `build_ref`, `release_tag`, `is_release_event`, gate prod (inchangés)

Job `build` (par canal) :
1. **Artefact Play** : `./gradlew :app:bundleProdRelease -PappLabel="$app_label"` → AAB `fr.forumhfr.redface2` + label canal → upload `r0adkll/upload-google-play` (packageName=`play_package`, track=`play_track`, status=`play_status`).
2. **Artefact F-Droid** : `./gradlew :app:assemble${fdroid_flavor}Release -PappLabel="$app_label"` → APK `fdroid_package` + label canal.
3. **Attach** à la Release GitHub : l'AAB+APK Play **et** l'APK F-Droid (suffixé), noms distincts :
   - Play : `redface2-<channel>-v<code>-<sha>.{aab,apk}` (appId base)
   - F-Droid : `redface2-fdroid-<channel>-v<code>-<sha>.apk` (appId suffixé)
4. Garde-fous conservés de la chaîne actuelle : signature vérifiée, `always()`+staged guard sur l'attach (recovery si Play échoue), flag prerelease préservé pour beta, gate prod en job dédié.

`notify-fdroid` — **fix du if-gotcha** (le job `await-prod-approval` skippé empoisonne le `success()` implicite via `build`) :
```yaml
if: ${{ always()
     && needs.resolve-target.result == 'success'
     && needs.build.result == 'success'
     && needs.resolve-target.outputs.fdroid_channel != 'none' }}
```
Payload `repository_dispatch` (déjà supporté côté redface2-fdroid) : `channel`, `package_name` = `fdroid_package`, `tag`, `apk_filename` = l'APK **F-Droid** (suffixé), `version_code`.

## 6. F-Droid `redface2-fdroid/.github/workflows/publish.yml`

- Le dispatch fournit `package_name` + `apk_filename` (APK suffixé) → `publish.yml` télécharge cet APK depuis la Release redface2 puis `fdroid update` (qui lit l'appId **depuis l'APK**, donc indexe correctement 2 packages dans le même dépôt).
- **Ajouter les metadata** des 2 nouveaux packages (Name = label visible côté F-Droid) :
  - `metadata/fr.forumhfr.redface2.beta.yml` → `Name: Redface 2 β`
  - `metadata/fr.forumhfr.redface2.dev.yml` → `Name: Redface 2 dev`
  - (conserver `fr.forumhfr.redface2.yml` pour le prod futur)
- Le dépôt F-Droid existant (gh-pages) marche déjà (index + APK v58→v68). Il passera de 1 package à 3 packages (base historique + `.beta` + `.dev`).
- ⚠️ **F-Droid affiche le `Name` de la metadata**, pas le label de l'APK → le « β/dev » visible côté F-Droid vient de `metadata.Name`, pas de `appLabel`. Cohérent : on met les deux en phase.

## 7. versionCode

- **F-Droid** : chaque `applicationId` (`.beta`, `.dev`, base) a son **propre** espace de versionCode → pas de collision inter-canaux. Builds successifs d'un même canal = versionCode croissant (le bump release suffit).
- **Play** (base appId, beta + dev = tracks de la même fiche) : **espace de versionCode partagé**. Un build dev (internal) et un build beta (open testing) ne peuvent pas avoir le **même** versionCode. La beta prend le versionCode de release ; un build dev doit en prendre un **distinct et croissant**.
  - ⚠️ **Question ouverte (cf. §10)** : un offset run_number pour dev est **incompatible** avec un appId unique (Play exige des codes croissants ; un code dev élevé bloquerait les beta/prod suivantes). Option pragmatique : dev sur Play reste **manuel/occasionnel** avec un versionCode bumpé exprès, ou on **retire dev de Play** (dev = F-Droid `.dev` + sideload uniquement), F-Droid n'ayant pas ce souci.

## 8. Prod différé

- Le chemin prod (Release stable → gate `production` → track production + F-Droid base) reste **codé** dans `resolve-target`/`build`/`notify-fdroid` mais **non déclenché** (aucune Release stable publiée jusqu'à la sortie de beta).
- À la 1.0 : publier une Release stable → Play production (base appId) + F-Droid package base. Les testeurs F-Droid `.beta` migrent manuellement vers le package base (à documenter).

## 9. Impact migration (état actuel)

- 0.4.0 (v72) est **déjà sur l'open testing** de `fr.forumhfr.redface2` (build prod, label « Redface 2 » neutre — état post-#266). Au prochain build beta de cette CD, l'open testing recevra le **même appId** avec le label **« Redface 2 β »** → pas de rupture pour les testeurs Play (même app, label mis à jour).
- Le dépôt F-Droid est bloqué à v68 (notify-fdroid jamais exécuté). Le fix §5 + les builds `.beta`/`.dev` le débloquent.
- Libellés in-app : restent **neutres** (#266) ; le canal est porté par le **label de lanceur** (Play) et le **Name** F-Droid, pas par les menus internes (sinon incohérence sur un binaire potentiellement promu).

## 10. Questions ouvertes pour Codex

1. **dev sur Play** : vaut-il le coût du versionCode partagé (base appId), ou vaut-il mieux **dev = F-Droid `.dev` + sideload uniquement** (et Play = beta only en pré-1.0) ? Recommandation provisoire : retirer dev de Play, le garder F-Droid+sideload.
2. **Double build par canal** (Play base + F-Droid suffixé) : acceptable en temps CI, ou faut-il une matrice/parallélisation ? Risque de divergence entre les 2 artefacts (même code, appId/label seuls diffèrent — a priori OK).
3. **Label via propriété vs flavor** : la propriété `-PappLabel` est-elle la bonne source unique, ou garder un défaut par flavor (robustesse si build manuel sans la propriété) ? Proposition : propriété prioritaire, fallback `@string/app_name`.
4. **Reproducibilité F-Droid** : notre dépôt publie des **APK prébuildés signés** (pas de build-from-source côté F-Droid). OK pour un dépôt privé ; à acter si on vise un jour le dépôt F-Droid officiel.
5. **Migration .beta → base à la 1.0** : confirmer le tradeoff (pas d'auto-update cross-appId) et le documenter, ou prévoir une stratégie (ex. garder F-Droid base dès la beta et n'utiliser `.dev` que pour dev ?).
6. **Cohérence label Play vs Name F-Droid** : « Redface 2 β » (label APK Play) vs `Name: Redface 2 β` (metadata F-Droid) — garder strictement identiques.

## 11. Récap des changements

- `app/build.gradle.kts` : label via `-PappLabel` (defaultConfig), retrait des labels par flavor, flavors = suffixe appId seul.
- `release.yml` : `resolve-target` (sorties label/flavor/package/track par canal), `build` double-artefact (Play base + F-Droid suffixé) + attach, fix `if` `notify-fdroid`.
- `redface2-fdroid/publish.yml` : metadata `.beta`/`.dev`, multi-package.
- `docs/guides/release.md` + `installation.md` : 1 fiche Play (label par canal) + F-Droid 2 apps coexistantes + migration 1.0.
- Prod : code conservé, non déclenché.

---

## 12. Révision 2 — corrections suite review Codex gpt-5.5 xhigh

Codex valide le fond (Play 1-app/tracks ; F-Droid packages distincts pour coexistence ; artefacts Play-base vs F-Droid-suffixé). **Deux trous corrigés** + décisions actées :

### Fix 1 — label : garder les defaults par flavor (ne PAS les retirer)
Le §4 disait « retirer les labels par flavor, source unique = propriété ». **Faux/fragile** : un build manuel `assembleBetaRelease` sans `-PappLabel` produirait une app `.beta` au label neutre. Correction :
- **Defaults par flavor conservés** : prod → `@string/app_name`, beta → « Redface 2 β », dev → « Redface 2 dev ».
- **Propriété CI `-PappLabel` prioritaire** (override), nécessaire pour le build Play (flavor `prod` base + label « β »). Logique :
  ```kotlin
  val cliLabel = providers.gradleProperty("appLabel").orNull
  // defaultConfig : manifestPlaceholders["appLabel"] = cliLabel ?: "@string/app_name"
  // beta flavor   : manifestPlaceholders["appLabel"] = cliLabel ?: "Redface 2 β"
  // dev flavor    : manifestPlaceholders["appLabel"] = cliLabel ?: "Redface 2 dev"
  ```
- **Check CI obligatoire** : après build, vérifier le `package` + le `application-label` de l'APK produit (via `aapt dump badging`) contre l'attendu du canal. Garde-fou contre un mauvais appId/label poussé sur le mauvais store.

### Fix 2 — source d'artefact pour le canal **dev** (le vrai trou)
Le publisher F-Droid (`redface2-fdroid/publish.yml`) télécharge l'APK **depuis une GitHub Release** (tag + apk_filename). Or **dev est déclenché par `workflow_dispatch` → pas de Release/tag** → F-Droid ne peut pas servir dev avec le mécanisme actuel. Et dev sur **Play** partage l'espace versionCode de la base appId (risqué). **Décision (reco Codex + à valider @XaaT) :**

> **dev = artefact Actions (sideload) uniquement** pour l'instant. **Pas de dev sur Play, pas de dev sur F-Droid.** Le canal public = **beta** (Play open testing + F-Droid `.beta`). Le dogfood dev reste le build `.debug` adb + l'APK `.dev` téléchargeable depuis les artefacts du run dispatch.

Si on veut **plus tard** dev sur F-Droid : introduire un **schéma de Release/tag dédié `app-dev-v<N>`** (prerelease) pour que le publisher ait une source — pas un dispatch.

### Conséquences sur le design
- **§3 schéma** : ligne `dev → Play (internal)` **supprimée** ; ligne `dev → F-Droid (.dev)` **supprimée** (→ artefact Actions seulement). Le flavor `dev` + son label restent (sideload local).
- **§7 versionCode** : le problème « espace partagé Play beta/dev » **disparaît** (seule la beta pousse sur la base appId ; un seul flux de versionCode côté Play). ✅
- **Internal track name** (Codex : la doc API Play nomme l'internal track `qa` par défaut) : **sans objet** puisqu'on retire dev de Play. À re-vérifier seulement si on réactive dev Play un jour.
- **§8 prod** : garder dormant **+ gate fort** — rien ne pousse prod tant qu'une Release stable n'est pas volontairement publiée (déjà le cas : gate Environment `production` + déclencheur stable-only).

### Schéma cible révisé (pré-1.0)
| Canal | Déclencheur | Play | F-Droid |
|---|---|---|---|
| **beta** | Release *pre-release* `app-v<N>` | `fr.forumhfr.redface2`, label « Redface 2 β », track **beta** | package `fr.forumhfr.redface2.beta` « Redface 2 β » |
| **dev** | `workflow_dispatch` | — (rien) | — (rien) → **artefact Actions / sideload** |
| **prod** *(différé)* | Release stable | production (gate) | package base |

### Reste à trancher avec @XaaT
**dev = sideload-only (reco)** OU **dev sur F-Droid via un schéma de Release/tag `app-dev-v<N>`** (plus de cérémonie, mais dev devient installable F-Droid en « Redface 2 dev »).
