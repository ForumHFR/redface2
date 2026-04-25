---
name: radar
description: Scanne issues, PRs, branches, milestones, CI et roadmap.md de Redface 2 et produit un rapport en 4 buckets (urgent / court terme / roadmap proche / gros chantiers). Spécialisé Redface 2 (lit la phase courante, ADRs, dépendances externes MPStorage2/hfr-redflag). Mode `collecte` par défaut (signaux objectifs), mode `score` en argument pour avis subjectif.
argument-hint: "[score] — sans argument: collecte brute. Avec 'score' ou 'triage': ajoute une couche de scoring subjective."
disable-model-invocation: true
---

# /radar — radar repo Redface 2

## Objectif

Faire le tour rapide de l'état du repo et produire une liste actionnable de **quoi faire ensuite**. Pas un audit, pas une review : un radar court qui catégorise issues / PRs / branches / CI / roadmap pour que le contributeur (humain ou agent) attaque le bon truc.

Spécialisé Redface 2 :
- connaît les phases (`docs/specs/roadmap.md`, milestones GitHub `Phase N — *`)
- détecte les blocages structurels en lisant `docs/specs/roadmap.md` (sections "prérequis externes", graphe mermaid de dépendances) et les corps d'issues du milestone courant (cherche `bloque #N`, `dépend de #N`, `prérequis #N` dans la description, ou les références d'autres issues via `gh issue view <n> --json body`). Ne pas hardcoder les numéros d'issues dans ce skill : ils dérivent dès qu'une issue est scindée ou fermée. Utiliser uniquement la roadmap canonique et les liens entre issues.
- connaît la méthodologie triple-hybride et le fait que le projet **n'a pas de dates fermes** — les phases sont ordonnées par dépendances, pas datées (cf. `docs/specs/methodology.md`)

## Quand l'invoquer

- Début de session quand on hésite sur la prochaine action.
- Avant d'ouvrir une nouvelle PR pour vérifier qu'on ne marche pas sur une PR existante.
- Avant un sync (humain ↔ agent, ou cross-agent) pour partager une vue d'ensemble.
- Après une grosse merge pour voir ce que ça débloque.

## Modes

| Mode | Argument | Sortie |
|---|---|---|
| **Collecte** (défaut) | aucun | Buckets remplis avec **signaux objectifs uniquement** (CI rouge, PR >14j, milestone courant, etc.). Aucune opinion, aucun classement à l'intérieur d'un bucket. |
| **Score** | `score` ou `triage` | Même collecte + couche **subjective** : score 1-5 par item (impact × effort × débloque-quoi) et top-3 recommandé pour la prochaine session. |

Le mode collecte est sûr (on ne se trompe pas en restituant des faits). Le mode score est une **opinion d'agent** : utile en lecture rapide, mais à recouper avec le jugement humain. Toujours afficher `mode: collecte` ou `mode: score` en tête du rapport.

## Étapes

### 1. Source of truth — phase courante

Lire :
- `docs/specs/roadmap.md` → lister les phases et leur statut (`À faire`, `En cours`, `Fait`).
- `docs/specs/methodology.md` → noter le mode opérationnel courant (spec / prototype / TDD).
- Milestones GitHub : `gh api repos/ForumHFR/redface2/milestones --jq '.[] | {title, due_on, open_issues, closed_issues}'`.

La phase **active** = première phase avec `open_issues > 0` ET dont les phases précédentes sont closes ou marquées « non bloquantes ». Ne pas inférer un statut depuis les dates des milestones — `due_on` est indicatif, pas contractuel.

### 2. Collecte des signaux

Tous les signaux ci-dessous sont objectifs (vrai/faux, pas d'opinion). Lancer en parallèle quand possible.

| Signal | Commande | Bucket par défaut |
|---|---|---|
| CI rouge sur `main` | `gh run list -R ForumHFR/redface2 --workflow=ci.yml --branch=main --limit 5 --json conclusion,createdAt` | **Urgent** |
| CI rouge sur PR ouverte | `gh pr list -R ForumHFR/redface2 --json number,statusCheckRollup --jq '.[] \| select(.statusCheckRollup[]?.conclusion=="FAILURE")'` | **Urgent** |
| Security alerts (Dependabot) | `gh api repos/ForumHFR/redface2/dependabot/alerts --jq '.[] \| select(.state=="open") \| {severity:.security_advisory.severity, package:.dependency.package.name, summary:.security_advisory.summary}'` | **Urgent** (severity ≥ moderate) |
| PR ouverte >14j sans review | `gh pr list -R ForumHFR/redface2 --state open --json number,title,createdAt,reviewDecision \| jq '[.[] \| select((now - (.createdAt \| fromdateiso8601)) > 1209600 and .reviewDecision != "APPROVED")]'` | **Urgent** |
| PR ouverte récente (< 14j) | `gh pr list -R ForumHFR/redface2 --state open` | **Court terme** |
| Issues `priority:high` ou `bug` ouvertes | `gh issue list -R ForumHFR/redface2 --state open --label "bug"` (le projet n'a pas encore de label `priority:*` ; ajouter ici les labels qui apparaissent plus tard) | **Urgent** si `bug`, sinon bucket de la phase concernée |
| Issues du milestone courant | `gh issue list -R ForumHFR/redface2 --state open --milestone "<phase courante>"` | **Roadmap proche** |
| Issues phase suivante | même commande, milestone N+1 | **Gros chantiers** |
| Issues phases ultérieures (N+1, N+2, …) | une commande par phase, ex. `for p in phase-2 phase-3 phase-4 phase-5; do gh issue list -R ForumHFR/redface2 --state open --label "$p" --json number,title,labels; done`. ⚠️ `gh issue list --label "a,b,c"` filtre en **AND** (intersection), pas en OR — ne pas utiliser en CSV pour une union de phases. | **Gros chantiers** |
| Branches feature actives | `git branch -r --list 'origin/feature/*' --sort=-committerdate \| head -10` | annoter chaque PR avec sa branche pour repérer les branches sans PR |
| Branches sans PR | comparer `git branch -r --list 'origin/feature/*'` avec `gh pr list --json headRefName --jq '.[].headRefName'` | **Court terme** (à transformer en PR ou nettoyer) |
| TODO/FIXME récents (commits derniers 30j) | `git log --since="30 days ago" --pickaxe-regex -S 'TODO\|FIXME' --oneline -- '*.kt' '*.md' \| head -20` puis pour chaque commit intéressant `git show <sha> -- '*.kt' '*.md' \| grep -E '^\+.*\b(TODO\|FIXME)\b'`. ⚠️ Éviter `git log -p` brut sur 30 jours : génère le diff complet de tous les commits `.kt`/`.md` avant de filtrer, lent dès que le repo grossit. `--pickaxe-regex -S` filtre côté Git et ne diff que les commits qui modifient effectivement une occurrence. | **Court terme** |
| Drafts ADR | `for f in docs/adr/0*.md; do status=$(awk '/^## Statut/{getline; getline; print; exit}' "$f"); echo "$f → $status"; done` puis filtrer ceux dont le statut commence par `Proposé`. ⚠️ Le statut est à 2 lignes après l'en-tête `## Statut` (ligne vide intermédiaire) — un `grep -A1 '## Statut'` ne fonctionne **pas**. | **Gros chantiers** (décisions structurelles à acter) |
| Dépendances externes | regarder hardcodé : MPStorage2 (`gh repo view XaaT/hfr-redkit --json updatedAt`) et hfr-redflag (`gh repo view XaaT/hfr-redflag --json updatedAt`) | **Gros chantiers** + flag « bloquant Phase N » |

Notes :
- `gh api dependabot/alerts` peut retourner 404 si Dependabot n'est pas activé sur le repo — c'est un signal en soi (à activer ?), pas une erreur du skill. Le rapporter en `ℹ️`.
- Toujours filtrer les issues `state:open`. Le radar ne re-traite pas le clos.
- Pour les issues, **toujours afficher le numéro et le label de phase** (ex. `#15 [phase-1] HfrParser …`) — le numéro est le levier principal.

### 3. Buckets

Quatre buckets, dans cet ordre. Toujours rendre les quatre, même vides (`(rien)` dans ce cas).

#### 3.1 Urgent
Bloquants immédiats. Doit pouvoir être attaqué dans la session courante.
- CI rouge sur `main` ou sur une PR ouverte
- Security alerts sévérité ≥ moderate
- PR ouverte >14j sans review et sans activité (signal de stagnation)
- Issues `bug` ouvertes (en attendant un label `priority:high`)

#### 3.2 Court terme (cette semaine)
Éléments qui méritent d'être tranchés rapidement mais pas critiques.
- PR ouvertes <14j (statut review, CI, derniers commentaires)
- Branches feature locales sans PR (à transformer ou supprimer)
- TODO/FIXME ajoutés dans les commits des 30 derniers jours
- Issues `bug` non triées dans une phase

#### 3.3 Roadmap proche (phase courante)
Issues attachées au milestone de la **phase courante** (cf. § 1).
- Trier par : présence d'un blocage déclaré dans la description → bloquantes en premier, puis grosses (label `architecture`, `storage`, `navigation`), puis le reste.
- Annoter chaque issue avec la liste de ses dépendances connues (ex. `#3 PostRenderer ← bloquée par #15 HfrParser`).
- Phase courante par défaut détectée auto en § 1 ; le contributeur peut override en passant un argument (à étendre plus tard).

#### 3.4 Gros chantiers (phases suivantes + structurels)
- Issues des phases N+1 et au-delà.
- ADRs `Proposé` non actées.
- Dépendances externes (MPStorage2, hfr-redflag) — flagger comme `🔗 externe`.
- Initiatives spec-only (pas encore d'issue) repérées dans `roadmap.md` comme « À acter ».

### 4. Mode `score` (optionnel)

Si l'argument est `score` ou `triage` :
1. Pour chaque item, scorer **impact** (1-5) × **effort inverse** (1 = gros / 5 = petit) × **débloque** (nb d'autres items que ça libère, plafonné à 3).
2. Trier l'intérieur de chaque bucket par score décroissant.
3. Ajouter en tête de rapport un **top-3** « si tu n'attaques qu'une chose cette semaine, attaque … » — avec une justification d'une phrase par item.
4. Toujours rappeler : `Score subjectif (mode score) — recouper avec le jugement humain.`

Ne jamais scorer en mode collecte. Ne jamais ajouter de top-3 en mode collecte. Si le mode est ambigu, défaut = collecte.

## Format de sortie

````
## /radar Redface 2 — <YYYY-MM-DD>

mode: <collecte | score>
phase courante: <Phase N — Nom> (milestone: N open / M closed)
mode opérationnel: <spec | prototype | TDD selon methodology.md>

### 🚨 Urgent
- (rien) ou liste

### ⏱️ Court terme
- ...

### 🗺️ Roadmap proche (phase courante)
- ...

### 🏔️ Gros chantiers
- ...

---

ℹ️ Notes (signaux secondaires, dépendances externes, drafts ADR, etc.)
````

Variante mode `score` : ajouter avant les buckets :

````
### ⭐ Top 3 recommandé (mode score)
1. **#NNN — <titre>** (score X/Y) — <justification 1 phrase>
2. ...
3. ...
````

## Notes

- Le skill **ne modifie rien** : c'est un radar lecture-seule. Aucun `gh issue create`, `gh pr comment`, push, ni édition de fichiers. Si une action est suggérée, c'est au contributeur (ou à un autre skill) de la déclencher.
- Les commandes réseau (`gh`, `git fetch`) doivent être autorisées par l'agent ou l'humain. Le skill assume que la session a au moins `gh auth status` OK et un `git fetch` récent — sinon il le note dans le rapport.
- Si le repo n'a pas encore de tags `phase-N` ou de milestones (cas projet jeune), tomber en mode dégradé : afficher la liste brute des issues open sans bucket roadmap. Le rapport reste utile.
- Le mode `score` est explicitement marqué « subjectif ». Jamais utilisé pour une décision automatisée — c'est une aide à la lecture, pas une priorité figée.
- Couplage avec d'autres skills : `/preflight` avant pour vérifier que `gh` est OK ; `/spec-reality` pour le pendant code/spec (le radar ne touche pas à la cohérence interne) ; `/spec-audit` pour un audit lourd des specs (radar = court, audit = long).
