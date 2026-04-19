<!--
This file is the source of truth for AI agent instructions across all supported LLM providers.
CLAUDE.md (Claude Code), GEMINI.md (Gemini CLI) et .github/copilot-instructions.md (GitHub Copilot)
sont des symlinks vers AGENTS.md. Le fichier .cursor/rules/project.mdc l'importe via `@AGENTS.md`.
Les skills vivent dans .agents/skills/<name>/SKILL.md au format agentskills.io — convention
cross-client pour interoperabilite multi-outil (Claude Code via symlink .claude/skills/,
Cursor, Codex, Copilot coding agent, Gemini CLI, Junie).
Voir SKILLS.md à la racine pour l'index humain des skills.
-->

# Redface 2

## Git et GitHub

- **Organisation GitHub** : ForumHFR
- Utiliser la config git locale (repo), jamais `--global`

## Projet

- Phase actuelle : **specifications** (pas encore de code)
- Licence : Apache 2.0
- Documentation : GitHub Pages via `docs/` (Jekyll + just-the-docs)
- Langue : code en anglais, issues et docs en francais

## Setup

```bash
# Pas de build applicatif — phase spec uniquement
# Preview Jekyll (necessite Ruby + Bundler)
cd docs && bundle install && bundle exec jekyll serve
```

## Structure des specs

```
docs/
  index.md           # Accueil du site
  specs/             # Pages canoniques qui font foi
    methodology.md   # Methode canonique : spec/prototype/TDD
    scope.md         # Scope produit et use cases
    stack.md         # Choix techniques justifies
    architecture.md  # Modules Gradle, couches, data flow, securite, erreurs
    navigation.md    # Ecrans, deep linking, back stack
    models.md        # Data classes Kotlin (source de verite pour les types)
    mvi.md           # Pattern MVI, exemples ViewModel/Screen/State
    protocol-hfr.md  # Contrats externes, endpoints, edge cases
    roadmap.md       # Phases de developpement
    extensions.md    # Extensions communautaires et architecture d'extensions
    adr/             # Architecture Decision Records
  guides/            # Pages d'accompagnement
    contributing.md  # Conventions, tests, accessibilite, localisation
    rationale.md     # Pourquoi la reecriture
    naming.md        # Candidats pour le nom de l'app
  _config.yml        # Config Jekyll (version des specs dans le footer)
```

## Stack (verrouillee)

Kotlin, Jetpack Compose, MVI, Compose Navigation 3, Hilt (KSP), OkHttp 5, Jsoup, Room, Coil, Coroutines + Flow, minSdk 29

## Tests

Pas de tests encore (phase spec). Strategie definie dans `docs/guides/contributing.md` :
- JUnit 4 + MockK + Robolectric + Turbine
- Couverture **hybride differenciee** : 100% sur les transformers du parser HFR (fixtures dictent l'exhaustivite), guidee par risque ailleurs (ViewModels, mappers, repositories). **Pas d'objectif 100% global.** Voir `docs/specs/methodology.md`.
- Fixtures HTML capturees depuis HFR reel, jamais fabriquees

## Conventions

- Issues et commentaires : toujours mentionner qui a demande l'action si generee par IA
- Conventional Commits : `feat:`, `fix:`, `docs:`, `chore:`, `test:`
- Branche principale : `main`

---

## Regles pour modifications de specs

### Methodologie canonique

La méthode du projet est documentée dans `docs/specs/methodology.md` et formalisée dans `docs/adr/000-methodologie-triple-hybride.md`.

Dans `AGENTS.md`, on ne garde que les conséquences opérationnelles pour les agents :
- lire `docs/specs/methodology.md` avant tout changement structurant de spec
- appliquer le bon mode de travail selon le sujet (spec / prototype / TDD / test-after), sans redéfinir la méthode ici
- ne pas dupliquer la méthodologie dans d'autres pages ; elles doivent pointer vers la source canonique

### Charte anti-derive IA-first

- **Le reel prime sur la prose** : pas de nouvelle couche de spec sur un sujet incertain sans spike, fixture reelle ou verification doc officielle.
- **Un sujet = une source canonique** : pour chaque theme structurant (methodologie, navigation, stack, modeles), un document de reference unique ; les autres fichiers pointent vers lui au lieu de dupliquer.
- **Pas de decision implicite** : une decision n'est "actee" que si l'issue, l'ADR ou la page canonique correspondante a ete mise a jour explicitement.
- **Spike avant architecture** : si un choix depend d'un comportement reel du parser, du rendu, du cache, de Room ou de Navigation, on prototype d'abord, on documente ensuite.
- **Pas de snippet decoratif** : tout exemple Kotlin doit etre conceptuellement compilable, aligne sur les types du repo et sur l'API stable actuelle.
- **Pas de claim sans preuve** : "teste", "verifie", "supporte", "stable" ou "compatible" ne sont utilises que si la commande, la fixture ou la doc officielle a reellement ete consultee.
- **Les issues suivent les specs, pas l'inverse** : une issue ouverte qui ne reflete plus l'etat courant des specs doit etre mise a jour, scindee ou fermee avant de devenir du travail actif.
- **Les drafts ne gouvernent rien** : un fichier dans `drafts/` reste non normatif tant qu'il n'a pas ete promu explicitement dans `docs/`.
- **Le noyau avant l'ecosysteme** : pas d'extensions, de release automation, de theming avance ou d'infra sophistiquee tant que le flux principal n'est pas prouve.
- **Validation separee** : le meme agent ne doit pas produire, corriger et valider seul un changement structurant ; utiliser une review humaine ou un agent distinct.

### Architecture et conception

- Avant de proposer un changement d'architecture, lire **tous** les fichiers `docs/` pour comprendre les dependances croisees. Un changement dans `docs/specs/architecture.md` a des impacts sur `docs/specs/models.md`, `docs/specs/mvi.md`, `docs/guides/contributing.md`, `docs/specs/extensions.md` et `docs/specs/navigation.md`.
- Apres chaque modification, verifier la coherence des elements suivants entre les fichiers :
  - Noms des modules Gradle (identiques dans `architecture.md`, `extensions.md`, `contributing.md`)
  - Noms des modeles (identiques dans `models.md`, `mvi.md`, `architecture.md`)
  - Noms des repositories et interfaces (identiques partout)
  - Dependances entre modules (diagramme mermaid = tableau texte = descriptions)
- Ne jamais ajouter un module Gradle sans mettre a jour : le diagramme mermaid, le tableau texte, ET les descriptions des couches dans `architecture.md`.
- Evaluer le over-engineering : si le projet a 0 contributeur, une convention documentee peut suffire. Si un mecanisme Gradle est necessaire pour N>5 contributeurs, le documenter mais ne pas le rendre obligatoire avant Phase 1.
- Pour les choix d'architecture avec plusieurs options viables (ex: 2 couches vs 3 couches), toujours presenter les alternatives avec arguments et demander l'avis humain. Ne pas decider seul.

### Qualite des specs et du code

- **Diagrammes mermaid** : doivent reflechir exactement le texte qui les entoure. Apres modification d'un diagramme, relire le paragraphe precedent et suivant pour verifier la coherence.
- **Exemples de code Kotlin** : doivent etre conceptuellement compilables. Verifier que :
  - Les proprietes utilisees existent dans les data classes definies (`state.filteredFlags` => `filteredFlags` est dans `FlagsState`)
  - Les types correspondent (`Instant` partout pour les dates, pas `String` dans certains endroits)
  - Les parametres de navigation correspondent (`lastReadPage` et non `unreadCount`)
  - Les imports implicites sont coherents (pas de `SwipeRefresh` si on dit utiliser Material 3)
- **Modeles de donnees** : quand un champ est reference dans `mvi.md` ou `navigation.md`, il doit exister dans `models.md`. Verifier apres chaque ajout.
- Ne jamais utiliser de proprietes/methodes/classes inexistantes dans les exemples. Si un exemple a besoin d'un helper (`matchesFilter`, `comparatorFor`), mentionner qu'il est a implementer.
- **Conventions de fichiers feature** : si la convention dans `contributing.md` liste `TopicRepository.kt` dans un feature, mais que les interfaces vivent dans `:core:domain`, corriger la convention pour refleter la realite.

### Processus d'audit

- Structurer un audit par severite (critique > important > moyen). Les critiques sont les incoherences logiques et les erreurs factuelles. Les importants sont les lacunes de spec. Les moyens sont du polish.
- Pour chaque point identifie : decrire le probleme, citer le fichier et la ligne, proposer un fix concret.
- Avant de pousser les corrections, faire une self-review : relire chaque fichier modifie et verifier qu'aucune incohérence n'a ete introduite par les corrections elles-memes.
- Utiliser un agent de review separe pour valider les changements (le meme agent qui corrige ne devrait pas valider).
- Apres un audit multi-fichiers, toujours verifier : `docs/guides/contributing.md` (structure de fichiers), `docs/index.md` (vue d'ensemble), et les diagrammes mermaid dans chaque fichier.

### Attribution et tracabilite

- Commits : toujours terminer par `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>` (ou le modele exact utilise).
- Commits d'audit : lister les numeros de points corriges dans le message (ex: "#1: fix diagram, #3: add filteredFlags").
- Issues : tout commentaire genere par IA commence par la ligne d'attribution correspondant au fournisseur, avec `@<demandeur>` remplace par le pseudo GitHub du contributeur qui a demande l'action.
- Ne jamais fermer une issue sans commentaire explicatif, meme si auto-closed par un commit.

### Regles specifiques au projet

- **BBCode HFR** : `[fixed]` est un bloc (block-level), jamais inline. Pour du monospace inline, utiliser `[b]`. `[size=X]` n'existe pas. Color se ferme avec `[/#XXXXXX]` (meme code couleur).
- **Smileys HFR** : deux syntaxes distinctes — builtin (~25, syntaxe `:code:` comme `:jap:`, `:o`, `:D`, `:bounce:`, `:pt1cable:`) et perso (centaines, syntaxe `[:smiley_name]`). Ne jamais inventer un code smiley — vérifier sur HFR réel ou laisser en placeholder.
- **Tests exécutés, pas juste compilés** : l'IA ne claim jamais "testé" sans avoir réellement exécuté la commande de test et lu le résultat. Un test qui compile n'est pas un test qui passe. En cas d'impossibilité d'exécuter (env manquant, fixture absente, etc.), dire explicitement "tests ecrits mais non execute".
- **Fixtures HTML** : capturees depuis HFR reel via `hfr-mcp` (`hfr_read ... output=path`), jamais inventees par une IA ou ecrites a la main. Nettoyer les donnees sensibles (cookies, hash_check, emails, identifiants) avant commit. Voir skill [`/parse-fixture`](https://github.com/ForumHFR/redface2/blob/main/.agents/skills/parse-fixture/SKILL.md).
- **Vérification API actuelle** : quand tu écris un exemple de code ou du code de prod avec une API dont tu n'es pas sûr à 100% (existe-t-elle ? est-elle dépréciée ?), vérifier via la documentation officielle actuelle. MCP recommandés : Context7 ou Docfork (cf. [#19](https://github.com/ForumHFR/redface2/issues/19)). **Toujours préciser "stable release"** dans la requête — Context7 indexe aussi les pre-release/snapshots (ex: Kotlin 2.4-SNAPSHOT alors que la stable courante est 2.3.x). Cette vérification prend 10 secondes et évite les pièges (SwipeRefresh, EncryptedSharedPreferences, APIs inexistantes).
- **Langue** : docs en francais avec accents. Code, noms de variables, noms de classes en anglais.
- **`numreponse`** : est unique par **categorie**, pas globalement sur le forum. Le mentionner quand pertinent.
- **Deep links** : Compose Navigation 3 (comme 2.x) ne gere pas les fragments URI (`#t{id}`) nativement — parser l'URI manuellement dans `MainActivity` et pousser la route typee dans le back stack.
- **Prefetch** : utiliser des requetes non authentifiees pour eviter de marquer les drapeaux comme lus.
- **OkHttp** : version 5 (5.3+) verrouillee. Stable depuis 07/2025.
- **Deprecations** : ne jamais utiliser de composants deprecies (Accompanist SwipeRefresh, EncryptedSharedPreferences, etc.) dans les exemples. Utiliser les alternatives 2026 : PullToRefreshBox, DataStore + Keystore (Option A credentials, sans Tink), Compose Navigation 3.

---

## Contributeurs multi-LLM

Ce projet accueille les contributions via plusieurs agents LLM. AGENTS.md (ce fichier) est le source of truth. Les règles sont les mêmes pour tous ; seule l'invocation des skills varie.

### Attribution IA par fournisseur

Tout commentaire sur issue, PR ou post HFR généré par un LLM doit commencer par :

| Fournisseur | Ligne d'attribution |
|---|---|
| Claude Code | `> Action par Claude Opus <version> (demandée par @<demandeur>)` |
| OpenAI Codex | `> Action par GPT-5 Codex (demandée par @<demandeur>)` |
| GitHub Copilot coding agent | `> Action par GitHub Copilot Agent (demandée par @<demandeur>)` |
| Gemini CLI | `> Action par Gemini <version> (demandée par @<demandeur>)` |
| Cursor agent | `> Action par Cursor Agent (<modèle sous-jacent>, demandée par @<demandeur>)` |
| aider | `> Action par aider (<modèle sous-jacent>, demandée par @<demandeur>)` |

Remplacer `@<demandeur>` par le pseudo GitHub du contributeur qui a demandé l'action avant publication.

### Skills et commandes

Les skills du projet vivent dans `.agents/skills/<name>/SKILL.md` au format [agentskills.io](https://agentskills.io/specification), emplacement recommandé par la spec pour l'interop cross-client. Claude Code les découvre via le symlink `.claude/skills → ../.agents/skills`. Portables entre Claude Code, Cursor, Codex, Copilot (coding agent), Gemini CLI et Junie. Index humain : voir [SKILLS.md](SKILLS.md).

Invocation :

| Outil | Invocation |
|---|---|
| Claude Code | `/<skill-name>` |
| Cursor (Agent mode) | Le skill est chargé via `.agents/skills/` + description dans `AGENTS.md` |
| OpenAI Codex | `@codex use skill <name>` ou via tool call si intégré |
| GitHub Copilot coding agent | Issue label `copilot` → skill auto-matché via description |
| Gemini CLI | `/skill <name>` |

### Configuration par outil

| Outil | Fichier lu | Notes |
|---|---|---|
| Claude Code | `CLAUDE.md` (symlink → `AGENTS.md`) | Skills via `.claude/skills/` → symlink vers `.agents/skills/` |
| OpenAI Codex | `AGENTS.md` | Support natif depuis 2025 |
| GitHub Copilot | `.github/copilot-instructions.md` (symlink → `AGENTS.md`) + `AGENTS.md` (coding agent) | Auto-détecté depuis 08/2025 |
| Gemini CLI | `GEMINI.md` (symlink → `AGENTS.md`) | Hiérarchique |
| Cursor | `.cursor/rules/project.mdc` (importe `@AGENTS.md`) | `alwaysApply: true` |
| aider | `CONVENTIONS.md` ou `AGENTS.md` (config dans `.aider.conf.yml`) | À ajouter si besoin |
| Windsurf | `.windsurfrules` (à ajouter si besoin) | Limite 12 KB |
