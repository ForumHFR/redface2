# Redface 2

> Le client Android communautaire pour [forum.hardware.fr](https://forum.hardware.fr), en bêta publique.

Réécriture complète de [Redface](https://github.com/ForumHFR/Redface) sur une stack 2026 : **Kotlin 2.4**, **Jetpack Compose** (Material 3), **MVI**, **Compose Navigation 3**, **Hilt**, **Room**, **OkHttp 5**. Le code est produit par des agents LLM orchestrés depuis Claude Code, avec relecture croisée et CI bloquante ; les testeurs du forum pilotent le produit.

## Installer

| Canal | Pour qui | Comment |
|---|---|---|
| **Google Play, bêta ouverte** | le plus simple | [Redface 2 sur Google Play](https://play.google.com/store/apps/details?id=fr.forumhfr.redface2) (programme de test ouvert) |
| **F-Droid, bêta** | sans services Google, auditable | ajouter le dépôt `https://forumhfr.github.io/redface2-fdroid/repo` (empreinte dans le [guide d'installation](https://forumhfr.github.io/redface2/guides/installation)), app « Redface 2 β » |
| **F-Droid, dev** | testeurs du canal de développement | même dépôt, app « Redface 2 dev », mise à jour à chaque release dev |
| **GitHub Releases** | sideload ponctuel | APK signés attachés à chaque [release `app-v<N>`](https://github.com/ForumHFR/redface2/releases) |

Les apps bêta et dev de F-Droid ont des identifiants distincts et cohabitent sur un même appareil. Détails, empreintes et pièges de signature : [Installer Redface 2](https://forumhfr.github.io/redface2/guides/installation).

## État

- **Bêta publique 0.50.2** (1er septembre 2026) : sondages, liens HFR, modération et rôles du staff, vue forum, citations, fiabilité.
- **Canal dev 0.52.x** : sous-menu Couleurs (presets, hexa, tons de fond, AMOLED, Material You), zoom interactif, largeur des images.
- **Livré** : connexion HFR, drapeaux (favoris, lus, non lus, MultiMP), catégories et sous-catégories, lecture de sujet (pagination, ancres, recherche intra-sujet, deep links), écriture (répondre, citer, citer plusieurs, éditer, créer un sujet, sondages, upload d'images, smileys), messages privés et MultiMP (lecture, réponse, citation, membres), profils, liste noire, réglages (affichage, densité, images, couleurs, réseau et cache, proxy), cache Room et prefetch non authentifié.
- **Organisation du travail** : les phases 0 à 4 de la [roadmap](https://forumhfr.github.io/redface2/specs/roadmap) sont livrées comme épics ; le suivi se fait désormais par milestones de vue (*Vue · Topic 2*, *Vue · Éditeur 2*, *Vue · Drapeaux 2*, *Vue · MP 1*, *Vue · Réglages 1*, *Vue · Compte HFR 1*, *Infra & dette*). Reste ouvert : architecture d'extensions ([#7](https://github.com/ForumHFR/redface2/issues/7)), sync MPStorage entre appareils ([#6](https://github.com/ForumHFR/redface2/issues/6)).
- **Historique** : [`app/CHANGELOG.md`](app/CHANGELOG.md) (une entrée par release), [`CHANGELOG.md`](CHANGELOG.md) (versions des specs).

Suivi communautaire sur HFR : [topic bêta](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35395&page=1) (retours utilisateurs) et [topic dev](https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35421&page=1) (changelogs et tests du canal dev).

## Documentation

Site : **[forumhfr.github.io/redface2](https://forumhfr.github.io/redface2)** (publié depuis `main`).

- [Spécifications](https://forumhfr.github.io/redface2/specs) : [scope](https://forumhfr.github.io/redface2/specs/scope), [stack](https://forumhfr.github.io/redface2/specs/stack), [architecture](https://forumhfr.github.io/redface2/specs/architecture), [navigation](https://forumhfr.github.io/redface2/specs/navigation), [modèles](https://forumhfr.github.io/redface2/specs/models), [MVI](https://forumhfr.github.io/redface2/specs/mvi), [protocole HFR](https://forumhfr.github.io/redface2/specs/protocol-hfr), [parité de lecture Topic ↔ MP](https://forumhfr.github.io/redface2/specs/reading-parity), [extensions](https://forumhfr.github.io/redface2/specs/extensions), [roadmap](https://forumhfr.github.io/redface2/specs/roadmap), [méthodologie](https://forumhfr.github.io/redface2/specs/methodology).
- [ADR](https://forumhfr.github.io/redface2/adr) : les décisions structurantes et leur pourquoi.
- [Guides](https://forumhfr.github.io/redface2/guides) : [installation](https://forumhfr.github.io/redface2/guides/installation), [contribuer](https://forumhfr.github.io/redface2/guides/contributing), [release](https://forumhfr.github.io/redface2/guides/release), [limitations connues](https://forumhfr.github.io/redface2/guides/known-issues), [proxy](https://forumhfr.github.io/redface2/guides/proxy), [profiling](https://forumhfr.github.io/redface2/guides/profiling), [pourquoi Redface 2](https://forumhfr.github.io/redface2/guides/rationale).

## Développer

```bash
# Build debug prod dans l'image Docker épinglée (SDK, JDK et caches reproductibles)
./scripts/docker-dev.sh ./gradlew :app:assembleProdDebug

# Reproduire la CI avant de pousser : tests JVM (dont modules JVM purs), detekt, lint (dont `:app` flavorisé)
./scripts/docker-dev.sh ./gradlew --continue test testDebugUnitTest :app:testProdDebugUnitTest detektAll lintDebug :app:lintProdDebug
```

- 18 modules Gradle : `:app`, huit `:core:*` (model, domain, data, network, parser, database, ui, extension), neuf `:feature:*` (auth, flags, forum, topic, editor, messages, search, settings, profile). Structure détaillée dans le [guide de contribution](https://forumhfr.github.io/redface2/guides/contributing).
- Branches : `dev` intègre, `main` publie. Toute modification passe par une pull request avec CI verte ; les branches partent d'`origin/dev`.
- Gardes machine : Konsist (frontières d'architecture), detekt, lint Android, tests JVM (JUnit 4, MockK, Robolectric, Turbine, Roborazzi en mode record), garde de parité de lecture sur les PR touchant le rendu partagé.
- Versions et outillage : voir [`gradle/libs.versions.toml`](gradle/libs.versions.toml) (source de vérité), `minSdk 29`, `targetSdk 36`, `compileSdk 37`.
- Agents LLM : les règles opérationnelles sont dans [`AGENTS.md`](AGENTS.md) (source de vérité multi-outils, `CLAUDE.md` et `GEMINI.md` sont des liens), les skills dans [`.agents/skills/`](.agents/skills) ([index](SKILLS.md)).

## Méthode

Spec, prototype et TDD sélectifs selon le sujet ([méthodologie](https://forumhfr.github.io/redface2/specs/methodology), [ADR-000](https://forumhfr.github.io/redface2/adr/000-methodologie-triple-hybride)). Les fixtures HTML sont capturées sur le forum réel, jamais écrites à la main. Un changement structurant est cadré, produit et relu par des agents distincts ; chaque action générée par IA est attribuée à la personne qui l'a demandée. Un [point chiffré](https://forumhfr.github.io/artifacts/redface2-en-chiffres/) du dépôt est publié périodiquement.

## Participer

Ouvrez une [issue](https://github.com/ForumHFR/redface2/issues) (bug, demande, divergence spec/code), commentez les existantes ou proposez une PR sur `dev`. Les retours de test passent aussi par les topics HFR ci-dessus. Voir [Contribuer](https://forumhfr.github.io/redface2/guides/contributing).

## Pourquoi réécrire ?

Redface v1 tourne sur une stack de 2015 : Java, Retrofit 1.9, RxJava 1, ButterKnife, Otto, minSdk 16. Chaque brique est obsolète ou dépréciée et les migrations s'entraînent les unes les autres. Le raisonnement complet, doutes compris, est dans [Pourquoi Redface 2 ?](https://forumhfr.github.io/redface2/guides/rationale).

## Licence

`GPL-3.0-only` : garder le client communautaire et éviter les forks applicatifs fermés ([ADR-010](https://forumhfr.github.io/redface2/adr/010-licence-client-android)).

Crédits des assets tiers : [mentions et licences](app/THIRD_PARTY_NOTICES.md).
