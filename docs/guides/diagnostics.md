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
également utile pour comparer les conditions. Le journal est limité à **200 entrées** et conservé en mémoire seulement.

L'en-tête contient la version, le build, le canal, l'API Android, le fabricant et le modèle.
Le bouton Copier ne conserve que les tags exacts `FlagsRefresh`, `HfrCall`, `AppForeground` et
`SmileyPicker` (sélecteur de smileys : ouverture et fermeture, détail, état et hauteur de la feuille,
chargement des mots-clés ; jamais de code de smiley) ;
les autres entrées restent visibles localement, avec leur nombre indiqué en fin d'export.
Le message entier des tags autorisés est copié : chaque nouveau champ doit donc être produit
à partir de valeurs fermées ou numériques, sans donnée personnelle ni message d'exception.

## Contexte au début du refresh

Les paires ci-dessous figurent sur la ligne `FlagsRefresh … begin` et sur sa synthèse finale.
Le contexte réseau, les économiseurs et les durées `uptimeS`/`foregroundS` proviennent du **même snapshot
immuable**, pris dans `begin` : une copie différée ne relit pas ces valeurs. L'état premier plan final
et les événements survenus pendant le refresh sont décrits séparément.

| Paire | Valeurs et sens |
|---|---|
| `transport=` | `wifi`, `cellular`, `ethernet`, `vpn`, leurs combinaisons avec `+`, `none` ou `unknown` |
| `metered=` | `1` si `NET_CAPABILITY_NOT_METERED` est absente, `0` si elle est présente, `?` sans capacités |
| `validated=` | `1` si `NET_CAPABILITY_VALIDATED` est présente, `0` si elle est absente, `?` sans capacités |
| `privateDns=` | `1` ou `0` selon `LinkProperties.isPrivateDnsActive`, `?` sans propriétés disponibles |
| `uptimeS=` | Secondes entières depuis le boot, horloge Android `SystemClock.elapsedRealtimeNanos` (veille incluse) |
| `foregroundS=` | Secondes entières depuis le dernier premier plan, `?` si aucun premier plan n'a été observé |
| `dataSaver=` | `disabled`, `whitelisted`, `enabled` selon `ConnectivityManager.RESTRICT_BACKGROUND_STATUS`, ou `unknown` |
| `batterySaver=` | `1` ou `0` selon `PowerManager.isPowerSaveMode`, `?` si indisponible |
| `foregroundStart=` | `foreground`, `background` ou `unknown` selon le dernier événement du cycle de vie observé |

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
foregroundStart=foreground dataSaver=disabled batterySaver=0 transport=vpn+wifi metered=0 validated=1 privateDns=1 uptimeS=106 foregroundS=5
```

## Événements pendant le refresh

La synthèse finale ajoute `foregroundEnd=foreground|background|unknown` et
`foregroundTransitions=[270:background;2550:foreground]`. Les temps sont des millisecondes monotones
relatives au début de l'observation dans `begin`, avec l'horloge Android de boot (veille incluse).
Ils ne sont pas relatifs au déclenchement utilisateur antérieur à l'admission du refresh.
Les répétitions sans changement d'état ne créent pas de transition. Au plus 16 transitions sont
conservées ; `foregroundOmitted` compte les suivantes. `unknown` signifie qu'aucun état du cycle
de vie n'a encore été observé, sans supposer que l'application était au premier plan.

`networkEvents=[280:lost transport=wifi validated=1;900:available transport=unknown validated=?;…]`
contient au plus **16 événements réseau par refresh** ; `networkEventsOmitted` compte les événements omis.
Les valeurs fermées des événements sont `available`, `lost`, `validated` et `transport`.
Chaque événement fournit le transport et la validation connus à cet instant, sans identifiant réseau.
La première réception des capacités après `available` peut produire `validated` et `transport` :
elle remplace des valeurs inconnues. Une notification de capacités identiques n'ajoute aucun événement.
`lost` décrit les dernières capacités connues du réseau par défaut perdu ; il ne déduit pas l'état d'un autre réseau.

Un seul rappel `registerDefaultNetworkCallback` est partagé entre les refresh concurrents.
Le premier refresh s'abonne et le dernier se désabonne ; la fermeture de chaque trace passe par les
`finally` existants, y compris après annulation ou exception. Les callbacks tardifs d'un ancien
abonnement sont ignorés. Les changements de capacités utilisent exclusivement les valeurs fournies
au callback, sans relire `activeNetwork` ou `getNetworkCapabilities` depuis celui-ci.
L'absence d'événement ne prouve pas que l'observation système était disponible.
Les événements restent dans la synthèse `FlagsRefresh`, sans nouveau tag d'export.

## Connexions tentées, errno et intention des appels

La synthèse distingue `calls` (appels OkHttp), `callsWithConnectAttempts` (appels ayant tenté au moins
une connexion) et `connectAttempts` (départs de connexion, dont les routes internes multiples).
`connectFailures` compte les tentatives terminées par `connectFailed` ; `connectAbandoned` compte
les tentatives encore ouvertes lors de l'arrêt de leur mesure. Un appel réutilisant une connexion
peut n'avoir aucune tentative. Les appels partagés sont dédupliqués par leur identité de mesure.
Ces compteurs n'affirment pas qu'une reprise applicative a été exécutée.

`connectErrnos=[refused:1;unreachable:2;network-down:1;timeout:3;other:1]` agrège les catégories des
callbacks `connectFailed`, dans chaque `HfrCall` et dans la synthèse du refresh, même si l'appel finit
par réussir sur une autre route. Les catégories absentes ne sont pas affichées. La chaîne de causes
est limitée à huit exceptions : errno Android typée, `SocketTimeoutException` ou `NoRouteToHostException`.
Sans cause reconnue, la catégorie reste `other`, même si le message mentionne une errno.
Aucun `Throwable.message`, message de fonction système ou texte d'exception n'est lu ou exporté par ce classement.

La liste MP `forum1.php?cat=prive` porte la famille `html:mp-list` (également pour `forum1f.php`).
`intent=mp-badge` identifie exclusivement la relève du badge MP. Une liste ouverte par l'utilisateur
conserve `intent=-` ; l'URL, le compte et les identifiants de conversation ne sont jamais exportés.

## Famille de la connexion acquise

Les lignes `HfrCall` conservent leurs champs et ajoutent **en fin** `ip=v4`, `ip=v6` ou `ip=-`.
La famille vient uniquement de la route de `connectionAcquired`, y compris en réutilisation du pool.
`-` signifie qu'aucune connexion n'a été acquise, ou que sa route n'expose pas de famille reconnue.
Une tentative IPv6 échouée suivie d'une connexion IPv4 acquise donne `ip=v4` ; un `connectEnd`
sans acquisition ne définit pas la famille. En cas d'acquisitions successives dans un appel,
la dernière acquisition observée fournit la famille.
Derrière un proxy configuré sur l'appareil, la famille est celle de la connexion au proxy (proxy HTTP),
ou `-` quand l'adresse cible n'est pas résolue localement (proxy SOCKS) : `-` ne veut alors pas dire
« aucune connexion ».
Le détail reste limité aux appels lents (≥ 1 s), échoués ou annulés, au plus **12 par refresh**,
avec `details-omitted` dans la synthèse. Les agrégats couvrent aussi les appels dont le détail a été omis.

Hors refresh, un échantillon des succès rapides porte `sample=isolated`, au plus **deux dans toute fenêtre
glissante de 60 secondes**. Aucun autre appel HFR observé ni refresh ne doit avoir chevauché leur durée
entière ; un chevauchement commencé après le départ de l'appel l'exclut aussi. Les appels d'images
contournent ces diagnostics et ne participent pas à cette définition de l'isolement.
Les appels lents, échoués et annulés gardent leur politique de détail existante, indépendamment de cet
échantillon. Les détails immédiats hors refresh, échantillons compris, restent plafonnés à 24 par fenêtre
de journalisation de 60 secondes. Aucun de ces compteurs n'augmente la capacité de 200 entrées du journal.

Ces nouveaux champs ne journalisent aucune adresse IP, aucun SSID, nom DNS, opérateur ou identifiant réseau.
`privateDns=1` indique seulement que le DNS privé est actif, sans identifier le résolveur ni
prouver qu'il explique un ralentissement. Le snapshot décrit le réseau au début du refresh,
pas nécessairement celui d'une connexion déjà en pool ou acquise après un changement de réseau.
