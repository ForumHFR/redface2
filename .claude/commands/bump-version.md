# Bump de version des specs

Met à jour la version des specs et crée un commit propre.

## Argument

$ARGUMENTS = numéro de version (ex: "0.4.0")

## Étapes

1. Vérifier qu'il y a des changements depuis la dernière version (git log)
2. Mettre à jour `docs/_config.yml` : footer_content avec la nouvelle version
3. Créer un commit :
   ```
   chore: bump spec version to v{version}

   {résumé des changements depuis la dernière version}

   Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
   ```
4. Pousser sur main
5. Confirmer que GitHub Pages se déploie
