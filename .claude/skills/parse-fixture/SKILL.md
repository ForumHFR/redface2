---
name: parse-fixture
description: Analyser une fixture HTML HFR (type de page, sélecteurs CSS, parser cible, variantes logué/non-logué, données sensibles à nettoyer). Use when user provides an HTML file from forum.hardware.fr for parser spec alignment.
argument-hint: chemin vers le fichier HTML à analyser
disable-model-invocation: true
---

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
- Lire aussi `docs/navigation.md` et `docs/mvi.md` si la fixture correspond à un écran déjà spécifié
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
- Drapeaux (`forum1f.php`) — présence de `tr.sujet` + `td.sujetCase7` (icône drapeau, spécifique aux drapeaux)
- Profil public (`profil.php`) — page de profil utilisateur
- MPs (`message.php`) — liste ou conversation de messages privés
- Recherche (`search.php`) — résultats de recherche
- Login / auth — page de validation ou échec login
- `modo.php` — formulaire ou état de modération
- Création / édition (`edit_post`, `edit_fp`, `new_topic`)
- Settings profil (`editprofil.php?page=1-7`) — formulaires de paramètres utilisateur
- Liste de contacts (`contactlist.php`) — gestion des contacts
- Historique sanctions (`modo/historique.php`) — tableau des sanctions
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

### 3. Extraire les données JS inline

HFR embarque des données dans des balises `<script>` (pas dans le DOM). Chercher et extraire :
- `listenumreponse` — tableau JS des numreponse de la page
- Variables de configuration topic (cat, post, page, numreponse courant)
- `hash_check` s'il est en JS plutôt qu'en hidden field
- Toute autre variable JS contenant des données métier

Format :
```
| Variable JS | Contenu | Exemple | Utilité parser |
|-------------|---------|---------|----------------|
```

Si aucune donnée JS pertinente : noter "Aucune donnée JS inline détectée".

### 4. Extraire les données parser-relevant

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

### 5. Extraire les champs de formulaire

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

### 6. Comparer avec les modèles existants

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

### 7. Identifier le parser cible

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
- `ProfileParser` (profil public)
- `ProfileSettingsParser` (pages editprofil p1-p7, contactlist, historique sanctions)
- `ModoParser`

### 8. Détecter les variantes logué / non-logué

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

### 9. Nettoyage et données sensibles

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

### 10. Recommandation parser / tests

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

### 11. Écrire le résultat dans un fichier

**Toujours** écrire l'analyse complète (étapes 1-10) dans un fichier markdown persistant :

- Fichier de sortie : même chemin que la fixture avec le suffixe `.analysis.md`
  - `flags_page.html` → `flags_page.analysis.md`
  - `profil-p3/FORUM HardWare.fr.html` → `profil-p3/FORUM HardWare.fr.analysis.md`
- Le fichier contient l'intégralité de l'analyse structurée
- Ne pas compter sur le contexte conversationnel — le fichier doit être autosuffisant
- Par défaut, ce fichier est un artefact de travail local
- Ne le commit que si l'utilisateur le demande explicitement ou si le repo adopte une convention claire pour versionner ces analyses
