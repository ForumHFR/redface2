---
title: ADR-003
parent: ADRs
grand_parent: Spécifications
nav_order: 3
permalink: /adr/003-api-rest-hfr-hybride
---

# ADR-003 — Stratégie hybride REST + HTML pour la couche réseau HFR

## Statut

Accepté — 2026-05-02

## Contexte

L'hypothèse de travail historique du projet (et de la PR #102 en cours sur `feature/1c-a-forum-parser`) était que **HFR n'expose pas d'API REST structurée** et que toute la couche réseau devait scraper du HTML brut. Cette hypothèse est encodée explicitement dans [ADR-009](009-okhttp-5-3-plus.md) :

> Redface 2 ne consomme pas une API REST structurée. L'application récupère principalement du HTML brut HFR, puis le parse.

Cette hypothèse est **fausse**. La vérification empirique 2026-05-01 (200+ endpoints testés, fixtures JSON capturées, doc V1 MesDiscussions retrouvée sur Wayback Machine) a établi que :

1. **HFR expose une vraie API REST/JSON** sur `https://forum.hardware.fr/webservices/rest_api.php?uri=<URI>`. Le hub `/api/` documenté en V1 renvoie 404 sur HFR (mod_rewrite jamais activé) — il faut réécrire les `href` HATEOAS en `?uri=` côté client.
2. La portion exposée est **partielle mais cohérente** : forums, catégories, sous-catégories, topic listings, drapeaux personnels (lus/participés/favoris), et metadata d'un topic. Auth via les cookies `md_id`/`md_user`/`md_passs` issus de `login_validation.php`.
3. La **lecture du contenu d'un post** (`/posts/`) est cassée serveur (HTTP 500 inconditionnel, 18+ variantes de CSRF/headers/path testées) et reste donc HTML-only.
4. Les **MPs** ne sont **pas exposés** en REST (∼300 variantes scannées : path-style, slugs, fautes, brute force IDs 0-999, endpoints V1 `users/<X>/threads/`). Limitation structurelle confirmée sur 13+ ans (`hfr4droid` 2013 avait déjà un fallback HTML pour la cat MP).
5. Les **mutations REST** sont partiellement exploitables : les POST de création topic / réponse ont été testés live avec succès, et `PUT topics/` est routé avec `hash_check`. En revanche la sémantique métier des drapeaux REST reste sous-documentée (downgrade `flag_owntopic` refusé, no-op refusé, valeurs hors-bornes silently ignored).
6. Un **bench mesuré** (4 000 fetches × 8 endpoints, 120 000 itérations parser, depuis Pologne/WiFi — ratios invariants) donne pour une session simulée de 19 navigations : **−91% bytes, −71% temps, 3.45× speedup** vs scraping HTML. Topic header metadata : **220× plus petit, 5.47× plus rapide**.

La synthèse utile à la décision est intégrée dans cette ADR. Les logs d'exploration et les raw CSV du bench restent des archives locales non normatives ; les fixtures JSON versionnées deviennent la preuve rejouable côté tests.

La conséquence est qu'on doit décider **par domaine** entre REST et HTML, plutôt que d'imposer une seule source.

## Décision

Adopter une **stratégie hybride explicite REST + HTML**, formalisée par domaine dans la table ci-dessous. Cette table devient la référence pour toute la couche réseau de Redface 2 v1.

| Domaine | Source v1 | Endpoint | Justification |
|---|---|---|---|
| Liste catégories / sous-catégories | **REST** | `forums/hardwarefr/categories/`, `categories/{cat}/subcategories/` | 3-3.7× speedup, JSON déjà structuré, ordre éditorial préservé |
| Liste topics d'une (sous-)catégorie | **REST** | `categories/{cat}/[subcategories/{sub}/]topics/last/?page=N&results_per_page=50` | 2.6× speedup, drapeaux `is_read`/`flag_owntopic` natifs en auth. `25` est le défaut HATEOAS, `50` est la cible RF2 pour limiter les requêtes. |
| Drapeaux personnels (cyan / rouge / favoris) | **REST** | `categories/{cat}/topics/{participated,read,favorites}/`, ou globaux `topics/{…}/` | Endpoints dédiés, format normalisé, retire le besoin de scraper `forum1f.php?owntopic=N`. Attention : le format global est groupé par catégorie, le format par-cat est plat. |
| Topic header metadata (titre, sticky, count) | **REST** | `categories/{cat}/topics/{post}/` | Gain ×220 sur la taille (1 KB vs 220 KB pour la page HTML complète) |
| Lecture posts d'un topic (contenu) | **HTML** | `forum2.php?cat=N&post=M&page=P` | REST `/posts/` HTTP 500 inconditionnel — voie morte côté serveur |
| Liste MPs + lecture MP + envoi MP | **HTML** | `forum1.php?cat=prive`, `forum2.php?cat=prive&post=N`, `bddpost.php` | Aucun endpoint REST exposé (vérifié exhaustivement) |
| Mutations drapeaux | **HTML** | `addflag.php` / `delflag.php` | REST `PUT topics/{id}/` trop fragile (sémantique downgrade/no-op opaque) |
| Création topic / réponse à un topic | **HTML v1, REST candidat v2** | `bddpost.php` (v1) ou `POST topics/last/` + `POST topics/{id}/posts/` (validés live) | Garder HTML stable en v1, réévaluer REST quand on aura un cycle CI complet et un compte/topic de test dédié |
| Login | **HTML** | `login_validation.php` | Pas d'`/api/auth.php` exposé sur HFR |

Choix de design pour la couche REST :

1. **Pas de Retrofit.** [ADR-009](009-okhttp-5-3-plus.md) reste valide : les endpoints REST consommés sont peu nombreux et les payloads sont parsés via `kotlinx.serialization`. Un `HfrApiClient` léger au-dessus d'OkHttp suffit. La séparation réseau / parsing reste nette.
2. **Helper de rewrite HATEOAS obligatoire.** Tout `href` retourné dans un payload pointe vers `/api/<…>` qui renvoie 404 sur HFR. Une fonction validante réécrit uniquement les URLs `https://forum.hardware.fr/api/<path>` vers `https://forum.hardware.fr/webservices/rest_api.php?uri=<path>` au moment du déréférencement. Le rewrite vit dans la couche réseau, jamais exposé aux ViewModels.
3. **Limite de parallélisme.** OkHttp `maxRequestsPerHost = 5` (default) reste en place. Bench montre une saturation TCP côté Apache LDLC à 20 parallèles ; ≤ 10 connexions simultanées est le seuil sain. Pas de tuning custom requis.
4. **Pas de fallback runtime global.** Si un endpoint REST utilisé en v1 commence à renvoyer 500/404 en prod, on réintroduit une source HTML pour le domaine touché via une PR dédiée. Pas de double-source automatique côté happy path : on ne paye pas le coût d'un fallback systématique sur des endpoints qui marchent.
5. **Fixtures canoniques.** Les 6 fixtures `core/data/src/test/resources/fixtures/rest_*.json` capturées le 2026-05-01 (avec leurs `.source.txt` documentant la commande curl d'origine) deviennent les fixtures de référence pour les mappers REST. Comme pour les fixtures HTML, les captures authentifiées doivent avoir leurs données sensibles nettoyées avant commit. Les fixtures vivent à côté de leurs consumers : les DTO + mappers REST sont dans `:core:data`, donc les fixtures aussi (initialement capturées dans `:core:parser`, déplacées en Phase 1C-A).
6. **Pas de mutation REST en v1.** Même si création topic / réponse ont été validées live, l'écriture reste en HTML v1 (`bddpost.php`, `addflag.php`, `delflag.php`). Pour les drapeaux, la sémantique de `PUT topics/` est trop opaque et le risque de marquer involontairement des drapeaux en prod (testé : downgrade refusé, no-op refusé, hors-bornes silently ignored) interdit un usage v1. À réévaluer post-v1 avec un compte de test dédié.
7. **Frontières de modules.** `:core:network` porte le transport REST, la construction d'URL et le rewrite HATEOAS. Les DTO `@Serializable` et les mappings vers `:core:model` vivent dans `:core:data`, au plus près des repositories qui consomment les endpoints. `:core:parser` reste dédié aux formats HFR historiques nécessitant une transformation structurante (HTML posts/MPs, AST).

## Conséquences

### Sur la PR #102 et la branche `feature/1c-a-forum-parser`

- La stratégie HTML-first de la PR #102 est **superseded** par cette ADR pour cats / subcats / topic-list / drapeaux. La PR peut être remplacée ou réécrite, mais le choix durable est REST-first pour ces domaines.
- Les artefacts utiles déjà produits (notamment les 6 fixtures `rest_*.json` actuellement untracked) seront repris dans la PR REST-first.
- La PR d'implémentation REST-first devrait être structurée en 4 commits reviewables : (1) `HfrApiClient` + helper rewrite HATEOAS + fixtures, (2) DTO/mappers REST + repos, (3) câblage MVI / écrans Phase 1C, (4) suppression du code de parser HTML rendu obsolète pour ces domaines.

### Sur les ADRs existantes

- [ADR-009](009-okhttp-5-3-plus.md) reste **Accepté** pour la décision OkHttp 5.3+ direct, sans Retrofit. Son contexte "pas d'API REST structurée" est partiellement superseded par la présente ADR : le motif devient HTML brut **+** JSON REST léger, mais le choix technique reste optimal pour ce volume d'endpoints.
- Pas d'impact sur [ADR-002](002-credentials-option-a.md) (credentials) ni [ADR-008](008-compose-navigation-3.md) (navigation) ni [ADR-011](011-postcontent-ast.md) (PostContent AST — concerne le rendu, pas la source).

### Sur les specs canoniques

- `docs/specs/protocol-hfr.md` : à enrichir d'une section REST (endpoints retenus, format JSON, helper HATEOAS, limites observées).
- `docs/specs/architecture.md` : la couche `:core:network` accueille `HfrApiClient` à côté de l'OkHttp brut. Le diagramme des flux gagne une branche JSON parallèle à la branche HTML.
- `docs/specs/stack.md` : documenter `kotlinx.serialization` comme dépendance directe déjà déclarée dans `libs.versions.toml`, à consommer explicitement par les modules qui parseront du JSON REST.
- `docs/guides/contributing.md` : ajouter la convention de fixtures `rest_*.json` à côté des fixtures HTML existantes.

### Sur la testabilité

- Couverture **100%** sur les transformers JSON / mappers DTO REST, fixtures dictent l'exhaustivité — règle déjà appliquée aux parsers HTML, étendue aux données REST.
- `MockWebServer` (mockwebserver3) déjà câblé via OkHttp 5 → utilisable directement pour les tests d'intégration `HfrApiClient`.
- Tests d'intégration live HFR exclus de la CI (réseau réel + dépendance auth) — restent manuels via `hfr-mcp` au moment de capturer une fixture.

### Sur la dette technique

- La dépendance `Jsoup` reste nécessaire (HTML pour posts, MPs, mutations, login). Pas de gain de dépendance possible v1.
- `kotlinx.serialization` est déjà déclaré dans `gradle/libs.versions.toml` et consommé par plusieurs modules. La PR REST devra l'ajouter explicitement au module consommateur si les DTO/mappers REST changent de module.
- L'erreur d'analyse initiale (« HFR n'a pas de REST, basé sur la lecture de `hfr-mcp` ») est consignée dans la memory `reference_hfr_rest_api.md` § « Erreur à ne plus refaire » : la preuve qu'une API n'existe pas, c'est de tester l'endpoint, pas de lire un client tiers.

## Alternatives considérées

- **Statu quo 100% HTML scraping (continuer la PR #102 telle quelle)** — Rejeté. Laisse un facteur 3.45× de performance sur la table, et impose de continuer à parser des pages 220 KB pour récupérer des metadata 1 KB côté topic header. La partie HTML cats / subcats / topic-list de #102 ne doit pas devenir la base durable de 1C.
- **100% REST** — Impossible. La lecture du contenu des posts (`/posts/`) est cassée côté serveur (HTTP 500 confirmé inconditionnel) et les MPs n'ont aucun endpoint REST. Impossible de couvrir le scope v1 sans HTML.
- **REST first avec fallback HTML systématique** — Rejeté. Coût d'implémentation élevé (chaque appel REST a son équivalent HTML à maintenir et tester) pour une protection contre une panne hypothétique. Une bascule HTML peut être réintroduite au cas par cas par domaine si un endpoint REST se révèle instable en prod.
- **Retrofit + interfaces typées sur la portion REST** — Rejeté. 6 endpoints justifient mal l'abstraction. ADR-009 reste applicable : OkHttp brut + parsing manuel via kotlinx.serialization. Le couplage faible avec le serveur (HFR peut couper la REST sans préavis) plaide aussi pour une couche client minimale qu'on peut facilement court-circuiter par domaine.
- **Mutations drapeaux via REST PUT en v1** — Rejeté. Sémantique opaque (downgrade `flag_owntopic` refusé, no-op refusé, hors-bornes silently ignored) confirmée par test live. Le format `error_description` qui leak l'état serveur (`"forbidden{flag_courant} {lp_envoyée} ..."`) est pratique pour debug mais fragile à dépendre. À reconsidérer post-v1 sur un compte de test sans drapeaux préexistants.
- **Création topic / réponse via REST POST en v1** — Reportée à v2. Endpoints routés (POST `topics/last/` et POST `topics/{id}/posts/` avec `hash_check`) testés live avec succès. Mais la suppression / rollback reste HTML (`DELETE` REST renvoie 501) et le risque opérationnel d'écriture est plus élevé qu'un simple GET. Garder `bddpost.php` en v1 minimise le risque ; basculer en v2 quand on a un cycle CI sandbox et un compte/topic de test dédié.

## Sources

- Sources versionnées : `core/data/src/test/resources/fixtures/rest_*.json` (6 fixtures + `.source.txt`) et cette ADR, qui intègre la synthèse des mesures et des endpoints retenus.
- Archives locales non normatives : `~/.claude/projects/-work-xaat/memory/reference_hfr_rest_api.md`, `tmp/redface2/drafts/hfr-rest-api_claude.md`, `tmp/redface2/drafts/hfr-rest-mp-doc-urls.md`, `tmp/redface2/bench-rest-vs-html-2026-05-01/results.md` (+ raw CSV + scripts rejouables).
- Doc V1 MesDiscussions (Wayback Machine) : `https://web.archive.org/web/2018/help.mesdiscussions.net/pages/viewpage.action?pageId=5013586`.
