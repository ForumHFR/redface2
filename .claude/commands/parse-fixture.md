# Analyse d'une fixture HTML HFR

Analyse un fichier HTML capturé depuis forum.hardware.fr et en extrait toutes les données utiles pour le parser et les modèles.

## Argument

$ARGUMENTS = chemin vers le fichier HTML à analyser

## Étapes

### 1. Identifier le type de page

Déterminer automatiquement quel type de page HFR c'est :
- Topic (`forum1.php`) — présence de `table.messagetable`
- Liste de topics (`forum2.php`) — présence de `tr.sujet`
- Drapeaux (`forum1f.php`) — présence de `tr.sujet` + contexte drapeaux
- Profil (`editprofil.php`) — présence de `form[name=ONSENFOU]`
- MPs (`message.php`) — présence de `cat=prive`
- Recherche (`search.php`)
- Login — présence de `login_validation`
- modo.php — présence de formulaire modération
- Autre — décrire

### 2. Extraire la structure DOM

Lister les éléments structurels importants :
- Conteneurs principaux (classes CSS, IDs)
- Tableaux et leurs colonnes
- Formulaires et leurs champs
- Navigation / pagination
- Éléments spécifiques HFR (cryptlinks, listenumreponse, etc.)

### 3. Extraire les données parsables

Pour chaque donnée visible dans la page :
- Nom du champ (ce que ça représente)
- Sélecteur CSS pour l'atteindre
- Type de donnée (String, Int, Instant, Boolean, enum)
- Exemple de valeur tirée de la fixture
- Nullable ? (présent dans certains cas seulement)

Format de sortie :
```
| Donnée | Sélecteur CSS | Type | Exemple | Nullable |
|--------|---------------|------|---------|----------|
```

### 4. Extraire les champs de formulaire

Si la page contient un formulaire :
```
| Champ | name= | Type HTML | Valeurs possibles | Défaut |
|-------|-------|-----------|-------------------|--------|
```

### 5. Comparer avec les modèles existants

Lire `docs/models.md` et vérifier :
- Quels champs extraits existent déjà dans les data classes
- Quels champs sont **nouveaux** (pas encore dans les modèles)
- Quels champs des modèles ne sont **pas présents** dans cette page

Format :
```
Champs existants : [liste]
Champs à ajouter : [liste avec type proposé]
Champs modèle absents de cette page : [liste]
```

### 6. Détecter les variantes logué / non-logué

Si le fichier est logué, identifier les éléments qui :
- N'apparaissent que pour un utilisateur connecté (boutons edit, quote, etc.)
- Changent selon le statut (propre post vs post d'un autre)
- Contiennent des données sensibles à nettoyer (cookies, tokens, hash_check)

### 7. Résumé

```
Type de page : [type]
Données parsables : [nombre]
Champs formulaire : [nombre]
Nouveaux champs à ajouter aux modèles : [liste]
Sélecteurs CSS clés : [liste]
Recommandation : [ce qu'il faut faire avec cette fixture]
```
