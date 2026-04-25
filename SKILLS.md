# Skills — Redface 2

Index humain des skills disponibles. Les skills vivent dans `.agents/skills/<name>/SKILL.md` au format [agentskills.io](https://agentskills.io/specification) — emplacement recommandé par la spec pour l'interop cross-client. Claude Code les lit via le symlink `.claude/skills → ../.agents/skills`. Portables entre Claude Code, Cursor (Agent mode), OpenAI Codex, GitHub Copilot coding agent, Gemini CLI et JetBrains Junie.

Le source of truth des règles projet est [`AGENTS.md`](AGENTS.md).

---

## Skills disponibles

| Nom | Résumé | Phase | Fichier |
|---|---|---|---|
| `spec-audit` | Audit complet des specs — détecte incohérences, lacunes, optimisations | Toute phase | [.agents/skills/spec-audit/SKILL.md](.agents/skills/spec-audit/SKILL.md) |
| `spec-check` | Vérifie la cohérence cross-file des specs (noms, types, diagrammes) | Après chaque modif spec | [.agents/skills/spec-check/SKILL.md](.agents/skills/spec-check/SKILL.md) |
| `parse-fixture` | Analyse une fixture HTML HFR (type page, selectors, parser cible, cas logué/non-logué) | Phase 1+ | [.agents/skills/parse-fixture/SKILL.md](.agents/skills/parse-fixture/SKILL.md) |
| `hfr-post` | Rédige et poste un message sur le topic HFR Redface 2 (cat=23, post=35395) | Communication | [.agents/skills/hfr-post/SKILL.md](.agents/skills/hfr-post/SKILL.md) |
| `bump-version` | Bump de la version des specs + commit + push | Fin de cycle | [.agents/skills/bump-version/SKILL.md](.agents/skills/bump-version/SKILL.md) |
| `m3-check` | Audit Material 3 sur un écran/composant Compose (19 règles, rapport markdown) | Phase 0+ | [.agents/skills/m3-check/SKILL.md](.agents/skills/m3-check/SKILL.md) |
| `m3-screen` | Génère un écran Compose complet (State/Intent/ViewModel/Screen/Previews) | Phase 1+ | [.agents/skills/m3-screen/SKILL.md](.agents/skills/m3-screen/SKILL.md) |
| `preflight` | Vérifie l'environnement de l'agent (MCP, comptes, CLI, repo) avant une session structurante | Toute phase | [.agents/skills/preflight/SKILL.md](.agents/skills/preflight/SKILL.md) |
| `radar` | Scanne issues/PRs/branches/CI/roadmap et produit un rapport en 4 buckets (urgent / court terme / roadmap / gros chantiers). Modes collecte (objectif) ou score (subjectif). | Toute phase | [.agents/skills/radar/SKILL.md](.agents/skills/radar/SKILL.md) |
| `spec-reality` | Vérifie l'alignement specs + ADR ↔ code réel (modules Gradle, libs.versions.toml, modèles Kotlin, dépréciations). Sortie par sévérité. | Avant bump version, refacto structurelle | [.agents/skills/spec-reality/SKILL.md](.agents/skills/spec-reality/SKILL.md) |

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
Les skills sont détectés automatiquement dans `.agents/skills/`. Invocation via l'UI Junie.

### aider / Continue.dev
Aucune détection automatique des skills. Pour un LLM sur ces outils, copier manuellement le contenu du `SKILL.md` concerné dans le chat.

---

## Créer ou modifier un skill

1. Créer le répertoire `.agents/skills/<slug>/` (ou éditer un skill existant).
2. Créer / mettre à jour `SKILL.md` avec frontmatter :
   ```yaml
   ---
   name: <slug>
   description: <une phrase qui déclenche l'auto-invocation — verbe + objet + contexte>
   argument-hint: <hint optionnel pour $ARGUMENTS>
   disable-model-invocation: true  # pour les commandes explicites (vs auto)
   ---
   ```
3. Body du skill en markdown standard, sans limite de taille.
4. Ajouter ou mettre à jour la ligne correspondante dans ce `SKILLS.md`.
5. **Tester avant push** — exécuter le skill dans la session courante (au moins sur Claude Code), confronter le rapport produit aux checks documentés, et corriger les écarts (commande KO, formulation ambiguë, cas non couvert) avant d'ouvrir la PR. La description de la PR mentionne le rapport obtenu (ou le bug détecté) pour preuve. Cette étape s'applique aussi aux modifications d'un skill existant — un skill non testé qui passe la review n'a pas été vérifié, et les bugs sont silencieux jusqu'à la prochaine session sous stress.

---

## Format agentskills.io

Spécification : [agentskills.io/specification](https://agentskills.io/specification).

Frontmatter YAML :
- **Requis** : `name`, `description`
- **Optionnels** : `license`, `metadata`, `compatibility`, `allowed-tools`, `disable-model-invocation`, `argument-hint`, `paths`

Body : markdown standard. L'accès à des scripts/assets est possible via le sous-répertoire `scripts/`, `references/`, `assets/` (recommandé pour les skills complexes, pas nécessaire pour nos skills actuels).
