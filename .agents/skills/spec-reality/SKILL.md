---
name: spec-reality
description: Vérifie l'alignement entre les specs canoniques (docs/specs/), les ADR (docs/adr/) et le code réel de Redface 2 (settings.gradle.kts, libs.versions.toml, modules Kotlin). Détecte les écarts par sévérité (critique / important / mineur). Complémentaire à spec-check (cross-spec) et spec-audit (audit lourd des specs seules).
disable-model-invocation: true
---

# /spec-reality — cohérence specs/ADR ↔ code

## Objectif

Détecter les écarts entre **ce que la doc dit** et **ce que le code fait**. Trois sources de vérité à recouper :

1. **Specs canoniques** (`docs/specs/*.md`) — architecture, modèles, navigation, stack, méthodologie.
2. **ADR** (`docs/adr/*.md`) — décisions structurelles actées (statut « Accepté »).
3. **Code réel** — `settings.gradle.kts`, `gradle/libs.versions.toml`, `*/build.gradle.kts`, fichiers Kotlin sous `core/*/src/` et `feature/*/src/`.

Le skill **n'arbitre pas** quel côté a raison : il liste les divergences et propose un fix dans chaque sens (mettre à jour la spec, ou corriger le code, ou acter le manque dans une issue / ADR).

## Distinction avec les autres skills

| Skill | Périmètre | Question répondue |
|---|---|---|
| `spec-check` | `docs/specs/*` ↔ `docs/specs/*` (cross-file specs) | Les specs sont-elles cohérentes entre elles ? |
| `spec-audit` | `docs/specs/*` + `AGENTS.md` + drafts | Les specs sont-elles à jour, complètes, sans dette ? |
| **`spec-reality`** | `docs/specs/*` + `docs/adr/*` ↔ **code Kotlin/Gradle/libs** | La spec décrit-elle le repo réel ? |
| `radar` | issues, PRs, milestones, branches | Quoi attaquer maintenant ? |

Si les trois premiers passent vert, le projet est aligné de bout en bout.

## Quand l'invoquer

- Avant un bump de version specs (`bump-version`) — un bump qui fige une spec décollée du code crée de la dette.
- Après une refacto structurelle (ajout/suppression d'un module Gradle, renommage de package, bump de stack majeur).
- Avant une PR qui touche `architecture.md`, `models.md`, `stack.md` ou un ADR — pour s'assurer qu'on ne documente pas du vide.
- Périodiquement (1×/mois ou 1×/phase) en santé générale.

## Étapes

### 1. Recenser le périmètre

Lister les inputs canoniques :

| Source | Lecture |
|---|---|
| Modules Gradle déclarés | `grep -E '^include' settings.gradle.kts` |
| Modules dans la spec | `grep -E ':core:|:feature:' docs/specs/architecture.md docs/adr/001-modules-gradle-v1.md` |
| Versions effectives | `gradle/libs.versions.toml` (section `[versions]`) |
| Versions documentées | `docs/specs/stack.md` + ADRs avec versions (`009-okhttp-5-3-plus.md`, `008-compose-navigation-3.md`, etc.) |
| Modèles spec | `docs/specs/models.md` (classes dans le `mermaid classDiagram`) |
| Modèles code | `find core/model core/domain -name '*.kt'` puis grep des `data class`, `sealed class`, `sealed interface`, `enum class` |
| Repositories spec | `docs/specs/architecture.md` + `docs/specs/mvi.md` |
| Repositories code | `find core/domain -name '*Repository*.kt'` |
| ADR statut | `for f in docs/adr/0*.md; do status=$(awk '/^## Statut/{getline; getline; print; exit}' "$f"); echo "$f → $status"; done` (filtrer ceux qui commencent par `Accepté`). ⚠️ Le statut est à 2 lignes après l'en-tête `## Statut` (ligne vide intermédiaire) — un `grep -A1 '## Statut'` retourne la ligne vide, pas le statut. |

### 2. Checks par axe

#### 2.1 Modules Gradle — spec ↔ `settings.gradle.kts`

Pour chaque module mentionné dans `docs/specs/architecture.md` (diagramme + tableau + texte) ET dans `docs/adr/001-modules-gradle-v1.md` :
- Si le module **est documenté** mais **absent** de `settings.gradle.kts` → `Critique` (la doc est mensongère). Distinction : un module commenté avec `// Phase 4 …` est documenté comme « non bootstrap » et **n'est pas un écart**.
- Si le module **existe** dans `settings.gradle.kts` mais **non documenté** → `Important` (ADR-001 énumère 15 modules ; tout module qui n'apparaît pas dans cette liste doit être ajouté à la spec ou retiré du code).
- ⚠️ **Exception `:app`** : ADR-001 n'énumère que les modules `:core:*` et `:feature:*` (15 modules). Le module `:app` (l'entrypoint application) est documenté dans `docs/specs/architecture.md` (diagramme + texte) mais pas dans la liste ADR-001. Ne **pas** le signaler comme « non documenté » — il est l'entrypoint et hors périmètre ADR-001 par construction.

#### 2.2 Versions — `libs.versions.toml` ↔ `stack.md` / ADRs

Pour chaque library citée avec une version dans une spec ou un ADR :
- Comparer `[versions].<key>` avec la version documentée.
- **Critique** si l'ADR dit « X 5.3+ retenu » et le code est sous une majeure incompatible.
- **Important** si la spec mentionne une minor obsolète (Compose BOM, Kotlin, AGP) — risque de drift entre la doc et l'éco.
- **Mineur** si une patch version est en retard sur ce qui est documenté.

Versions à recouper (liste évolutive) :
- Kotlin (cf. `methodology.md` + `stack.md`)
- AGP
- Compose BOM
- OkHttp (ADR-009)
- Navigation 3 (ADR-008)
- Hilt
- Room
- Coil
- minSdk / targetSdk / compileSdk (cf. `CLAUDE.md` du repo : minSdk 29)

#### 2.3 Modèles — `models.md` ↔ Kotlin

Pour chaque classe du `classDiagram` mermaid de `models.md` :
- Si la classe est annotée « À définir avec les écrans » (cf. § « À définir avec les écrans » de `models.md`) **et n'a pas encore de fichier Kotlin** → ne pas signaler, c'est volontaire.
- Sinon, vérifier qu'un fichier `core/model/**/<ClassName>.kt` ou `core/domain/**/<ClassName>.kt` existe, et que les champs cités dans le diagramme apparaissent dans la `data class`.
- Champ documenté absent du code → `Important`.
- Champ dans le code absent de la spec → `Mineur` (peut être du détail d'implémentation, mais à signaler quand même).
- Type différent (ex. `String` côté spec, `Instant` côté code) → `Important`.

Commande pour lister les classes Kotlin existantes (à comparer aux classes du `classDiagram`) :

```bash
find core/model core/domain -name '*.kt' -exec grep -hE '^(data class|sealed class|sealed interface|enum class) [A-Z][A-Za-z0-9]*' {} \; | sort -u
```

Pour lister les champs d'une `data class` donnée et les confronter au diagramme :

```bash
awk '/^data class <ClassName>\(/,/^\)/' core/model/**/<ClassName>.kt core/domain/**/<ClassName>.kt 2>/dev/null
```

#### 2.4 Repositories — `architecture.md` / `mvi.md` ↔ `core/domain`

Pour chaque interface `*Repository` mentionnée :
- L'interface doit vivre dans `:core:domain`.
- Une implémentation doit exister dans `:core:data` (ou un module de données concret).
- Si la spec mentionne `TopicRepository` mais aucun fichier Kotlin n'existe → `Important` (manquant attendu Phase 1) ou `Critique` selon la phase courante (cf. `radar`).

#### 2.5 ADR « Accepté » non implémentés

Pour chaque ADR au statut `Accepté` :
- Lire la section **Décision** pour extraire les conséquences vérifiables (ex. ADR-002 « DataStore + Keystore, sans password stocké » → vérifier qu'il n'y a aucun usage de `EncryptedSharedPreferences` ni de stockage password en clair).
- Lire la section **Conséquences** : les points listés sont-ils visibles dans le code (frontières module, injection Hilt, etc.) ?
- Distinguer **contradiction** vs **manque** :
  - **Contradiction** = le code implémente activement quelque chose qui contredit l'ADR (ex. ADR dit "DataStore" mais le code utilise `EncryptedSharedPreferences`). Sévérité = `Critique` quelle que soit la phase.
  - **Manque** = la fonctionnalité dont parle l'ADR n'est pas encore implémentée du tout (ex. l'ADR auth `Accepté` mais aucun module auth n'existe encore). Sévérité = `Important` si la phase courante est censée l'implémenter, sinon `Mineur`. **Ne pas escalader en `Critique` sur un manque** — c'est juste de la dette de phase, pas une régression.
- Un ADR `Proposé` non encore implémenté = ne pas signaler (c'est l'attendu).

#### 2.6 Conventions feature — `contributing.md` ↔ structure réelle

Pour chaque convention de fichier dans `docs/guides/contributing.md` :
- Si la convention liste `TopicRepository.kt` dans un feature mais l'interface vit dans `:core:domain`, c'est la **convention** qui doit être corrigée, pas le code (cf. règle dans `AGENTS.md`).
- Vérifier que la structure type `feature/<name>/src/main/kotlin/.../{State,Intent,ViewModel,Screen}.kt` est respectée par les features qui existent (Phase 1 : `:feature:topic` au minimum).

#### 2.7 Dépréciations — `AGENTS.md` ↔ code

`AGENTS.md` interdit certains patterns dépréciés (`EncryptedSharedPreferences`, Accompanist `SwipeRefresh`, Compose Navigation string-based, etc.).

Regex à exécuter :

- `grep -rnE 'EncryptedSharedPreferences|SwipeRefresh' core/ feature/ app/ 2>/dev/null` — `EncryptedSharedPreferences` et Accompanist `SwipeRefresh` ne devraient apparaître nulle part. Toute occurrence = `Critique`.
- `grep -rnE 'com\.google\.accompanist\.swiperefresh' core/ feature/ app/ 2>/dev/null` — l'import Accompanist legacy. `Critique` si trouvé.
- Compose Navigation **2.x** string-based : ⚠️ ne **pas** utiliser une regex naïve sur `androidx.navigation.` — Navigation 3 utilise aussi ce préfixe (`androidx.navigation3.runtime`, `androidx.navigation3.ui`) ainsi que des sous-packages compatibles (ex. `androidx.navigation.compose` lors de la coexistence). Pattern recommandé pour cibler l'API 2.x : `grep -rnE 'NavGraphBuilder|composable\("[^"]+"|navController\.navigate\("[^"]+"' core/ feature/ app/ 2>/dev/null` — le triplet diagnostic est `NavGraphBuilder` (DSL de construction du graphe), `composable("route_string")` (déclaration de destination par String) et `navigate("route_string")` (navigation par String). À recouper visuellement — quelques faux positifs possibles (ex. analytics qui logue une route littérale). `Critique` si confirmé.

### 3. Sévérité

| Niveau | Critère | Action |
|---|---|---|
| **🔴 Critique** | Décision actée contredite par le code, frontière de compilation violée, dépendance dépréciée présente, version majeure incompatible avec un ADR | Bloquer le bump version specs / la PR structurelle. Issue ouverte le jour même. |
| **🟠 Important** | Spec décrit X, code n'a pas X (et la phase courante l'attendait), version minor en retard, type/nom de champ divergent | Tracer dans une issue, fixer dans la prochaine PR de la phase courante. |
| **🟡 Mineur** | Code a X non mentionné dans la spec, ordre de champs différent, formulation à harmoniser | Polish, peut attendre le prochain audit. |

### 4. Mode opératoire

Le skill **rapporte uniquement**, ne corrige pas. Pour chaque écart :

1. Citer la source : `docs/adr/009-okhttp-5-3-plus.md:23` ou `gradle/libs.versions.toml:20`.
2. Décrire l'écart en une phrase.
3. Proposer **deux fixes** (mettre à jour la spec OU corriger le code) — laisser le contributeur arbitrer.
4. Si l'écart révèle qu'une décision n'a jamais été actée formellement (code fait X, spec dit Y, pas d'ADR sur le sujet), proposer un **ADR à créer** comme troisième option.

Ne **jamais** appliquer un fix automatiquement. Cf. règle `AGENTS.md` : « Pas de décision implicite ».

## Format de sortie

````
## /spec-reality Redface 2 — <YYYY-MM-DD>

phase courante: <Phase N — Nom> (cf. radar pour le détail)
sources lues: docs/specs/*.md (N), docs/adr/*.md (M Accepté), settings.gradle.kts, gradle/libs.versions.toml, core/**/*.kt, feature/**/*.kt

### 🔴 Critique (N)

1. **<axe — sujet>**
   - source spec : `<fichier:ligne>` — « <citation courte> »
   - réalité code : `<fichier:ligne>` ou `(absent)`
   - fix A (mettre à jour la spec) : <action concrète>
   - fix B (corriger le code) : <action concrète>
   - fix C (acter dans un ADR) : <si pertinent>

### 🟠 Important (M)
…

### 🟡 Mineur (K)
…

---

Verdict :
- ✅ Aligné — aucun écart
- ⚠️ Aligné avec écarts mineurs — N mineurs, 0 critique / important
- ⛔ Désaligné — ≥1 critique ou ≥3 importants ; bloquer le prochain bump version specs

Stats : <X> écarts (rouge:R, orange:O, jaune:Y) sur <Z> éléments examinés.
````

## Notes

- Le skill assume que `gh` est OK et que `git fetch` est récent (cf. `/preflight`). Il ne re-vérifie pas l'environnement.
- Couplage : faire passer `/preflight` avant, puis `/spec-check` (cross-spec), puis `/spec-reality` (spec ↔ code). Les trois sont rapides combinés.
- Le skill **n'écrit pas** d'ADR ni d'issue. Il **propose**. C'est conforme à la règle `AGENTS.md` § « Validation séparée » : le même agent ne produit pas, corrige et valide seul un changement structurant.
- Si la phase courante est encore Phase 0 (bootstrap), la plupart des modèles/repos seront absents — c'est normal. Le skill doit être indulgent et privilégier la sévérité « important » plutôt que « critique » pour les manques attendus en phase amont.
- Si un ADR est marqué `Superseded par ADR-XXX`, le considérer comme historique et lire le successeur uniquement.
