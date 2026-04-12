# Agents — Redface 2

Guide pour les subagents qui travaillent sur ce repo.

## Projet

Client Android pour forum.hardware.fr. **Phase actuelle : spécifications** (aucun code).
Toute la documentation est dans `docs/` (Jekyll + just-the-docs, déployé sur GitHub Pages).

## Fichiers de specs et leurs dépendances

```
docs/
  index.md          — vue d'ensemble, diagramme simplifié (doit refléter architecture.md)
  stack.md           — choix techniques justifiés (source de vérité pour les technos)
  architecture.md    — modules Gradle, couches, data flow, cache, session, sécurité, erreurs
  navigation.md      — écrans, flows, deep linking, back stack
  models.md          — data classes Kotlin (source de vérité pour les types)
  mvi.md             — pattern MVI, exemples ViewModel/Screen/State (utilise les types de models.md)
  features.md        — features communautaires, architecture d'extensions
  naming.md          — candidats pour le nom de l'app
  roadmap.md         — phases de développement
  contributing.md    — conventions, tests, accessibilité, localisation
  _config.yml        — config Jekyll (version des specs dans le footer)
```

### Graphe de dépendances entre fichiers

Un changement dans un fichier impacte potentiellement les autres :

```
models.md ──→ mvi.md (types utilisés dans les exemples State/Intent/Effect)
    │     ──→ architecture.md (noms de modèles dans les exemples Repository)
    │     ──→ navigation.md (champs utilisés dans les deep links)
    │
architecture.md ──→ features.md (modules extension, interfaces PostDecorator)
    │           ──→ contributing.md (structure de fichiers, conventions)
    │           ──→ index.md (diagramme simplifié)
    │
stack.md ──→ mvi.md (composants utilisés : PullToRefreshBox, ObserveAsEvents)
    │    ──→ architecture.md (libs mentionnées : OkHttp, Jsoup, Room, Coil)
    │
roadmap.md ──→ architecture.md (phases des modules)
```

**Règle absolue :** après toute modification, vérifier tous les fichiers en aval dans ce graphe.

## Architecture actuelle (v0.3.0)

23 modules Gradle en 3 couches :

**Core (8) :** model, domain, data, network, parser, database, ui, extension
**Features base (7) :** forum, topic, editor, messages, auth, search, settings
**Features extension (8, Phase 4) :** bookmarks, blacklist, qualitay, redflag, colortag, imagehost, gifpicker, stats

Les features ne dépendent que de `:core:domain` + `:core:ui`. Jamais de network/parser/database directement.

## Tâches courantes pour un subagent

### Modifier un modèle de données

1. Modifier dans `docs/models.md` (data class Kotlin + diagramme mermaid)
2. Vérifier `docs/mvi.md` — les exemples State/Intent utilisent-ils ce modèle ?
3. Vérifier `docs/architecture.md` — les exemples Repository utilisent-ils ce modèle ?
4. Vérifier `docs/navigation.md` — les deep links utilisent-ils des champs de ce modèle ?

### Modifier l'architecture (ajouter/retirer un module)

1. Modifier dans `docs/architecture.md` :
   - Diagramme mermaid des modules Gradle
   - Tableau des modules core OU feature
   - Description de la couche concernée
2. Vérifier `docs/features.md` — les modules extension sont-ils impactés ?
3. Vérifier `docs/contributing.md` — la structure de fichiers est-elle à jour ?
4. Vérifier `docs/index.md` — le diagramme simplifié est-il encore cohérent ?

### Faire un audit de specs

1. Lire TOUS les fichiers `docs/` en entier
2. Structurer les findings par sévérité (critique > important > moyen)
3. Pour chaque point : fichier, ligne, problème, fix proposé
4. Vérifier les exemples Kotlin : types corrects, propriétés existantes, imports cohérents
5. Vérifier les diagrammes mermaid : correspondent-ils au texte ?
6. Utiliser un agent reviewer séparé pour valider (pas de self-review)

### Poster sur le topic HFR

Topic Redface 2 : cat=23, post=35395

Règles BBCode :
- `[fixed]` est block-only, jamais inline → utiliser `[b]` pour le monospace inline
- Ne jamais utiliser de smileys non vérifiés (`:fleche:` et `:flechd:` n'existent pas)
- Pas de `[size=X]` (n'existe pas sur HFR)
- Color : `[#CC0000]texte[/#CC0000]` (fermer avec la même couleur)

## Discussions en cours

- **Issue #2** : Koin vs Hilt, KMP, MVI vs MVVM (retour communauté de Corran Horn)
- **Issue #15** : Parser modulaire, lib pure Kotlin/JVM indépendante d'Android
- **Issue #16** : Guidelines dev IA, attribution, skills

## Ne pas faire

- Ne pas modifier la stack sans discussion en issue
- Ne pas fermer une issue sans commentaire explicatif
- Ne pas hardcoder des strings UI dans les exemples Kotlin
- Ne pas utiliser de composants dépréciés (Accompanist SwipeRefresh, etc.)
- Ne pas prendre de décision d'architecture seul — proposer et demander l'avis humain
