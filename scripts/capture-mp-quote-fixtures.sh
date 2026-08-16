#!/usr/bin/env bash
# Capture groupée des trois preuves live manquantes sur les citations MP (#1040).
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

declare -A selected=(
  [mp_quote]=0
  [dt_quote]=0
  [quoted_thread]=0
)
declare -A target_url=(
  [mp_quote]=''
  [dt_quote]=''
  [quoted_thread]=''
)
declare -A target_message=(
  [mp_quote]=''
  [dt_quote]=''
)
declare -A capture_status=(
  [mp_quote]='non demandée'
  [dt_quote]='non demandée'
  [quoted_thread]='non demandée'
)

usage() {
  cat <<'EOF'
Usage : scripts/capture-mp-quote-fixtures.sh [OPTIONS]

Capture, dans une seule session HFR authentifiée, tout ou partie des trois preuves
live manquantes du lot 4 de #1040 :

  --mp-quote       seconde citation dans une conversation MP 1:1
  --dt-quote       citation dans une discussion de groupe (DT / MultiMP)
  --quoted-thread  conversation MP contenant déjà une citation
  --all            les trois captures (comportement par défaut)
  -h, --help       afficher cette aide

Le script demande des URL complètes de pages de conversation et, pour les deux
formulaires, le numéro visible via l'icône « n°… » du message à citer (ou dans
le fragment #t… de son lien). Une URL laissée vide reporte seulement ce cas :
les autres captures continuent. Relancer avec une seule option permet de reprendre
le cas manquant.

Pour le troisième cas, si aucune conversation de test ne contient encore de
citation, Redface 2 0.42.4 permet d'en créer une : citer un message dans une
conversation de test consentie, puis fournir au script la page qui affiche le
message nouvellement envoyé.

Sécurité :
  - le mot de passe est lu sans écho et envoyé sur l'entrée standard de curl ;
  - le seul POST est l'authentification fixe vers login_validation.php ;
  - toutes les captures suivantes sont des GET, et bddpost.php est refusé ;
  - aucun formulaire de message n'est soumis ;
  - les HTML bruts et le rapport restent dans un mktemp privé sous /tmp.

Le script CAPTURE et OBSERVE, mais n'assainit rien. Le rapport observations.txt
contient le contenu intégral de messages privés réels. Ne le collez jamais dans
une issue, ne le joignez à aucun message et ne le copiez dans aucun dépôt : il
sert à décider localement, puis doit être supprimé. Ne copiez pas non plus les
HTML bruts dans le dépôt.

Peut être rapportée publiquement : la structure observée (présence et noms des
champs cachés, présence ou absence de ref, valeur de numreponse, nombre de blocs
[quotemsg]). Ne peut pas l'être : le contenu des champs, notamment content_form
et les blocs [quotemsg]. Avant toute fixture : réduire à form[name=hop],
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
from urllib.parse import parse_qs, urldefrag, urlsplit, urlunsplit

HFR_HOST = "forum.hardware.fr"


def fail(message, code=2):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def split_hfr_url(raw_url, expected_path):
    try:
        parts = urlsplit(raw_url.strip())
        port = parts.port
    except ValueError as error:
        fail(f"URL invalide : {error}")
    if parts.scheme.lower() != "https" or parts.hostname != HFR_HOST:
        fail("URL refusée : fournir une URL HTTPS de forum.hardware.fr")
    if parts.username is not None or parts.password is not None or port not in (None, 443):
        fail("URL refusée : identifiants ou port inattendus")
    if parts.path != expected_path:
        fail(f"URL refusée : chemin attendu {expected_path}, reçu {parts.path or '/'}")
    query = parse_qs(parts.query, keep_blank_values=True)
    if query.get("cat") != ["prive"]:
        fail("URL refusée : la requête doit porter exactement cat=prive")
    return parts, query


def validate_url(raw_url, expected_path, require_quote_keys=False):
    parts, query = split_hfr_url(raw_url, expected_path)
    if require_quote_keys and ("numrep" not in query or "ref" not in query):
        fail("URL de citation refusée : numrep et ref doivent provenir du href servi")
    # Un fragment navigateur n'est jamais envoyé au serveur.
    print(urlunsplit(("https", HFR_HOST, parts.path, parts.query, "")))


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
    if query.get("cat") != ["prive"] or "numrep" not in query or "ref" not in query:
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


def report_form(file_path, label):
    parser = HopFormParser()
    with open(file_path, encoding="utf-8", errors="replace") as source:
        parser.feed(source.read())
    parser.close()
    print(f"\n--- Observations : {label} ---")
    print(f"form[name=hop] servis : {len(parser.forms)}")
    for index, form in enumerate(parser.forms, start=1):
        attrs = form["attrs"]
        print(
            f"Formulaire {index} : method={attrs.get('method')!r} ; "
            f"action={attrs.get('action')!r}"
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
            print(f"  content_form {textarea_index} — contenu intégral exact servi :")
            print(content)
            if quote_blocks:
                for quote_index, block in enumerate(quote_blocks, start=1):
                    print(f"  [quotemsg] {quote_index} — contenu exact servi :")
                    print(block)
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
        print(f"  {index:02d}. href={href!r} ; texte={text!r}")
    if parser.other_quote_links:
        print(
            "Autres href dans le corps des citations "
            f"({len(parser.other_quote_links)}, non confondus avec les en-têtes) :"
        )
        for index, (href, text) in enumerate(parser.other_quote_links, start=1):
            print(f"  {index:02d}. href={href!r} ; texte={text!r}")
    if not parser.header_links:
        print("Aucun href d'en-tête trouvé : la cible reste à capturer.")
        raise SystemExit(4)


if len(sys.argv) < 2:
    fail("Sous-commande d'analyse absente")
command = sys.argv[1]
if command == "validate-thread" and len(sys.argv) == 3:
    validate_url(sys.argv[2], "/forum2.php")
elif command == "validate-quote" and len(sys.argv) == 3:
    validate_url(sys.argv[2], "/message.php", require_quote_keys=True)
elif command == "quote-path" and len(sys.argv) == 4:
    extract_quote_path(sys.argv[2], sys.argv[3])
elif command == "form-report" and len(sys.argv) == 4:
    report_form(sys.argv[2], sys.argv[3])
elif command == "citation-report" and len(sys.argv) == 4:
    report_citation_headers(sys.argv[2], sys.argv[3])
else:
    fail("Sous-commande d'analyse invalide")
PY
}

prompt_line() {
  local prompt="$1"
  local variable_name="$2"
  local answer=''
  printf '%s' "$prompt" >&2
  IFS= read -r answer || die 'entrée interrompue'
  printf -v "$variable_name" '%s' "$answer"
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
  local validated_url=''
  local validator=''
  case "$kind" in
    thread) validator='validate-thread' ;;
    quote) validator='validate-quote' ;;
    *) die "type de GET interne inconnu : $kind" ;;
  esac
  if ! validated_url="$(html_tool "$validator" "$requested_url" 2>&1)"; then
    record "GET refusé avant réseau : $validated_url"
    return 1
  fi
  if [[ "$validated_url" == *'/bddpost.php'* ]]; then
    record 'GET refusé avant réseau : bddpost.php est interdit.'
    return 1
  fi
  curl -fsSL \
    --proto '=https' \
    --request GET \
    -A "$HFR_USER_AGENT" \
    -b "$cookie_jar" \
    -c "$cookie_jar" \
    "$validated_url" \
    -o "$output_file"
  chmod 600 "$output_file" "$cookie_jar"
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
  record "Page demandée : ${target_url[$key]}"
  record "Message ciblé : t${target_message[$key]}"
  if ! hfr_get thread "${target_url[$key]}" "$thread_html"; then
    record 'Capture de la page impossible ; ce cas reste à faire.'
    capture_status["$key"]='à faire — GET de la conversation en échec'
    return
  fi
  record "Page brute capturée : $thread_html"

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
  record "href réellement servi (entités HTML décodées) : $quote_href"
  record "Chemin GET exact (fragment retiré, query non reconstruite) : $quote_path"

  if ! hfr_get quote "$HFR_ORIGIN$quote_path" "$quote_html"; then
    record 'Capture du formulaire impossible ; ce cas reste à faire.'
    capture_status["$key"]='à faire — GET du formulaire en échec'
    return
  fi
  record "Formulaire brut capturé : $quote_html"
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
  record "Page demandée : ${target_url[$key]}"
  if ! hfr_get thread "${target_url[$key]}" "$thread_html"; then
    record 'Capture de la page impossible ; ce cas reste à faire.'
    capture_status["$key"]='à faire — GET de la conversation en échec'
    return
  fi
  record "Page brute capturée : $thread_html"
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

print_summary() {
  printf '\nRésultat des captures demandées\n'
  printf '  - Seconde citation MP 1:1 : %s\n' "${capture_status[mp_quote]}"
  printf '  - Citation DT / MultiMP : %s\n' "${capture_status[dt_quote]}"
  printf '  - Conversation avec citation : %s\n' "${capture_status[quoted_thread]}"
  printf '\nRapport exhaustif : %s\n' "$observations_file"
  printf '%s\n' \
    'ATTENTION : ce rapport contient du contenu de message privé réel.' \
    'Ne le collez dans aucune issue, ne le joignez à aucun message et ne le copiez dans aucun dépôt.' \
    'Il sert uniquement à décider localement, puis doit être supprimé.'
  printf 'Bruts privés : %s\n' "$capture_dir"
  printf '%s\n' \
    'ATTENTION : captures non réduites et non assainies — ne rien copier dans le dépôt.' \
    'Étape humaine restante : réduire à form[name=hop] (pour les formulaires),' \
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
    --all)
      selected[mp_quote]=1
      selected[dt_quote]=1
      selected[quoted_thread]=1
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

if [[ "$explicit_selection" -eq 0 ]]; then
  selected[mp_quote]=1
  selected[dt_quote]=1
  selected[quoted_thread]=1
fi

for dependency in curl python3 awk mktemp chmod tee; do
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

targets_to_capture=0
for key in mp_quote dt_quote quoted_thread; do
  if [[ -n "${target_url[$key]}" ]]; then
    targets_to_capture=$((targets_to_capture + 1))
  fi
done
if [[ "$targets_to_capture" -eq 0 ]]; then
  printf '\nAucune cible renseignée. Rien n’a été envoyé à HFR ; les cas sélectionnés restent à faire.\n'
  printf '  - Seconde citation MP 1:1 : %s\n' "${capture_status[mp_quote]}"
  printf '  - Citation DT / MultiMP : %s\n' "${capture_status[dt_quote]}"
  printf '  - Conversation avec citation : %s\n' "${capture_status[quoted_thread]}"
  printf 'Relancez le script avec --mp-quote, --dt-quote ou --quoted-thread.\n'
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

print_summary
