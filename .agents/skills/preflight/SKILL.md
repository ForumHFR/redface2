---
name: preflight
description: Vérifie qu'un agent (Claude Code, Codex, Cursor, Gemini CLI…) est paré pour travailler sur Redface 2 — MCP, identité git/gh, CLI, état du repo. Génère un rapport OK/MISSING avec fix concrets. Use at the start of a structuring session before any operation that interacts with HFR, GitHub, Context7 / Docfork, or the Docker build.
disable-model-invocation: true
---

# /preflight — vérifier l'environnement de l'agent

## Objectif

Lever les ambiguïtés sur l'environnement avant d'attaquer une session de travail :
- les MCP servers attendus sont-ils chargés ?
- les CLI / binaires nécessaires sont-ils disponibles et configurés correctement ?
- l'identité git/gh active est-elle bien celle que le contributeur veut utiliser pour ce repo ?
- le repo local est-il dans un état sain ?

Détecter ces choses **après** avoir commencé un travail = perte de temps + actions risquées (push sous une identité non voulue, commits inconsistants, blocage mid-session sur un MCP manquant, etc.).

## Quand l'invoquer

- Au début d'une session si tu n'es pas certain de ton environnement.
- Avant toute opération qui touche : HFR (capture fixtures, post, edit), GitHub (issues, PRs, commits, comments), Context7 / Docfork (vérif APIs stables), build Docker.
- Après un changement de configuration (ajout MCP, rotation comptes…).

## Étapes

### 1. MCP servers attendus

Vérifier que ces tools sont exposés dans la session courante. La méthode dépend de l'agent :

| Agent | Détection |
|---|---|
| Claude Code | tools `mcp__<server>__*` visibles dans la tool list |
| Codex CLI | `codex mcp list` (si dispo) ou tools exposés dans la session |
| Cursor | settings MCP visibles dans l'UI / `~/.cursor/mcp.json` |
| Gemini CLI | `gemini mcp list` ou config `~/.gemini/config.json` |

| MCP | Tools clés | Pourquoi | Fix si absent |
|---|---|---|---|
| `hfr` | `mcp__hfr__hfr_read`, `hfr_reply`, `hfr_edit`, `hfr_topics`, `hfr_cats`, `hfr_quote`, `hfr_mp`, `hfr_create_topic` | Capture fixtures HTML réelles, post/edit, lecture topic communauté | voir [hfr-mcp](https://github.com/XaaT/hfr-mcp), ajouter dans la config MCP de l'agent |
| `context7` ou `docfork` | resolve-library-id, get-library-docs (Context7) ou équivalent | Vérif APIs stables (cf. règle "Vérification API actuelle" dans `AGENTS.md`) | configurer dans la config MCP de l'agent |

### 2. CLI / binaires

| Tool | Test | Attendu | Fix si KO |
|---|---|---|---|
| `gh` actif | `gh auth status` | un compte avec `Active account: true` et scopes au moins `'repo', 'workflow', 'read:org'` | `gh auth login` ou `gh auth refresh -s repo,workflow,read:org` |
| Plusieurs comptes `gh` | `gh auth status` | si plusieurs comptes, l'actif doit être celui souhaité pour ce repo | `gh auth switch --user <login>` |
| `git user.name` local | `git config --local user.name` | non vide | `git config --local user.name <name>` |
| `git user.email` local | `git config --local user.email` | non vide | `git config --local user.email <email>` |
| `docker` ou `podman` | `docker info` ou `podman info` | exit 0 | dépend OS |
| Image build redface2 | `docker images \| grep cirruslabs/android-sdk` | image présente | `docker pull ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31` |

### 3. État du repo

| Check | Test | Attendu | Fix si KO |
|---|---|---|---|
| Working tree propre | `git status -s` | vide ou modifs intentionnelles | manuel |
| Pas en retard de `main` | `git fetch origin && git status -sb` | pas de `behind` | `git pull --ff-only` |
| Pas de stash résiduel inattendu | `git stash list` | vide ou justifié | manuel |
| Worktrees cohérents | `git worktree list` | pas de worktree fantôme sur des branches mortes | `git worktree prune` |
| Permissions `.gradle/` saines | `stat -c '%U' .gradle 2>/dev/null` | utilisateur courant ou inexistant | si appartenance autre utilisateur (ex: artefacts Docker possédés par `nobody`) : passer par un worktree clean (`git worktree add --detach …`) au lieu de toucher au workspace principal |

### 4. Identité git/GitHub — cohérence

⚠️ **Règle générique** : ce repo respecte les règles d'attribution définies dans `AGENTS.md` (Conventional Commits, attribution IA, co-author pour les commits IA-assistés). Une opération `gh issue/pr/api` faite sous un compte non-aligné avec celui que le contributeur a choisi pour ce projet peut entraîner :
- des commits ou PR sous une identité non voulue (gênant si tu sépares un alias dédié au projet d'une identité personnelle)
- des fuites involontaires (email perso dans des commits publics, rattachement d'activité à un autre profil)

Le skill ne prescrit aucune identité — il vérifie qu'elle est **définie et cohérente** :

- `gh auth status` montre un seul `Active account` ou plusieurs comptes dont un actif clairement choisi
- `git config --local user.name` / `user.email` sont définis pour ce repo (pas hérités d'une config globale qui pourrait fuiter)
- Si plusieurs comptes `gh` coexistent (compte perso + compte projet), confirmer que l'actif est celui prévu **avant** toute opération publique

Si le contributeur maintient une convention privée (alias dédié), elle vit dans son `~/.claude/CLAUDE.md`, son `~/.codex/instructions.md` ou équivalent — pas dans ce skill ni dans `AGENTS.md` du repo.

## Format de sortie

```
## Preflight Redface 2

### MCP
✅ hfr — mcp__hfr__hfr_read exposé
❌ context7 — non exposé. Fix: configurer dans la config MCP de l'agent

### CLI
✅ gh actif sur <login> avec scopes repo,workflow,read:org
✅ git local user.name = <name>
✅ git local user.email = <email>
✅ docker info OK
⚠️ Image cirruslabs/android-sdk:36 non pull — `docker pull ghcr.io/...`

### Repo
✅ working tree propre
✅ main à jour avec origin/main
✅ pas de stash résiduel
⚠️ .gradle possédé par <autre utilisateur> — utiliser un worktree clean pour le build

### Identité (cohérence)
✅ gh actif = <login>, git local = <name>/<email>
ℹ️ Plusieurs comptes gh détectés — actif: <login>. Vérifier que c'est l'identité voulue pour ce repo.

---

Verdict: ⚠️ READY WITH WARNINGS (2 fixes recommandés non bloquants)
```

Variantes du verdict :
- `✅ READY` — tout vert
- `⚠️ READY WITH WARNINGS` — checks non critiques en échec, opérations safes possibles
- `❌ NOT READY` — checks critiques en échec (gh non authentifié, identité git locale absente, MCP HFR pour une session édition HFR manquant…). L'agent ne doit pas continuer l'opération risquée tant que ce n'est pas résolu.

## Notes

- Le skill **ne modifie rien** : il diagnostique et propose des fix. C'est l'agent ou l'humain qui décide d'appliquer.
- Couvrir d'autres environnements (CI GitHub Actions, agent cloud) reste hors scope tant qu'on n'en a pas besoin.
- Si un nouveau MCP devient prérequis du projet, l'ajouter à la table § 1.
