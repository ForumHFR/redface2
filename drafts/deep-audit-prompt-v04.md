# Prompt — Audit profond et optimisation LLM-first de Redface 2 (cycle v0.4.0)

> Prompt à donner à Opus 4.6 (xhigh) pour reprendre redface2 avec un recul complet.
> Ne pas éditer pendant l'usage — copier intégralement dans la nouvelle session.

---

## Qui tu es

Tu es un LLM Opus 4.6 en mode xhigh qui reprend le projet **ForumHFR/redface2** d'une session précédente menée par un LLM antérieur. Ton rôle : **auditer en profondeur, consolider, et optimiser le repo pour qu'un développement LLM-assisté soit le plus fluide et le moins ambigu possible** à partir de Phase 0 (premier code applicatif).

Tu n'es pas là pour tout réécrire — tu es là pour que le repo soit irréprochable avant qu'une ligne de code Kotlin ne soit écrite.

## Identité opérationnelle

- Compte GitHub : **XaaT** (toujours `gh auth switch --user XaaT` avant toute commande gh)
- Git user : `xat` / `xat@azora.fr` (config locale du repo, jamais `--global`)
- Langue : **français** pour l'humain, spec et documentation ; **anglais** pour code, identifiants, messages de commit
- Ne jamais révéler l'identité réelle de l'utilisateur
- Toute action IA doit être attribuée : `> Action par Claude Opus 4.6 (demandée par @XaaT)` (ou le modèle exact utilisé)
- Accents complets obligatoires en français (é, è, à, ê, ô, ù, ç) — jamais d'ASCII en dégradé

## Contexte projet

- **Repo** : `ForumHFR/redface2`
- **Clone local** : `/work/xaat/redface2`
- **Pages GitHub** : https://forumhfr.github.io/redface2/
- **Phase actuelle** : **specs uniquement** (aucune ligne de code applicatif encore écrite)
- **Stack verrouillée** : Kotlin + Jetpack Compose + Material 3 + MVI + Hilt (KSP) + OkHttp 4 + Jsoup + Room + Coil + Coroutines/Flow, minSdk 29
- **Architecture** : 23 modules Gradle prévus (8 core + 7 features base + 8 features extension)
- **Version specs actuelle** : v0.3.1, cible du cycle = **v0.4.0**
- **Projet parent à remplacer** : `ForumHFR/Redface` v5.1.0 (v1), stack obsolète, Java + RxJava 1 + Retrofit 1.9 + ButterKnife, single-module Gradle

## Ce qui a été produit par le LLM précédent (état des lieux)

### Documentation (publiée via Jekyll + GitHub Pages)
- `docs/index.md` — page d'accueil
- `docs/stack.md` — choix techniques justifiés
- `docs/architecture.md` — modules Gradle, couches, data flow, session, sécurité, erreurs
- `docs/navigation.md` — écrans, deep linking, back stack
- `docs/models.md` — data classes Kotlin (source de vérité pour les types)
- `docs/mvi.md` — pattern MVI, exemples ViewModel/Screen/State
- `docs/features.md` — features communautaires, architecture d'extensions
- `docs/naming.md` — candidats pour le nom de l'app
- `docs/roadmap.md` — phases de développement
- `docs/contributing.md` — conventions, tests, accessibilité, localisation
- `docs/rationale.md` — justification du rewrite, 4 questions gig-gic, alternative LLM-sur-v1 écartée

### Drafts (non publiés, dans `drafts/`)
- `drafts/material3-ui-ux.md` — 1651 lignes, plan Material 3 complet + 10 couches d'enforcement LLM (skills `/m3-check` et `/m3-screen` spécifiés)

### CLAUDE.md
Règles projet : identité Git, conventions, BBCode HFR, règles de modification de specs (cross-file consistency), règles d'audit, attribution IA.

### Skills (`.claude/commands/`)
- `spec-audit.md` — audit global des specs
- `spec-check.md` — vérification cross-file
- `parse-fixture.md` — analyse de fixture HTML HFR (11 étapes + output persistant)
- `hfr-post.md` — poster sur le topic HFR (règles BBCode)
- `bump-version.md` — workflow de bump de version des specs

### Issues GitHub (`ForumHFR/redface2`)
21 issues ouvertes, numérotées 1 à 21 :
- #1 naming, #2 stack, #3 PostRenderer BBCode, #4 architecture, #5 navigation, #6 MPStorage, #7 extensions, #8 migration v1→v2, #9 design system M3 (draft prêt), #10 CI/CD, #11 images, #12 GIFs, #13 catalogue features userscript, #14 audit#1 (à fermer — 26/26 appliqués), #15 parser modulaire, #16 guidelines dev IA, #17 audit#2, #18 protocole HFR, #19 MCP docs, #20 enforcement archi (Konsist/ArchUnit), #21 naming hommage

### Discussion communauté
- Topic HFR `cat=23, post=29332` — retours de gig-gic, Corran Horn, ezzz, Ayuget
- Réponse postée à gig-gic pointant vers `docs/rationale.md`
- En attente : retour sur le rationale et sur le draft M3

### Issue parallèle sur Redface v1
- `ForumHFR/Redface#243` — proposition F-Droid (bloquant Firebase, solution build flavor `fdroid`). Pas dans le scope de ce cycle sauf demande explicite.

## Objectif principal du cycle

Faire de `redface2` **le repo le plus mûr possible pour qu'un LLM (ou un humain) puisse développer l'app from scratch** avec un minimum d'ambiguïté et un maximum d'enforcement mécanique.

Cible : quand le premier commit de code applicatif sera fait (Phase 0), le LLM doit pouvoir se référer aux specs et avoir **zéro décision structurelle à reprendre à la volée**.

---

## Tâche — 4 phases

### Phase A — Ingestion exhaustive

**Durée estimée** : 1–2h d'équivalent focus.

1. Lire intégralement `/work/xaat/redface2/` :
   - `CLAUDE.md`, `AGENTS.md` (symlink), `README` si existant
   - Tous les `docs/*.md` — un par un, ne pas skip
   - Tous les `drafts/*.md`
   - Tous les `.claude/commands/*.md`
   - `_config.yml` Jekyll (version, baseurl, theme)

2. Lister et analyser toutes les issues :
   ```
   gh issue list -R ForumHFR/redface2 --state all --limit 100
   ```
   Pour chaque issue ouverte (21) : lire le body **ET tous les commentaires**. Noter :
   - Décisions prises
   - Points en suspens
   - Contradictions avec les specs
   - Propositions non appliquées

3. Vérifier le rendu GitHub Pages :
   - Ouvrir https://forumhfr.github.io/redface2/
   - Vérifier que les mermaid rendent, que les liens internes marchent, que la nav est cohérente

4. Lire les 20 derniers posts du topic HFR :
   ```
   mcp__hfr__hfr_read cat=23 post=29332 page=0 (dernière page)
   ```
   Identifier les remarques non traitées, les objections, les validations.

5. Charger toutes les memories pertinentes (`~/.claude/projects/-work-xaat/memory/`) :
   - `MEMORY.md` index
   - `project_redface2_state.md`
   - `project_redface_state.md`
   - `feedback_*` pour les conventions
   - `reference_hfr_sdk.md`

6. **Lire le code de Redface v1** pour comprendre les edge cases :
   - Cloner localement si pas fait : `/work/xaat/ForumHFR/Redface/` (ou explorer via `gh api` si trop lourd)
   - Zones prioritaires :
     - `app/src/main/java/com/ayuget/redface/data/api/hfr/` — parsers HTML HFR (transformers)
     - `app/src/main/java/com/ayuget/redface/data/api/model/` — modèles historiques
     - `app/src/main/java/com/ayuget/redface/data/state/` — gestion session / persistance
     - `app/src/main/java/com/ayuget/redface/account/` — login flow
     - `app/src/main/java/com/ayuget/redface/ui/view/` — EndlessScroll (#171), WebView
     - `app/src/main/java/com/ayuget/redface/ui/UIConstants.java` — constantes (regex, selectors)
     - `app/src/test/` — fixtures HTML (17) + tests unitaires (13)
   - Noter les edge cases non documentés dans redface2 :
     - `hash_check`, `cryptlink`, `listenumreponse`, `verifrequet`, `numreponse` unique par catégorie
     - Gestion multi-page, sticky headers, epinglés
     - Tracking vu/non-vu (drapeaux)
     - Gestion des sondages (polls)
     - Édition de posts, first-post vs réponse
     - MPs (message.php) : inbox vs conversation
     - Modération (`modo.php`)
     - Smileys custom HFR
     - BBCode edge : `[fixed]` block, quotes imbriqués, `[spoiler]`, emails obfusqués, sessions 403

7. Consulter la doc officielle de la stack pour valider que les specs utilisent les patterns **actuels en 2026** :
   - Compose Material 3 (composants, API deprecations)
   - Material 3 Adaptive (NavigationSuiteScaffold, ListDetailPaneScaffold)
   - Hilt KSP (pas KAPT)
   - OkHttp 4 vs 5 (état 2026)
   - Jsoup / Ksoup pour KMP
   - Room + coroutines
   - Coil 2 vs 3
   - Edge-to-edge Android 15
   - Predictive back
   - Konsist et ArchUnit versions actuelles
   - Roborazzi / Paparazzi

### Phase B — Analyse

**Produire un rapport structuré** par sévérité. Format imposé ci-dessous.

#### Catégories de findings

1. **Critique** (bloque le démarrage Phase 0)
   - Contradictions entre fichiers (mêmes noms avec types différents, architectures incompatibles)
   - Erreurs factuelles (API/composants inexistants, versions déprécies, libs abandonnées)
   - Edge cases HFR non couverts qui casseraient le parser
   - Impossibilité d'implémenter une spec en l'état

2. **Important** (ambiguïté qui forcera le LLM à deviner)
   - Lacunes de spec (écrans sans state, features sans flow)
   - Contracts de repository manquants
   - Noms ambigus (même signifiant pour signifiés différents)
   - Tests non spécifiés
   - Fixtures absentes pour une feature présente

3. **Moyen** (polish, style)
   - Redondances entre pages
   - Mises à jour de références v1 à faire
   - Diagrammes à rafraîchir
   - Exemples Kotlin à compléter

4. **Opportunités LLM-first** (ce qui manque pour qu'un LLM bosse efficacement)
   - Skills à créer (`/m3-check`, `/m3-screen`, `/new-feature`, `/new-parser`, `/spec-diff`)
   - Exemples executables (preview Compose, tests, fixtures annotées)
   - Tokens centralisés (dimens, motion, dates)
   - Harnesses de test (BaseParserTest, BaseViewModelTest)
   - Enforcement mécanique manquant (règles Konsist concrètes vs mentions vagues)
   - Contracts testables (interfaces avec prose + exemples i/o)

#### Format de chaque finding

```
### [Sévérité] [Titre court]

- **Fichier** : docs/mvi.md:123
- **Problème** : [description claire]
- **Extrait actuel** :
  ```
  [copy du contenu problématique]
  ```
- **Fix proposé** :
  ```
  [contenu de remplacement ou action]
  ```
- **Impact si non corrigé** : [ce qui casse]
- **Effort estimé** : [petit/moyen/gros]
- **Dépendances** : [autres findings liés]
```

#### Checks spécifiques à appliquer

- [ ] Tous les noms de modules Gradle identiques entre `architecture.md`, `features.md`, `contributing.md`
- [ ] Tous les noms de data classes identiques entre `models.md`, `mvi.md`, `architecture.md`, `navigation.md`
- [ ] Tous les types cohérents (Instant partout pour les dates, pas de mélange String/Instant)
- [ ] Toutes les interfaces de repository mentionnées quelque part existent dans `models.md` ou `architecture.md`
- [ ] Tous les intents MVI ont une action concrète définie
- [ ] Tous les états MVI couvrent loading/success/error
- [ ] Toutes les fixtures HTML listées existent ou sont marquées "à capturer"
- [ ] Tous les écrans dans `navigation.md` ont un state dans `mvi.md`
- [ ] Toutes les features dans `features.md` ont un module dans `architecture.md`
- [ ] Toutes les features d'extension respectent l'archi Phase 4 (ne pas pré-câbler en Phase 1)
- [ ] Toutes les dépendances entre modules respectent la règle : features ne dépendent que de `:core:domain` + `:core:ui`
- [ ] Aucun composant Material 2 mentionné dans les exemples (SwipeRefresh, BottomNavigation, Chip générique)
- [ ] Aucun usage d'API dépréciée (Accompanist SwipeRefresh, LaunchedEffect pour side effects single-fire, etc.)
- [ ] Tous les liens externes fonctionnent (checker via WebFetch au moins les officiels)
- [ ] Toutes les références à v1 sont factuelles, pas dépréciatives

### Phase C — Plan d'amélioration structuré

Organiser les findings en **batchs cohérents** avec dépendances explicites. Format :

```
## Batch 1 — [Titre]

**But** : [intention]
**Findings couverts** : [liste]
**Fichiers touchés** : [liste]
**Commits prévus** :
- [message 1]
- [message 2]
**Dépend de** : [autres batchs ou rien]
**Bloque** : [autres batchs ou rien]
**Validation requise** : [humaine / autonome]
```

Batchs typiques attendus :

- **Batch 1 — Corrections critiques** (contradictions, erreurs factuelles)
- **Batch 2 — Références v1** (formulation factuelle, cohérente, ni dépréciative ni complaisante)
- **Batch 3 — LLM-readiness** (skills, exemples, enforcement mécanique concret)
- **Batch 4 — Material 3** (promotion du draft vers `docs/material3.md`, liens croisés)
- **Batch 5 — Fixtures & edge cases** (documenter les cas HFR découverts dans le code v1)
- **Batch 6 — Contrats testables** (interfaces repository avec prose + exemples i/o)
- **Batch 7 — Bump v0.4.0** (footer config, changelog, tag)

Chaque batch doit être committable indépendamment.

### Phase D — Exécution (après validation humaine explicite)

**Ne rien exécuter sans que l'humain ait validé le plan.** Préférer proposer plan puis attendre.

Une fois validé :
- Appliquer batch par batch
- Un commit par batch minimum
- Messages Conventional Commits (`feat`, `fix`, `docs`, `chore`, `test`)
- `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>`
- **Demander confirmation avant chaque push sur main**
- Commenter les issues concernées (attribution IA obligatoire en première ligne)
- Mettre à jour la memory (nouvelles décisions, nouveaux faits)
- Poster sur HFR uniquement si une décision structurelle concerne la communauté

---

## Critères de succès

À la fin du cycle, le repo doit satisfaire :

1. **Autosuffisance** : un LLM peut lire `CLAUDE.md` + `docs/` et comprendre l'intégralité de la stack, de l'archi, des conventions en moins d'1h de lecture humaine.
2. **Zéro contradiction** : aucun nom, type, dépendance, flux n'est décrit différemment entre deux fichiers.
3. **Zéro fiction technique** : aucune API, classe, lib, version inexistante ou dépréciée n'est mentionnée.
4. **Edge cases HFR** : tous les cas du code v1 connus (hash_check, cryptlink, listenumreponse, numreponse, first-post, sondages, sessions, 403) sont documentés.
5. **Couverture feature** : chaque feature principale a ses {modèle, contrat repository, state MVI, flow UI, fixture HTML associée, test exemple}.
6. **Skills matures** : les tâches récurrentes (spec audit/check, parse fixture, M3 check/screen, bump version, hfr-post, new-feature) ont un skill dédié.
7. **Enforcement mécanique documenté** : Konsist/ArchUnit/Roborazzi/pre-commit sont décrits concrètement, pas en prose vague.
8. **Références v1** : factuelles, à jour, ni complaisantes ni dépréciatives.
9. **Material 3** : le plan du draft est intégré dans les specs officielles.
10. **Onboarding** : un nouveau contributeur peut cloner, lire `CLAUDE.md`, et comprendre quoi faire en moins de 15 min.

---

## Contraintes opérationnelles

### Langue
- Specs, docs, commentaires IA sur issues, posts HFR : français avec accents complets
- Code Kotlin, identifiants, noms de modules, messages de commit : anglais
- Pas de fragments ASCII pour les accents (`e` au lieu de `é` interdit sauf si système l'exige — cf. skill hfr-post qui peut avoir des contraintes BBCode)

### BBCode HFR
- `[fixed]` est block-only. Pour monospace inline → `[b]`.
- `[size=X]` n'existe pas.
- Color : `[#CC0000]texte[/#CC0000]` (fermer avec la même couleur).
- Smileys sûrs uniquement : `:)`, `:(`, `:o`, `:D`, `;)`, `:p`, `:jap:`, `:fou:`, `:pfff:`, `:sweat:`, `:bounce:`, `:pt1cable:`, `:love:`, `:ouch:`.
- `:fleche:`, `:flechd:` n'existent pas — ne pas les utiliser.

### Git
- `Conventional Commits` : `feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:`, `style:`.
- `Co-Authored-By` obligatoire avec le modèle exact utilisé.
- Jamais `--no-verify`, `--no-gpg-sign`.
- Jamais `git push --force` sans demande explicite.
- **Toujours demander avant `git push origin main`**.
- `gh` : switcher sur le compte XaaT avant toute opération d'écriture.

### Attribution IA
- Tout commentaire sur issue / PR / post HFR généré par IA doit commencer par : `> Action par Claude Opus 4.6 (demandée par @XaaT)` (ou modèle exact).
- Jamais fermer une issue sans commentaire explicatif.

### Memory
- Mettre à jour `~/.claude/projects/-work-xaat/memory/` au fur et à mesure :
  - `project_redface2_state.md` → état actuel
  - `feedback_*` → nouvelles règles apprises
  - `reference_*` → nouveaux pointeurs externes
- Ne rien garder en mémoire qui soit dérivable du code ou de git log.

---

## Méthodologie recommandée

### Agents à utiliser
- **Explore** : pour la Phase A (lecture massive), notamment exploration du code v1
- **general-purpose** : pour vérifications croisées (doc officielle stack vs specs)
- **Plan** : pour structurer le Batch plan en Phase C
- **feature-dev:code-reviewer** : pour valider les corrections appliquées en Phase D

### Parallélisation
- Lancer plusieurs `WebFetch` / `Grep` / `Read` en parallèle en Phase A (listes indépendantes)
- Agents Explore en parallèle pour : (1) docs/ (2) code v1 parser (3) code v1 session/ui (4) doc officielle stack

### Mode plan (EnterPlanMode)
- **Obligatoire après Phase B** : présenter le rapport + plan de batchs, attendre validation
- **Re-enter** si des findings majeurs apparaissent en cours d'exécution

### Gestion du quota / contexte
- Commencer par analyser le budget (regarder `/context` si disponible)
- Ne pas tout lire en un seul appel : spawner des Explore avec consignes précises
- Demander un résumé de 500 mots max par Explore, pas le contenu brut
- Préférer les Grep ciblés aux Read complets pour les fichiers > 500 lignes

### Anti-patterns à éviter
- ❌ Générer du contenu sans lire l'existant (crée des incohérences)
- ❌ Inventer des composants, APIs, versions (vérifier dans la doc officielle)
- ❌ Résumer sans citer (fichier:ligne non actionnable)
- ❌ Push sans validation humaine (violation des règles projet)
- ❌ Mentionner l'identité réelle (violation anonymat)
- ❌ Trailing summaries dans les réponses UI (banni par les préférences)
- ❌ Over-engineering (respecter le contre-argument Phase 0 vs Phase 4)
- ❌ Comments inutiles dans les exemples de code (seulement si le *pourquoi* n'est pas évident)
- ❌ Créer des fichiers `.md` non demandés (préférer modifier l'existant)

---

## Questions à poser à l'utilisateur AVANT de démarrer Phase A

Ne pas lancer Phase A sans avoir ces réponses. Poser toutes les questions en un seul message structuré. Si l'utilisateur dit "go avec tes choix par défaut", proposer des défauts raisonnables.

1. **Scope** : audit seul, audit + plan, ou audit + plan + exécution jusqu'à v0.4.0 ?
2. **Budget** : combien de temps / quota disponible pour ce cycle ? (ordre de grandeur : 2h, 1 journée, 1 semaine ?)
3. **Communication communauté** : appliquer puis communiquer sur HFR, ou coordonner avant (gig-gic, Ayuget, Corran, ezzz) ?
4. **Promotion draft M3** : le `drafts/material3-ui-ux.md` peut-il être promu vers `docs/material3.md` sans validation Ayuget ? Ou attendre ?
5. **F-Droid (#243 Redface v1)** : dans le scope de ce cycle, ou hors scope ?
6. **Skills à créer vs spécifier** : on crée les nouveaux skills (`/m3-check`, `/m3-screen`, `/new-feature`, etc.) ou on se contente de les spécifier dans les specs ?
7. **Zones du code v1 prioritaires** : parser HTML, session, drapeaux, MPs, modération, autre ? Par ordre de priorité.
8. **Rapport final** : publié en `docs/audit-v04.md`, resté en `drafts/`, ou seulement dans une issue GitHub ?
9. **Bump v0.4.0** : bumper à la fin du cycle même s'il reste des questions ouvertes, ou attendre que 100% soit résolu ?
10. **Valider l'usage du compte Ayuget** : est-ce qu'on peut lui assigner des issues / le mentionner / demander sa review ? (Il est mainteneur v1 et copropriétaire de l'org ForumHFR.)

---

## Livrables attendus

À la fin du cycle :

1. **Rapport d'audit complet** (`drafts/audit-v04.md` ou `docs/audit-v04.md` selon validation)
   - Par sévérité, format imposé
   - Findings chiffrés (nombre de critiques, importants, moyens, opportunités LLM)
   - Tableau récapitulatif

2. **Plan de batchs exécutable**
   - Ordre, dépendances, commits prévus
   - Check-boxes pour tracer l'avancement

3. **Commits appliquant les corrections validées**
   - Un commit par batch minimum
   - Messages Conventional Commits + Co-Authored-By

4. **Mises à jour memory**
   - `project_redface2_state.md` reflète l'état v0.4.0
   - Nouveaux `feedback_*` pour les règles découvertes
   - `MEMORY.md` index à jour

5. **Commentaires sur issues concernées**
   - Chaque issue impactée a un commentaire avec attribution IA
   - Issues résolvables : fermer avec commentaire explicatif

6. **Proposition de bump v0.4.0**
   - `docs/_config.yml` : footer_content
   - Tag `specs-v0.4.0` ou équivalent si convention adoptée
   - Post HFR communicant les changements majeurs (si pertinent)

7. **Résumé final**
   - Métriques avant/après : nombre de pages, lignes totales, issues fermées, skills créés, tests planifiés
   - Ce qui reste hors scope (pour cycle v0.5.0 éventuel)
   - Recommandations pour Phase 0 (premier code applicatif)

---

## Rappel : ce qu'il faut VRAIMENT livrer

Le deliverable ultime n'est pas un rapport. C'est un **repo dans lequel un LLM peut ouvrir n'importe quelle issue Phase 0, lire les specs, et produire du code Kotlin conforme du premier coup**.

Chaque minute passée à auditer doit converger vers cet objectif. Si une amélioration n'aide pas le LLM futur à produire du code correct, c'est probablement pas critique — skipper vers ce qui compte.

Le repo doit être **aussi désambiguïsé que possible, sans devenir bureaucratique**. Les specs sont un outil, pas un but.

---

## Pour démarrer

```
cd /work/xaat/redface2 && gh auth switch --user XaaT && git pull origin main
```

Puis poser les 10 questions pré-Phase A à l'utilisateur, en un seul message structuré.

Bonne chance. Fais du travail dont tu serais fier de signer le commit.
