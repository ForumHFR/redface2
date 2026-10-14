---
layout: default
title: Diagnostics du refresh des drapeaux
parent: Guides
nav_order: 10
permalink: /guides/diagnostics
---

# Diagnostics du refresh des drapeaux

Pour analyser un refresh lent ou bloqué ([#984](https://github.com/ForumHFR/redface2/issues/984)),
ouvrir **menu compte → Diagnostics → Copier** après le refresh. Un export de refresh **rapide** est
également utile pour comparer les conditions. Le journal est borné et conservé en mémoire seulement.

L'en-tête contient la version, le build, le canal, l'API Android, le fabricant et le modèle.
Le bouton Copier ne conserve que les tags exacts `FlagsRefresh`, `HfrCall` et `AppForeground` ;
les autres entrées restent visibles localement, avec leur nombre indiqué en fin d'export.
Le message entier des tags autorisés est copié : chaque nouveau champ doit donc être produit
à partir de valeurs fermées ou numériques, sans donnée personnelle ni message d'exception.

## Contexte au début du refresh

Ces paires sont ajoutées **en fin** de la ligne `FlagsRefresh … begin` et de sa synthèse finale,
après les champs existants. Elles proviennent du **même snapshot immuable**, pris dans `begin` :
la synthèse et une copie différée ne relisent ni le réseau ni les durées.

| Paire | Valeurs et sens |
|---|---|
| `transport=` | `wifi`, `cellular`, `ethernet`, `vpn`, leurs combinaisons avec `+`, `none` ou `unknown` |
| `metered=` | `1` si `NET_CAPABILITY_NOT_METERED` est absente, `0` si elle est présente, `?` sans capacités |
| `validated=` | `1` si `NET_CAPABILITY_VALIDATED` est présente, `0` si elle est absente, `?` sans capacités |
| `privateDns=` | `1` ou `0` selon `LinkProperties.isPrivateDnsActive`, `?` sans propriétés disponibles |
| `uptimeS=` | Secondes entières depuis le boot, horloge Android `SystemClock.elapsedRealtimeNanos` (veille incluse) |
| `foregroundS=` | Secondes entières depuis le dernier premier plan, `?` si aucun premier plan n'a été observé |

L'ordre des transports combinés est `vpn`, `wifi`, `cellular`, `ethernet`.
Seuls les transports exposés par les capacités du réseau actif sont utilisés : un VPN seul reste
`vpn`, un VPN exposant aussi le wifi devient `vpn+wifi`. Aucun porteur sous-jacent n'est déduit.
Un transport actif non reconnu devient `unknown`, jamais `none`.

L'adaptateur Android lit `activeNetwork` une seule fois, puis les capacités et les propriétés
de ce même `Network`. Ce sont des lectures système locales synchrones, sans requête réseau.
Sans réseau actif : `transport=none metered=? validated=? privateDns=?`.
Si le service manque ou qu'une lecture lève une exception :
`transport=unknown metered=? validated=? privateDns=?`, sans exporter l'exception.
La permission normale `ACCESS_NETWORK_STATE` est déclarée dans `:core:network` et fusionnée dans l'app.

Le premier plan est enregistré avant la composition au démarrage froid, puis à chaque vraie reprise.
Une rotation ou un double `onStart` ne remet pas le compteur à zéro. `AppForeground backgroundMs=…`
conserve son format et reste silencieux au démarrage froid ; sa durée utilise aussi l'horloge de boot.

Exemple de suffixe :

```text
transport=vpn+wifi metered=0 validated=1 privateDns=1 uptimeS=106 foregroundS=5
```

## Famille de la connexion acquise

Les lignes `HfrCall` conservent leurs champs et ajoutent **en fin** `ip=v4`, `ip=v6` ou `ip=-`.
La famille vient uniquement de la route de `connectionAcquired`, y compris en réutilisation du pool.
`-` signifie qu'aucune connexion n'a été acquise, ou que sa route n'expose pas de famille reconnue.
Une tentative IPv6 échouée suivie d'une connexion IPv4 acquise donne `ip=v4` ; un `connectEnd`
sans acquisition ne définit pas la famille. En cas d'acquisitions successives dans un appel,
la dernière acquisition observée fournit la famille.
Le détail reste limité aux appels lents (≥ 1 s), échoués ou annulés, au plus 12 par refresh.

Ces nouveaux champs ne journalisent aucune adresse IP, aucun SSID, nom DNS, opérateur ou identifiant réseau.
`privateDns=1` indique seulement que le DNS privé est actif, sans identifier le résolveur ni
prouver qu'il explique un ralentissement. Le contexte décrit le réseau au début du refresh,
pas nécessairement celui d'une connexion déjà en pool ou acquise après un changement de réseau.
