# Analyse d'une fixture HTML HFR

Analyse un fichier HTML capturé depuis forum.hardware.fr dans le contexte de `redface2`.

Objectif :
- identifier précisément le type de page
- extraire uniquement les données utiles au parser et aux modèles
- décider quoi faire de cette fixture côté parser, tests et couverture logué/non-logué

## Argument

`$ARGUMENTS` = chemin vers le fichier HTML à analyser

## Règles

- Lire aussi `docs/models.md` et `docs/contributing.md`
- Ne pas faire une analyse HTML générique exhaustive : se concentrer sur les données **parser-relevant**
- Distinguer les signaux **forts** des signaux **faibles**
- Si le type de page reste ambigu, le dire explicitement avec un niveau de confiance
- Toujours identifier si une fixture jumelle logué/non-logué est nécessaire
- Toujours signaler les données sensibles à nettoyer avant commit

## Étapes

### 1. Identifier le type de page avec niveau de confiance

Déterminer le type de page en croisant :
- le nom du fichier
- l'URL probable / le path HFR visible dans le HTML si présent
- le titre de page
- les formulaires principaux
- les marqueurs DOM spécifiques

Types possibles :
- Topic (`forum1.php`) — présence de `table.messagetable`, ancres `a[name^=t]`, pagination topic
- Liste de topics (`forum2.php`) — présence de `tr.sujet`, colonnes topic/auteur/date
- Drapeaux (`forum1f.php`) — présence de `tr.sujet` + contexte "Mes sujets", drapeaux, filtres
- Profil / paramètres (`editprofil.php`, `profil.php`) — formulaires profil, pages settings
- MPs (`message.php`) — liste ou conversation de messages privés
- Recherche (`search.php`) — résultats de recherche
- Login / auth — page de validation ou échec login
- `modo.php` — formulaire ou état de modération
- Création / édition (`edit_post`, `edit_fp`, `new_topic`)
- Autre — décrire

Format :
```
Type de page : [type principal]
Confiance : [haute / moyenne / faible]
Indices forts : [liste]
Indices faibles : [liste]
Autres candidats : [liste si ambigu]
```

### 2. Cartographier la structure DOM utile

Lister uniquement les éléments structurels qui serviront réellement au parser :
- conteneurs racine
- blocs répétables
- pagination
- formulaires
- ancres HFR spécifiques (`cryptlink`, `listenumreponse`, `hash_check`, etc.)

Format :
```
| Élément structurel | Sélecteur / repère | Rôle parser |
|--------------------|--------------------|-------------|
```

### 3. Extraire les données parser-relevant

Pour chaque donnée utile au parsing ou aux modèles :
- nom du champ
- ce qu'il représente
- sélecteur CSS ou stratégie d'extraction
- type proposé
- exemple de valeur tirée de la fixture
- nullable ou non
- statut :
  - `source-of-truth`
  - `dérivée`
  - `présentation uniquement`

Ne pas lister le bruit purement visuel sans intérêt métier.

Format :
```
| Donnée | Sélecteur / extraction | Type | Exemple | Nullable | Statut |
|--------|-------------------------|------|---------|----------|--------|
```

### 4. Extraire les champs de formulaire

Si la page contient un formulaire, lister :
- `name`
- type HTML
- valeurs possibles
- valeur par défaut
- sens métier
- si le champ doit être conservé pour soumission future

Format :
```
| Champ | name= | Type HTML | Valeurs possibles | Défaut | Utilité |
|-------|-------|-----------|-------------------|--------|---------|
```

### 5. Comparer avec les modèles existants

Lire `docs/models.md` et répondre explicitement :
- quels champs extraits existent déjà dans les data classes
- quels champs sont nouveaux et méritent peut-être d'être ajoutés
- quels champs des modèles attendus ne peuvent pas être obtenus depuis cette page

Si la page correspond à un écran déjà décrit dans `navigation.md` ou `mvi.md`, le signaler.

Format :
```
Champs déjà couverts par les modèles :
- [...]

Champs à ajouter ou clarifier dans les modèles :
- [nom] -> [type proposé] -> [raison]

Champs attendus par les specs mais absents de cette page :
- [...]
```

### 6. Identifier le parser cible

Déterminer quel parser doit consommer cette fixture :
- parser existant si évident
- parser à créer si nécessaire

Format :
```
Parser cible : [nom]
Pourquoi : [raison]
Fonctions de parsing concernées : [liste]
```

Exemples :
- `TopicPageParser`
- `FlagPageParser`
- `CategoryParser`
- `EditPageParser`
- `SearchResultParser`
- `MessageParser`
- `LoginParser`
- `ProfileParser`
- `ModoParser`

### 7. Détecter les variantes logué / non-logué

Identifier :
- ce qui n'apparaît que connecté
- ce qui change selon "mon post" vs "post d'un autre"
- ce qui change selon la page (vide / pleine / dernière page / edge case)
- s'il faut une fixture jumelle logué/non-logué

Format :
```
Variantes détectées :
- [...]

Fixture jumelle recommandée :
- oui/non
- si oui : [type de variante à capturer]
```

### 8. Nettoyage et données sensibles

Lister ce qu'il faut nettoyer avant commit :
- cookies
- tokens / `hash_check`
- identifiants personnels
- emails
- URLs signées
- tout secret implicite

Format :
```
Données sensibles à nettoyer :
- [...]
```

### 9. Recommandation parser / tests

Conclure de manière actionnable :
- faut-il garder cette fixture
- faut-il créer une fixture jumelle
- quel test unitaire ou de non-régression elle doit alimenter
- quels sélecteurs CSS sont critiques
- quels modèles / specs doivent être mis à jour

Format :
```
Résumé :
- Type de page : [type]
- Confiance : [haute / moyenne / faible]
- Données parser-relevant : [nombre]
- Champs formulaire : [nombre]
- Parser cible : [nom]
- Fixture jumelle nécessaire : [oui/non]
- Sélecteurs CSS clés : [liste]
- Modèles à revoir : [liste]
- Test à ajouter : [description]
- Recommandation finale : [action concrète]
```

### 10. Écrire le résultat dans un fichier

**Toujours** écrire l'analyse complète (étapes 1-9) dans un fichier markdown persistant :

- Fichier de sortie : même chemin que la fixture avec le suffixe `.analysis.md`
  - `flags_page.html` → `flags_page.analysis.md`
  - `profil-p3/FORUM HardWare.fr.html` → `profil-p3/FORUM HardWare.fr.analysis.md`
- Le fichier contient l'intégralité de l'analyse structurée
- Ne pas compter sur le contexte conversationnel — le fichier doit être autosuffisant
