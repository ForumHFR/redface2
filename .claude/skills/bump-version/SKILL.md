---
name: bump-version
description: Bump la version des specs Redface 2 (footer _config.yml + CHANGELOG) et prépare un commit propre. Use when user asks to release a new specs version or prepare v0.X.Y.
argument-hint: numéro de version (ex 0.4.0)
disable-model-invocation: true
---

# Bump de version des specs

Met à jour la version des specs et crée un commit propre.

## Argument

`$ARGUMENTS` = numéro de version (ex: `0.4.0`).

## Étapes

1. Vérifier qu'il y a des changements depuis la dernière version (`git log --oneline origin/main..HEAD` ou depuis le tag précédent).
2. Mettre à jour `docs/_config.yml` : `footer_content` avec la nouvelle version.
3. Mettre à jour `CHANGELOG.md` (s'il existe, le créer sinon) : ajouter une entrée `## v<version> — YYYY-MM-DD` avec :
   - Résumé des batchs appliqués
   - Liens vers les commits clés
   - Migrations / breaking changes éventuels
4. Créer un commit :
   ```
   chore: bump spec version to v<version>

   <résumé des changements>

   Co-Authored-By: Claude Opus <version modèle> (1M context) <noreply@anthropic.com>
   ```
5. **Demander confirmation avant `git push origin main`**.
6. Une fois pushé, confirmer que GitHub Pages se déploie (5–10 min) en vérifiant le footer mis à jour sur [forumhfr.github.io/redface2](https://forumhfr.github.io/redface2/).
7. Créer un tag git `specs-v<version>` si la convention l'adopte.

## Attribution

Le `Co-Authored-By` doit refléter le **modèle exact** utilisé (ex : `Claude Opus 4.7 (1M context)`, `GPT-5 Codex`, etc.).
