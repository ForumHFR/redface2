---
title: Installation
parent: Guides
nav_order: 1
---

# Installer Redface 2

Trois canaux de distribution sont disponibles. Ils utilisent des **clés de signature différentes**, donc l'app installée depuis l'un ne peut pas être mise à jour depuis un autre sans désinstallation préalable. Choisissez votre canal et restez dessus.

## Google Play Store

Canal le plus large, recommandé pour la plupart des utilisateurs.

- Recherchez **« Redface 2 »** dans le Play Store.
- Lien direct : à publier une fois la review Play terminée (l'app est actuellement en alpha closed-testing).
- Mises à jour automatiques.

Signature : générée par Google via le système **Play App Signing** (notre clé d'upload est utilisée pour signer le bundle envoyé à Play, puis Google re-signe l'APK distribué).

## F-Droid (dépôt privé)

Canal préféré pour les utilisateurs qui veulent rester loin des Google Play Services ou auditer chaque release.

1. Installez un client F-Droid : [F-Droid officiel](https://f-droid.org/), [Neo Store](https://github.com/NeoApplications/Neo-Store), [Foxy Droid](https://github.com/kitsunyan/foxy-droid), ou équivalent.
2. Dans le client, ouvrez **Paramètres → Dépôts → Ajouter un dépôt**.
3. Entrez l'URL et le fingerprint suivants :

   ```
   URL         : https://forumhfr.github.io/redface2-fdroid/repo
   Fingerprint : B0D265D6783596834715E6AB8C54C4A94A2642F6AD15E279F948A58DF174C8AB
   ```

   Certains clients permettent de coller l'URL et le fingerprint comme un seul lien : `https://forumhfr.github.io/redface2-fdroid/repo?fingerprint=B0D265D6783596834715E6AB8C54C4A94A2642F6AD15E279F948A58DF174C8AB`.

4. Activez le dépôt. Redface 2 devrait apparaître dans la liste des applications disponibles.
5. Installez. Les mises à jour seront proposées automatiquement à chaque nouvelle release (~1-2 min après publication sur Play).

Signature : notre **clé d'upload d'origine** (la même que celle envoyée à Play avant le re-signing Google).

> **Important** : si vous avez déjà installé Redface 2 via Google Play Store, **désinstallez-la d'abord** avant d'installer via F-Droid. Les deux APKs sont signés par des clés différentes et Android refuse les mises à jour cross-signature.

## GitHub Releases (sideload direct)

Pour un audit ponctuel ou un test d'une version spécifique sans passer par un client tiers.

1. Allez sur la page [Releases du dépôt redface2](https://github.com/ForumHFR/redface2/releases).
2. Choisissez la version souhaitée.
3. Téléchargez le fichier `redface2-vNN-XXXXXXX.apk` (où `NN` est le version code, `XXXXXXX` le commit SHA court).
4. Sur Android, ouvrez le fichier APK téléchargé et acceptez l'installation depuis des sources inconnues si demandé.

Signature : identique à F-Droid (clé d'upload d'origine). Donc un utilisateur peut basculer GitHub Releases ↔ F-Droid sans désinstaller, contrairement à Play.

Pas de mises à jour automatiques par ce canal. Refaire l'opération à chaque nouvelle version.

## Comparaison des canaux

| Canal | Mises à jour auto | Signature | Audit | Recommandation |
|---|---|---|---|---|
| Play Store | ✅ | Google (re-signé) | difficile | grand public |
| F-Droid (dépôt privé) | ✅ | Clé d'upload | facile (HTML statique) | utilisateurs F-Droid / audit |
| GitHub Releases sideload | ❌ | Clé d'upload | facile (hash + signature visibles) | test ponctuel |

## Désinstaller proprement

Si vous voulez changer de canal, désinstallez d'abord :

```
Paramètres Android → Applications → Redface 2 → Désinstaller
```

Ensuite installez via le nouveau canal.

> **Vos données locales** (cookies de session HFR, préférences, cache Room) sont supprimées à la désinstallation. Il faudra vous reconnecter au forum après la réinstallation.
