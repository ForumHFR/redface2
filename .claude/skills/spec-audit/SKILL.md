---
name: spec-audit
description: Audit exhaustif des specs Redface 2 pour détecter incohérences, lacunes et erreurs factuelles, avec classement par sévérité et fixes proposés. Use when user asks for a specs audit, review, or before bumping to a new version.
disable-model-invocation: true
---

# Audit complet des specs

Analyse approfondie des spécifications pour détecter les incohérences, les lacunes et les optimisations possibles.

## Étapes

1. Lire **TOUS** les fichiers `docs/*.md`, `AGENTS.md` (source of truth) et les drafts pertinents (`drafts/*.md`).
2. Analyser chaque page en profondeur :
   - **Choix techniques** : toujours à jour ? Alternatives meilleures en 2026 ?
   - **Modèles** : champs manquants, types incorrects, redondances ?
   - **Architecture** : over-engineering ? Lacunes ? Dépendances incorrectes ?
   - **Exemples de code** : conceptuellement compilables ? Patterns dépréciés (Accompanist, EncryptedSharedPreferences, etc.) ?
   - **Diagrammes mermaid** : reflètent bien le texte qui les entoure ?
   - **Roadmap** : réaliste ? Bien ordonnée par dépendances ?
3. Classer les findings par sévérité :
   - **Critique** : bloque Phase 0, erreur factuelle, incohérence majeure
   - **Important** : ambiguïté qui forcera le LLM à deviner
   - **Moyen** : polish, style, formulation
   - **Opportunité LLM-first** : ce qui manque pour qu'un LLM travaille efficacement
4. Pour chaque point : décrire le problème, citer `fichier:ligne`, proposer un fix concret, estimer l'effort (petit / moyen / gros).

## Format de sortie

Créer une issue GitHub (ou un fichier `drafts/audit-vX.Y.md`) avec :
- **Titre** : `Audit specs vX.Y — N points à corriger`
- **Body** : structuré par sévérité, avec tableaux récapitulatifs
- **Plan de batchs** : organiser les findings en batchs committables indépendamment, avec dépendances

Ne **pas** appliquer les corrections pendant l'audit — lister ce qu'il faudrait changer. L'exécution se fait dans un second temps, après validation humaine du plan.

## Méthodologie recommandée

- Utiliser des agents (Explore, general-purpose) en parallèle pour : lecture docs, code v1, stack 2026, multi-LLM state of the art, résumés de drafts lourds.
- Toujours vérifier les versions de librairies dans la doc officielle au moment de l'audit.
- Préférer `hfr_read` pour valider les claims contre HFR réel (topic IDs, exemples, fixtures).
