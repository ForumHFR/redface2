---
layout: default
title: Proxy utilisateur
parent: Guides
nav_order: 7
permalink: /guides/proxy
---

# Proxy utilisateur

Redface 2 peut router son trafic HFR via un proxy configuré dans l'app. Le réglage est prévu pour les réseaux contraints : accès depuis l'étranger, proxy d'entreprise, proxy maison, ou tunnel déjà géré par l'utilisateur.

## Scope Phase 2H

Le MVP supporte un proxy **HTTP** classique :

- hôte + port ;
- authentification HTTP Basic optionnelle ;
- requêtes HTTP et HTTPS HFR via le même proxy (HTTPS passe par `CONNECT`) ;
- OkHttp et Coil partagent le même client réseau, mais **seuls les hosts HFR (`hardware.fr` / `*.hardware.fr`) passent par le proxy** ;
- les images externes `[img]` (rehost.diberie.com, super-h.fr, imgur, etc.) restent en direct, même quand le proxy est activé ;
- application du changement après redémarrage de l'app.

Sont volontairement hors scope : proxy embarqué, SOCKS, PAC, bypass list configurable, VPN/Tor intégré. Le mode « proxy pour tout le trafic image externe » est aussi hors scope du MVP — il pourrait devenir une option future si un besoin réel apparaît.

## Configuration dans l'app

1. Ouvrir l'onglet **Messages**.
2. Aller dans **Paramètres alpha**.
3. Activer le proxy.
4. Renseigner l'hôte et le port.
5. Renseigner utilisateur/mot de passe uniquement si le proxy les exige.
6. Enregistrer puis redémarrer l'app.

Exemples :

| Cas | Hôte | Port | Auth |
|---|---|---|---|
| Proxy local | `127.0.0.1` | `8080` | vide |
| Proxy LAN | `proxy.home.lan` | `3128` | vide |
| Proxy authentifié | `proxy.example.net` | `8080` | utilisateur + mot de passe |

Ne pas inclure `http://`, `https://` ou `user:pass@` dans le champ hôte. Les identifiants ont leurs champs dédiés.

## Dépannage

| Symptôme | Cause probable | Action |
|---|---|---|
| Plus aucune page HFR ne charge | Hôte/port incorrects ou proxy éteint | Désactiver le proxy, enregistrer, redémarrer |
| Erreur uniquement sur HTTPS | Proxy d'entreprise avec interception TLS | Installer le certificat utilisateur Android si vous faites confiance à ce proxy |
| Smileys / assets HFR ne chargent pas (`forum-images.hardware.fr`) | Le proxy bloque les sous-domaines HFR | Vérifier que le proxy autorise `*.hardware.fr` ; logs côté proxy |
| Images externes `[img]` ne chargent pas | Hors scope proxy HFR-only : la requête part en direct, le problème vient du device | Vérifier la connexion directe, DNS, certificat HTTPS du device sur le host concerné |
| Auth proxy refusée | Identifiants invalides ou type d'auth non Basic | Corriger les identifiants ; NTLM/Kerberos hors scope |

Les credentials proxy ne doivent pas apparaître dans `DiagnosticsLog`. Si un diagnostic les contient, ouvrir une issue de sécurité sur le repo.

Leur stockage local suit la même politique que les cookies HFR : DataStore non chiffré + protection FBE Android, formalisée dans l'[ADR-012]({{ site.baseurl }}/adr/012-credentials-proxy).
