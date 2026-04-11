---
title: Roadmap
nav_order: 9
---

# Roadmap
{: .fs-8 }

Phases de developpement, de la fondation au polish.
{: .fs-5 .fw-300 }

---

## Vue d'ensemble

```mermaid
gantt
    title Redface 2 — Roadmap
    dateFormat YYYY-MM
    axisFormat %b %Y

    section Fondation
    Phase 0 — Bootstrap          :p0, 2026-04, 2026-05
    Phase 1 — Core               :p1, 2026-05, 2026-08

    section Interaction
    Phase 2 — Ecriture           :p2, 2026-08, 2026-10
    Phase 3 — Messages           :p3, 2026-10, 2026-12

    section Communaute
    Phase 4 — Extensions         :p4, 2027-01, 2027-03
    Phase 5 — Polish             :p5, 2027-03, 2027-05
```

---

## Phase 0 — Bootstrap

**Objectif :** un squelette d'app qui compile, avec CI, theme et navigation.

- [ ] Structure Gradle multi-modules
- [ ] CI GitHub Actions (build, lint, tests)
- [ ] Theme Material 3 (clair, sombre, AMOLED)
- [ ] Navigation graph (bottom nav + ecrans vides)
- [ ] Hilt wiring
- [ ] Design system de base (typographie, couleurs, composants)

**Livrable :** une app qui demarre, affiche la bottom nav, et navigue entre des ecrans placeholder.

---

## Phase 1 — Core (lecture seule)

**Objectif :** lire le forum. C'est 80% du use case.

- [ ] Login HFR (cookies persistants)
- [ ] Ecran Drapeaux (accueil) — tri par date/categorie, filtres
- [ ] Ecran Topic — lecture, pagination, scroll fluide
- [ ] PostRenderer — rendu BBCode natif en Compose
- [ ] Ecran Forum — categories, sous-categories, liste de topics
- [ ] Cache Room — topics et drapeaux
- [ ] Deep linking (URLs HFR → app)
- [ ] Prefetch pages suivantes
- [ ] Images + smileys (Coil)

**Livrable :** une app utilisable pour **lire** le forum au quotidien. Pas encore de possibilite d'ecrire.

### PostRenderer — le sous-chantier critique

Le rendu natif Compose du BBCode HFR est le composant le plus complexe de toute l'app. Il doit gerer :

| Element | Complexite |
|---------|-----------|
| Texte formate (gras, italique, souligne, couleur, taille) | Moyenne |
| Citations imbriquees | Elevee |
| Blocs de code | Faible |
| Images inline | Moyenne |
| Smileys HFR | Moyenne (cache + mapping) |
| URLs cliquables | Faible |
| Spoilers (clic pour reveler) | Moyenne |
| Listes | Faible |

Le PostRenderer sera developpe de maniere incrementale : texte brut d'abord, puis formatage, puis citations, puis images.

---

## Phase 2 — Ecriture

**Objectif :** interagir avec le forum.

- [ ] Reply — repondre a un topic
- [ ] Quote — citer un post → reply pre-rempli
- [ ] Edit — editer son propre post
- [ ] Edit FP — editer le first post (sujet, contenu, sondage)
- [ ] Create topic — nouveau topic avec categorie, sujet, contenu, sondage optionnel
- [ ] Toolbar BBCode — boutons de formatage dans l'editeur
- [ ] Preview BBCode — avant-premiere du rendu
- [ ] Recherche

**Livrable :** une app complete pour lire ET ecrire sur le forum.

---

## Phase 3 — Messages

**Objectif :** les messages prives, classiques et multi.

- [ ] Inbox MPs classiques — liste, lecture, reply
- [ ] Nouveau MP — creation
- [ ] MultiMPs — liste avec vue drapeaux, lecture, reply, quote
- [ ] Nouveau MultiMP — creation (2+ destinataires)
- [ ] MPStorage natif — tracking lu/non-lu en Room
- [ ] Notifications MP

**Livrable :** gestion complete des MPs, y compris les MultiMPs avec etat lu/non-lu.

---

## Phase 4 — Extensions communautaires

**Objectif :** les features inspirees des userscripts HFR.

- [ ] Architecture d'extensions (PostDecorator, TopicToolbarContributor)
- [ ] Bookmarks — sauvegarder des posts
- [ ] Blacklist — masquer des utilisateurs
- [ ] Alertes Qualitay — signaler un post remarquable
- [ ] Redflag — alertes intelligentes sur topics suivis

**Livrable :** les features communautaires les plus demandees, integrees nativement.

---

## Phase 5 — Polish

**Objectif :** l'experience utilisateur raffinee.

- [ ] Animations et transitions
- [ ] Mode offline complet (lecture + file d'attente d'ecriture)
- [ ] Notifications push configurables
- [ ] Theme dynamique (Material You)
- [ ] Theme "HFR classique"
- [ ] Widgets Android
- [ ] Migration automatique des donnees Redface v1
- [ ] Tests de performance (scroll, cold start, memoire)
- [ ] Publication Play Store

**Livrable :** une app prete pour le grand public.

---

## Participation

Chaque phase sera trackee via les [issues GitHub](https://github.com/ForumHFR/redface2/issues) et des milestones. Les contributions sont les bienvenues a partir de la Phase 1.

Pour contribuer :
1. Choisir une issue non assignee
2. Commenter pour signaler qu'on la prend
3. Ouvrir une PR sur une branche feature
4. Review par un mainteneur
