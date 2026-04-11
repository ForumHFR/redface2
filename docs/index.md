---
title: Accueil
nav_order: 1
---

# Redface 2
{: .fs-9 }

Le futur client Android pour Hardware.fr.
{: .fs-6 .fw-300 }

[Voir la stack technique]({% link stack.md %}){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[Voir sur GitHub](https://github.com/ForumHFR/redface2){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Pourquoi une reecriture ?

Redface v1 a rendu service a la communaute HFR pendant des annees. Mais sa stack technique a atteint ses limites :

| | Redface v1 | Redface 2 |
|---|---|---|
| Langage | Java 11 | **Kotlin** |
| UI | XML + ButterKnife | **Jetpack Compose** |
| Reseau | Retrofit 1.9 (!), OkHttp 3 | **OkHttp 4** |
| Async | RxJava 1 | **Coroutines + Flow** |
| Injection | Dagger 2 | **Hilt (KSP)** |
| Event bus | Otto | **StateFlow** |
| minSdk | 16 (Android 4.1, 2012) | **29 (Android 10, 2019)** |

Retrofit 1.9 n'est plus maintenu depuis 2016. RxJava 1 depuis 2018. ButterKnife est officiellement deprecie. Le minSdk 16 empeche d'utiliser les APIs modernes.

**Un refactoring incremental serait plus couteux qu'une reecriture.** Chaque brique depend des autres — migrer Retrofit demande de migrer RxJava, qui demande de migrer les patterns async, qui touche toute l'architecture.

## Vision

Redface 2 est concu pour :

- **La vitesse** — Scroll fluide a 120fps, prefetch intelligent, cache agressif. L'objectif : que le forum semble local.
- **Les features communautaires** — Les meilleurs ajouts des userscripts HFR (alertes qualitay, bookmarks, blacklist, redflag...) integres nativement.
- **La maintenabilite** — Architecture modulaire, testable, ou chaque feature est isolee. Facile a comprendre pour un nouveau contributeur.
- **L'ouverture** — Systeme d'extensions pour que la communaute ajoute ses propres features sans toucher au coeur de l'app.

## Vue d'ensemble

```mermaid
graph TB
    subgraph "Presentation"
        A[Jetpack Compose] --> B[MVI ViewModels]
    end
    subgraph "Domaine"
        B --> C[Repositories]
    end
    subgraph "Donnees"
        C --> D[OkHttp 4 + Jsoup]
        C --> E[Room Cache]
    end
    D --> F["forum.hardware.fr"]
    style A fill:#e74c3c,color:#fff
    style B fill:#e67e22,color:#fff
    style C fill:#f1c40f,color:#000
    style D fill:#2ecc71,color:#fff
    style E fill:#3498db,color:#fff
    style F fill:#95a5a6,color:#fff
```

## Etat du projet

Ce repository est en phase de **specification**. Aucun code n'est encore ecrit. L'objectif est de :

1. Verrouiller les choix techniques avec la communaute
2. Definir l'architecture en detail
3. Planifier les phases de developpement
4. Commencer le dev sur des bases solides

Les contributions aux specs sont les bienvenues — ouvrez une issue ou commentez les existantes.

---

## Sommaire

- [Stack technique]({% link stack.md %}) — Pourquoi chaque techno a ete choisie
- [Architecture]({% link architecture.md %}) — Couches, modules, data flow
- [Navigation]({% link navigation.md %}) — Ecrans, flows, deep linking
- [Modeles de donnees]({% link models.md %}) — Structures du domaine
- [Pattern MVI]({% link mvi.md %}) — Architecture UI en detail
- [Features communautaires]({% link features.md %}) — Les addons userscript qui deviennent natifs
- [Nommage]({% link naming.md %}) — Le futur nom de l'app
- [Roadmap]({% link roadmap.md %}) — Phases de developpement
- [Contribuer]({% link contributing.md %}) — Comment participer
