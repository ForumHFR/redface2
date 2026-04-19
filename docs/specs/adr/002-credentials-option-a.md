---
title: ADR-002
parent: ADRs
grand_parent: Spécifications
nav_order: 2
permalink: /specs/adr/002-credentials-option-a
---

# ADR-002 — Credentials Option A : DataStore + Keystore, sans password stocké

## Statut

Accepté — 2026-04-19

## Contexte

L'application doit conserver la session HFR entre deux lancements, sans introduire une stratégie de stockage des credentials inutilement complexe.

Plusieurs options existaient :
- stocker le mot de passe pour relogin transparent
- chiffrer via une couche supplémentaire de type Tink
- utiliser `EncryptedSharedPreferences`
- protéger par biométrie

Le projet n'a besoin que de persister les **cookies de session HFR**. Pas d'un coffre-fort générique multi-secrets.

## Décision

Redface 2 retient **Option A** :

- stockage des **cookies de session HFR** uniquement
- persistance via **DataStore**
- chiffrement avec clé **AES/GCM** dans **Android Keystore**
- **pas de Tink**
- **pas de password stocké**
- **pas de relogin transparent**
- **pas de biométrie** dans le scope v1

Quand la session expire, l'utilisateur repasse par l'écran de login.

## Conséquences

- surface d'attaque et complexité réduites
- moins de dépendances et moins de maintenance
- pas de gestion du cas "clé Keystore invalidée + password absent" : on redemande simplement le login
- l'expérience utilisateur accepte un relogin manuel en échange d'une stratégie plus simple et plus lisible

## Alternatives considérées

- **Password stocké pour relogin transparent** : plus confortable, mais beaucoup plus sensible
- **DataStore + Tink + Keystore** : trop lourd pour un seul secret
- **EncryptedSharedPreferences** : API dépréciée, signal faible pour un projet neuf
- **Biométrie v1** : complexité UX et technique disproportionnée pour un forum
