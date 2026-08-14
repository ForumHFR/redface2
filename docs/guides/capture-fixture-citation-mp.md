---
title: Capturer une fixture de citation MP
parent: Guides
nav_order: 8
permalink: /guides/capture-fixture-citation-mp
---

# Capturer une fixture de citation MP
{: .fs-8 }

Recette reproductible pour capturer, sans envoyer de message, le formulaire HFR ouvert depuis
l'action « Répondre à ce message » d'une conversation privée.
{: .fs-5 .fw-300 }

---

## Pourquoi cette recette vit dans `docs/guides/`

La forme du formulaire de citation MP reste un contrat serveur **à mesurer**. Elle ne doit donc pas
entrer dans la spécification canonique du protocole avant une capture réelle. Ce guide est une
procédure opérationnelle durable, au même titre que les règles de fixtures de
[`contributing.md`]({{ site.baseurl }}/guides/contributing#fixtures-html-pour-le-parser) ; il ne vit
pas dans `drafts/`, qui n'est pas normatif.

Le chemin a déjà été éprouvé pour la fixture de lecture de [#298](https://github.com/ForumHFR/redface2/issues/298)
et l'investigation authentifiée de [#361](https://github.com/ForumHFR/redface2/issues/361) : login
HTTP, cookie jar local, puis GET authentifié. `hfr-mcp` ne couvre pas ce cas : `hfr_read` et
`hfr_quote` exigent une catégorie entière alors que les MP utilisent `cat=prive`, et `hfr_mp` sait
seulement créer un nouveau MP.

## Conditions préalables

- utiliser un compte de test autorisé et une conversation jetable dont tous les participants ont
  accepté la capture ;
- choisir un message au contenu non sensible, ou prévoir son remplacement intégral avant copie dans
  le dépôt ;
- travailler dans un répertoire temporaire privé hors du dépôt ;
- ne jamais soumettre le formulaire `bddpost.php` : seul son GET d'ouverture est capturé.

## 1. Ouvrir une session HTTP authentifiée

Depuis la racine du dépôt, créer un répertoire temporaire et un cookie jar lisible uniquement par
l'utilisateur courant :

```bash
capture_dir="$(mktemp -d /tmp/redface2-mp-quote.XXXXXX)"
chmod 700 "$capture_dir"
cookie_jar="$capture_dir/cookies.txt"
login_headers="$capture_dir/login.headers"
login_body="$capture_dir/login.html"
user_agent='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/138 Safari/537.36'

read -r -p 'Pseudo HFR de test : ' hfr_capture_pseudo
read -r -s -p 'Mot de passe HFR : ' hfr_capture_password
printf '\n'
printf '%s' "$hfr_capture_password" | curl -sS \
  -A "$user_agent" \
  -D "$login_headers" \
  -c "$cookie_jar" \
  -b "$cookie_jar" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "pseudo=$hfr_capture_pseudo" \
  --data-urlencode 'password@-' \
  'https://forum.hardware.fr/login_validation.php?config=hfr.inc&redirect=&url=' \
  -o "$login_body"
unset hfr_capture_password
chmod 600 "$cookie_jar" "$login_headers" "$login_body"
```

Ne pas ajouter `-L` au login : comme le client Redface 2, la recette doit conserver les
`Set-Cookie` de la réponse initiale, qu'elle soit `200` ou `302`. Vérifier la présence du cookie
d'identité sans en afficher la valeur :

```bash
awk '$6 == "md_user" { found=1 } END { exit !found }' "$cookie_jar"
```

Le cookie jar, les en-têtes et le body de login sont des secrets de session. Ils restent sous
`/tmp`, ne sont jamais copiés dans le dépôt et ne sont jamais joints à une issue.

## 2. Capturer la page brute de la conversation

Renseigner la conversation et la page choisies, puis effectuer le même GET authentifié que la
fixture `private_message_thread.html` :

```bash
thread_id='<thread-id>'
thread_page='<page>'
thread_html="$capture_dir/private-message-thread.raw.html"
thread_url="https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=prive&post=$thread_id&page=$thread_page&p=1&sondage=0&owntopic=0&trash=0&trash_post=0&print=0&numreponse=0&quote_only=0&new=0&nojs=0"

curl -fsSL \
  -A "$user_agent" \
  -b "$cookie_jar" \
  -c "$cookie_jar" \
  "$thread_url" \
  -o "$thread_html"
```

Il faut conserver le HTML **brut sans exécution JavaScript**. Repérer dans la toolbar du message
cible le `href` de citation qui contient à la fois `numrep=<numreponse>` et `ref=<rang>` :

```bash
rg -o 'href="/message\.php\?[^\"]*numrep=[0-9]+[^\"]*ref=[0-9]+[^\"]*"' "$thread_html"
```

Dans la fixture #298, sa forme observée est :

```text
/message.php?config=hfr.inc&cat=prive&post=<thread-id>&numrep=<message-cite>&ref=<rang-page>&page=<page>&p=1&subcat=0&sondage=0&owntopic=0&new=0#formulaire
```

Copier **le `href` réellement servi**, décoder les `&amp;` HTML en `&`, et retirer le fragment
`#formulaire` (il n'est jamais envoyé au serveur). Ne pas recalculer `ref` et ne pas remplacer
`numrep` par le dernier message de la page : ce dernier est le préremplissage connu de la réponse
MP simple, précisément pas une preuve du contrat de citation.

## 3. Capturer le formulaire de citation, sans le soumettre

Placer le chemin exact observé dans `quote_path`, puis effectuer uniquement son GET :

```bash
quote_path='/message.php?config=hfr.inc&cat=prive&post=<thread-id>&numrep=<message-cite>&ref=<rang-page>&page=<page>&p=1&subcat=0&sondage=0&owntopic=0&new=0'
quote_html="$capture_dir/private-message-quote-form.raw.html"

curl -fsSL \
  -A "$user_agent" \
  -b "$cookie_jar" \
  -c "$cookie_jar" \
  "https://forum.hardware.fr$quote_path" \
  -o "$quote_html"
```

La capture doit alors **vérifier**, et non présupposer, les points suivants :

- un formulaire `form[name=hop]` en `method=post`, normalement dirigé vers
  `/bddpost.php?config=hfr.inc` comme les formulaires reply/quote déjà capturés ;
- un `textarea[name=content_form]` prérempli par HFR avec un bloc `[quotemsg=…]…[/quotemsg]` ;
- les champs cachés réellement servis, notamment `hash_check`, `cat=prive`, `post`, `numrep`,
  `page`, `p`, `subcat`, `new`, `verifrequet` et `sujet`, ainsi que `ref`, `sondage`, `owntopic` ou
  les options si HFR les émet ;
- la valeur et la sémantique de `numrep` dans ce formulaire : message cité ou autre valeur. C'est
  l'inconnu que la fixture doit trancher ;
- en DT, la présence éventuelle de `newdest` pour le créateur ou de la ligne « Destinataires » en
  lecture seule pour un participant.

Aucune commande `POST /bddpost.php` ne fait partie de cette recette.

## 4. Assainir avant toute copie dans le dépôt

Créer la version nettoyée sous `/tmp` et remplacer de façon **cohérente** toutes les occurrences des
données privées, sans reformater le DOM :

- tous les `hash_check`, cookies, en-têtes de session, mots de passe et tokens ;
- pseudos, destinataires, e-mails, sujet, corps du message cité, autres corps visibles et éventuel
  récapitulatif du message source ;
- identifiants de profil, noms de fichiers avatar et autres identifiants personnels ;
- identifiant de conversation, `numreponse`, `numrep`, ancres `t…`, tableaux JavaScript et URL qui
  les répètent, avec un mapping fictif stable qui préserve leurs relations ;
- dates ou métadonnées d'activité si elles permettent de rattacher la capture à une personne.

Conserver la structure du formulaire, les noms de champs, les valeurs non sensibles, le rang `ref`
et la forme du BBCode prérempli. Le corps privé peut être remplacé par un placeholder à l'intérieur
du `[quotemsg]`, sans toucher aux délimiteurs produits par HFR.

La destination prévue est :

```text
core/parser/src/test/resources/fixtures/private_message_quote_form.html
core/parser/src/test/resources/fixtures/private_message_quote_form.source.txt
```

Le sidecar de provenance doit indiquer : source HFR, date, compte de test ou rôle du compte, URL
capturée avec identifiants déjà fictifs, séquence « login POST puis GET authentifié », absence de
soumission du formulaire, limitation `hfr-mcp`, et liste exacte des substitutions de confidentialité.

Avant commit, contrôler au minimum :

```bash
./scripts/check-fixtures-provenance.sh
rg -n 'hash_check|md_user|md_pass|password|@|profil-[0-9]+|mesdiscussions-[0-9]+' \
  core/parser/src/test/resources/fixtures/private_message_quote_form.html \
  core/parser/src/test/resources/fixtures/private_message_quote_form.source.txt
```

Chaque résultat du scan doit être soit neutralisé, soit explicitement justifié comme valeur fictive
dans le sidecar. Après vérification de la copie assainie, détruire le répertoire temporaire brut :

```bash
case "$capture_dir" in
  /tmp/redface2-mp-quote.*) rm -rf -- "$capture_dir" ;;
  *) printf 'Chemin temporaire inattendu, suppression refusée : %s\n' "$capture_dir" >&2 ;;
esac
unset capture_dir cookie_jar login_headers login_body thread_html quote_html
```
