#!/usr/bin/env python3
"""Build paginated before/after sheets from reference changes against a Git commit.

Requires Python 3 and Pillow already installed locally; never downloads dependencies.
Includes staged/unstaged changes and untracked references. Renames appear as delete/add.
"""

import argparse
from html import escape
from io import BytesIO
from pathlib import Path
import subprocess
import textwrap

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    raise SystemExit("Pillow is required. Use a local Python with Pillow (e.g. /usr/bin/python3).")


CELL_WIDTH = 480
CELL_HEIGHT = 640
GAP = 20
LABEL_HEIGHT = 90
ROWS_PER_PAGE = 3


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=root)


def reference(name: str) -> bool:
    return "/src/test/screenshots/" in name and name.endswith(".png")


def changed_references(root: Path, base: str, include_deleted: bool) -> list[str]:
    changed = git(root, "diff", "--name-only", "-z", "--no-renames", base, "--").decode().split("\0")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")
    return sorted(name for name in set(changed + untracked) if reference(name) and
                  (include_deleted or (root / name).is_file()))


def load(data: bytes) -> Image.Image:
    with Image.open(BytesIO(data)) as image:
        return image.convert("RGBA")


def draw_image(page: Image.Image, data: bytes | None, x: int, y: int, font: ImageFont.ImageFont) -> None:
    draw = ImageDraw.Draw(page)
    draw.rectangle((x, y, x + CELL_WIDTH, y + CELL_HEIGHT), fill="#eeeeee", outline="#aaaaaa")
    if data is None:
        draw.text((x + GAP, y + GAP), "Absent", font=font, fill="black")
        return
    image = load(data)
    draw.text((x, y - 24), f"{image.width} x {image.height} px", font=font, fill="black")
    image.thumbnail((CELL_WIDTH - 2, CELL_HEIGHT - 2), Image.Resampling.LANCZOS)
    page.paste(image, (x + (CELL_WIDTH - image.width) // 2, y + (CELL_HEIGHT - image.height) // 2), image)


def generate(root: Path, base: str, output: Path, include_deleted: bool) -> None:
    # Resolve once: all git-show calls use the exact same commit, even if the branch later moves.
    commit = git(root, "rev-parse", "--verify", f"{base}^{{commit}}").decode().strip()
    names = changed_references(root, commit, include_deleted)
    base_paths = set(git(root, "ls-tree", "-r", "--name-only", "-z", commit).decode().split("\0"))
    output.mkdir(parents=True, exist_ok=True)
    originals = output / "originals"
    originals.mkdir(exist_ok=True)
    font = ImageFont.load_default(size=16)
    row_height = CELL_HEIGHT + LABEL_HEIGHT + 40
    pages = []
    links = []
    for offset in range(0, len(names), ROWS_PER_PAGE):
        batch = names[offset:offset + ROWS_PER_PAGE]
        page = Image.new("RGB", (2 * CELL_WIDTH + 3 * GAP, len(batch) * row_height + GAP), "white")
        draw = ImageDraw.Draw(page)
        for row, name in enumerate(batch):
            before = git(root, "show", f"{commit}:{name}") if name in base_paths else None
            after = (root / name).read_bytes() if (root / name).is_file() else None
            status = "added" if before is None else "deleted" if after is None else "modified"
            y = GAP + row * row_height
            title = f"[{status}] {name}"
            draw.multiline_text((GAP, y), "\n".join(textwrap.wrap(title, width=100)), font=font, fill="black")
            image_links = []
            for column, (label, data) in enumerate((("Before", before), ("After", after))):
                x = GAP + column * (CELL_WIDTH + GAP)
                draw.text((x, y + LABEL_HEIGHT - 28), label, font=font, fill="black")
                draw_image(page, data, x, y + LABEL_HEIGHT + 24, font)
                if data is not None:
                    original = f"{offset + row + 1:04}-{label.lower()}.png"
                    (originals / original).write_bytes(data)
                    image_links.append(f"<a href='originals/{original}'>{label}</a>")
            links.append(f"<li>{escape(title)}: {' / '.join(image_links)}</li>")
        filename = f"contact-{offset // ROWS_PER_PAGE + 1:03}.png"
        page.save(output / filename)
        pages.append(filename)

    # This index only links the current run's pages, even if an older run produced more pages.
    content = f"<!doctype html><meta charset='utf-8'><title>Roborazzi</title><h1>{len(names)} références</h1>"
    content += f"<p>Base : {escape(base)} ({commit}). Avant à gauche, après à droite.</p>"
    content += "<p>Ouvrir les planches à leur taille réelle pour relire les détails.</p>"
    content += "".join(f"<p><a href='{name}'>{name}</a></p><img src='{name}' style='max-width:100%'>"
                       for name in pages)
    content += "<h2>Originaux à pleine résolution</h2><ul>" + "".join(links) + "</ul>"
    (output / "index.html").write_text(content, encoding="utf-8")
    print(f"{len(names)} references, {len(pages)} sheets: {output / 'index.html'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="Commit or local ref to compare with the working tree")
    parser.add_argument("--output", type=Path, default=Path("build/reports/roborazzi-contact-sheet"))
    parser.add_argument("--include-deleted", action="store_true", help="Also show removed references")
    args = parser.parse_args()
    repo = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
    generate(repo, args.base, args.output.resolve(), args.include_deleted)
