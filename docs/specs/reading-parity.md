---
title: Parité de lecture Topic ↔ MP
parent: Spécifications
nav_order: 10
permalink: /specs/reading-parity
---

# Parité de lecture Topic ↔ MP
{: .fs-8 }

La matrice qui empêche la surface de lecture des MP/DT de décrocher silencieusement de celle du Topic.
{: .fs-5 .fw-300 }

---

## Pourquoi cette page

Entre la migration c3 de [#351](https://github.com/ForumHFR/redface2/issues/351) (2026-06-20) et le
cadrage de [#1040](https://github.com/ForumHFR/redface2/issues/1040) (2026-08-12), **74 commits** ont
touché `feature/topic` contre **7** `feature/messages` — sans qu'aucun document ne trace l'écart :
chaque fonction de lecture était conçue, livrée et testée côté topic, et son absence côté MP n'était
écrite nulle part. Cette page est la réponse structurelle : **pour chaque fonction de la surface de
lecture, elle dit si la fonction s'applique aux MP/DT, et dans quel état elle s'y trouve.**

**Règle d'entretien** `[advisory]` : toute PR qui ajoute ou modifie une fonction de la surface de
lecture (rendu d'un `Post`, gestes de page, préférences de lecture) **ajoute ou met à jour sa ligne
ici**. Une fonction absente de cette matrice est un écart non tracé — précisément ce que cette page
existe pour empêcher.

Trois verdicts possibles :

- **oui, livré** — la fonction est effective côté MP (souvent via un mécanisme partagé : `RedfaceTheme`,
  `PostListScaffold`, `PostRenderer`) ;
- **non par nature** — la fonction ne peut pas ou ne doit pas s'appliquer aux MP, avec la raison
  (contrainte serveur, confidentialité) ;
- **oui mais absent** — la fonction a du sens en MP et n'y est pas câblée. C'est le backlog du
  chantier #1040.

Contexte d'architecture : le partage se fait au niveau de la **carte d'un message** (cible
`ReadingPostCard` dans `:core:ui`, lot 1 de #1040), pas des écrans ni des ViewModels — cf. #1040 et
[ADR-013]({{ site.baseurl }}/adr/013-mp-lecture-cache-prefetch) (amendée 2026-08-12).

Trois contrats de test décrivaient l'écart comme un design et **gelaient** autant de lignes « oui
mais absent ». Leur **réarbitrage a été rendu le 2026-08-12** (#1041) — propositions rédigées par
Fable, décision prise par Sol contre le code, le producteur ne pouvant pas être son propre juge :

- `MessageCardShellSmokeTest` (#884) — **amendé maintenant** (lot 0). Les deux assertions sont
  conservées : elles caractérisent le chemin par défaut (encarté) de `PostCardShell`. Seul le KDoc
  change, parce qu'il présentait la hairline du mode plat comme une affordance « topic-owned » que
  le MP « never opts into » — une caractérisation du défaut y était écrite en interdiction
  permanente. La parité pleine largeur reste un chemin opt-in du lot 2, qui devra apporter sa propre
  couverture (toggle à chaud, survie d'un repli déplié).
- `PostRendererHostMatrixTest` de `:feature:messages` (#958) — **amendé au lot 3**, dans la même PR
  que le provider `LocalPostImageActions` côté MP. Assertions **retournées** (tap et appui long
  définis, cible `PostImageTarget` vérifiée) et harnais conservé, plus un cas « callback absent ⇒
  image inerte » : la capacité vient de la présence du callback, pas de la surface hôte. Tant que le
  MP ne fournit rien, l'inertie que ce test épingle est **réelle**, pas décorative.
- le KDoc de `MessageCard` (#351c) — **amendé au lot 1, PR 2**, avec le comportement : l'amender
  avant ferait mentir le code (la carte MP code encore ses paddings et laisse `PostRenderer` à son
  défaut non sélectionnable). Tout le discours négatif tombe alors, pas seulement les phrases
  densité et sélection — « no footer » et « no multi-quote border » gèleraient les lots citation.
  Les contrats de prose collatéraux (KDoc de `PostCardShell`, commentaire du paramètre `selectable`
  de `PostRenderer`, commentaire de densité de `ThreadMessages`) sont corrigés dans cette même PR.

Un contrat ne se flippe **jamais** en silence : le test change dans la même PR que le comportement,
sinon la CI dit — à raison — que le comportement promis a changé.

## Matrice

État vérifié dans le code le 2026-08-12 (`dev` @ `767407c3`). Les références sont des symboles, pas
des numéros de ligne.

| Fonction | Réf. | MP/DT ? | Détail |
|---|---|---|---|
| Taille de police | #287, `RedfaceTheme` | **oui, livré** | Typo scalée par le préréglage, fournie par le thème — effective partout. |
| Pliage des longues citations | #332, `LocalFoldLongQuotes` | **oui, livré** | Fourni par `RedfaceTheme`, lu dans `PostRenderer.QuoteBlock` — effectif en MP. |
| Ascenseur intra-page | #300/#351c, `PostListScaffold` + `LazyListScrollbar` | **oui, livré** | Arrive par le scaffold partagé, qui lit `LocalShowScrollbar` lui-même. Une recherche de symbole côté MP le rate — l'écart se mesure par lecture, pas au grep. |
| Profil d'affichage des médias (GIF S/M/L) | #973, `LocalMediaDisplayProfile` | **oui, livré** | Fourni par `RedfaceTheme`, lu dans `PostRenderer.BlockImage`. |
| Retry unitaire d'un média en erreur | `PainterAttempt` (`PostRenderer`) | **oui, livré** | Le slot d'erreur + retry par image vit dans le renderer partagé. Le **retry en masse au refresh explicite** (#813/#960) est, lui, câblé côté topic seulement → « oui mais absent ». |
| Pull-to-refresh | #335/#351a, `PullToRefreshBox` | **oui, livré** | Keep-content (la page reste affichée pendant le rechargement). |
| Swipe de page horizontal | #282/#351b, `threadPageSwipe` | **oui, livré** (écart de durcissement) | Géométrie et seuils partagés (`core.ui.pager.PageSwipe`). Le MP n'a ni slide-out (assumé sans cache, ADR-013), ni l'annulation multi-touch #936, ni la dead-zone des bandes système #752 — gagnées par le topic après #351b. |
| Reprise de la page de lecture | #430, `mp_read_positions` (ADR-013 étage 1) | **oui, livré** (contrat propre) | Position **locale** (page par conversation, par compte, purgée au logout) : il n'existe **aucune position de lecture serveur** pour les MP (#361 Q3, dot binaire par conversation) — le contrat diffère du dernier-lu topic par nature, ce n'est pas une lacune. |
| Densité structurelle | #287, `LocalDisplayMetrics` | **oui mais absent** | Le CompositionLocal est fourni globalement par `RedfaceTheme` mais le MP ne le lit pas : paddings en dur (`PaddingValues(16.dp)` / `spacedBy(12.dp)` dans `ThreadMessages`, densité « feature-owned » de `MessageCard`). **Lot 1, PR 2** — la densité peut recomposer des valeurs, jamais remplacer la carte, ses slots ou la branche `SelectionContainer`. |
| Mode pleine largeur | #884, `PostCardShell(flat)` | **oui mais absent** | Le shell partagé porte le mode ; `MessageCard` ne passe rien. `MessageCardShellSmokeTest` **caractérise le chemin par défaut** (KDoc réarbitré le 2026-08-12, assertions conservées) : il n'interdit plus la parité MP, qui reste un opt-in du **lot 2** avec sa propre couverture. |
| Sélection / copie du texte | #281, `PostRenderer(selectable)` | **oui mais absent** | Le topic force `selectable = true` ; défaut OFF côté MP, documenté comme choix dans le KDoc de `MessageCard`. **Lot 1, PR 2** (réarbitrage du 2026-08-12) : le corps MP devient sélectionnable, et cette capacité doit rester **structurellement constante** sur la durée de vie de la carte — #946, flipper `selectable` insère/retire le `SelectionContainer` à l'entrée de `PostRenderer`, ce qui recrée le sous-arbre du corps et jette l'état `rememberSaveable` des replis de citation. |
| Signatures | #330, `Post.signature` | **oui mais absent** | Parsées par le `PostsParser` partagé, rendues côté topic (préférence + `LocalIgnoreInlineColors` #553), jamais rendues en MP. Présence sur page MP réelle à prouver (la fixture actuelle n'a pas de signature — caractérisation #1041). |
| Marqueur EgoQuote | #874 Q4 / #1028, `LocalEgoQuotePseudo` | **oui mais absent** | Le MP reste au défaut `null` (documenté dans `TopicPostCard`). Sens surtout en DT (plusieurs participants qui se citent). |
| Marqueur EgoPost | #874 P1 / #1028, `egoPostHighlighted` | **oui mais absent** | Résolu par la liste topic uniquement ; jamais résolu côté MP. |
| Pinceau doré des créateurs | #221, `isRf2Creator` + `rememberCreatorPseudoBrush` | **oui mais absent** | Appliqué par le header d'identité topic ; `MessageCard` utilise le pseudo fallback de `PostIdentityHeader`. Un auteur de MP est un pseudo HFR comme un autre. |
| Menu contextuel de message | #362, `PostMenuSheet` | **oui mais absent** | Vit dans `:feature:topic`. Sous-ensemble MP à définir : copier le texte, citer, profil — pas de drapeau/favori ; « copier le permalien » expose une URL de conversation privée (#316) → à arbitrer. Lot 3. |
| Actions d'image (viewer, menu appui long) | #831/#958, `LocalPostImageActions` | **oui mais absent** | Jamais fourni côté MP : dans `PostRenderer`, `OnClick`/`OnLongClick` dépendent directement de `LocalPostImageActions.current != null`, donc l'inertie qu'épingle `PostRendererHostMatrixTest` (`:feature:messages`) est réelle. **Lot 3** (réarbitrage du 2026-08-12) : assertions retournées et cible `PostImageTarget` vérifiée, dans la même PR que le provider. |
| Citation simple | #146 (topic), « Citer » par message | **oui mais absent** (contrat serveur **mesuré**) | Aucun bouton par message côté app. Le serveur, lui, expose le lien « citer » par message sur les pages `cat=prive`, et le spike #1041 a capturé le formulaire renvoyé (2026-08-12, fixtures `private_message_quote_form.html` + témoin `private_message_reply_form.html`) : `numrep` = **le message cité** (4ᵉ d'une page de 5), `content_form` prérempli `[quotemsg=numrep,ref,userId]`, aucun champ caché `ref`, même endpoint `bddpost.php`. `ReplyFormParser` le parse **sans modification** — le lot 4 n'a plus d'inconnu serveur. Détail dans [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) § « MP — citer un message ». |
| Citation multiple | #291, panier multi-quote | **oui mais absent** | Dépend de la citation simple (lot 4). Le web HFR expose ses boutons quote+/quote- sur les pages MP (même fixture) — la fonction s'applique. Le panier vit dans `:app`, clé `(cat, post)` — un scope MP typé serait requis (`cat=prive` est une `String`). |
| Saut vers le message cité | #699/#782, `onGoToCitedPost` | **oui mais absent** | Jamais passé au `PostRenderer` côté MP (headers de citation inertes). Caveat partagé : la forme dynamique authentifiée n'est déjà pas reconnue côté topic (#625). |
| Index du message | `Post.postIndex` | **oui mais absent** | Affiché via le menu contextuel côté topic (#362) ; pas de menu en MP. Présence de l'index sur les pages `cat=prive` à caractériser (#1041). |
| Marqueur d'édition | `Post.editedAt` | **oui mais absent** | Ligne « Édité le … » du menu topic. Présence du trailer sur les pages MP à caractériser (#1041). |
| Compteur de citations | #239/#863, `Post.citedCount` | **oui mais absent** | Pill « cité N fois » (badges) + ligne du menu côté topic. Présence de « Message cité N fois » sur les pages MP à caractériser (#1041 — la fixture actuelle n'en contient pas). |
| Profil au tap (avatar/pseudo) | #208, `onOpenProfile` | **oui mais absent** | Jamais câblé côté MP ; `Post.profileId` épinglé sur fixture MP (#1041). Les participants d'un MP/DT ont des profils publics — la fonction s'applique. Contrainte d'API : `PostIdentityHeader` pose `heading()` sur son pseudo **de repli** mais n'en ajoute aucun quand un slot pseudo est fourni (chemin du topic) — un slot d'identité MP doit donc porter son propre `heading()`, jamais zéro ni deux. |
| Double-tap pour rafraîchir | #382 | **oui mais absent** | Geste topic (RF1 parity) ; le MP n'a que le pull-to-refresh. |
| Zoom pincé | #182, `TopicZoom` + `TopicZoomMath` | **oui mais absent** | Vit dans `:feature:topic` (magnifier, scroll contrôlé, suspension des gestes). Lot 6. |
| Liste noire | #509, `BlacklistRepository` | **oui mais absent** | Filtre vivant dans `TopicViewModel` uniquement. **Décision produit requise** : sens en DT (participants non choisis), discutable en 1:1 (on choisit ses conversations) — lot 2. |
| Ancres de scroll par page (session) | #307/#895 F3, `pageAnchors` | **oui mais absent** | Le topic restaure la position par page visitée (moteur in-VM) ; le MP atterrit en haut à chaque changement de page (`ScrollToTopOnPageChange`). La reprise de **page**, elle, est livrée (ligne dédiée). |
| Pagination riche (picker, premier/dernier, snapshots RAM, stale-while-switching, chrome) | #895 étape 4, `TopicViewModel.switchToPage` | **oui mais absent** | Le MP a un aller-retour réseau direct par page et sa route de thread est encore remplacée après envoi (recréation du ViewModel). Lot 6 — le ressenti dépend du cache/prefetch (lot 5). |
| Prefetch **anonyme** de la page suivante | règle prefetch, `@AnonymousClient` | **non par nature** | `cat=prive` répond **403 en anonyme** : le prefetch anonyme qui donne au topic son swipe instantané est structurellement impossible en MP. |
| Prefetch **authentifié borné** (N±1, conversation ouverte) | ADR-013 décision 3 | **oui mais absent** | Autorisé et borné par l'ADR-013 (jamais depuis la liste — read receipt MultiMP) ; non implémenté. Préalable lot 5 : **étendre la garde Konsist au domaine MP**, exigence de l'ADR elle-même — la garde actuelle ne surveille que les appels topic, donc un prefetch MP ne la déclencherait pas. La contradiction `AGENTS.md` ↔ ADR-013 sur la règle du prefetch est **tranchée** (2026-08-12) : `AGENTS.md` renvoie désormais à [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr), qui porte la règle générale et son unique exception. |
| Cache RAM de session (retours de page instantanés) | ADR-013 décision 2 étage 2 | **oui mais absent** | Décidé, non implémenté (lot 5). Clé par compte/thread/page, purge logout **et** bascule de compte ; `CacheInvalidator` à étendre au contenu. |
| Cache Room du contenu | ADR-013 décision 2 étage 3 | **oui mais absent** (opt-in OFF par décision) | Décidé opt-in explicite, défaut OFF, purge au logout — non implémenté (lot 7). Le risque confidentialité le plus élevé du chantier. |

## Ce qui reste hors matrice, par surface

Chrome de l'écran, pagination visuelle, sondages, drapeaux, recherche intra-topic (`transsearch`,
authentifiée par conception — son applicabilité à `cat=prive` n'a jamais été caractérisée), roster
des participants (#612), gestion des membres DT (#606) : propres à chaque surface, ils ne relèvent
pas de la carte de lecture partagée. Les décisions délibérées de confidentialité (#316 routes opaques, pas de persistance par
défaut) ne sont pas des écarts de parité — ne pas chercher à les effacer.
