---
title: Icône de l'application
parent: Guides
nav_order: 5
---

# Icône de l'application
{: .fs-8 }

Procédure de génération et structure de l'icône de lancement Android.
{: .fs-5 .fw-300 }

## Statut actuel — placeholder

L'icône actuelle est un **placeholder pour builds internes**, dérivée du
drapeau historique HFR (`flag1.gif`, 14 × 11, pixel art teal/rose avec
bordure noire). Elle sera remplacée par une identité visuelle propre à
Redface 2 avant toute publication publique.

La source vit en dehors du repo (`~/Téléchargements/flag1.gif` sur la
machine de dev). Les ressources générées sont dans `app/src/main/res/`.

## Structure (adaptive icon + legacy fallback)

minSdk 29 ⇒ toutes les cibles supportent les adaptive icons (API 26+). On
fournit quand même les PNG legacy pour les launchers qui n'utilisent pas encore
la structure adaptive.

```
app/src/main/res/
├── drawable/
│   └── ic_launcher_background.xml         # solid color #FFFFFF (contraste avec la bordure noire du drapeau)
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml                    # <adaptive-icon> fg+bg
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
