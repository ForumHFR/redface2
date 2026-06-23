---
name: techno-check
description: Vérifie le statut actuel d'une API/lib (existe ? dépréciée ? version stable ?) via Context7/Docfork et produit un rapport décision/ADR. Use before writing prod code or a spec snippet with an API whose status is uncertain.
argument-hint: "<lib/API> (ex: 'androidx.compose.material3 PullToRefreshBox' ou 'OkHttp 5 CookieJar')"
disable-model-invocation: true
---

# /techno-check — vérification d'API actuelle

Évite les pièges « API inexistante / dépréciée / pré-release » (SwipeRefresh, EncryptedSharedPreferences, Kotlin 2.4-SNAPSHOT pris pour la stable). Produit un **rapport vérifiable**, pas un rappel de prose.

## Argument

`$ARGUMENTS` = la lib / l'API à vérifier.

## Étapes

1. Résoudre la lib via Context7 (`resolve-library-id`) ou Docfork.
2. Interroger la doc en précisant **« stable release »** (Context7 indexe aussi snapshots / pre-release).
3. Confronter à la stack verrouillée (`gradle/libs.versions.toml`, `docs/specs/stack.md`).
4. Si la décision est structurante → scaffolder un ADR (`docs/adr/NNN-*.md`) ; sinon, pas d'ADR.

## Sortie (rapport)

```
API            : <nom>
Statut         : existe / dépréciée / inexistante
Version stable : <x.y.z> (source : Context7/Docfork, consulté le <date>)
Aligné stack   : oui/non (libs.versions.toml = <…>)
Décision       : <à utiliser / alternative <…> / ne pas utiliser>
ADR nécessaire : oui (scaffold #/chemin) / non
```

## Règles

- Ne jamais conclure sans avoir **réellement** interrogé la doc (pas depuis la mémoire).
- « stable release » obligatoire dans la requête.
- Pas d'ADR pour un choix non structurant (méthodo : ADR a posteriori, pas spéculatif).
- Un seul artefact = le rapport ci-dessus ; s'il n'apporte rien de vérifiable, ne pas lancer le skill (rester sur la règle prose de `methodology.md`).
