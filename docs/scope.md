---
title: Scope fonctionnel
nav_order: 7
---

# Scope fonctionnel
{: .fs-8 }

Ce que l'app doit permettre de faire, pas comment elle est implémentée.
{: .fs-5 .fw-300 }

---

## Rôle de ce document

Cette page liste **ce que l'app fait**, pas *comment*.

Elle sert à :
- partager le scope à la communauté HFR
- onboarder un nouveau contributeur en 5 minutes
- vérifier qu'on n'oublie pas un use case entre les phases

Pour le détail technique :
- Écrans et navigation → [Navigation]({{ site.baseurl }}/navigation)
- Extensions communautaires → [Extensions]({{ site.baseurl }}/extensions)
- Edge cases protocole HFR → [Protocole HFR]({{ site.baseurl }}/protocol-hfr)
- Phases et ordonnancement → [Roadmap]({{ site.baseurl }}/roadmap)

## Acteurs

| Acteur | Scope |
|---|---|
| **Visiteur** | Non connecté. Lecture publique uniquement. |
| **Utilisateur** | Compte HFR connecté. Toutes les capabilities. |

Pas de "modérateur" comme acteur distinct en v1 : un modo utilise les mêmes écrans, avec au besoin des capacités natives HFR accessibles ailleurs. Ce n'est pas un axe produit prioritaire de v1.

## Capabilities

Groupées par domaine. Une ligne par use case.

Quand `docs/roadmap.md` tranche explicitement une phase, cette page s'aligne dessus. Quand la roadmap ne détaille pas encore un point précis, la phase indiquée ici reste **indicative**.

### Lecture

- **Voir les drapeaux** — écran d'accueil du connecté : liste des topics avec activité non lue (cyan, favori, rouge). Phase 1.
- **Lire un topic** — navigation paginée (`postsPerPage` configurable HFR), pagination, ancre `#t{numreponse}`. Phase 1.
- **Parcourir les catégories** — liste des 22 catégories HFR, sous-catégories, liste des topics par sous-cat. Phase 1.
- **Voir un profil utilisateur** — popup rapide (avatar, inscription, posts, localisation) + page complète. Phase 2.
- **Voter dans un sondage** — lecture des résultats + vote si connecté. Phase 2.
- **Ouvrir un deep link** — une URL HFR publique de topic, liste ou drapeaux, y compris un fragment `#t{numreponse}`, ouvre directement le bon écran. Phase 1.

### Écriture

- **Se connecter** — login HFR (pseudo + password), récupère les cookies de session. Phase 1.
- **Se déconnecter** — invalide les cookies locaux. Phase 1.
- **Répondre dans un topic** — éditeur BBCode, preview, envoi. Phase 2.
- **Citer un post** — insère `[quotemsg=]...[/quotemsg]` dans l'éditeur. Phase 2.
- **Éditer un post** — uniquement ses propres posts, tant que non-locked. Phase 2.
- **Éditer le first post (FP)** — sujet, sous-catégorie, sondage éditables en plus du contenu. Phase 2.
- **Créer un topic** — sous-catégorie + sujet + contenu + sondage optionnel. Phase 2.
- **Insérer un smiley** — builtin HFR (~25, syntaxe `:code:`) ou perso (centaines, `[:name]`). Phase 2.
- **Insérer un lien / image / citation** — boutons toolbar BBCode. Phase 2.

### Social

- **Lire un MP classique** — conversation à 2. Phase 3.
- **Lire un MultiMP** — conversation multi-participants (MPStorage pour les flags). Phase 3.
- **Envoyer un MP** — éditeur + destinataire(s). Phase 3.
- **Supprimer / archiver un MP** — actions natives HFR. Phase 3.
- **Voir sa liste de contacts** — ajout/suppression. Phase 3.
- **Alerter un post (modération)** — extension `redflag`. Phase 4.

### Recherche

- **Recherche globale** — mot-clé sur tout le forum. Phase 2.
- **Recherche filtrée** — catégorie, auteur, fourchette de dates. Phase 2.

### Personnalisation

- **Choisir un thème** — 3 thèmes v1 (seed `#A62C2C`, dynamic OFF, cf. [#9](https://github.com/ForumHFR/redface2/issues/9)). Phase 1.
- **Régler les préférences** — `postsPerPage`, avatars, signatures, timezone, langue (lus depuis HFR). Phase 2.
- **Mode data saver** — désactive le chargement auto des images lourdes. Phase 1.
- **Gérer le cache** — purge manuelle. Phase 2.

### Offline & performance

- **Prefetch** — pré-charger la page suivante d'un topic en requête **non authentifiée** (ne pas marquer les drapeaux comme lus). Phase 1.
- **Cache local** — topics lus, MPs, paramètres stockés en Room. Phase 1.
- **Preview + tap-to-full images** — auto-détection thumbs HFR, fullscreen au tap. Phase 1.
- **Synchronisation MPStorage** — drapeaux MultiMP, bookmarks, préférences stockés dans un MP HFR dédié. Phase 3.

### Extensions communautaires (Phase 4)

Détails dans [Extensions]({{ site.baseurl }}/extensions). Résumé :

- **Bookmarks** — sauvegarder un post hors drapeaux.
- **Blacklist** — masquer les posts d'un utilisateur.
- **Qualitay** — signaler un post de qualité (canal communautaire).
- **Redflag** — alertes intelligentes via Cloudflare Worker externe.
- **Colortag** — colorer/annoter les pseudos.
- **Image host** — upload et bibliothèque d'images (diberie, super-h.fr, imgur).
- **GIF picker** — recherche et insertion de GIFs.
- **Stats utilisateur** — statistiques d'activité.

### Plateforme & accessibilité

- **Edge-to-edge** — UI sous la status bar + navigation bar (Android 15+). Phase 1.
- **Dark / light / system** — mode sombre/clair selon le système. Phase 1.
- **Predictive back** — animation de retour anticipé (Compose + manifest). Phase 1.
- **Tablette / pliable** — `ListDetailPaneScaffold` M3 Adaptive (liste drapeaux + topic en 2 panes). Phase 2.
- **Taille de texte** — respect des préférences système. Phase 1.

## Non-goals v1

Choses explicitement **hors scope** pour éviter l'ambiguïté :

- **Notifications push** — HFR n'expose pas de canal push ; polling uniquement côté app si décidé ultérieurement.
- **Modération native (ban, lock, sticky)** — passerait par WebView HFR, pas prioritaire v1.
- **Multi-compte** — un seul compte HFR à la fois.
- **Mode offline écriture** — on ne bufferise pas des replies hors ligne (risque de double-post).
- **Wiki smileys / galerie avancée** — l'éditeur propose les smileys présents HFR, pas de gestion d'upload de perso depuis l'app en v1.
- **Traduction** — app en français uniquement v1.

## Questions ouvertes

- Notifications locales (poll de drapeaux en arrière-plan) — à trancher Phase 2+.
- Support tablette complet ou best-effort — à spec au premier prototype tablette.
- Wear OS / Android Auto — non.
