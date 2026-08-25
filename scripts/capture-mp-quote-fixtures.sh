#!/usr/bin/env bash
# Capture groupée des preuves live manquantes sur les citations et la lecture MP (#1040/#1107).
# La recette normative et les règles d'assainissement restent dans
# docs/guides/capture-fixture-citation-mp.md.
set -euo pipefail

readonly HFR_ORIGIN='https://forum.hardware.fr'
readonly HFR_LOGIN_URL="${HFR_ORIGIN}/login_validation.php?config=hfr.inc&redirect=&url="
readonly HFR_USER_AGENT='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/138 Safari/537.36'

capture_dir=''
cookie_jar=''
observations_file=''
hfr_capture_password=''
search_tool=''
self_test_control_redirect=0
last_effective_url=''
last_response_date=''
last_response_code=''
last_response_sha256=''

declare -A selected=(
  [mp_quote]=0
  [dt_quote]=0
  [quoted_thread]=0
  [thread_multipage]=0
  [control_topic]=0
  [ref_probe]=0
)
declare -A target_url=(
  [mp_quote]=''
  [dt_quote]=''
  [quoted_thread]=''
  [multipage_center]=''
  [multipage_previous]=''
  [multipage_next]=''
  [multipage_outside]=''
  [control_subject]=''
  [control_topic]=''
  [ref_probe]=''
)
declare -A target_message=(
  [mp_quote]=''
  [dt_quote]=''
  [ref_probe]=''
)
declare -A capture_status=(
  [mp_quote]='non demandée'
  [dt_quote]='non demandée'
  [quoted_thread]='non demandée'
  [thread_multipage]='non demandée'
  [control_topic]='non demandée'
  [ref_probe]='non demandée'
)

usage() {
  cat <<'EOF'
Usage : scripts/capture-mp-quote-fixtures.sh [OPTIONS]

Capture, dans une seule session HFR authentifiée, tout ou partie des preuves live
manquantes de #1040/#1107 :

  --mp-quote       seconde citation dans une conversation MP 1:1
  --dt-quote       citation dans une discussion de groupe (DT / MultiMP)
  --quoted-thread  conversation MP contenant déjà une citation
  --thread-multipage
                   pages N, N-1, N+1 puis dernière+1 d'une conversation d'au
                   moins trois pages ; fournir quatre URL complètes, le script
                   ne fabrique jamais les URL de pagination
  --control-topic  paire sujet MP + topic public authentifié, avec rapport de
                   présence sur les deux pages dans la même session
  --ref-probe      depuis un href de citation servi : ref d'origine, ref absent,
                   puis ref=0, sans soumettre aucun formulaire
  --self-test-control-redirect
                   tester hors ligne la garde de redirection du topic public
  --all            les six captures (comportement par défaut)
  -h, --help       afficher cette aide

Un rapport structurel presence-report est exécuté sur chaque page capturée. Pour
les signatures, marqueurs d'édition et compteurs de citations, il distingue
« absent », « présent » et « présent mais vide » sans recopier aucun contenu.

Le script demande des URL complètes de pages de conversation et, pour les cas
de citation, le numéro visible via l'icône « n°… » du message à citer (ou dans
le fragment #t… de son lien). Une URL laissée vide reporte seulement ce cas :
les autres captures continuent. Relancer avec une seule option permet de reprendre
le cas manquant.

Pour le troisième cas, si aucune conversation de test ne contient encore de
citation, Redface 2 0.42.4 permet d'en créer une : citer un message dans une
conversation de test consentie, puis fournir au script la page qui affiche le
message nouvellement envoyé.

ATTENTION pour toute capture MP : chaque requête authentifiée sur une conversation
efface le dot non-lu du compte lecteur. Sur un MultiMP, elle retire aussi ce lecteur
de la liste « pas lu par » visible par les autres participants : elle modifie donc
un état visible par des tiers. Ne capturer que des conversations 1:1 déjà lues,
pour lesquelles cet effet est nul.

ATTENTION pour --control-topic : le GET est authentifié. Lire ainsi un topic
public déplace le drapeau de lecture du compte sur ce topic. Choisir un contrôle
pour lequel cet effet de bord est accepté.

Pour --thread-multipage, choisir une page intérieure N et fournir séparément les
URL complètes de N, N-1, N+1 et de la page demandée dernière+1. Elles doivent
désigner la même conversation. Le script capture dans cet ordre exact et vérifie
le pager réellement servi, y compris le rabattement de la requête hors borne.

Sécurité :
  - le mot de passe est lu sans écho et envoyé sur l'entrée standard de curl ;
  - le seul POST est l'authentification fixe vers login_validation.php ;
  - toutes les captures suivantes sont des GET, et bddpost.php est refusé ;
  - aucun formulaire de message n'est soumis ;
  - les HTML bruts, en-têtes, métadonnées et rapports restent dans un mktemp
    privé sous /tmp.

Le script CAPTURE et OBSERVE, mais n'assainit rien. Le rapport observations.txt
contient le contenu intégral de messages privés réels. Ne le collez jamais dans
une issue, ne le joignez à aucun message et ne le copiez dans aucun dépôt : il
sert à décider localement, puis doit être supprimé. Ne copiez pas non plus les
HTML bruts dans le dépôt.

Peut être rapportée publiquement : la structure observée (présence et noms des
champs cachés, présence ou absence de ref, valeur de numreponse, nombre de blocs
[quotemsg], comptes d'ancres et comptes structurels). Les listes d'ancres, URL
et rapports exhaustifs restent privés. Ne peut pas l'être : le contenu des
champs, notamment content_form et les blocs [quotemsg]. Avant toute fixture :
réduire à form[name=hop] ou au sous-arbre strictement nécessaire,
décoder/neutraliser les cryptlinks, assainir et énumérer intégralement le DOM
selon le guide normatif.
EOF
}

die() {
  printf 'ERREUR : %s\n' "$*" >&2
  if [[ -n "$capture_dir" ]]; then
    printf 'Les éventuels fichiers bruts restent dans le répertoire privé : %s\n' "$capture_dir" >&2
  fi
  exit 1
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "commande requise absente : $command_name"
}

select_search_tool() {
  if command -v rg >/dev/null 2>&1; then
    search_tool='rg'
  elif command -v grep >/dev/null 2>&1; then
    search_tool='grep'
  else
    die 'commande requise absente : installer rg ou grep'
  fi
}

file_contains() {
  local literal="$1"
  local file="$2"
  if [[ "$search_tool" == 'rg' ]]; then
    rg --quiet --fixed-strings -- "$literal" "$file"
  else
    grep -qF -- "$literal" "$file"
  fi
}

record() {
  printf '%s\n' "$*" | tee -a "$observations_file"
}

# Analyse uniquement le HTML reçu. Cette fonction ne fabrique ni identifiant ni URL HFR.
html_tool() {
  python3 - "$@" <<'PY'
import html
import re
import sys
from html.parser import HTMLParser
from urllib.parse import parse_qs, unquote_plus, urldefrag, urlsplit, urlunsplit

HFR_HOST = "forum.hardware.fr"
HFR_HOST_URL = "https://forum.hardware.fr"
PRETTY_TOPIC_PATH = re.compile(
    r"^/hfr/(?:[^/]+/)+[^/]+-sujet_([1-9][0-9]*)_([1-9][0-9]*)\.htm$"
)


def fail(message, code=2):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def split_hfr_origin_url(raw_url):
    try:
        parts = urlsplit(raw_url.strip())
        port = parts.port
    except ValueError as error:
        fail(f"URL invalide : {error}")
    if parts.scheme.lower() != "https" or parts.hostname != HFR_HOST:
        fail("URL refusée : fournir une URL HTTPS de forum.hardware.fr")
    if parts.username is not None or parts.password is not None or port not in (None, 443):
        fail("URL refusée : identifiants ou port inattendus")
    return parts


def split_hfr_url(raw_url, expected_path, category="private"):
    parts = split_hfr_origin_url(raw_url)
    if parts.path != expected_path:
        fail(f"URL refusée : chemin attendu {expected_path}, reçu {parts.path or '/'}")
    query = parse_qs(parts.query, keep_blank_values=True)
    cat_values = query.get("cat")
    if category == "private" and cat_values != ["prive"]:
        fail("URL refusée : la requête doit porter exactement cat=prive")
    if category == "public":
        if cat_values is None or len(cat_values) != 1 or not cat_values[0].isdigit():
            fail("URL de contrôle refusée : cat doit être un entier public unique")
        if int(cat_values[0]) <= 0:
            fail("URL de contrôle refusée : cat doit être strictement positif")
    return parts, query


def require_single_positive(query, key, label=None):
    values = query.get(key)
    if values is None or len(values) != 1 or not values[0].isdigit() or int(values[0]) <= 0:
        fail(f"URL refusée : {label or key} doit être un entier positif unique")
    return int(values[0])


def validate_url(
    raw_url,
    expected_path,
    category="private",
    require_quote_keys=False,
    allow_missing_ref=False,
    require_thread_page=False,
):
    parts, query = split_hfr_url(raw_url, expected_path, category=category)
    if require_thread_page:
        require_single_positive(query, "post", "post")
        require_single_positive(query, "page", "page")
    if require_quote_keys:
        require_single_positive(query, "numrep", "numrep")
        ref_values = query.get("ref")
        if ref_values is None:
            if not allow_missing_ref:
                fail("URL de citation refusée : ref doit provenir du href servi")
        elif len(ref_values) != 1 or not ref_values[0].isdigit():
            fail("URL de citation refusée : ref doit être un entier unique ou être absent")
    # Un fragment navigateur n'est jamais envoyé au serveur.
    print(urlunsplit(("https", HFR_HOST, parts.path, parts.query, "")))


def validate_control_effective_url(raw_url, requested_url):
    _requested_parts, requested_query = split_hfr_url(
        requested_url,
        "/forum2.php",
        category="public",
    )
    requested_post = require_single_positive(requested_query, "post", "post")
    requested_page = require_single_positive(requested_query, "page", "page")

    parts = split_hfr_origin_url(raw_url)
    if parts.path == "/forum2.php":
        _parts, query = split_hfr_url(raw_url, "/forum2.php", category="public")
        require_single_positive(query, "post", "post")
        require_single_positive(query, "page", "page")
        print(urlunsplit(("https", HFR_HOST, parts.path, parts.query, "")))
        return

    match = PRETTY_TOPIC_PATH.fullmatch(parts.path)
    if match is None or parts.query:
        fail(
            "URL refusée : chemin attendu /forum2.php ou URL jolie de topic public, "
            f"reçu {parts.path or '/'}"
        )
    effective_post, effective_page = map(int, match.groups())
    if effective_post != requested_post or effective_page != requested_page:
        fail(
            "URL jolie de contrôle refusée : post/page effectifs "
            f"{effective_post}/{effective_page}, attendus {requested_post}/{requested_page}"
        )
    print(urlunsplit(("https", HFR_HOST, parts.path, "", "")))


def self_test_control_effective_url():
    import contextlib
    import io

    requested = "https://forum.hardware.fr/forum2.php?cat=23&post=35421&page=66"
    accepted = (
        "https://forum.hardware.fr/hfr/gsmgpspda/android/"
        "redface-canal-developpement-sujet_35421_66.htm"
    )
    rejected = (
        accepted.replace("sujet_35421_", "sujet_99999_"),
        accepted.replace("_66.htm", "_67.htm"),
        accepted.replace("forum.hardware.fr", "example.com"),
    )
    with contextlib.redirect_stdout(io.StringIO()):
        validate_control_effective_url(accepted, requested)
    for candidate in rejected:
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                validate_control_effective_url(candidate, requested)
        except SystemExit:
            continue
        fail(f"Auto-test en échec : URL indûment acceptée : {candidate}", code=1)
    print("Auto-test redirection control_topic : OK")


def validate_multipage_urls(raw_urls):
    if len(raw_urls) != 4:
        fail("Quatre URL multipage sont requises")
    entries = []
    for raw_url in raw_urls:
        _parts, query = split_hfr_url(raw_url, "/forum2.php")
        entries.append(
            (
                require_single_positive(query, "post", "post"),
                require_single_positive(query, "page", "page"),
            )
        )
    posts = {post for post, _page in entries}
    if len(posts) != 1:
        fail("Les quatre URL multipage doivent désigner la même conversation")
    center, previous, following, outside = [page for _post, page in entries]
    if center < 2 or previous != center - 1 or following != center + 1:
        fail("Ordre multipage invalide : fournir exactement N, N-1 puis N+1")
    if outside <= following:
        fail("La quatrième URL doit demander une page hors borne après N+1")


def raw_query_segments(parts):
    return parts.query.split("&") if parts.query else []


def decoded_query_key(segment):
    return unquote_plus(segment.partition("=")[0])


def derive_quote_variants(raw_path):
    parts, query = split_hfr_url(HFR_HOST_URL + raw_path, "/message.php")
    require_single_positive(query, "numrep", "numrep")
    segments = raw_query_segments(parts)
    ref_indexes = [index for index, segment in enumerate(segments) if decoded_query_key(segment) == "ref"]
    if len(ref_indexes) != 1:
        fail("Dérivation ref refusée : le href servi doit porter un unique paramètre ref")
    ref_index = ref_indexes[0]
    key, separator, value = segments[ref_index].partition("=")
    if separator != "=" or not value.isdigit():
        fail("Dérivation ref refusée : la valeur servie doit être un entier")

    # Exception unique à la règle « ne jamais fabriquer une URL » : #1110 exige
    # de dériver UN href servi en supprimant son unique paramètre ref ou en mettant
    # sa valeur à zéro. Tous les autres segments restent octet pour octet, dans le
    # même ordre ; aucune autre URL du script n'est reconstruite de cette manière.
    without_ref = segments[:ref_index] + segments[ref_index + 1 :]
    zero_ref = list(segments)
    zero_ref[ref_index] = key + "=0"

    def path_with(query_segments):
        query_string = "&".join(query_segments)
        return urlunsplit(("", "", parts.path, query_string, ""))

    print("original=" + path_with(segments))
    print("without_ref=" + path_with(without_ref))
    print("ref_zero=" + path_with(zero_ref))


def redacted_url_value(raw_url):
    try:
        parts = urlsplit(raw_url)
    except ValueError:
        return "<URL invalide non affichée>"
    redacted = []
    for segment in raw_query_segments(parts):
        key, separator, _value = segment.partition("=")
        if decoded_query_key(segment).lower() == "hash_check":
            redacted.append(key + separator + "<masqué>")
        else:
            redacted.append(segment)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "&".join(redacted), parts.fragment))


def redact_url(raw_url):
    print(redacted_url_value(raw_url))


def quote_path_from_href(href):
    # HTMLParser décode déjà les attributs ; unescape rend l'intention explicite et
    # couvre aussi un attribut doublement encodé par un intermédiaire.
    served_href = html.unescape(href)
    href_without_fragment, _fragment = urldefrag(served_href)
    parts = urlsplit(href_without_fragment)
    if parts.scheme and (parts.scheme.lower() != "https" or parts.hostname != HFR_HOST):
        return None
    if not parts.scheme and parts.netloc:
        return None
    if parts.path != "/message.php":
        return None
    query = parse_qs(parts.query, keep_blank_values=True)
    if query.get("cat") != ["prive"]:
        return None
    if len(query.get("numrep", [])) != 1 or len(query.get("ref", [])) != 1:
        return None
    if not query["numrep"][0].isdigit() or not query["ref"][0].isdigit():
        return None
    # Préserver mot pour mot le chemin et la query servis : aucun parse/re-encode,
    # aucun calcul de ref, aucune substitution de numrep.
    path = parts.path
    if parts.query:
        path += "?" + parts.query
    return served_href, path, query


class QuoteToolbarParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.message_depth = 0
        self.toolbar_depth = 0
        self.current = None
        self.messages = []

    def handle_starttag(self, tag, attrs):
        attrs_dict = dict(attrs)
        classes = attrs_dict.get("class", "").split()
        if tag == "table":
            if self.message_depth:
                self.message_depth += 1
            elif "messagetable" in classes:
                self.message_depth = 1
                self.current = {"anchors": [], "hrefs": []}
        if not self.message_depth or self.current is None:
            return
        if tag == "div":
            if self.toolbar_depth:
                self.toolbar_depth += 1
            elif "toolbar" in classes:
                self.toolbar_depth = 1
        if tag == "a":
            anchor_name = attrs_dict.get("name", "")
            if re.fullmatch(r"t[0-9]+", anchor_name):
                self.current["anchors"].append(anchor_name[1:])
            href = attrs_dict.get("href")
            if self.toolbar_depth and href:
                self.current["hrefs"].append(href)

    def handle_endtag(self, tag):
        if not self.message_depth:
            return
        if tag == "div" and self.toolbar_depth:
            self.toolbar_depth -= 1
        if tag == "table":
            self.message_depth -= 1
            if self.message_depth == 0:
                self.messages.append(self.current)
                self.current = None
                self.toolbar_depth = 0


def extract_quote_path(file_path, target_message):
    if not re.fullmatch(r"[0-9]+", target_message):
        fail("Le numéro du message ciblé doit contenir uniquement des chiffres")
    parser = QuoteToolbarParser()
    with open(file_path, encoding="utf-8", errors="replace") as source:
        parser.feed(source.read())
    parser.close()
    matching_messages = [message for message in parser.messages if target_message in message["anchors"]]
    if not matching_messages:
        fail(f"Message t{target_message} absent de la page capturée")
    if len(matching_messages) != 1:
        fail(f"Message t{target_message} présent dans plusieurs tables : sélection refusée")
    candidates = []
    for href in matching_messages[0]["hrefs"]:
        extracted = quote_path_from_href(href)
        if extracted is None:
            continue
        served_href, path, query = extracted
        # Le numéro sert seulement à rattacher le href à la toolbar ciblée. Il n'est
        # jamais injecté dans l'URL : une divergence fait échouer fermé.
        if query.get("numrep") != [target_message]:
            continue
        if (served_href, path) not in candidates:
            candidates.append((served_href, path))
    if not candidates:
        fail(
            f"Aucun href de citation avec numrep={target_message} et ref servi "
            "dans la toolbar ciblée"
        )
    if len(candidates) != 1:
        fail(f"Plusieurs href de citation distincts servis pour t{target_message} : sélection refusée")
    served_href, path = candidates[0]
    print("href_servi=" + served_href)
    print("chemin_get=" + path)


class HopFormParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.active_form = None
        self.form_depth = 0
        self.textarea = None
        self.forms = []

    def handle_starttag(self, tag, attrs):
        attrs_dict = dict(attrs)
        if self.active_form is None:
            if tag == "form" and attrs_dict.get("name") == "hop":
                self.active_form = {
                    "attrs": attrs_dict,
                    "hidden": [],
                    "textareas": [],
                }
                self.form_depth = 1
            return
        if tag == "form":
            self.form_depth += 1
        if tag == "input" and attrs_dict.get("type", "").lower() == "hidden":
            self.active_form["hidden"].append(
                (attrs_dict.get("name"), attrs_dict.get("value", ""))
            )
        if tag == "textarea" and attrs_dict.get("name") == "content_form":
            self.textarea = []

    def handle_startendtag(self, tag, attrs):
        if tag == "input" and self.active_form is not None:
            attrs_dict = dict(attrs)
            if attrs_dict.get("type", "").lower() == "hidden":
                self.active_form["hidden"].append(
                    (attrs_dict.get("name"), attrs_dict.get("value", ""))
                )

    def handle_data(self, data):
        if self.textarea is not None:
            self.textarea.append(data)

    def handle_endtag(self, tag):
        if self.active_form is None:
            return
        if tag == "textarea" and self.textarea is not None:
            self.active_form["textareas"].append("".join(self.textarea))
            self.textarea = None
        if tag == "form":
            self.form_depth -= 1
            if self.form_depth == 0:
                self.forms.append(self.active_form)
                self.active_form = None


def display_values(values):
    if not values:
        return "absent"
    return "présent : " + ", ".join(repr(value) for value in values)


def mask_hash_check_text(value):
    return re.sub(
        r"(?i)(hash_check=)[^&\s\"'<>\]]*",
        r"\1<masqué>",
        value,
    )


def report_form(file_path, label):
    parser = HopFormParser()
    with open(file_path, encoding="utf-8", errors="replace") as source:
        parser.feed(source.read())
    parser.close()
    print(f"\n--- Observations : {label} ---")
    print(f"form[name=hop] servis : {len(parser.forms)}")
    for index, form in enumerate(parser.forms, start=1):
        attrs = form["attrs"]
        action = redacted_url_value(attrs.get("action") or "")
        print(
            f"Formulaire {index} : method={attrs.get('method')!r} ; "
            f"action={action!r}"
        )
        print(f"Champs cachés servis ({len(form['hidden'])}, ordre DOM, liste exhaustive) :")
        for field_index, (name, value) in enumerate(form["hidden"], start=1):
            displayed_value = "<valeur masquée : secret de session>" if name == "hash_check" else repr(value)
            print(f"  {field_index:02d}. {name or '<sans nom>'} = {displayed_value}")
        for field_name in ("ref", "numreponse", "numrep"):
            values = [value for name, value in form["hidden"] if name == field_name]
            print(f"Synthèse {field_name} : {display_values(values)}")
        print(f"textarea[name=content_form] servis : {len(form['textareas'])}")
        for textarea_index, content in enumerate(form["textareas"], start=1):
            quote_blocks = re.findall(
                r"\[quotemsg=[^\]]+\].*?\[/quotemsg\]",
                content,
                flags=re.IGNORECASE | re.DOTALL,
            )
            print(f"  content_form {textarea_index} — blocs [quotemsg] : {len(quote_blocks)}")
            print(f"  content_form {textarea_index} — contenu servi (hash_check masqué) :")
            print(mask_hash_check_text(content))
            if quote_blocks:
                for quote_index, block in enumerate(quote_blocks, start=1):
                    print(f"  [quotemsg] {quote_index} — contenu servi (hash_check masqué) :")
                    print(mask_hash_check_text(block))
            else:
                print("  Aucun bloc [quotemsg=…] servi (absence observée, non suppléée).")
    if len(parser.forms) != 1:
        raise SystemExit(3)


class CitationHeaderParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.table_stack = []
        self.quote_depth = 0
        self.header_stack = []
        self.current_anchor = None
        self.quote_tables = 0
        self.header_links = []
        self.other_quote_links = []

    def handle_starttag(self, tag, attrs):
        attrs_dict = dict(attrs)
        classes = attrs_dict.get("class", "").split()
        if tag == "table":
            is_quote = "citation" in classes or "oldcitation" in classes
            self.table_stack.append(is_quote)
            if is_quote:
                self.quote_depth += 1
                self.quote_tables += 1
        if tag == "b":
            is_header = self.quote_depth > 0 and "s1" in classes
            self.header_stack.append(is_header)
        if tag == "a" and self.quote_depth > 0 and attrs_dict.get("href"):
            self.current_anchor = {
                "href": html.unescape(attrs_dict["href"]),
                "text": [],
                "header": any(self.header_stack),
            }

    def handle_data(self, data):
        if self.current_anchor is not None:
            self.current_anchor["text"].append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.current_anchor is not None:
            entry = (
                self.current_anchor["href"],
                "".join(self.current_anchor["text"]).strip(),
            )
            if self.current_anchor["header"]:
                self.header_links.append(entry)
            else:
                self.other_quote_links.append(entry)
            self.current_anchor = None
        if tag == "b" and self.header_stack:
            self.header_stack.pop()
        if tag == "table" and self.table_stack:
            was_quote = self.table_stack.pop()
            if was_quote:
                self.quote_depth -= 1


def report_citation_headers(file_path, label):
    parser = CitationHeaderParser()
    with open(file_path, encoding="utf-8", errors="replace") as source:
        parser.feed(source.read())
    parser.close()
    print(f"\n--- Observations : {label} ---")
    print(f"Tables citation/oldcitation servies : {parser.quote_tables}")
    print(f"href d'en-tête de citation trouvés : {len(parser.header_links)}")
    for index, (href, text) in enumerate(parser.header_links, start=1):
        print(f"  {index:02d}. href={redacted_url_value(href)!r} ; texte={text!r}")
    if parser.other_quote_links:
        print(
            "Autres href dans le corps des citations "
            f"({len(parser.other_quote_links)}, non confondus avec les en-têtes) :"
        )
        for index, (href, text) in enumerate(parser.other_quote_links, start=1):
            print(f"  {index:02d}. href={redacted_url_value(href)!r} ; texte={text!r}")
    if not parser.header_links:
        print("Aucun href d'en-tête trouvé : la cible reste à capturer.")
        raise SystemExit(4)


CITED_COUNT_REGEX = re.compile(r"Message[\s\u00a0]+cité[\s\u00a0]+(\d+)[\s\u00a0]+fois")
EDITED_AT_REGEX = re.compile(
    r"Message édité par .+ le \d{2}-\d{2}-\d{4} à \d{2}:\d{2}:\d{2}"
)
REPRISE_TEXT = "Reprise du message précédent"


def normalized_text(parts):
    return re.sub(r"\s+", " ", "".join(parts).replace("\u00a0", " ")).strip()


def quote_ref_from_href(href, anchor):
    try:
        parts = urlsplit(html.unescape(href))
    except ValueError:
        return None
    if parts.path != "/message.php":
        return None
    query = parse_qs(parts.query, keep_blank_values=True)
    if query.get("numrep") != [anchor] or len(query.get("ref", [])) != 1:
        return None
    return int(query["ref"][0]) if query["ref"][0].isdigit() else None


class PageEvidenceParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.current_message = None
        self.message_table_depth = 0
        self.signature_depth = 0
        self.signature_text = None
        self.signature_rendered_children = 0
        self.content_depth = 0
        self.edited_depth = 0
        self.edited_text = None
        self.toolbar_depth = 0
        self.messages = []
        self.pager_depth = 0
        self.pager_left_depth = 0
        self.pager_b_depth = 0
        self.pager_b_text = None
        self.pager_numbers = []
        self.pager_current_candidates = []
        self.thread_ids = []

    def handle_starttag(self, tag, attrs):
        attrs_dict = dict(attrs)
        classes = attrs_dict.get("class", "").split()

        if tag == "input" and attrs_dict.get("name") == "post":
            value = attrs_dict.get("value", "")
            if value.isdigit():
                self.thread_ids.append(int(value))

        if tag == "tr":
            if self.pager_depth:
                self.pager_depth += 1
            elif "fondForum2PagesHaut" in classes:
                self.pager_depth = 1
        if self.pager_depth and tag == "div":
            if self.pager_left_depth:
                self.pager_left_depth += 1
            elif "left" in classes:
                self.pager_left_depth = 1
        if self.pager_left_depth and tag == "b":
            self.pager_b_depth += 1
            if self.pager_b_depth == 1:
                self.pager_b_text = []

        if tag == "table":
            if self.current_message is not None:
                self.message_table_depth += 1
            elif "messagetable" in classes:
                self.current_message = {
                    "anchors": [],
                    "signatures": [],
                    "edited": [],
                    "hrefs": [],
                    "text": [],
                }
                self.message_table_depth = 1

        if self.current_message is None:
            return
        if tag == "a":
            anchor_name = attrs_dict.get("name", "")
            if re.fullmatch(r"t[0-9]+", anchor_name):
                self.current_message["anchors"].append(anchor_name[1:])
            href = attrs_dict.get("href")
            if self.toolbar_depth and href:
                self.current_message["hrefs"].append(html.unescape(href))
        if tag == "span" and self.content_depth:
            if self.signature_depth:
                self.signature_depth += 1
            elif "signature" in classes:
                self.signature_depth = 1
                self.signature_text = []
                self.signature_rendered_children = 0
        if self.signature_depth and tag == "img":
            self.signature_rendered_children += 1
        if tag == "div":
            if self.content_depth:
                self.content_depth += 1
            elif attrs_dict.get("id", "").startswith("para"):
                self.content_depth = 1
            if self.edited_depth:
                self.edited_depth += 1
            elif "edited" in classes:
                self.edited_depth = 1
                self.edited_text = []
            if self.toolbar_depth:
                self.toolbar_depth += 1
            elif "toolbar" in classes:
                self.toolbar_depth = 1

    def handle_data(self, data):
        if self.pager_left_depth:
            stripped = data.strip()
            if stripped.isdigit():
                self.pager_numbers.append(int(stripped))
        if self.pager_b_text is not None:
            self.pager_b_text.append(data)
        if self.current_message is not None:
            self.current_message["text"].append(data)
        if self.signature_text is not None:
            self.signature_text.append(data)
        if self.edited_text is not None:
            self.edited_text.append(data)

    def handle_endtag(self, tag):
        if tag == "b" and self.pager_b_depth:
            self.pager_b_depth -= 1
            if self.pager_b_depth == 0 and self.pager_b_text is not None:
                value = normalized_text(self.pager_b_text)
                if value.isdigit():
                    self.pager_current_candidates.append(int(value))
                self.pager_b_text = None
        if tag == "div" and self.pager_left_depth:
            self.pager_left_depth -= 1
        if tag == "tr" and self.pager_depth:
            self.pager_depth -= 1

        if self.current_message is None:
            return
        if tag == "span" and self.signature_depth:
            self.signature_depth -= 1
            if self.signature_depth == 0 and self.signature_text is not None:
                self.current_message["signatures"].append(
                    {
                        "text": normalized_text(self.signature_text),
                        "rendered_children": self.signature_rendered_children,
                    }
                )
                self.signature_text = None
                self.signature_rendered_children = 0
        if tag == "div":
            if self.edited_depth:
                self.edited_depth -= 1
                if self.edited_depth == 0 and self.edited_text is not None:
                    self.current_message["edited"].append(normalized_text(self.edited_text))
                    self.edited_text = None
            if self.toolbar_depth:
                self.toolbar_depth -= 1
            if self.content_depth:
                self.content_depth -= 1
        if tag == "table" and self.message_table_depth:
            self.message_table_depth -= 1
            if self.message_table_depth == 0:
                if self.current_message["anchors"]:
                    self.messages.append(self.current_message)
                self.current_message = None
                self.signature_depth = 0
                self.signature_text = None
                self.signature_rendered_children = 0
                self.content_depth = 0
                self.edited_depth = 0
                self.edited_text = None
                self.toolbar_depth = 0

    @property
    def current_page(self):
        return self.pager_current_candidates[-1] if self.pager_current_candidates else 1

    @property
    def total_pages(self):
        return max([self.current_page] + self.pager_numbers)

    @property
    def anchors(self):
        return [anchor for message in self.messages for anchor in message["anchors"]]

    @property
    def thread_id(self):
        unique = set(self.thread_ids)
        return next(iter(unique)) if len(unique) == 1 else None

    def first_quote_ref(self):
        if not self.messages or not self.messages[0]["anchors"]:
            return None
        anchor = self.messages[0]["anchors"][0]
        refs = {
            ref
            for href in self.messages[0]["hrefs"]
            if (ref := quote_ref_from_href(href, anchor)) is not None
        }
        return next(iter(refs)) if len(refs) == 1 else None

    def first_is_reprise(self):
        return bool(self.messages) and REPRISE_TEXT in normalized_text(self.messages[0]["text"])

    def real_anchors(self):
        anchors = list(self.anchors)
        if anchors and self.first_is_reprise() and self.first_quote_ref() == 0:
            return anchors[1:]
        return anchors


def parse_page(file_path):
    parser = PageEvidenceParser()
    with open(file_path, encoding="utf-8", errors="replace") as source:
        parser.feed(source.read())
    parser.close()
    return parser


def verdict_counts(messages, classifier):
    counts = {"absent": 0, "présent": 0, "présent mais vide": 0}
    for message in messages:
        counts[classifier(message)] += 1
    return counts


def signature_verdict(message):
    signatures = message["signatures"]
    if not signatures:
        return "absent"
    meaningful = [
        re.sub(r"^\s*-{3,}\s*", "", signature["text"]).strip()
        or signature["rendered_children"] > 0
        for signature in signatures
    ]
    return "présent" if any(meaningful) else "présent mais vide"


def marker_verdict(message, pattern):
    trailers = message["edited"]
    if not trailers:
        return "absent"
    if any(pattern.search(trailer) for trailer in trailers):
        return "présent"
    if all(not trailer for trailer in trailers):
        return "présent mais vide"
    return "absent"


def print_verdict_line(label, counts):
    print(
        f"{label} — absent : {counts['absent']} ; présent : {counts['présent']} ; "
        f"présent mais vide : {counts['présent mais vide']}"
    )


def report_presence(file_path, label):
    page = parse_page(file_path)
    signatures = verdict_counts(page.messages, signature_verdict)
    edited = verdict_counts(page.messages, lambda message: marker_verdict(message, EDITED_AT_REGEX))
    cited = verdict_counts(page.messages, lambda message: marker_verdict(message, CITED_COUNT_REGEX))
    print(f"\n--- Presence-report : {label} ---")
    print(f"Messages structuraux (table.messagetable avec ancre tN) : {len(page.messages)}")
    print(f"Éléments span.signature servis : {sum(len(message['signatures']) for message in page.messages)}")
    print(f"Éléments div.edited servis : {sum(len(message['edited']) for message in page.messages)}")
    print_verdict_line("Signature", signatures)
    print_verdict_line("Marqueur d'édition", edited)
    print_verdict_line("Compteur de citations", cited)


def report_page(file_path, label):
    page = parse_page(file_path)
    first = page.anchors[0] if page.anchors else "absente"
    last = page.anchors[-1] if page.anchors else "absente"
    first_ref = page.first_quote_ref()
    print(f"\n--- Page-report : {label} ---")
    print(f"Page réellement rendue par le pager : {page.current_page}")
    print(f"Dernière page annoncée par le pager : {page.total_pages}")
    print(f"Ancres tN servies : {len(page.anchors)}")
    print("Liste privée des ancres : " + ", ".join("t" + anchor for anchor in page.anchors))
    print(f"Première ancre : t{first}" if first != "absente" else "Première ancre : absente")
    print(f"Dernière ancre : t{last}" if last != "absente" else "Dernière ancre : absente")
    print(f"Première ancre marquée Reprise : {'oui' if page.first_is_reprise() else 'non'}")
    print(f"ref servi pour la première ancre : {first_ref if first_ref is not None else 'absent/ambigu'}")
    if not page.anchors:
        raise SystemExit(5)


def report_anchor_count(file_path):
    print(len(parse_page(file_path).anchors))


def validate_multipage_center(center_file, raw_urls):
    validate_multipage_urls(raw_urls)
    center_page = parse_page(center_file)
    requested = []
    for raw_url in raw_urls:
        _parts, query = split_hfr_url(raw_url, "/forum2.php")
        requested.append(require_single_positive(query, "page", "page"))
    center, _previous, _following, outside = requested
    if center_page.current_page != center:
        fail(
            "La première capture ne rend pas la page N demandée "
            f"({center_page.current_page} au lieu de {center})"
        )
    if center_page.total_pages < 3 or not 1 < center < center_page.total_pages:
        fail("La conversation doit avoir au moins trois pages et N doit être une page intérieure")
    if outside != center_page.total_pages + 1:
        fail(
            "La quatrième URL doit demander exactement dernière+1 "
            f"({center_page.total_pages + 1})"
        )
    print(
        "Contrat multipage initial validé : page intérieure, conversation >= 3 pages "
        "et requête hors borne dernière+1."
    )


def report_multipage_consistency(file_paths):
    if len(file_paths) != 4:
        fail("Quatre fichiers multipage sont requis")
    center, previous, following, outside = [parse_page(path) for path in file_paths]
    pages = [center, previous, following]
    thread_ids = {page.thread_id for page in [center, previous, following, outside]}
    if None in thread_ids or len(thread_ids) != 1:
        fail("Les captures multipage ne prouvent pas une conversation unique")
    if [page.current_page for page in pages] != [center.current_page, center.current_page - 1, center.current_page + 1]:
        fail("Les pages rendues ne suivent pas l'ordre N, N-1, N+1")
    if outside.current_page != center.total_pages:
        fail("La requête dernière+1 ne s'est pas rabattue sur la dernière page annoncée")
    real_sets = [set(page.real_anchors()) for page in pages]
    if any(left & right for index, left in enumerate(real_sets) for right in real_sets[index + 1 :]):
        fail("Les jeux d'ancres réelles de N-1, N et N+1 ne sont pas disjoints")
    if not previous.anchors or not center.anchors or center.anchors[0] != previous.anchors[-1]:
        fail("La reprise attendue entre N-1 et N n'est pas prouvée par les ancres")
    if not center.first_is_reprise() or center.first_quote_ref() != 0:
        fail("La première ancre de N n'est pas une reprise servie avec ref=0")
    if not following.anchors or following.anchors[0] != center.anchors[-1]:
        fail("La reprise attendue entre N et N+1 n'est pas prouvée par les ancres")
    if not following.first_is_reprise() or following.first_quote_ref() != 0:
        fail("La première ancre de N+1 n'est pas une reprise servie avec ref=0")
    print("\n--- Cohérence multipage ---")
    print("Conversation unique : oui")
    print("Ordre rendu N, N-1, N+1 : oui")
    print("Jeux d'ancres réelles disjoints : oui")
    print("Reprise N = dernière ancre N-1 avec ref=0 : oui")
    print("Reprise N+1 = dernière ancre N avec ref=0 : oui")
    print("Rabattement dernière+1 vers la dernière page : oui")


if len(sys.argv) < 2:
    fail("Sous-commande d'analyse absente")
command = sys.argv[1]
if command == "validate-thread" and len(sys.argv) == 3:
    validate_url(sys.argv[2], "/forum2.php")
elif command == "validate-thread-page" and len(sys.argv) == 3:
    validate_url(sys.argv[2], "/forum2.php", require_thread_page=True)
elif command == "validate-control" and len(sys.argv) == 3:
    validate_url(
        sys.argv[2],
        "/forum2.php",
        category="public",
        require_thread_page=True,
    )
elif command == "validate-control-effective" and len(sys.argv) == 4:
    validate_control_effective_url(sys.argv[2], sys.argv[3])
elif command == "self-test-control-effective" and len(sys.argv) == 2:
    self_test_control_effective_url()
elif command == "validate-quote" and len(sys.argv) == 3:
    validate_url(sys.argv[2], "/message.php", require_quote_keys=True)
elif command == "validate-quote-probe" and len(sys.argv) == 3:
    validate_url(
        sys.argv[2],
        "/message.php",
        require_quote_keys=True,
        allow_missing_ref=True,
    )
elif command == "validate-multipage-urls" and len(sys.argv) == 6:
    validate_multipage_urls(sys.argv[2:])
elif command == "quote-variants" and len(sys.argv) == 3:
    derive_quote_variants(sys.argv[2])
elif command == "redact-url" and len(sys.argv) == 3:
    redact_url(sys.argv[2])
elif command == "quote-path" and len(sys.argv) == 4:
    extract_quote_path(sys.argv[2], sys.argv[3])
elif command == "form-report" and len(sys.argv) == 4:
    report_form(sys.argv[2], sys.argv[3])
elif command == "citation-report" and len(sys.argv) == 4:
    report_citation_headers(sys.argv[2], sys.argv[3])
elif command == "presence-report" and len(sys.argv) == 4:
    report_presence(sys.argv[2], sys.argv[3])
elif command == "page-report" and len(sys.argv) == 4:
    report_page(sys.argv[2], sys.argv[3])
elif command == "anchor-count" and len(sys.argv) == 3:
    report_anchor_count(sys.argv[2])
elif command == "validate-multipage-center" and len(sys.argv) == 7:
    validate_multipage_center(sys.argv[2], sys.argv[3:])
elif command == "multipage-consistency" and len(sys.argv) == 6:
    report_multipage_consistency(sys.argv[2:])
else:
    fail("Sous-commande d'analyse invalide")
PY
}

record_url() {
  local label="$1"
  local raw_url="$2"
  local redacted_url=''
  if redacted_url="$(html_tool redact-url "$raw_url" 2>&1)"; then
    record "$label : $redacted_url"
  else
    record "$label : <URL non affichée — masquage impossible>"
  fi
}

record_get_provenance() {
  local label="$1"
  local requested_url="$2"
  local rank="$3"
  local count="$4"
  local page_file="${5:-}"
  local anchor_count=''
  record "Provenance GET — $label"
  record_url '  URL demandée' "$requested_url"
  record_url '  URL effective' "$last_effective_url"
  record "  Code HTTP : ${last_response_code:-absent}"
  record "  Date HTTP : ${last_response_date:-absente}"
  record "  SHA-256 du fichier brut : ${last_response_sha256:-absent}"
  record "  Rang de capture : $rank/$count"
  if [[ -n "$page_file" ]]; then
    if ! anchor_count="$(html_tool anchor-count "$page_file" 2>&1)" \
      || [[ ! "$anchor_count" =~ ^[0-9]+$ ]]; then
      die "comptage des ancres impossible pour la provenance : $label"
    fi
    record "  Nombre d’ancres tN : $anchor_count"
  fi
}

append_presence_report() {
  local file_path="$1"
  local label="$2"
  if html_tool presence-report "$file_path" "$label" >> "$observations_file"; then
    record "Presence-report ajouté au rapport privé pour : $label"
  else
    record "Presence-report incomplet pour : $label"
  fi
}

prompt_line() {
  local prompt="$1"
  local variable_name="$2"
  local answer=''
  printf '%s' "$prompt" >&2
  IFS= read -r answer || die 'entrée interrompue'
  printf -v "$variable_name" '%s' "$answer"
}

prompt_url_with_validator() {
  local key="$1"
  local label="$2"
  local prompt="$3"
  local validator="$4"
  local raw_url=''
  local validated_url=''
  local validation_error=''
  while true; do
    printf '\n%s\n' "$label" >&2
    prompt_line "$prompt" raw_url
    if [[ -z "$raw_url" ]]; then
      return 1
    fi
    if validated_url="$(html_tool "$validator" "$raw_url" 2>&1)"; then
      target_url["$key"]="$validated_url"
      return 0
    else
      validation_error="$validated_url"
      printf 'URL non retenue : %s\n' "$validation_error" >&2
    fi
  done
}

prompt_thread_url() {
  local key="$1"
  local label="$2"
  local raw_url=''
  local validated_url=''
  local validation_error=''
  while true; do
    printf '\n%s\n' "$label" >&2
    prompt_line 'URL complète de la page forum2.php (Entrée = reporter ce cas) : ' raw_url
    if [[ -z "$raw_url" ]]; then
      capture_status["$key"]='à faire — cible non renseignée'
      return
    fi
    if validated_url="$(html_tool validate-thread "$raw_url" 2>&1)"; then
      target_url["$key"]="$validated_url"
      return
    else
      validation_error="$validated_url"
      printf 'URL non retenue : %s\n' "$validation_error" >&2
    fi
  done
}

prompt_thread_multipage() {
  local validation_error=''
  local key=''
  local -a prompts=(
    'Page intérieure N — URL complète forum2.php : '
    'Page N-1 — URL complète forum2.php : '
    'Page N+1 — URL complète forum2.php : '
    'Page hors borne dernière+1 — URL complète forum2.php : '
  )
  local -a keys=(multipage_center multipage_previous multipage_next multipage_outside)
  local index=0
  for index in "${!keys[@]}"; do
    if ! prompt_url_with_validator \
      "${keys[$index]}" \
      'Capture multipage : les URL sont fournies, jamais dérivées par le script.' \
      "${prompts[$index]}" \
      validate-thread-page; then
      capture_status[thread_multipage]='à faire — série d’URL incomplète'
      for key in "${keys[@]}"; do
        target_url["$key"]=''
      done
      return
    fi
  done
  if ! validation_error="$(html_tool validate-multipage-urls \
    "${target_url[multipage_center]}" \
    "${target_url[multipage_previous]}" \
    "${target_url[multipage_next]}" \
    "${target_url[multipage_outside]}" 2>&1)"; then
    capture_status[thread_multipage]='à faire — ordre ou conversation incohérents'
    for key in "${keys[@]}"; do
      target_url["$key"]=''
    done
    printf 'Série multipage refusée : %s\n' "$validation_error" >&2
  fi
}

prompt_control_pair() {
  if ! prompt_url_with_validator \
    control_subject \
    'Contrôle de présence : page du sujet MP à comparer.' \
    'URL complète de la page MP forum2.php (Entrée = reporter ce cas) : ' \
    validate-thread; then
    capture_status[control_topic]='à faire — sujet MP non renseigné'
    return
  fi
  printf '%s\n' \
    'ATTENTION : le prochain GET sera authentifié et déplacera le drapeau de lecture' \
    'du compte sur ce topic public.' >&2
  if ! prompt_url_with_validator \
    control_topic \
    'Contrôle positif : topic public du même auteur, capturé dans la même session.' \
    'URL complète du topic public forum2.php (cat numérique ; Entrée = reporter) : ' \
    validate-control; then
    target_url[control_subject]=''
    capture_status[control_topic]='à faire — topic de contrôle non renseigné'
  fi
}

prompt_quote_target() {
  local key="$1"
  local label="$2"
  local message_number=''
  prompt_thread_url "$key" "$label"
  if [[ -z "${target_url[$key]}" ]]; then
    return
  fi
  while true; do
    prompt_line \
      'Numéro du message à citer (icône « n°… » ou fragment #t… ; chiffres seuls) : ' \
      message_number
    if [[ "$message_number" =~ ^[0-9]+$ ]]; then
      target_message["$key"]="$message_number"
      return
    fi
    if [[ -z "$message_number" ]]; then
      target_url["$key"]=''
      capture_status["$key"]='à faire — message cible non renseigné'
      return
    fi
    printf 'Numéro invalide : saisir uniquement les chiffres après « n° » ou « #t ».\n' >&2
  done
}

create_private_capture_dir() {
  umask 077
  capture_dir="$(mktemp -d /tmp/redface2-mp-quotes.XXXXXX)"
  chmod 700 "$capture_dir"
  cookie_jar="$capture_dir/cookies.txt"
  observations_file="$capture_dir/observations.txt"
  : > "$observations_file"
  chmod 600 "$observations_file"
  printf '%s\n' \
    'ATTENTION — CE RAPPORT CONTIENT DU CONTENU DE MESSAGE PRIVÉ RÉEL.' \
    'Ne le collez dans aucune issue, ne le joignez à aucun message et ne le copiez dans aucun dépôt.' \
    'Il sert uniquement à décider localement, puis doit être supprimé.' \
    '' \
    > "$observations_file"
}

authenticate_once() {
  local hfr_capture_pseudo=''
  local login_headers="$capture_dir/login.headers"
  local login_body="$capture_dir/login.html"
  : > "$cookie_jar"
  : > "$login_headers"
  : > "$login_body"
  chmod 600 "$cookie_jar" "$login_headers" "$login_body"

  printf '\nAuthentification HFR unique pour toutes les cibles renseignées.\n' >&2
  prompt_line 'Pseudo HFR du compte de test : ' hfr_capture_pseudo
  [[ -n "$hfr_capture_pseudo" ]] || die 'pseudo HFR vide'
  printf 'Mot de passe HFR (non affiché) : ' >&2
  IFS= read -r -s hfr_capture_password || die 'lecture du mot de passe interrompue'
  printf '\n' >&2
  [[ -n "$hfr_capture_password" ]] || die 'mot de passe HFR vide'

  # Unique exception au trafic GET : HFR exige ce POST d'authentification fixe.
  # Le mot de passe passe sur stdin et n'apparaît jamais dans argv. Aucun autre
  # appel curl du script n'a de corps, et aucune URL bddpost.php n'est acceptée.
  if ! printf '%s' "$hfr_capture_password" | curl -sS \
    --proto '=https' \
    --request POST \
    -A "$HFR_USER_AGENT" \
    -D "$login_headers" \
    -c "$cookie_jar" \
    -b "$cookie_jar" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "pseudo=$hfr_capture_pseudo" \
    --data-urlencode 'password@-' \
    "$HFR_LOGIN_URL" \
    -o "$login_body"; then
    unset hfr_capture_password
    die "échec réseau pendant l'authentification HFR"
  fi
  unset hfr_capture_password

  # Ne jamais ajouter -L au login : il faut garder les Set-Cookie de la réponse
  # initiale. La valeur du cookie d'identité ne doit jamais être affichée.
  if ! awk '$6 == "md_user" { found=1 } END { exit !found }' "$cookie_jar"; then
    die "authentification non confirmée : cookie d'identité md_user absent"
  fi
  record 'Authentification : cookie md_user présent (valeur volontairement non affichée).'
  record "User-Agent commun au login et aux GET : $HFR_USER_AGENT"
}

# Garde réseau centrale : après authenticate_once, toutes les captures passent ici.
# Seuls deux endpoints de lecture sont admis, toujours en GET. bddpost.php, les
# endpoints de logout/favoris et tout autre chemin sont donc impossibles à appeler.
hfr_get() {
  local kind="$1"
  local requested_url="$2"
  local output_file="$3"
  local headers_file="$output_file.headers"
  local transfer_metadata_file="$output_file.transfer-metadata"
  local -a transfer_metadata=()
  local validated_url=''
  local effective_validation=''
  local validator=''
  last_effective_url=''
  last_response_date=''
  last_response_code=''
  last_response_sha256=''
  case "$kind" in
    thread) validator='validate-thread' ;;
    thread-page) validator='validate-thread-page' ;;
    control-topic) validator='validate-control' ;;
    quote) validator='validate-quote' ;;
    quote-probe) validator='validate-quote-probe' ;;
    *) die "type de GET interne inconnu : $kind" ;;
  esac
  case "$output_file" in
    "$capture_dir"/*) ;;
    *) die "sortie GET hors du répertoire privé refusée : $output_file" ;;
  esac
  if ! validated_url="$(html_tool "$validator" "$requested_url" 2>&1)"; then
    record "GET refusé avant réseau : $validated_url"
    return 1
  fi
  if [[ "$validated_url" == *'/bddpost.php'* ]]; then
    record 'GET refusé avant réseau : bddpost.php est interdit.'
    return 1
  fi
  : > "$output_file"
  : > "$headers_file"
  : > "$transfer_metadata_file"
  chmod 600 "$output_file" "$headers_file" "$transfer_metadata_file"
  if ! curl -fsSL \
    --proto '=https' \
    --request GET \
    -A "$HFR_USER_AGENT" \
    -b "$cookie_jar" \
    -c "$cookie_jar" \
    -D "$headers_file" \
    -w '%{url_effective}\n%{http_code}\n' \
    "$validated_url" \
    -o "$output_file" > "$transfer_metadata_file"; then
    chmod 600 "$output_file" "$headers_file" "$transfer_metadata_file" "$cookie_jar"
    return 1
  fi
  chmod 600 "$output_file" "$headers_file" "$transfer_metadata_file" "$cookie_jar"
  mapfile -t transfer_metadata < "$transfer_metadata_file"
  last_effective_url="${transfer_metadata[0]:-}"
  last_response_code="${transfer_metadata[1]:-}"
  if [[ -z "$last_effective_url" ]]; then
    record 'GET refusé après réseau : curl n’a pas fourni d’URL effective.'
    return 1
  fi
  if [[ ! "$last_response_code" =~ ^[0-9]{3}$ ]]; then
    record 'GET refusé après réseau : curl n’a pas fourni de code HTTP exploitable.'
    return 1
  fi
  if [[ "$kind" == 'control-topic' ]]; then
    effective_validation="$(
      html_tool validate-control-effective "$last_effective_url" "$validated_url" 2>&1
    )" || {
      record "GET refusé après redirection : $effective_validation"
      return 1
    }
  elif ! effective_validation="$(html_tool "$validator" "$last_effective_url" 2>&1)"; then
    record "GET refusé après redirection : $effective_validation"
    return 1
  fi
  last_effective_url="$effective_validation"
  last_response_date="$(awk '
    BEGIN { date = "" }
    {
      lower = tolower($0)
    }
    lower ~ /^http\// { date = "" }
    lower ~ /^date:[[:space:]]*/ {
      line = $0
      sub(/^[^:]*:[[:space:]]*/, "", line)
      sub(/\r$/, "", line)
      date = line
    }
    END { print date }
  ' "$headers_file")"
  last_response_sha256="$(sha256sum "$output_file")"
  last_response_sha256="${last_response_sha256%% *}"
}

capture_quote_form() {
  local key="$1"
  local label="$2"
  local basename="$3"
  local thread_html="$capture_dir/${basename}-thread.raw.html"
  local quote_html="$capture_dir/${basename}-form.raw.html"
  local extracted_link="$capture_dir/${basename}-href.txt"
  local extraction_error="$capture_dir/${basename}-href.error.txt"
  local quote_href=''
  local quote_path=''
  local -a extracted_lines=()

  record ''
  record "=== $label ==="
  record_url 'Page demandée' "${target_url[$key]}"
  record "Message ciblé : t${target_message[$key]}"
  if ! hfr_get thread "${target_url[$key]}" "$thread_html"; then
    record 'Capture de la page impossible ; ce cas reste à faire.'
    capture_status["$key"]='à faire — GET de la conversation en échec'
    return
  fi
  record_get_provenance "$label — page source" "${target_url[$key]}" 1 2
  record "Page brute capturée : $thread_html"
  append_presence_report "$thread_html" "$label — page source"

  if ! file_contains 'numrep=' "$thread_html"; then
    record 'Aucun texte numrep= servi dans cette page ; aucun href ne sera reconstruit.'
    capture_status["$key"]='à faire — aucun href de citation servi'
    return
  fi
  : > "$extracted_link"
  : > "$extraction_error"
  chmod 600 "$extracted_link" "$extraction_error"
  if ! html_tool quote-path "$thread_html" "${target_message[$key]}" \
    > "$extracted_link" 2> "$extraction_error"; then
    record "Lien de citation non relevé : $(<"$extraction_error")"
    capture_status["$key"]='à faire — toolbar ou href cible introuvable'
    return
  fi
  mapfile -t extracted_lines < "$extracted_link"
  if [[ "${#extracted_lines[@]}" -ne 2 ]]; then
    record 'Extraction ambiguë : deux lignes href/chemin étaient attendues ; GET refusé.'
    capture_status["$key"]='à faire — extraction du href ambiguë'
    return
  fi
  quote_href="${extracted_lines[0]#href_servi=}"
  quote_path="${extracted_lines[1]#chemin_get=}"
  record_url 'href réellement servi (entités HTML décodées)' "$quote_href"
  record_url 'Chemin GET exact (fragment retiré, query non reconstruite)' "$quote_path"

  if ! hfr_get quote "$HFR_ORIGIN$quote_path" "$quote_html"; then
    record 'Capture du formulaire impossible ; ce cas reste à faire.'
    capture_status["$key"]='à faire — GET du formulaire en échec'
    return
  fi
  record_get_provenance "$label — formulaire" "$HFR_ORIGIN$quote_path" 2 2
  record "Formulaire brut capturé : $quote_html"
  append_presence_report "$quote_html" "$label — formulaire"
  # Le rapport exhaustif contient le corps MP cité : il reste dans le fichier privé
  # et n'est pas dupliqué dans l'historique du terminal.
  if html_tool form-report "$quote_html" "$label" >> "$observations_file"; then
    capture_status["$key"]='capturée et observée'
    record "Observations exhaustives ajoutées au rapport privé : $observations_file"
  else
    capture_status["$key"]='à faire — form[name=hop] absent ou ambigu'
    record "Formulaire absent ou ambigu ; observations partielles conservées dans : $observations_file"
  fi
}

capture_quoted_thread() {
  local key='quoted_thread'
  local label='Conversation MP contenant un message qui en cite un autre'
  local thread_html="$capture_dir/mp-quoted-thread.raw.html"
  record ''
  record "=== $label ==="
  record_url 'Page demandée' "${target_url[$key]}"
  if ! hfr_get thread "${target_url[$key]}" "$thread_html"; then
    record 'Capture de la page impossible ; ce cas reste à faire.'
    capture_status["$key"]='à faire — GET de la conversation en échec'
    return
  fi
  record_get_provenance "$label" "${target_url[$key]}" 1 1
  record "Page brute capturée : $thread_html"
  append_presence_report "$thread_html" "$label"
  # Les href et textes d'en-tête peuvent identifier une conversation privée : le
  # détail exhaustif reste lui aussi dans le rapport temporaire privé.
  if html_tool citation-report "$thread_html" "$label" >> "$observations_file"; then
    capture_status["$key"]='capturée et observée'
    record "href d'en-tête exhaustifs ajoutés au rapport privé : $observations_file"
  else
    capture_status["$key"]='à faire — aucun href d’en-tête de citation trouvé'
    record "Aucun href d'en-tête trouvé ; observations conservées dans : $observations_file"
  fi
}

capture_thread_multipage() {
  local label='Conversation MP multipage'
  local center_file="$capture_dir/mp-multipage-n.raw.html"
  local previous_file="$capture_dir/mp-multipage-n-minus-1.raw.html"
  local next_file="$capture_dir/mp-multipage-n-plus-1.raw.html"
  local outside_file="$capture_dir/mp-multipage-last-plus-1.raw.html"
  local -a files=("$center_file" "$previous_file" "$next_file" "$outside_file")
  local -a keys=(multipage_center multipage_previous multipage_next multipage_outside)
  local -a labels=('N' 'N-1' 'N+1' 'dernière+1')
  local index=0
  local capture_key=''

  record ''
  record "=== $label ==="
  if ! hfr_get thread-page "${target_url[multipage_center]}" "$center_file"; then
    capture_status[thread_multipage]='à faire — GET de N en échec'
    return
  fi
  record_get_provenance "$label — N" "${target_url[multipage_center]}" 1 4 "$center_file"
  record "Page brute capturée : $center_file"
  append_presence_report "$center_file" "$label — N"
  if ! html_tool page-report "$center_file" "$label — N" >> "$observations_file"; then
    capture_status[thread_multipage]='à faire — page-report de N incomplet'
    return
  fi
  if ! html_tool validate-multipage-center \
    "$center_file" \
    "${target_url[multipage_center]}" \
    "${target_url[multipage_previous]}" \
    "${target_url[multipage_next]}" \
    "${target_url[multipage_outside]}" >> "$observations_file"; then
    capture_status[thread_multipage]='à faire — N non intérieure, moins de trois pages ou dernière+1 invalide'
    record 'Contrat multipage refusé après lecture du pager de N ; les trois GET suivants ne sont pas lancés.'
    return
  fi

  for index in 1 2 3; do
    capture_key="${keys[$index]}"
    if ! hfr_get thread-page "${target_url[$capture_key]}" "${files[$index]}"; then
      capture_status[thread_multipage]="à faire — GET de ${labels[$index]} en échec"
      return
    fi
    record_get_provenance \
      "$label — ${labels[$index]}" \
      "${target_url[$capture_key]}" \
      "$((index + 1))" \
      4 \
      "${files[$index]}"
    record "Page brute capturée : ${files[$index]}"
    append_presence_report "${files[$index]}" "$label — ${labels[$index]}"
    if ! html_tool page-report \
      "${files[$index]}" \
      "$label — ${labels[$index]}" >> "$observations_file"; then
      capture_status[thread_multipage]="à faire — page-report de ${labels[$index]} incomplet"
      return
    fi
  done

  if html_tool multipage-consistency "${files[@]}" >> "$observations_file"; then
    capture_status[thread_multipage]='capturée et cohérente'
    record "Page-reports et contrôle croisé ajoutés au rapport privé : $observations_file"
  else
    capture_status[thread_multipage]='à faire — cohérence des pages ou rabattement non prouvé'
    record "Contrôle multipage en échec ; rapports privés conservés dans : $observations_file"
  fi
}

capture_control_topic() {
  local subject_file="$capture_dir/presence-subject-mp.raw.html"
  local control_file="$capture_dir/presence-control-topic.raw.html"
  record ''
  record '=== Contrôle positif topic public authentifié ==='
  if ! hfr_get thread "${target_url[control_subject]}" "$subject_file"; then
    capture_status[control_topic]='à faire — GET du sujet MP en échec'
    return
  fi
  record_get_provenance 'Sujet MP' "${target_url[control_subject]}" 1 2
  record "Sujet MP brut capturé : $subject_file"
  append_presence_report "$subject_file" 'Sujet MP comparé au contrôle public'

  record 'ATTENTION consignée : le GET public authentifié peut déplacer le drapeau du compte.'
  if ! hfr_get control-topic "${target_url[control_topic]}" "$control_file"; then
    capture_status[control_topic]='à faire — GET du topic public en échec'
    return
  fi
  record_get_provenance 'Topic public de contrôle' "${target_url[control_topic]}" 2 2
  record "Topic public brut capturé : $control_file"
  append_presence_report "$control_file" 'Topic public de contrôle positif'
  capture_status[control_topic]='paire capturée et observée'
}

capture_ref_probe() {
  local key='ref_probe'
  local label='Sonde citation MP : ref servi / absent / zéro'
  local thread_html="$capture_dir/ref-probe-thread.raw.html"
  local extracted_link="$capture_dir/ref-probe-href.txt"
  local extraction_error="$capture_dir/ref-probe-href.error.txt"
  local variants_file="$capture_dir/ref-probe-variants.txt"
  local variants_error="$capture_dir/ref-probe-variants.error.txt"
  local quote_path=''
  local quote_href=''
  local -a extracted_lines=()
  local -a variant_lines=()
  local -a variant_names=('ref d’origine' 'ref absent' 'ref=0')
  local -a variant_paths=()
  local -a variant_files=(
    "$capture_dir/ref-probe-original.raw.html"
    "$capture_dir/ref-probe-without-ref.raw.html"
    "$capture_dir/ref-probe-ref-zero.raw.html"
  )
  local index=0
  local all_forms_valid=1

  record ''
  record "=== $label ==="
  record "Message ciblé : t${target_message[$key]}"
  if ! hfr_get thread "${target_url[$key]}" "$thread_html"; then
    capture_status[ref_probe]='à faire — GET de la conversation en échec'
    return
  fi
  record_get_provenance "$label — href source" "${target_url[$key]}" 1 4
  record "Page source brute capturée : $thread_html"
  append_presence_report "$thread_html" "$label — href source"

  : > "$extracted_link"
  : > "$extraction_error"
  : > "$variants_file"
  : > "$variants_error"
  chmod 600 "$extracted_link" "$extraction_error" "$variants_file" "$variants_error"
  if ! html_tool quote-path "$thread_html" "${target_message[$key]}" \
    > "$extracted_link" 2> "$extraction_error"; then
    capture_status[ref_probe]='à faire — href servi cible introuvable'
    record "Lien de citation non relevé : $(<"$extraction_error")"
    return
  fi
  mapfile -t extracted_lines < "$extracted_link"
  if [[ "${#extracted_lines[@]}" -ne 2 ]]; then
    capture_status[ref_probe]='à faire — extraction du href ambiguë'
    record 'Extraction ambiguë : deux lignes href/chemin étaient attendues.'
    return
  fi
  quote_href="${extracted_lines[0]#href_servi=}"
  quote_path="${extracted_lines[1]#chemin_get=}"
  record_url 'href source réellement servi' "$quote_href"
  if ! html_tool quote-variants "$quote_path" > "$variants_file" 2> "$variants_error"; then
    capture_status[ref_probe]='à faire — dérivation ref refusée'
    record "Dérivation ref refusée : $(<"$variants_error")"
    return
  fi
  mapfile -t variant_lines < "$variants_file"
  if [[ "${#variant_lines[@]}" -ne 3 ]]; then
    capture_status[ref_probe]='à faire — trois variantes non produites'
    record 'Dérivation ambiguë : trois chemins étaient attendus.'
    return
  fi
  variant_paths=(
    "${variant_lines[0]#original=}"
    "${variant_lines[1]#without_ref=}"
    "${variant_lines[2]#ref_zero=}"
  )

  for index in 0 1 2; do
    if ! hfr_get quote-probe "$HFR_ORIGIN${variant_paths[$index]}" "${variant_files[$index]}"; then
      capture_status[ref_probe]="à faire — GET ${variant_names[$index]} en échec"
      return
    fi
    record_get_provenance \
      "$label — ${variant_names[$index]}" \
      "$HFR_ORIGIN${variant_paths[$index]}" \
      "$((index + 2))" \
      4
    record "Formulaire brut capturé (${variant_names[$index]}) : ${variant_files[$index]}"
    append_presence_report "${variant_files[$index]}" "$label — ${variant_names[$index]}"
    if ! html_tool form-report \
      "${variant_files[$index]}" \
      "$label — ${variant_names[$index]}" >> "$observations_file"; then
      all_forms_valid=0
    fi
  done

  if [[ "$all_forms_valid" -eq 1 ]]; then
    capture_status[ref_probe]='trois variantes capturées et observées'
  else
    capture_status[ref_probe]='capturée — au moins un form[name=hop] absent ou ambigu'
  fi
  record "Résultats des trois variantes ajoutés au rapport privé : $observations_file"
}

print_summary() {
  printf '\nRésultat des captures demandées\n'
  printf '  - Seconde citation MP 1:1 : %s\n' "${capture_status[mp_quote]}"
  printf '  - Citation DT / MultiMP : %s\n' "${capture_status[dt_quote]}"
  printf '  - Conversation avec citation : %s\n' "${capture_status[quoted_thread]}"
  printf '  - Conversation multipage : %s\n' "${capture_status[thread_multipage]}"
  printf '  - Contrôle topic public : %s\n' "${capture_status[control_topic]}"
  printf '  - Sonde ref : %s\n' "${capture_status[ref_probe]}"
  printf '\nRapport exhaustif : %s\n' "$observations_file"
  printf '%s\n' \
    'ATTENTION : ce rapport contient du contenu de message privé réel.' \
    'Ne le collez dans aucune issue, ne le joignez à aucun message et ne le copiez dans aucun dépôt.' \
    'Il sert uniquement à décider localement, puis doit être supprimé.'
  printf 'Bruts privés : %s\n' "$capture_dir"
  printf '%s\n' \
    'ATTENTION : captures non réduites et non assainies — ne rien copier dans le dépôt.' \
    'Étape humaine restante : réduire au sous-arbre strictement nécessaire,' \
    'décoder et neutraliser les cryptlinks, assainir toutes les données personnelles,' \
    'énumérer intégralement le DOM, créer les sidecars, puis supprimer ce répertoire privé.'
}

explicit_selection=0
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --mp-quote)
      selected[mp_quote]=1
      explicit_selection=1
      ;;
    --dt-quote)
      selected[dt_quote]=1
      explicit_selection=1
      ;;
    --quoted-thread)
      selected[quoted_thread]=1
      explicit_selection=1
      ;;
    --thread-multipage)
      selected[thread_multipage]=1
      explicit_selection=1
      ;;
    --control-topic)
      selected[control_topic]=1
      explicit_selection=1
      ;;
    --ref-probe)
      selected[ref_probe]=1
      explicit_selection=1
      ;;
    --self-test-control-redirect)
      self_test_control_redirect=1
      ;;
    --all)
      selected[mp_quote]=1
      selected[dt_quote]=1
      selected[quoted_thread]=1
      selected[thread_multipage]=1
      selected[control_topic]=1
      selected[ref_probe]=1
      explicit_selection=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Option inconnue : %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$self_test_control_redirect" -eq 1 ]]; then
  require_command python3
  html_tool self-test-control-effective
  exit 0
fi

if [[ "$explicit_selection" -eq 0 ]]; then
  selected[mp_quote]=1
  selected[dt_quote]=1
  selected[quoted_thread]=1
  selected[thread_multipage]=1
  selected[control_topic]=1
  selected[ref_probe]=1
fi

for dependency in curl python3 awk sha256sum mktemp chmod tee; do
  require_command "$dependency"
done
select_search_tool

if [[ "${selected[mp_quote]}" -eq 1 ]]; then
  capture_status[mp_quote]='à faire — cible non renseignée'
  prompt_quote_target \
    mp_quote \
    'Seconde citation MP 1:1 : choisir un AUTRE message que la première fixture, sur une autre page si possible.'
fi
if [[ "${selected[dt_quote]}" -eq 1 ]]; then
  capture_status[dt_quote]='à faire — cible non renseignée'
  prompt_quote_target \
    dt_quote \
    'Citation DT / MultiMP : choisir une discussion de groupe de test où ce compte peut citer.'
fi
if [[ "${selected[quoted_thread]}" -eq 1 ]]; then
  capture_status[quoted_thread]='à faire — cible non renseignée'
  prompt_thread_url \
    quoted_thread \
    'Conversation avec citation : page affichant un message qui cite déjà un autre message (créable avec Redface 2 0.42.4).'
fi
if [[ "${selected[thread_multipage]}" -eq 1 ]]; then
  capture_status[thread_multipage]='à faire — cibles non renseignées'
  prompt_thread_multipage
fi
if [[ "${selected[control_topic]}" -eq 1 ]]; then
  capture_status[control_topic]='à faire — cibles non renseignées'
  prompt_control_pair
fi
if [[ "${selected[ref_probe]}" -eq 1 ]]; then
  capture_status[ref_probe]='à faire — cible non renseignée'
  prompt_quote_target \
    ref_probe \
    'Sonde ref #1110 : choisir un message dont la toolbar sert un href de citation avec ref.'
fi

targets_to_capture=0
for key in mp_quote dt_quote quoted_thread multipage_center control_subject ref_probe; do
  if [[ -n "${target_url[$key]}" ]]; then
    targets_to_capture=$((targets_to_capture + 1))
  fi
done
if [[ "$targets_to_capture" -eq 0 ]]; then
  printf '\nAucune cible renseignée. Rien n’a été envoyé à HFR ; les cas sélectionnés restent à faire.\n'
  printf '  - Seconde citation MP 1:1 : %s\n' "${capture_status[mp_quote]}"
  printf '  - Citation DT / MultiMP : %s\n' "${capture_status[dt_quote]}"
  printf '  - Conversation avec citation : %s\n' "${capture_status[quoted_thread]}"
  printf '  - Conversation multipage : %s\n' "${capture_status[thread_multipage]}"
  printf '  - Contrôle topic public : %s\n' "${capture_status[control_topic]}"
  printf '  - Sonde ref : %s\n' "${capture_status[ref_probe]}"
  printf 'Relancez le script avec une option de capture ; voir --help.\n'
  exit 0
fi

create_private_capture_dir
trap 'unset hfr_capture_password' EXIT
record "Répertoire temporaire privé : $capture_dir"
record "Outil de recherche détecté : $search_tool"
record 'Politique réseau : un POST de login fixe, puis uniquement des GET de lecture validés.'
authenticate_once

if [[ -n "${target_url[mp_quote]}" ]]; then
  capture_quote_form \
    mp_quote \
    'Seconde citation MP 1:1' \
    'mp-second-quote'
fi
if [[ -n "${target_url[dt_quote]}" ]]; then
  capture_quote_form \
    dt_quote \
    'Citation DT / MultiMP' \
    'mp-dt-quote'
fi
if [[ -n "${target_url[quoted_thread]}" ]]; then
  capture_quoted_thread
fi
if [[ -n "${target_url[multipage_center]}" ]]; then
  capture_thread_multipage
fi
if [[ -n "${target_url[control_subject]}" ]]; then
  capture_control_topic
fi
if [[ -n "${target_url[ref_probe]}" ]]; then
  capture_ref_probe
fi

print_summary
