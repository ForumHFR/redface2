# Skills — Redface 2

Index humain des skills disponibles. Les skills vivent dans `.claude/skills/<name>/SKILL.md` au format [agentskills.io](https://agentskills.io/specification) et sont portables entre Claude Code, Cursor (Agent mode), OpenAI Codex, GitHub Copilot coding agent, Gemini CLI et JetBrains Junie.

Le source of truth des règles projet est [`AGENTS.md`](AGENTS.md).

---

## Skills disponibles

| Nom | Résumé | Phase | Fichier |
|---|---|---|---|
| `spec-audit` | Audit complet des specs — détecte incohérences, lacunes, optimisations | Toute phase | [.claude/skills/spec-audit/SKILL.md](.claude/skills/spec-audit/SKILL.md) |
| `spec-check` | Vérifie la cohérence cross-file des specs (noms, types, diagrammes) | Après chaque modif spec | [.claude/skills/spec-check/SKILL.md](.claude/skills/spec-check/SKILL.md) |
| `parse-fixture` | Analyse une fixture HTML HFR (type page, selectors, parser cible, cas logué/non-logué) | Phase 1+ | [.claude/skills/parse-fixture/SKILL.md](.claude/skills/parse-fixture/SKILL.md) |
| `hfr-post` | Rédige et poste un message sur le topic HFR Redface 2 (cat=23, post=35395) | Communication | [.claude/skills/hfr-post/SKILL.md](.claude/skills/hfr-post/SKILL.md) |
| `bump-version` | Bump de la version des specs + commit + push | Fin de cycle | [.claude/skills/bump-version/SKILL.md](.claude/skills/bump-version/SKILL.md) |
| `m3-check` | Audit Material 3 sur un écran/composant Compose (19 règles, rapport markdown) | Phase 0+ | [.claude/skills/m3-check/SKILL.md](.claude/skills/m3-check/SKILL.md) |
| `m3-screen` | Génère un écran Compose complet (State/Intent/ViewModel/Screen/Previews) | Phase 1+ | [.claude/skills/m3-screen/SKILL.md](.claude/skills/m3-screen/SKILL.md) |

---

## Invocation par outil

### Claude Code
```
/<skill-name> [arguments]
```
Exemple : `/bump-version 0.4.0`, `/parse-fixture /path/to/fixture.html`.

### Cursor (Agent mode)
Les skills sont auto-matchés par leur `description` dans le frontmatter. En mode Agent, demander :
> "Run the hfr-post skill with ..."

### OpenAI Codex
```
@codex use skill <name>
```
Ou via tool call si l'intégration MCP est active.

### GitHub Copilot coding agent
Le label `copilot` + la description du skill déclenche l'auto-matching. Alternativement, mentionner dans l'issue :
> "@github-copilot please use the <name> skill"

### Gemini CLI
```
/skill <name> [arguments]
```

### JetBrains Junie
Les skills sont détectés automatiquement dans `.claude/skills/`. Invocation via l'UI Junie.

### aider / Continue.dev
Aucune détection automatique des skills. Pour un LLM sur ces outils, copier manuellement le contenu du `SKILL.md` concerné dans le chat.

---

## Créer un nouveau skill

1. Créer le répertoire `.claude/skills/<slug>/`.
2. Créer `SKILL.md` avec frontmatter :
   ```yaml
   ---
   name: <slug>
   description: <une phrase qui déclenche l'auto-invocation — verbe + objet + contexte>
   argument-hint: <hint optionnel pour $ARGUMENTS>
   disable-model-invocation: true  # pour les commandes explicites (vs auto)
   ---
   ```
3. Body du skill en markdown standard, sans limite de taille.
4. Ajouter une ligne à ce `SKILLS.md`.
5. Tester l'invocation sur au moins Claude Code avant de commit.

---

## Format agentskills.io

Spécification : [agentskills.io/specification](https://agentskills.io/specification).

Frontmatter YAML :
- **Requis** : `name`, `description`
- **Optionnels** : `license`, `metadata`, `compatibility`, `allowed-tools`, `disable-model-invocation`, `argument-hint`, `paths`

Body : markdown standard. L'accès à des scripts/assets est possible via le sous-répertoire `scripts/`, `references/`, `assets/` (recommandé pour les skills complexes, pas nécessaire pour nos skills actuels).
