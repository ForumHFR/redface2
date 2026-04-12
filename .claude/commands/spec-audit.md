# Audit complet des specs

Analyse approfondie des spécifications pour détecter les incohérences, les lacunes et les optimisations possibles.

## Étapes

1. Lire TOUS les fichiers `docs/*.md` et `CLAUDE.md`
2. Analyser chaque page en profondeur :
   - Choix techniques : sont-ils encore à jour ? Alternatives meilleures ?
   - Modèles : champs manquants, types incorrects, redondances ?
   - Architecture : over-engineering ? Lacunes ? Dépendances incorrectes ?
   - Exemples de code : compilables ? Patterns dépréciés ?
   - Diagrammes : reflètent le texte ?
   - Roadmap : réaliste ? Bien ordonnée ?
3. Classer les findings par sévérité (critique / important / moyen)
4. Pour chaque point : décrire le problème, citer le fichier et la ligne, proposer un fix

## Format de sortie

Créer une issue GitHub avec :
- Titre : "Audit specs vX.Y.Z — N points à corriger"
- Body structuré par sévérité
- Un commentaire avec les propositions concrètes pour chaque point

Ne pas appliquer les corrections — lister ce qu'il faudrait changer.
