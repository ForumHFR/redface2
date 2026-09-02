---
title: Installation
parent: Guides
nav_order: 1
---

# Installer Redface 2

Redface 2 est distribuée sur trois canaux et deux niveaux de fraîcheur : la **bêta** (promue depuis `main`, testée sur le [topic bêta HFR](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35395&page=1)) et le canal **dev** (chaque merge notable sur `dev`, annoncé sur le [topic dev HFR](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35421&page=1)). Les identifiants d'application et les signatures diffèrent selon le canal : le tableau de la fin dit ce qui cohabite et ce qui exige une désinstallation.

## Google Play, bêta ouverte

Canal le plus simple, mises à jour automatiques.

- Fiche : [Redface 2 sur Google Play](https://play.google.com/store/apps/details?id=fr.forumhfr.redface2). L'app est en **test ouvert** : Play peut demander de rejoindre le programme de test avant l'installation.
- Identifiant `fr.forumhfr.redface2`, libellé « Redface 2 β ».
- Signature : **Play App Signing**. Google re-signe l'APK distribué avec sa propre clé ; notre clé d'upload ne sert qu'à envoyer le bundle.

Le canal dev est aussi poussé sur Play, en **test interne** (liste fermée) : pour suivre le dev sans invitation Play, passer par F-Droid.

## F-Droid, bêta et dev

Canal préféré pour rester loin des services Google ou auditer chaque release. Le dépôt publie **deux applications distinctes** :

| Application | Identifiant | Rythme |
|---|---|---|
| **Redface 2 β** | `fr.forumhfr.redface2.beta` | chaque bêta (même version que Play) |
| **Redface 2 dev** | `fr.forumhfr.redface2.dev` | chaque release dev, souvent plusieurs par semaine |

1. Installez un client F-Droid : [F-Droid](https://f-droid.org/), [Neo Store](https://github.com/NeoApplications/Neo-Store), [Droid-ify](https://github.com/Droid-ify/client) ou équivalent.
2. Dans le client, ouvrez **Paramètres → Dépôts → Ajouter un dépôt** et saisissez :

   ```
   URL         : https://forumhfr.github.io/redface2-fdroid/repo
   Fingerprint : B0D265D6783596834715E6AB8C54C4A94A2642F6AD15E279F948A58DF174C8AB
   ```

   Certains clients acceptent l'URL et l'empreinte en un seul lien : `https://forumhfr.github.io/redface2-fdroid/repo?fingerprint=B0D265D6783596834715E6AB8C54C4A94A2642F6AD15E279F948A58DF174C8AB`.

3. Activez le dépôt, puis installez « Redface 2 β », « Redface 2 dev », ou les deux. Les mises à jour arrivent une à deux minutes après chaque publication.

Signature : notre **clé d'upload**, la même pour les deux applications. Les identifiants `.beta` et `.dev` étant distincts de celui de Play, ces apps **cohabitent** avec la version Play et entre elles sur un même appareil.

> L'entrée « Redface 2 » sans suffixe (`fr.forumhfr.redface2`) encore visible dans le dépôt est l'ancien canal alpha, figée en 0.3.x : ne pas l'installer.

## GitHub Releases, sideload

Pour auditer ou tester une version précise sans client tiers. Chaque publication crée une [release `app-v<N>`](https://github.com/ForumHFR/redface2/releases) (`N` = `versionCode`) avec trois artefacts :

- `redface2-<canal>-v<N>-<sha>.apk` : identifiant Play `fr.forumhfr.redface2`, signé avec la clé d'upload. **Incompatible avec l'installation Play** (signature différente pour le même identifiant).
- `redface2-fdroid-<canal>-v<N>-<sha>.apk` : la même app que celle du dépôt F-Droid (`.beta` ou `.dev`), donc interchangeable avec elle.
- `redface2-<canal>-v<N>-<sha>.aab` : le bundle envoyé à Play, pour vérification.

Sur Android, ouvrez l'APK téléchargé et acceptez l'installation depuis une source inconnue si demandé. Pas de mise à jour automatique par ce canal.

## Qui cohabite avec qui

| Installation | Identifiant | Signature | Cohabite avec |
|---|---|---|---|
| Play (bêta ouverte ou test interne) | `fr.forumhfr.redface2` | Google | F-Droid β, F-Droid dev |
| F-Droid « Redface 2 β » ou APK `redface2-fdroid-beta` | `fr.forumhfr.redface2.beta` | clé d'upload | tout le reste |
| F-Droid « Redface 2 dev » ou APK `redface2-fdroid-dev` | `fr.forumhfr.redface2.dev` | clé d'upload | tout le reste |
| APK `redface2-<canal>` d'une release GitHub | `fr.forumhfr.redface2` | clé d'upload | F-Droid β, F-Droid dev ; **pas** avec Play |

Le seul conflit : passer entre Play et l'APK `redface2-<canal>` d'une release, même identifiant mais signatures différentes. Android refuse la mise à jour croisée ; il faut désinstaller l'une avant d'installer l'autre.

## Désinstaller proprement

```
Paramètres Android → Applications → Redface 2 → Désinstaller
```

> **Vos données locales** (cookies de session HFR, préférences, cache Room) sont supprimées à la désinstallation. Il faudra vous reconnecter au forum après la réinstallation. Les préférences ne sont pas partagées entre les apps β et dev.
