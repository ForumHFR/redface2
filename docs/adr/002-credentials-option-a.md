---
title: ADR-002
parent: ADRs
grand_parent: Spécifications
nav_order: 2
permalink: /adr/002-credentials-option-a
---

# ADR-002 — Credentials Option A : DataStore non chiffré, sans password stocké

## Statut

Accepté — 2026-04-19 · amendé 2026-04-27 (alignement avec [#24 thème 13](https://github.com/ForumHFR/redface2/issues/24#issuecomment-3526003625))

## Contexte

L'application doit conserver la session HFR entre deux lancements, sans introduire une stratégie de stockage des credentials inutilement complexe.

Plusieurs options existaient :
- stocker le mot de passe pour relogin transparent
- chiffrer via une couche supplémentaire de type Tink
- utiliser `EncryptedSharedPreferences`
- chiffrer le cookie via une clé AES/GCM Android Keystore
- protéger par biométrie

Le projet n'a besoin que de persister les **cookies de session HFR** (`md_user`, `md_pass`). Pas d'un coffre-fort générique multi-secrets.

## Décision

Redface 2 retient **Option A — minimale** :

- stockage des **cookies de session HFR** uniquement (`md_user`, `md_pass`)
- persistance via **DataStore non chiffré**
- protection au repos assurée par **File-Based Encryption (FBE)** d'Android, active par défaut depuis Android 7+ (minSdk 29 garantit FBE)
- `android:allowBackup="false"` pour exclure les cookies des backups Google Drive
- **pas de Tink**, **pas de clé Android Keystore custom**, **pas de password stocké**, **pas de relogin transparent**, **pas de biométrie** dans le scope v1

Quand la session expire, l'utilisateur repasse par l'écran de login et ressaisit son mot de passe.

## Conséquences

- ~60 lignes de plumberie crypto en moins (pas de `Cipher`, pas de `KeyGenParameterSpec`, pas de gestion IV/Base64)
- 0 dépendance crypto custom
- pas de gestion du cas "clé Keystore invalidée" (rotation système, restauration depuis backup, perte StrongBox) — il n'y a aucune clé custom à invalider
- l'expérience utilisateur accepte un relogin manuel à l'expiration de session, en échange d'une stratégie plus simple et plus lisible

## Threat model retenu

| Menace | Couverture FBE + sandbox |
|---|---|
| Application tierce sur device non-rooté | ✅ sandbox `/data/data/<pkg>` |
| `adb run-as` sur device locké en release build | ✅ non-debuggable + FBE chiffre tant que device locké |
| Backup Google Drive | ✅ `allowBackup="false"` |
| Forensic flash mémoire device locké | ✅ FBE (clé dérivée du PIN/pattern) |
| Device rooté avec accès root runtime | ❌ non couvert (mais ajouter du Keystore ne change pas grand-chose : un attaquant runtime voit aussi le cookie en mémoire) |
| Adversaire passif sur le wire | ❌ HFR n'utilise pas TLS pour tous les sous-domaines historiquement — hors scope client (HTTPS bout-en-bout reste forcé côté `:core:network`) |

Le **password** transitant en clair dans le POST `login_validation.php` (HFR ne supporte pas le hash côté client), tout chiffrement local du cookie est **redondant face à un attaquant runtime**, qui verrait de toute façon le password lors du prochain login.

## Alternatives considérées

- **Password stocké pour relogin transparent** : plus confortable, mais beaucoup plus sensible. Augmente la surface d'attaque sans gain proportionné (sessions HFR durent ~1 an).
- **DataStore + Tink + Keystore** : trop lourd pour un seul secret. Tink brille avec rotation multi-keyset et AEAD streaming, aucun n'est utile ici.
- **DataStore + clé AES/GCM Keystore custom** : ajoute ~150 lignes de plumberie pour une protection marginale (couvre uniquement device rooté + forensic, pas le cas password en clair).
- **EncryptedSharedPreferences** : API dépréciée depuis `security-crypto 1.1.0-beta01` (04/06/2025), signal faible pour un projet neuf.
- **Biométrie v1** : complexité UX et technique disproportionnée pour un forum.
