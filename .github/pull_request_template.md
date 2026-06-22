## Summary

<!-- Résume le problème traité et l'effet attendu de la PR. -->

## Linked Issue

- Closes #

## Changes

- 

## Validation Run

- [ ] Branché depuis `origin/dev` frais
- [ ] `./scripts/docker-dev.sh ./gradlew detektAll :app:lintProdDebug` (detekt racine + lint flavoré)
- [ ] `./gradlew :feature:<module>:testDebugUnitTest` — modules touchés **exécutés** (pas `:app:testDevDebugUnitTest` seul, qui ne lance pas les tests feature/core)
- [ ] `./gradlew :app:assembleProdDebug` (avant push/PR)
- [ ] Fixtures ajoutées accompagnées d'un `.source.txt`/header
- [ ] `app/CHANGELOG.md` mis à jour si `versionName` change
- [ ] Autre validation utile décrite ci-dessous

## Docs / Specs Impact

- [ ] Aucun
- [ ] Oui, docs/specs mises à jour

## Review

- [ ] Review Codex jointe (cadrage si non-trivial + relecture du diff) — label `codex-reviewed`
- [ ] Validation par un agent/relecteur distinct du producteur

## AI Attribution

<!-- Si la PR a été rédigée ou implémentée avec aide IA, reprendre la ligne d'attribution du projet. -->

