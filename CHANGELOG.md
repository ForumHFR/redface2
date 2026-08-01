# Changelog

Toutes les évolutions notables des specs Redface 2.

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions sont celles des specs (`docs/_config.yml` `footer_content`). À partir de v0.6.0, elles incluent les changements code/spec couplés : depuis Phase 1, les specs reflètent l'état réel du repo et sont bumpées en lockstep avec les PRs structurantes (cf. `/spec-reality` dans `AGENTS.md`).

---

## [Unreleased]

Phase 2 finish (#206/#214) — create-topic : succès correctement classé et sujet créé mis en évidence dans la liste d'arrivée. Phase 2G-B (#150 suite) — recherche HFR alignée sur le formulaire réel : choix Titres+messages/Titres/Messages, parsing du lien « Dernier message correspondant » quand HFR fournit un `numreponse`, pivot catégories rendu comme un scope secondaire. Phase 2G-A (#150 partiel) — recherche HFR réelle dans les titres de topics (`forum1.php?recherches=1&...`), parser pivot/single/multi/no-results, écran Recherche fonctionnel avec navigation vers le topic. Phase 2F-C (#11 partiel) — picker smileys symétrique sur `TopicFormScreen` (Edit FP + New topic). Phase 2F-B (#11 partiel) — picker smileys dans l'éditeur (bottom-sheet Material 3, onglet Standard 25 builtins HFR + onglet Wiki live via `message-smi-mp-aj.php`). Phase 2E (#149) création de topic + follow-up Phase 2B-B (#144) déjà mergés décrits plus bas.

### Fixed (images MediaStore #1008)
- `AndroidPostImageSaver` : la totalité de la transaction MediaStore (insert, openOutputStream, update, delete de nettoyage) est désormais englobée dans la frontière de traduction d'exceptions. Toute `RuntimeException` ou `IOException` levée par le `ContentResolver` est convertie en `ImageSaveException` au lieu de s'échapper du `viewModelScope.launch` et de faire tomber le processus.
- Le résultat de `resolver.update` est maintenant vérifié : si aucune ligne n'est publiée (`rowsUpdated == 0`), une `ImageSaveException` est levée plutôt qu'émettre un succès alors que le fichier reste en état `pending` et invisible dans la galerie.
- Le nettoyage `resolver.delete` en cas d'erreur est exécuté dans un bloc best-effort (`runCatching`) : un échec du delete n'écrase plus l'exception originale.
- `PostImageActionsViewModel` propage correctement toutes les `ImageSaveException` issues de la couche data vers le toast « Enregistrement impossible », y compris les nouvelles erreurs `ContentResolver`.
- Tests `PostImageActionsViewModelTest` étendus : couverture des scénarios `insert` qui lève une `RuntimeException`, `openOutputStream` qui lève une `IOException`, `update` renvoyant `0` (fichier resté pending), et `delete` de nettoyage qui lève — vérification que dans chaque cas le ViewModel émet l'état d'erreur sans crasher.

### Added (Phase 2 finish #206/#214)
- `ReplySubmitResponseParser` reconnaît le succès create-topic réel « Votre message a été posté avec succès ! » via la fixture live `write_create_topic_success_response.html`. Cette réponse refresh vers `liste_sujet-1.htm` et ne contient aucun topic id : `NewTopicSubmitResult.Success(newTopicId = null, newNumreponse = null)` est le comportement normal.
- Le workaround #206 remplace la navigation directe impossible : après création, `TopicFormEffect.NewTopicCreated.subject` est propagé jusqu'à `CategoryRoute.highlightTitle`, puis `ForumCategoryScreen` met en évidence la ligne dont le titre correspond exactement au sujet posté sur la page/sous-catégorie d'arrivée.
- `ReplySubmitResponseParser` continue d'extraire `topicId`, page et `numreponse` depuis les refresh URLs `sujet_{topicId}_{page}` des flux reply / quote / edit, avec `SUJET_SEGMENT_REGEX` ancré (`(?<![a-z_])sujet_(\d+)_(\d+)`).

### Added (Phase 2G-B #150 suite)
- `SearchTextScope` (`TitlesAndPosts`, `TitlesOnly`, `PostsOnly`) plumbé de `SearchUiState` jusqu'à `HfrClient.searchTopics(...)`. Le défaut mobile devient `titre=3` (titres + messages), avec `orderSearch=0` pour trier par date du message correspondant.
- `SearchResultParser` lit le second lien HFR `forum2.php?...page=N&numreponse=M` et l'extrait `.citation` « Dernier message correspondant » quand une recherche contenu le fournit. Les résultats titre restent honnêtement `page=null`, `numreponse=null`, `matchedExcerpt=null`.
- Écran Recherche : chips de scope textuel, pivot catégories horizontal avec texte d'aide, lieu du rés