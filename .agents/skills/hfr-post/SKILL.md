---
name: hfr-post
description: Rédiger et poster un message BBCode sur l'un des deux topics Redface 2 de forum.hardware.fr (bêta cat=23 post=35395, dev cat=23 post=35421) en respectant les règles BBCode HFR et l'attribution IA. Use when user asks to post, reply or respond on a Redface 2 HFR discussion topic.
argument-hint: contenu ou instruction pour le post
disable-model-invocation: true
---

# Poster sur le topic Redface 2 (HFR)

Deux topics officiels, tous deux en `cat=23` :

- **Bêta / TU** : `post=35395` (créé par XaTriX le 11-04-2026) — retours des utilisateurs de la bêta (Play, F-Droid).
- **DEV** : `post=35421` — changelogs et tests du canal dev (une annonce par release dev).

Choisir le topic selon le canal concerné ; ne jamais poster un changelog dev sur le topic bêta.

> **Attention** : `cat=23, post=29332` est le topic **Redface v1** d'Ayuget (03-04-2015). Ne pas confondre.

## Argument

`$ARGUMENTS` = contenu ou instruction pour le post.

## Règles BBCode HFR

- `[fixed]` est **block-only** (comme `<pre>`), jamais inline → utiliser `[b]` pour du monospace inline
- Ne jamais utiliser `[size=X]` (n'existe pas sur HFR)
- Color : `[#CC0000]texte[/#CC0000]` (fermer avec la même couleur)
- Ne jamais utiliser de smileys non vérifiés (`:fleche:`, `:flechd:` n'existent pas)
- Smileys sûrs : `:)`, `:(`, `:o`, `:D`, `;)`, `:p`, `:pt1cable:`, `:jap:`, `:fou:`, `:pfff:`, `:sweat:`, `:bounce:`
- Accents français : **autorisés** (HFR les supporte, prouvé par les posts communauté avec "maîtrise", "périodes", "Général"). Ne pas dégrader en ASCII.

## Étapes

1. Rédiger le contenu en BBCode HFR en respectant les règles ci-dessus.
2. **Montrer le contenu à l'utilisateur avant de poster** — confirmation explicite obligatoire.
3. Poster avec `mcp__hfr__hfr_reply` (cat=23, post=35395 pour la bêta, post=35421 pour le dev).

## Attribution

Toujours terminer le post par `[i]Post par Claude <modèle> <version>[/i]` en utilisant le nom et la version exacts du modèle actuel (ex : `Claude Fable 5.1`, `Claude Opus 5`).

Pour les autres LLMs : `[i]Post par <LLM+modèle>[/i]` (ex : `GPT-5 Codex`, `Gemini 2.5 Pro`, etc.).
