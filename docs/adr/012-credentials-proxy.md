---
title: ADR-012
parent: ADRs
grand_parent: Spécifications
nav_order: 12
permalink: /adr/012-credentials-proxy
---

# ADR-012 — Credentials proxy : extension d'Option A

## Statut

Accepté — 2026-05-23

## Contexte

La Phase 2H ajoute un réglage proxy utilisateur pour router le trafic HFR via un proxy HTTP configuré localement.

Ce proxy peut exiger une authentification HTTP Basic. Les champs utilisateur et mot de passe proxy deviennent donc un nouveau secret local, distinct des cookies HFR couverts par l'[ADR-002]({{ site.baseurl }}/adr/002-credentials-option-a).

## Décision

Les credentials proxy suivent la même stratégie que les cookies de session HFR :

- persistance via DataStore non chiffré ;
- protection au repos assurée par le sandbox applicatif Android et File-Based Encryption ;
- exclusion des backups via `android:allowBackup="false"` ;
- aucun stockage dans `DiagnosticsLog`, logs réseau ou diagnostics copiables ;
- pas de Tink, Keystore custom ou coffre-fort générique dans le MVP.

Le proxy password n'est utilisé que pour construire l'en-tête `Proxy-Authorization` en mémoire au moment des requêtes.

## Conséquences

- Le réglage reste simple, testable et cohérent avec l'ADR-002.
- Un utilisateur sur device rooté ou compromis peut lire les préférences locales : non couvert.
- Un attaquant runtime capable d'instrumenter l'app verrait aussi le credential au moment où OkHttp l'injecte dans la requête proxy : un chiffrement local n'éliminerait pas cette classe d'attaque.
- Toute évolution vers un stockage chiffré doit être traitée comme un chantier transverse credentials, pas comme une exception propre au proxy.

## Alternatives considérées

- **Tink + Keystore pour le proxy uniquement** : ajoute une deuxième politique de secret incohérente avec les cookies HFR, pour un gain limité face à un attaquant runtime.
- **Ne pas persister le mot de passe proxy** : plus sûr au repos, mais rend le proxy authentifié pénible à chaque lancement et contredit l'objectif du réglage réseau utilisateur.
- **Proxy sans authentification Basic** : trop restrictif ; beaucoup de proxies d'entreprise ou personnels exigent une auth simple.
