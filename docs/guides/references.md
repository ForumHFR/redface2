---
title: Références écosystème HFR
parent: Guides
nav_order: 4
permalink: /guides/references
---

# Références écosystème HFR
{: .fs-8 }

Inventaire des clients tiers, des documentations historiques et de l'outillage compagnon utiles à la conception et au reality-check de Redface 2.
{: .fs-5 .fw-300 }

---

## Pourquoi cette page

Redface 2 n'est pas le premier client HFR. Un inventaire des codebases existantes (Android, iOS, web, autres plateformes) et de la documentation MesDiscussions historique sert à :

- comprendre les choix d'architecture qui ont marché ou échoué ;
- récupérer des fixtures HTML, des sélecteurs CSS, des edge cases documentés par d'autres ;
- éviter de réinventer la roue (parsing BBCode, session management, MP) ;
- référencer les APIs non-documentées officiellement (SDK MesDiscussions sur Wayback Machine).

Tous les clients listés sont des sources d'**inspiration et de compréhension du protocole HFR**, pas une banque de code à emprunter — voir [§ Licences](#licences--point-dattention).

---

## Documentation MesDiscussions

HFR (`forum.hardware.fr`) tourne sur le moteur **MesDiscussions.net (MD)**. Une documentation officielle existait, archivée sur Wayback Machine. Tous les liens ont été revérifiés et répondent en `HTTP 200`.

### Point d'entrée

Page d'index publique listant les 4 docs : [`mesdiscussions.net/documentation.php`](https://web.archive.org/web/20110102072928/http://www.mesdiscussions.net/documentation.php) (snapshot 2011-01-02).

### Les 4 sections archivées

| Section | URL racine | Format | Snapshots disponibles |
|---|---|---|---|
| **Utilisateur** | [`mesdiscussions.net/doc/user/html/`](https://web.archive.org/web/20110102072928/http://www.mesdiscussions.net/doc/user/html/) | DocBook (`ch01.html` → `ch06s07.html+`) | 2006-2011 |
| **Modérateur** | [`mesdiscussions.net/doc/modo/html/`](https://web.archive.org/web/20110102072928/http://www.mesdiscussions.net/doc/modo/html/) | DocBook + variants `ch1.php` → `ch2_3.php` | 2006-2011 |
| **Administrateur** | [`mesdiscussions.net/doc/admin/html/`](https://web.archive.org/web/20110102072928/http://www.mesdiscussions.net/doc/admin/html/) | DocBook (`ch01.html` → `ch07.html+`) | 2007-2011 |
| **SDK** | [`mesdiscussions.net/forum/sdk_documentation/html/`](https://web.archive.org/web/20110102155330/http://www.mesdiscussions.net/forum/sdk_documentation/html/index.html) | phpDocumentor v1.3.1 | 2007-2011 |

Topic d'annonce d'origine (2007) : [« Documentations & nouveautés »](https://web.archive.org/web/20071014014452/http://www.mesdiscussions.net/forum/md/Blog/Annonces-officielles/documentations-nouveautes-sujet_7_1.htm).

### Index SDK (phpDocumentor)

Pages utiles pour naviguer la doc SDK :

- [Index](https://web.archive.org/web/20070630112138/http://www.mesdiscussions.net:80/forum/sdk_documentation/html/index.html)
- [Class tree](https://web.archive.org/web/20070702163551/http://www.mesdiscussions.net:80/forum/sdk_documentation/html/classtrees_SDK.html)
- [Element index](https://web.archive.org/web/20070702163429/http://www.mesdiscussions.net:80/forum/sdk_documentation/html/elementindex_SDK.html)
- [All elements](https://web.archive.org/web/20070702162016/http://www.mesdiscussions.net:80/forum/sdk_documentation/html/elementindex.html)
- [Package list](https://web.archive.org/web/20070702161611/http://www.mesdiscussions.net:80/forum/sdk_documentation/html/li_SDK.html)

Dans les snapshots disponibles, seule la classe `md_search` apparaît documentée publiquement. Pages individuelles fréquemment citées dans [`docs/specs/protocol-hfr.md`]({{ site.baseurl }}/specs/protocol-hfr) :

- [`common_func_url.php`](https://web.archive.org/web/20110102155330/http://www.mesdiscussions.net/forum/sdk_documentation/html/SDK/_common_func_url.php.html) — URL builders
- [`common_func.php`](https://web.archive.org/web/20110102155330/http://www.mesdiscussions.net/forum/sdk_documentation/html/SDK/_common_func.php.html) — helpers communs
- [`search_engine_api.php`](https://web.archive.org/web/20110102155330/http://www.mesdiscussions.net/forum/sdk_documentation/html/SDK/_search_engine_api.php.html) — API recherche
- [`md_search` class](https://web.archive.org/web/20110102155330/http://www.mesdiscussions.net/forum/sdk_documentation/html/SDK/md_search.html) — classe de recherche

### Doc V1 de l'API REST MesDiscussions

Une documentation Confluence de l'API REST V1 a été retrouvée sur Wayback Machine et a servi de base à [ADR-003]({{ site.baseurl }}/adr/003-api-rest-hfr-hybride) :

- [`help.mesdiscussions.net/pages/viewpage.action?pageId=5013586`](https://web.archive.org/web/2018/help.mesdiscussions.net/pages/viewpage.action?pageId=5013586) (snapshot 2018)

### Topic d'aide HFR

Pas trouvé via les sondages publics (`/aide.php`, `/charte.html`, `/faq.php` → 404 ; `/smilies.php` existe mais ne pointe vers aucune documentation centrale). L'aide HFR semble distribuée sous forme de **topics épinglés par sous-forum** plutôt qu'en une page centralisée. Nécessite une recherche interne authentifiée (via `hfr-mcp` par exemple) pour les retrouver. Cf. [§ TODO communautaires](#todo-communautaires).

---

## Clients Android

| Repo | Auteur | Langage | Dernier push | ⭐ | Licence | Notes |
|---|---|---|---|---|---|---|
| [ToYonos/hfr4droid](https://github.com/ToYonos/hfr4droid) | ToYonos | Java | 2015-04-24 | 7 | aucune | Client historique. Homepage = topic HFR `sujet_21748_1`. |
| [Ayuget/HFR4droid](https://github.com/Ayuget/HFR4droid) | Ayuget | Java | 2014-09-08 | 2 | aucune | Fork/variante d'Ayuget avant Redface v1. |
| [ForumHFR/Redface](https://github.com/ForumHFR/Redface) | Ayuget / ForumHFR | Java | 2026-03-30 | 43 | **Apache-2.0** | Redface v1, base de référence pour Redface 2. La licence Apache-2.0 a été ajoutée au repo depuis l'inventaire d'origine — code désormais empruntable sous réserve de respecter la licence amont. |

---

## Clients iOS

### Repos GitHub

| Repo | Auteur | Langage | Dernier push | ⭐ | Licence | Notes |
|---|---|---|---|---|---|---|
| [FLKone/HFRplus](https://github.com/FLKone/HFRplus) | FLKone | Objective-C | 2017-12-05 | 16 | aucune | iOS 5+ — origine de la lignée. |
| [FLKone/SuperHFRplus](https://github.com/FLKone/SuperHFRplus) | FLKone | Objective-C | 2021-06-12 | 2 | aucune | iOS 11+, successeur de HFRplus. |
| [ezzz/HFRnow](https://github.com/ezzz/HFRnow) | ezzz | Objective-C | **2026-05-17** | 1 | aucune | Fork de SuperHFRplus, **actif** : 52 263 commits, ciblage iOS 15+ pour v2.0. C'est la branche vivante actuelle côté open source. |
| [Aynolor/SuperHFRplus](https://github.com/Aynolor/SuperHFRplus) | Aynolor | Objective-C | 2023-11-06 | 6 | aucune | Fork intermédiaire entre FLKone et ezzz, 48 238 commits. |
| [feilaoda/HFRplus](https://github.com/feilaoda/HFRplus) | feilaoda | Objective-C | 2013-06-25 | 0 | aucune | Fork ancien de HFRplus, abandonné. |
| [FLKone/HFRrehost](https://github.com/FLKone/HFRrehost) | FLKone | Objective-C | 2015-02-12 | 2 | aucune | App iOS compagnon pour `reho.st` — pas un client forum mais écosystème iOS HFR. |
| [flaiehfr/hfr-rehost](https://github.com/flaiehfr/hfr-rehost) | flaiehfr | Swift | 2021-09-03 | 0 | aucune | Share Extension Swift pour envoyer des images vers Rehost + générer du BBCode. Non distribué sur App Store. |

### Apps App Store

Les 3 apps iOS HFR du store dérivent toutes du même code d'origine (auteur historique FLKone, cf. repos GitHub). Différence = itérations successives.

| App ID | Nom | Statut | Dernière version | Notes |
|---|---|---|---|---|
| **384464712** | **HFR-** | Retirée (annoncée « bientôt retirée ») | 1.10.10 (2010) | Première app iOS HFR, 288 ratings 4.8/5. |
| **781621952** | **HFR+** (ex HFR+REDFACE) | « Version historique », non maintenue | 1.10.12 (2013-12) | 6304 ratings 4.4/5, plus de MAJ depuis ~8 ans. |
| **1303081080** | [**Super HFR+**](https://apps.apple.com/fr/app/super-hfr/id1303081080) | **Live** | 3.2.1 (2025-01-04), iOS 16.6+ | 61 ratings 4.7/5. Publisher App Store actuel ≠ auteur code historique (reprise de mainteneur). |

Topic forum officiel : [`forum.hardware.fr/hfr/apple` topic_id 1711](https://forum.hardware.fr/hfr/apple/unique-super-forum-sujet_1711_199.htm) — a successivement hébergé HFR- → HFR+ → Super HFR+ au fil des renommages.

### Synthèse iOS

- **Auteur code d'origine** : FLKone (GitHub) / thefolken (pseudo HFR) = même personne, publie les 3 apps entre 2010 et 2017.
- **Publisher App Store actuel de Super HFR+** : B.A. (reprise en 2025, aucun repo GitHub public identifié).
- **Mainteneur open source actuel** : ezzz via [`ezzz/HFRnow`](https://github.com/ezzz/HFRnow) — fork 2026 vivant, non distribué sur App Store à notre connaissance.

---

## Autres plateformes

| Repo | Plateforme | Langage | Dernier push | ⭐ | Licence | Notes |
|---|---|---|---|---|---|---|
| [amonchakai/HFR10](https://github.com/amonchakai/HFR10) | BlackBerry 10 | QML | 2017-12-20 | 3 | aucune | App BB10. |
| [tinanigro/HFR10](https://github.com/tinanigro/HFR10) | Windows 8.1 / Windows Phone | C# | 2017-12-20 | 4 | **GPL-3.0** | App Windows, abandonnée. Licence GPL-3.0 compatible avec Redface 2. |

---

## Parsers et libs

| Repo | Langage | Dernier push | ⭐ | Licence | Notes |
|---|---|---|---|---|---|
| [chef-du-quiche/hfr-parser](https://github.com/chef-du-quiche/hfr-parser) | Java | 2015-10-29 | 0 | **MIT** | Utilitaires de parsing HFR — petit (144 commits) mais code lisible, source d'inspiration BBCode. Licence MIT compatible. |

---

## Userscripts et extensions navigateur

Userscripts Greasemonkey et extensions navigateur — pertinents pour Phase 4 (extensions communautaires), cf. [`docs/specs/extensions.md`]({{ site.baseurl }}/specs/extensions).

### Suite DdsT

Six userscripts publiés par DdsT, tous sous AGPL-3.0 :

| Repo | Sujet | Dernier push |
|---|---|---|
| [DdsT/HFR_Chat](https://github.com/DdsT/HFR_Chat) | Modernisation de l'apparence du forum | 2022-02-15 |
| [DdsT/HFR_Live](https://github.com/DdsT/HFR_Live) | Mise à jour temps réel des pages | 2020-03-13 |
| [DdsT/HFR_ColorTag](https://github.com/DdsT/HFR_ColorTag) | Tag coloré des membres | 2022-04-13 |
| [DdsT/HFR_Drapeaux_Persos](https://github.com/DdsT/HFR_Drapeaux_Persos) | Renommage perso des topics suivis | 2020-10-27 |
| [DdsT/HFR_Stats](https://github.com/DdsT/HFR_Stats) | Analyse de l'activité des membres | 2021-04-18 |
| [DdsT/HFR_Filtre_AV](https://github.com/DdsT/HFR_Filtre_AV) | Filtrage des topics d'AV (achat/vente) | 2020-11-07 |

### Autres userscripts et extensions

| Repo | Plateforme | Langage | Dernier push | ⭐ | Licence | Notes |
|---|---|---|---|---|---|---|
| [psykhi/HFRLive](https://github.com/psykhi/HFRLive) | Extension Chrome | JS | 2015-04-12 | 1 | aucune | Refresh live HFR. |
| [Orken/Infinite-Scroll-4-HFR](https://github.com/Orken/Infinite-Scroll-4-HFR) | Userscript | JS | 2014-03-21 | 1 | **MIT** | Infinite scroll dans les topics. |
| [dotvav/hfr-stuff](https://github.com/dotvav/hfr-stuff) | Userscript / outils | JS | 2025-02-06 | 0 | aucune | Outillage divers, plus récemment actif des userscripts non-DdsT. |
| [fonfano/HFR-Direct-to-drapals](https://github.com/fonfano/HFR-Direct-to-drapals) | Userscript | JS | 2023-02-08 | 0 | aucune | Redirige la racine HFR vers la page drapeaux. |
| [ToYonos/HFR-stuff](https://github.com/ToYonos/HFR-stuff) | Outils divers | JS | 2022-12-08 | 0 | **Apache-2.0** | Outillage divers HFR (JavaScript). |

---

## Outillage et écosystème compagnon

Composants maintenus dans l'écosystème Redface 2 (pas des clients HFR mais des dépendances ou outils).

| Repo | Sujet | Langage | Dernier push | Licence | Notes |
|---|---|---|---|---|---|
| [XaaT/hfr-mcp](https://github.com/XaaT/hfr-mcp) | Serveur MCP + CLI pour interagir avec HFR depuis les agents LLM | Go | 2026-05-04 | aucune | Source des fixtures HTML réelles ([`hfr_read … output=path`]({{ site.baseurl }}/guides/contributing#fixtures)), des captures `write_*` Phase 2A, et de la cible des extensions MCP pour writes. |
| [XaaT/hfr-redflag](https://github.com/XaaT/hfr-redflag) | Userscript Greasemonkey + Cloudflare Worker — alertes intelligentes sur topics suivis | JS | 2026-04-06 | **MIT** | Référence pour Phase 4 « Redflag » (cf. roadmap). Worker D1 backend. |
| [XaaT/hfr-redkit](https://github.com/XaaT/hfr-redkit) | Kit commun pour les userscripts HFR red* — UI partagée, config MPStorage, utilitaires | (TBD) | 2026-03-22 | aucune | Dépendance Phase 3 (intégration MPStorage). Cf. issue [#6](https://github.com/ForumHFR/redface2/issues/6). |

---

## Licences — point d'attention

| Statut | Compteur | Conséquences pour Redface 2 (GPL-3.0-only, cf. [ADR-010]({{ site.baseurl }}/adr/010-licence-client-android)) |
|---|---|---|
| Sans `LICENSE` (= tous droits réservés par défaut) | 15 repos | Code non empruntable sans accord écrit. Inspiration et compréhension du protocole HFR uniquement. |
| Apache-2.0 | 3 repos (`ForumHFR/Redface`, `ToYonos/HFR-stuff`, et licence ADR-010) | Compatible avec emprunt sous GPL-3.0. |
| GPL-3.0 | 1 repo (`tinanigro/HFR10`) | Compatible directement. |
| MIT | 3 repos (`chef-du-quiche/hfr-parser`, `Orken/Infinite-Scroll-4-HFR`, `XaaT/hfr-redflag`) | Compatible avec emprunt sous GPL-3.0. |
| AGPL-3.0 | 6 repos (suite DdsT) | Compatible mais contagieuse — réflexion à mener au cas par cas avant d'intégrer du code AGPL dans Redface 2. |

> **Évolution depuis le premier inventaire d'avril 2026** : [`ForumHFR/Redface`](https://github.com/ForumHFR/Redface) a déclaré une `LICENSE` Apache-2.0 dans l'intervalle. Le repo est désormais empruntable, sous réserve de respecter la licence amont.

Tableau récapitulatif **avant tout emprunt** :

- vérifier le `LICENSE` actuel du repo cible (le statut peut bouger, comme l'a fait `ForumHFR/Redface`) ;
- préférer l'inspiration (lire le code, le comprendre, réécrire de zéro en Kotlin Compose) à la copie textuelle ;
- conserver une trace dans le commit / PR concerné si une approche est manifestement reprise d'un client cité ici.

---

## TODO communautaires

Restant à compléter par la communauté ou par des recherches authentifiées :

- **Topic d'aide HFR centralisé** : non trouvé via les sondages publics. Nécessite une recherche interne authentifiée (via `hfr-mcp` `hfr_read`) sur les topics épinglés par sous-forum.
- **Autres apps Android** : exhaustivité inconnue, les 3 listés (`hfr4droid`, `HFR4droid`, `Redface`) sont les seuls trouvés à date.
- **Autres clients Windows / desktop / extensions navigateur** : compléter selon les mémoires de la communauté.
- **Apps Android Play Store historiques** : pas inventorié ici (focus repo GitHub d'abord).

Compléments bienvenus via une PR sur cette page ou un commentaire sur l'issue de référence [#32](https://github.com/ForumHFR/redface2/issues/32).
