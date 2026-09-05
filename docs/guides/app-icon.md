---
title: Icône de l'application
parent: Guides
nav_order: 5
---

# Icône de l'application
{: .fs-8 }

Catalogue et structure des icônes de lancement Android.
{: .fs-5 .fw-300 }

## Icône Classique — drapeau historique

L'icône Classique, activée par défaut, est le **placeholder historique**, dérivé du
drapeau historique HFR (`flag1.gif`, 14 × 11, pixel art teal/rose avec
bordure noire). La galerie propose aussi Redface 1 et trois dessins originaux ;
ces derniers restent à valider visuellement par XaTriX avant leur diffusion en bêta.

La source vit en dehors du repo (`~/Téléchargements/flag1.gif` sur la
machine de dev). Les ressources générées sont dans `app/src/main/res/`.

## Structure de Classique (adaptive icon + legacy fallback)

minSdk 29 ⇒ toutes les cibles supportent les adaptive icons (API 26+). On
fournit quand même les PNG legacy pour les launchers qui n'utilisent pas encore
la structure adaptive.

```
app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml         # solid color #FFFFFF (contraste avec la bordure noire du drapeau)
│   └── ic_launcher_monochrome.xml         # silhouette vectorielle en aplat du drapeau
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml                    # <adaptive-icon> fg+bg+monochrome
│   └── ic_launcher_round.xml              # idem, pour le slot roundIcon
├── mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
│   ├── ic_launcher.png                    # fallback legacy carré (avec bg intégré)
│   ├── ic_launcher_round.png              # fallback legacy (launchers applique un mask rond)
│   └── ic_launcher_foreground.png         # foreground de l'adaptive icon (transparent)
```

`AndroidManifest.xml` :

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round" ... />
```

## Icônes alternatives

Réglages → Affichage → Icône de l’application ouvre une galerie de cinq icônes complètes,
dans une grille défilante à deux colonnes. Le bouton Appliquer reste sous la grille.
Les aperçus chargent leurs ressources adaptatives : le masque est celui du système Android.

| Choix | Alias du manifeste | Composition |
|-------|--------------------|-------------|
| Classique | `.LauncherClassic` | Drapeau RF2 existant, fond `#FFFFFF` ; silhouette vectorielle pour le monochrome |
| Redface 1 | `.LauncherRf1` | Foreground et fond adaptatifs d’origine de Redface 1 (PNG aux cinq densités) |
| Monogramme RF | `.LauncherMonogram` | Lettres RF géométriques blanches, fond rouge `#D32F2F` |
| Discussion | `.LauncherBubbles` | Deux bulles rouges `#D32F2F`, pleine et contour, fond blanc cassé `#FAFAFA` |
| Puce | `.LauncherChip` | Puce rouge `#E53935`, douze broches et carré central blancs, fond anthracite `#263238` |

Les trois créations sont des `VectorDrawable` de 108 × 108 dp, sans police ni dégradé.
Pour chaque nom `monogram`, `bubbles` ou `chip`, `drawable/ic_launcher_<nom>_{background,foreground,monochrome}.xml`
fournit les trois couches ; `mipmap-anydpi-v26/ic_launcher_<nom>.xml` et sa variante `_round.xml`
référencent les mêmes couches, avec `<monochrome>` pour les icônes thématiques Android 13+.

- **Monogramme RF** : deux lettres en chemins, hautes de 40 dp, traits principaux de 9 dp ;
  le R garde une contreforme transparente. Le monochrome conserve uniquement les lettres.
- **Discussion** : bulle pleine en bas à gauche et bulle à contour de 6 dp en haut à droite,
  avec coins arrondis et queues triangulaires. Un espace transparent les sépare aussi en monochrome.
- **Puce** : corps carré de 44 dp à coins arrondis, trois broches de 4 × 10 dp par côté,
  carré central de 12 dp. En monochrome, ce carré devient une découpe transparente dans la puce.

Le rouge `#D32F2F` reprend la valeur de `FlagPalette.Red`
(`core/ui/src/main/kotlin/fr/forumhfr/redface2/core/ui/theme/FlagPalette.kt`), couleur fixe des drapeaux ;
il ne dépend pas de l’accent actif de l’application. Les dessins sont centrés sur (54, 54) :
le monogramme tient dans le cercle sûr de 66 dp ; les bulles et broches restent dans celui de 72 dp.
Les nouveaux dessins ne reprennent aucun logo existant. Aucun PNG legacy supplémentaire n’est nécessaire avec `minSdk 29`.

La sélection reste provisoire jusqu’au bouton **Appliquer**. Le redémarrage suit le schéma
ProcessPhoenix en quatre temps :

1. L’application attend la persistance DataStore et **active l’alias cible** avec `DONT_KILL_APP`,
   sans toucher aux autres : l’ancien alias est encore l’`origActivity` de la tâche courante.
2. Elle termine l’ancienne tâche avec **`finishAffinity()` avant `startActivity()`**, puis démarre
   `LauncherIconRestartActivity` avec `NEW_TASK | CLEAR_TASK`. Cette activité non exportée a une
   affinité vide, le mode `singleInstance`, son propre processus `:launcherIconRestart` et reste
   exclue des applications récentes.
3. L’activité de relance reçoit **le nom de classe de l’alias cible et la route**, pas un `Intent`
   parcelable (pattern `UnsafeIntentLaunch`) : elle valide le nom via `isKnownLauncherAlias` puis
   **reconstruit l’intent localement**. Elle **tue le processus principal**, puis lance l’intent
   `MAIN` / `LAUNCHER` de l’alias cible avec `NEW_TASK`. La nouvelle tâche a ainsi l’alias cible pour `origActivity`.
   L’activité de relance se termine et quitte aussi son propre processus.
4. Au démarrage à froid, la réconciliation de `MainActivity` **désactive les anciens alias** sans
   re-basculer l’état de la cible déjà active. L’extra interne `settings/app-icon`, porté par
   l’intent de lancement, restaure Réglages → Affichage → Icône de l’application via
   `IntentDelivery` dès `onCreate`, sans attendre `onNewIntent`.

Un lancement direct avec `NEW_TASK`, même accompagné de `MULTIPLE_TASK`, ne suffit pas : le mode
`singleTop` de `MainActivity` peut livrer l’intent à l’activité existante. La tâche conserve alors
l’ancien alias comme `origActivity` et le système la ferme lorsque cet alias est désactivé.
Le processus de relance survit à la mort du processus principal pour effectuer un lancement à froid.
Si le passage à l’activité de relance échoue, l’application rétablit Classique avant de retenter
la relance. Le lanceur peut rafraîchir son cache avec quelques secondes de retard.

Chaque alias cible `.MainActivity` et porte son propre filtre `MAIN` / `LAUNCHER`. `MainActivity`
conserve les filtres `VIEW` des deep links HFR. Les alias `.LauncherDark`, `.LauncherRose` et
`.LauncherRed` et leurs ressources restent déclarés pour les installations dev 0.54.0, mais ne sont
plus proposés. Leurs valeurs persistées se lisent comme `CLASSIC`, sans écriture à la lecture.
Au démarrage de `MainActivity`, le contrôleur vérifie les états effectifs des huit composants sur
le dispatcher IO : un ancien alias actif ou l’absence d’alias actif entraîne un retour à Classique
et sa persistance ; les autres écarts suivent la préférence sélectionnable. Ce contrôle partage
un verrou avec Appliquer et devient sans effet une fois les états cohérents.

RF1 fournit une couche `<monochrome>` à partir de son foreground. Classique dispose d’une couche
monochrome vectorielle tracée depuis les pixels opaques du foreground mdpi existant ; ses ressources
couleur sont conservées. Aucun PNG legacy RF1 n’est nécessaire avec `minSdk 29`.
Origine, licence Apache 2.0 et adaptation : [mentions des assets tiers](https://github.com/ForumHFR/redface2/blob/dev/app/THIRD_PARTY_NOTICES.md).

Pour ajouter une icône :

1. fournir son foreground et son fond, avec leurs crédits ;
2. ajouter les adaptive icons normale et ronde, plus la couche monochrome si disponible ;
3. déclarer un `activity-alias` désactivé par défaut dans le manifeste ;
4. étendre `AppLauncherIcon`, `launcherAliasFor` et le mapping de ressources côté `:app` ;
5. vérifier les tests d’exclusivité des alias, de persistance avant application et de restauration.

## Dimensions (108dp canvas adaptive + 48dp legacy)

| Densité | Adaptive foreground | Legacy icon |
|---------|---------------------|-------------|
| mdpi    | 108 × 108           | 48 × 48     |
| hdpi    | 162 × 162           | 72 × 72     |
| xhdpi   | 216 × 216           | 96 × 96     |
| xxhdpi  | 324 × 324           | 144 × 144   |
| xxxhdpi | 432 × 432           | 192 × 192   |

Le drapeau source fait 14 × 11 (ratio 14:11) : on l'upscale en
**nearest-neighbor** (`-filter point`) pour préserver l'esthétique pixel art.
Pour l'adaptive foreground, il occupe 60 % de la largeur du canvas, centré
(bien à l'intérieur de la safe zone circulaire de 66 dp). Pour les icônes
legacy, il occupe 70 % de la largeur sur fond `#FFFFFF`.

## Régénération

Script reproductible avec ImageMagick :

```bash
cd /tmp && rm -rf icon_gen && mkdir -p icon_gen && cd icon_gen
magick ~/Téléchargements/flag1.gif[0] flag.png

# Adaptive foreground (transparent background, pixel art preserved)
for name in "mdpi 108" "hdpi 162" "xhdpi 216" "xxhdpi 324" "xxxhdpi 432"; do
  d=${name%% *}; s=${name##* }
  fw=$((s * 60 / 100)); fh=$((fw * 11 / 14))
  magick -size ${s}x${s} xc:transparent \
    \( flag.png -filter point -resize ${fw}x${fh}\! \) -gravity center -composite \
    foreground_${d}.png
done

# Legacy square (flag on #FFFFFF, pixel art preserved)
for name in "mdpi 48" "hdpi 72" "xhdpi 96" "xxhdpi 144" "xxxhdpi 192"; do
  d=${name%% *}; s=${name##* }
  fw=$((s * 70 / 100)); fh=$((fw * 11 / 14))
  magick -size ${s}x${s} xc:"#FFFFFF" \
    \( flag.png -filter point -resize ${fw}x${fh}\! \) -gravity center -composite \
    legacy_${d}.png
done
```

Puis copier dans `app/src/main/res/mipmap-{densité}/` sous les noms
`ic_launcher_foreground.png` (adaptive) et `ic_launcher.png` +
`ic_launcher_round.png` (legacy).

## Remplacement futur

Quand une identité visuelle dédiée sera disponible :
1. Supprimer les PNG et XML générés depuis `mipmap-*` + `drawable/ic_launcher_background.xml`.
2. Régénérer via Android Studio (`New → Image Asset`) qui produit la même
   structure avec le crop et les densités corrects.
3. Mettre à jour cette page et retirer la mention "placeholder".
