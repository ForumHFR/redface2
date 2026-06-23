---
name: codex-review
description: Fait cadrer/relire/valider un plan, un diff ou un prompt par un agent distinct (Codex) hors-bande, et intègre le verdict. Use for the project's separate-validator cadence (framing before a non-trivial change, diff review, gate before merge).
argument-hint: "<plan|diff|prompt à reviewer> + la question précise à trancher"
disable-model-invocation: true
---

# /codex-review — validation par un agent distinct (Codex)

Matérialise la cadence projet « le même agent ne produit + valide pas seul » ([AGENTS.md] § Cadence de validation). L'agent producteur (ex. Claude) fait **cadrer** l'approche (avant un chantier non-trivial), **relire** le diff, et **gater** avant merge par un agent distinct.

> ⚠️ **Dépendance hors-repo, par conception.** L'implémentation de référence repose sur le CLI `codex` (harness-side, **non versionné** dans ce repo). Sur un agent qui n'a pas Codex, **dégrader** vers : un autre LLM distinct, une review humaine, ou `/code-review`. Ce skill décrit le **flow** (et la discipline), pas un binaire garanti présent.

## Flow A — dossier de faits (reviewer un plan / diff / prompt collé)

1. Écrire un **dossier de faits** : contexte (résumé, pas d'exploration attendue) + l'objet collé (diff / plan / prompt) + la **question précise** à trancher. **Aucun secret.**
2. Lancer Codex (réf. wrapper `codex-run.sh` côté harness ; modèle/effort = ceux que le harness fournit).
3. Lire le **VERDICT**, l'intégrer, re-soumettre si une 2ᵉ passe est demandée.

## Flow B — repo-context (confronter au code vivant)

Quand la review doit vérifier des affirmations contre le **code réel** : lancer `codex exec` **dans** le repo (lecture autorisée, ne PAS interdire l'exploration), sans modifier ni builder.

## Règles

- Les verdicts Codex se **recoupent au code** — ils ne font pas autorité seuls (Codex se trompe aussi : exemple vécu, un `runBlocking` prod manqué).
- Préfixer « NE MODIFIE RIEN » pour une review pure.
- Toujours **intégrer** le verdict avant de conclure (cycle NO-GO → fix → GO).
- Mode dossier-de-faits = pas d'exploration (anti-timeout) ; mode repo-context = exploration voulue. Choisir selon la question.
