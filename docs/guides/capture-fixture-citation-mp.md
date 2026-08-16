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

Les trois captures MP du lot 4 peuvent être enchaînées dans une session par [`scripts/capture-mp-quote-fixtures.sh`](https://github.com/ForumHFR/redface2/blob/main/scripts/capture-mp-quote-fixtures.sh) ; la recette manuelle ci-dessous reste la référence normative.

---

> **Exécutée le 2026-08-12** ([#1041](https://github.com/ForumHFR/redface2/issues/1041)) — la recette
> a produit `private_message_quote_form.html` et son témoin `private_message_reply_form.html`. Ce
> qu'elle a mesuré, et les deux écarts constatés par rapport à ce qu'elle anticipait, sont consignés
> au § « Résultat de la première exécution » en fin de page ; le contrat lui-même vit dans
> [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) § « MP — citer un message ».

## Pourquoi cette recette vit dans `docs/guides/`

La forme du formulaire de citation MP a été mesurée le 2026-08-12. Le contrat canonique vit désormais
dans `protocol-hfr.md` ; ce guide conserve la procédure de capture pour le reproduire et détecter une
éventuelle dérive serveur, au même titre que les règles de fixtures de
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
  `page`, `p`, `subcat`, `new`, `verifrequet` et `sujet`, ainsi que `sondage`, `owntopic` ou les
  options si HFR les émet ; la capture du 2026-08-12 ne sert aucun champ caché `ref` ;
- la valeur de `numrep`, qui doit désigner le message cité selon le contrat mesuré ; tout écart est
  une dérive à reporter dans `protocol-hfr.md`, pas une nouvelle sémantique à deviner côté client ;
- en DT, la présence éventuelle de `newdest` pour le créateur ou de la ligne « Destinataires » en
  lecture seule pour un participant.

Aucune commande `POST /bddpost.php` ne fait partie de cette recette.

## 4. Assainir avant toute copie dans le dépôt

> **Étape 0, non négociable : RÉDUIRE la capture avant de l'assainir.** Ne copie dans le dépôt que le
> **sous-arbre `form[name=hop]`**, enveloppé dans un squelette HTML minimal. Ça supprime d'un coup les
> trois familles de fuites qui vivent **hors** du formulaire : la toolbar **par message** et ses liens
> obfusqués, le récapitulatif du message cité, et la « Vue Rapide de la discussion » avec tous les corps
> de message. Une capture brute assainie « à la main » a déjà laissé passer les trois (incident du
> 2026-08-12, § Historique en fin de page). Le sidecar doit dire ce qui a été retiré.
>
> **Ce que la réduction ne garantit pas toute seule** : tous les sélecteurs DOM de `ReplyFormParser`
> visent bien le formulaire (`form[action*=bddpost.php]`, ses `input[name]`, son `textarea`, la ligne
> « Destinataires » via `th.repCase1`), **mais `SmileyUserIdExtractor` travaille sur la chaîne entière
> et prend le premier `find_smilies_timer(…)` rencontré**. Il se trouve que ce marqueur est à
> l'intérieur du panneau de smileys du composer, donc du formulaire — vérifie-le sur ta capture plutôt
> que de le supposer, et si HFR l'a déplacé, garde-le explicitement ou documente que l'userId n'est plus
> extractible de cette fixture. La barre d'outils BBCode du composer, elle, est dans le formulaire :
> elle reste, avec ses textes d'aide, qui ne sont pas des données personnelles.

Sur ce sous-arbre, remplacer ensuite de façon **cohérente** toutes les occurrences des données
privées, sans reformater le DOM :

- tous les `hash_check`, cookies, en-têtes de session, mots de passe et tokens ;
- **tout `md_*cryptlink` : le décoder, regarder ce qu'il contient, puis le neutraliser ou le retirer.**
  Ces classes CSS ne sont pas opaques — `CryptlinkDecoder` (#227) les décode en trois lignes, et l'une
  d'elles est l'URL `/unlog.php?…&codehex=…` qui porte un **jeton de déconnexion lié au compte**. Un
  cryptlink laissé tel quel est un secret publié, pas une chaîne décorative ;
- pseudos, destinataires, e-mails, sujet, corps du message cité, autres corps visibles et éventuel
  récapitulatif du message source ;
- **la signature du compte** et **la liste de ses smileys favoris** (`images/perso/<nom>.gif`) : les
  deux sont des données personnelles et le nom d'un smiley perso peut être le pseudo lui-même. Garder
  la structure du panneau, remplacer les noms par des noms fictifs ;
- identifiants de profil, noms de fichiers avatar et autres identifiants personnels ;
- identifiant de conversation, `numreponse`, `numrep`, ancres `t…`, tableaux JavaScript et URL qui
  les répètent, avec un mapping fictif stable qui préserve leurs relations ;
- dates ou métadonnées d'activité si elles permettent de rattacher la capture à une personne.

**Le mapping fictif doit être le même que celui des fixtures existantes de la même conversation.**
`private_message_thread.html` (#298) fixe `TestUser` / compte courant = `990002` et `Correspondant` =
`990001` : inverser ces ids donne deux identités contradictoires pour la même personne d'une fixture à
l'autre.

**Ne jamais vérifier un remplacement par une seule regex de structure.** Une regex ancrée sur
`<td class="messCase1bis">…</td><td>…</td>` a laissé passer un corps de message réel parce que la ligne
suivante avait une forme légèrement différente. La vérification qui marche est **l'énumération** :
lister *tous* les nœuds de texte et *toutes* les valeurs d'attribut de la fixture finale, et les
regarder un par un ; la réduction rend cette revue praticable sans prétendre la rendre courte.

Ce script énumère, sans troncature, ce qu'`HTMLParser` expose : chaque nom de balise ouvrante, chaque
fragment de texte, espaces compris (y compris à l'intérieur des `<script>`, où un jeton se cache très
bien), chaque commentaire, déclaration (`DOCTYPE`), instruction de traitement, déclaration inconnue
(`CDATA` notamment), référence d'entité ou référence numérique présente dans le texte, et chaque couple
attribut/valeur — `href`, `src`, `class`, `onclick` compris, pas seulement `value`/`alt`/`title`.
`HTMLParser` décode toujours les références de caractères présentes dans les valeurs d'attribut et
normalise la syntaxe des balises : pour contrôler leur graphie brute, les guillemets et la casse
d'origine, il faut aussi relire le fichier.

```bash
for fixture in core/parser/src/test/resources/fixtures/private_message_quote_form.html \
               core/parser/src/test/resources/fixtures/private_message_reply_form.html; do
  printf '\n########## %s\n' "$fixture"
  python3 - "$fixture" <<'EOF'
import sys
from html.parser import HTMLParser

class Dump(HTMLParser):
    def handle_starttag(self, tag, attrs):
        print(f"  tag   <{tag}>")
        for name, value in attrs:
            print(f"  attr  {tag}@{name} = {value!r}")
    handle_startendtag = handle_starttag
    def handle_data(self, data):
        print(f"  texte {data!r}")
    def handle_comment(self, data):
        print(f"  comm  {data!r}")
    def handle_decl(self, data):
        print(f"  decl  {data!r}")
    def handle_pi(self, data):
        print(f"  pi    {data!r}")
    def unknown_decl(self, data):
        print(f"  udecl {data!r}")
    def handle_entityref(self, name):
        print(f"  ent   &{name};")
    def handle_charref(self, name):
        print(f"  char  &#{name};")

p = Dump(convert_charrefs=False)
p.feed(open(sys.argv[1], encoding='utf-8').read())
p.close()
EOF
done
```

Relis **toute** la sortie. L'énumération des fragments blancs l'allonge volontairement : la paire de
fixtures actuelle produit près de 2 000 lignes. Chaque entrée doit être soit du chrome HFR, soit une
valeur fictive. Tout le reste est une fuite.

Conserver la structure du formulaire, les noms de champs, les valeurs non sensibles, le rang `ref`
dans le BBCode prérempli et la forme de celui-ci. Le corps privé peut être remplacé par un placeholder
à l'intérieur du `[quotemsg]`, sans toucher aux délimiteurs produits par HFR.

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
git diff --check
rg -n 'hash_check|md_user|md_pass|password|codehex|cryptlink|@|profil-[0-9]+|mesdiscussions-[0-9]+|images/perso/' \
  core/parser/src/test/resources/fixtures/private_message_quote_form.html \
  core/parser/src/test/resources/fixtures/private_message_quote_form.source.txt \
  core/parser/src/test/resources/fixtures/private_message_reply_form.html \
  core/parser/src/test/resources/fixtures/private_message_reply_form.source.txt
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

## Résultat de la première exécution (2026-08-12)

La recette a été suivie telle quelle et a livré les deux fixtures. Ce qui a été **vérifié** et non
présupposé : `form[name=hop]` en `method=post` vers `/bddpost.php?config=hfr.inc`,
`textarea[name=content_form]` préremplie d'un bloc `[quotemsg=…]`, et surtout la sémantique de
`numrep` — **le message cité**, pas le dernier message de la page. Le contrat complet, avec le tableau
des trois sens de `numrep`, est dans [protocol-hfr.md]({{ site.baseurl }}/specs/protocol-hfr) § « MP —
citer un message ».

Deux écarts par rapport à ce que la recette anticipait, à garder pour la prochaine exécution :

- **`ref` n'est pas servi comme champ caché** sur cette capture 1:1, alors que le § 3 le listait parmi
  les champs à vérifier « si HFR les émet ». Le rang ne voyage que dans l'URL et dans le second
  paramètre du `[quotemsg]`. Le formulaire de réponse d'un DT owner (`private_message_dt_owner_reply_form.html`),
  lui, porte bien `ref=0` — donc à vérifier par shape, jamais à supposer.
- **Un témoin est nécessaire.** Le seul formulaire de citation ne prouve pas ce que la citation
  change : il a fallu capturer aussi le formulaire de réponse simple du même `message.php`, même
  conversation, même session. Leur delta (deux champs) est ce qui fait le contrat. Toute réexécution
  devrait capturer la paire.

Deux conditions préalables du § « Conditions préalables » n'ont **pas** pu être tenues et sont
assumées, tracées dans les sidecars : la conversation n'était pas jetable (aucune n'est disponible —
écrire sous le compte du mainteneur est interdit) et son contenu réel était sensible, donc
intégralement remplacé.

## Historique — l'incident qui a durci cette recette (2026-08-12)

La première exécution a produit des fixtures **non réduites**, assainies par substitution ciblée. Elles
ont été committées et poussées sur une branche publique, où le gate final a trouvé **trois fuites** :

1. un **fragment de message privé réel** — la regex de substitution, ancrée sur la forme
   `<td class="messCase1bis">…</td><td>…</td>`, avait sauté une ligne de la « Vue Rapide » ;
2. un **`md_noclass_cryptlink` décodable** en `/unlog.php?…&codehex=<réel>`, soit un jeton de
   déconnexion lié au compte, dans un dépôt public ;
3. la **liste des smileys favoris** du compte, dont un smiley portant le pseudo.

Le sidecar affirmait pourtant « le brut n'est PAS conservé ». La branche a été réécrite et
force-pushée, les fixtures reprises en version **réduite**. Les trois règles qui en sortent sont
maintenant en tête du § 4 : réduire au `form[name=hop]` avant d'assainir, décoder tout cryptlink, et
vérifier par **énumération** des nœuds de texte plutôt que par une regex de structure. Elles ne sont
pas des précautions théoriques : chacune correspond à une fuite réellement publiée.
