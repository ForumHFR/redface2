---
title: ADRs
nav_exclude: true
---

# Architecture Decision Records

Les ADRs formalisent les décisions structurantes **après arbitrage réel**, pas avant.

Format retenu : **Michael Nygard minimaliste**.

Chaque ADR contient :
- **Statut**
- **Contexte**
- **Décision**
- **Conséquences**
- **Alternatives considérées**

Règles du repo :
- pas d'ADR spéculative
- pas d'ADR pour un micro-choix sans portée structurelle
- statut simple : `Accepté` ou `Superseded par ADR-XXX`
- les pages canoniques de `docs/` restent la source détaillée ; l'ADR capture le **pourquoi** et le **choix**

## Index initial

| ADR | Sujet |
|---|---|
| [ADR-000]({{ site.baseurl }}/adr/000-methodologie-triple-hybride) | Méthodologie triple-hybride SDD + Prototype + TDD |
| [ADR-001]({{ site.baseurl }}/adr/001-modules-gradle-v1) | Découpage Gradle v1 : 15 modules avant les extensions |
| [ADR-002]({{ site.baseurl }}/adr/002-credentials-option-a) | Credentials Option A : DataStore + Keystore, sans password stocké |
| [ADR-008]({{ site.baseurl }}/adr/008-compose-navigation-3) | Compose Navigation 3 retenu pour la navigation |
| [ADR-009]({{ site.baseurl }}/adr/009-okhttp-5-3-plus) | OkHttp 5.3+ retenu comme client HTTP principal |
