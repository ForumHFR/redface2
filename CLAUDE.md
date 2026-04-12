# Redface 2

## Identite Git/GitHub

- **Organisation GitHub** : ForumHFR
- **Git user.name** : xat
- **Git user.email** : xat@azora.fr
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
  index.md           # Page d'accueil, diagramme simplifie
  stack.md           # Choix techniques justifies
  architecture.md    # Modules Gradle, couches, data flow, session, securite, erreurs
  navigation.md      # Ecrans, deep linking, back stack
  models.md          # Data classes Kotlin (source de verite pour les types)
  mvi.md             # Pattern MVI, exemples ViewModel/Screen/State
  features.md        # Features communautaires, architecture d'extensions
  naming.md          # Candidats pour le nom de l'app
  roadmap.md         # Phases de developpement
  contributing.md    # Conventions, tests, accessibilite, localisation
  _config.yml        # Config Jekyll (version des specs dans le footer)
```

## Stack (verrouillee)

Kotlin, Jetpack Compose, MVI, Compose Navigation, Hilt (KSP), OkHttp 4, Jsoup, Room, Coil, Coroutines + Flow, minSdk 29

## Tests

Pas de tests encore (phase spec). Strategie definie dans `docs/contributing.md` :
- JUnit 4 + MockK + Robolectric + Turbine
- Couverture 100% sur parser, database, ViewModels
- Fixtures HTML capturees depuis HFR reel, jamais fabriquees

## Conventions

- Issues et commentaires : toujours mentionner qui a demande l'action si generee par IA
- Conventional Commits : `feat:`, `fix:`, `docs:`, `chore:`, `test:`
- Branche principale : `main`

---

## Regles pour modifications de specs

### Architecture et conception

- Avant de proposer un changement d'architecture, lire **tous** les fichiers `docs/` pour comprendre les dependances croisees. Un changement dans `architecture.md` a des impacts sur `models.md`, `mvi.md`, `contributing.md`, `features.md` et `navigation.md`.
- Apres chaque modification, verifier la coherence des elements suivants entre les fichiers :
  - Noms des modules Gradle (identiques dans `architecture.md`, `features.md`, `contributing.md`)
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
- Apres un audit multi-fichiers, toujours verifier : `contributing.md` (structure de fichiers), `index.md` (vue d'ensemble), et les diagrammes mermaid dans chaque fichier.

### Attribution et tracabilite

- Commits : toujours terminer par `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>` (ou le modele exact utilise).
- Commits d'audit : lister les numeros de points corriges dans le message (ex: "#1: fix diagram, #3: add filteredFlags").
- Issues : tout commentaire genere par IA commence par `> Action par Claude (demande par @XaaT)`.
- Ne jamais fermer une issue sans commentaire explicatif, meme si auto-closed par un commit.

### Regles specifiques au projet

- **BBCode HFR** : `[fixed]` est un bloc (block-level), jamais inline. Pour du monospace inline, utiliser `[b]`. `[size=X]` n'existe pas. Color se ferme avec `[/#XXXXXX]` (meme code couleur).
- **Smileys** : ne jamais ajouter de smiley HFR non verifie dans les specs ou exemples. Smileys surs : `:)`, `:(`, `:o`, `:D`, `;)`, `:p`, `:jap:`, `:fou:`, `:pfff:`, `:sweat:`, `:bounce:`, `:pt1cable:`.
- **Langue** : docs en francais avec accents. Code, noms de variables, noms de classes en anglais.
- **`numreponse`** : est unique par **categorie**, pas globalement sur le forum. Le mentionner quand pertinent.
- **Deep links** : Compose Navigation ne supporte pas les fragments (`#t{id}`). Toujours prevoir un traitement custom dans MainActivity.
- **Prefetch** : utiliser des requetes non authentifiees pour eviter de marquer les drapeaux comme lus.
- **OkHttp** : version 4 verrouillee, revisable en Phase 0 si OkHttp 5 est stable.
- **Deprecations** : ne jamais utiliser de composants deprecies (Accompanist SwipeRefresh, etc.) dans les exemples. Utiliser les alternatives Material 3.
