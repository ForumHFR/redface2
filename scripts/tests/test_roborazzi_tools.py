"""Local regression checks: python3 -m unittest discover -s scripts/tests -v (Pillow required)."""

from contextlib import redirect_stdout
import importlib.util
from io import BytesIO, StringIO
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard = load_script("check-roborazzi-duplicates")
sheet = load_script("roborazzi-contact-sheet")


class DuplicateGuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)

    def write(self, name, data=b"deterministic PNG bytes"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def check(self):
        with redirect_stdout(StringIO()):
            return guard.check(self.root)

    def test_untracked_duplicate_fails_but_identical_bytes_across_modules_pass(self):
        self.write("core/ui/src/test/screenshots/a.png")
        self.write("feature/topic/src/test/screenshots/a.png")
        self.assertEqual(0, self.check())
        self.write("core/ui/src/test/screenshots/nested/b.png")
        self.assertEqual(1, self.check())

    def test_deleted_tracked_reference_and_build_outputs_do_not_count(self):
        name = "core/ui/src/test/screenshots/a.png"
        self.write(name)
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        (self.root / name).unlink()
        self.write("core/ui/src/test/screenshots/b.png")
        self.write("core/ui/build/outputs/roborazzi/actual.png")
        self.assertEqual(0, self.check())

    def test_exception_must_match_exact_group_and_have_a_reason(self):
        a, b, c = (f"core/ui/src/test/screenshots/{name}.png" for name in "abc")
        self.write(a)
        self.write(b)
        with patch.dict(guard.DUPLICATE_EXCEPTIONS, {frozenset((a, b)): "deliberate test exception"}):
            self.assertEqual(0, self.check())
            self.write(c)
            self.assertEqual(1, self.check())
            (self.root / c).unlink()
            (self.root / b).unlink()
            self.assertEqual(1, self.check())  # stale exception


class ContactSheetTest(unittest.TestCase):
    def test_selection_includes_modified_staged_and_untracked_references(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = [f"core/ui/src/test/screenshots/{name}.png" for name in ("mod space", "add", "delete")]
            for name in names[:2]:
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).touch()
            diff = ("\0".join([names[0], names[2], "unrelated.png"]) + "\0").encode()
            untracked = (names[1] + "\0").encode()
            with patch.object(sheet, "git", side_effect=[diff, untracked]):
                self.assertEqual(sorted(names[:2]), sheet.changed_references(root, "base", False))
            with patch.object(sheet, "git", side_effect=[diff, untracked]):
                self.assertEqual(sorted(names), sheet.changed_references(root, "base", True))

    def test_paginated_before_after_handles_add_delete_sizes_and_transparency(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = [f"core/ui/src/test/screenshots/{name}.png" for name in ("mod", "add", "delete", "fourth")]
            buffer = BytesIO()
            Image.new("RGBA", (16, 20), (0, 0, 0, 0)).save(buffer, format="PNG")
            old = buffer.getvalue()
            for name in (names[0], names[1], names[3]):
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                Image.new("RGB", (800, 200), "red").save(root / name)

            def fake_git(_root, *args):
                if args[0] == "rev-parse":
                    return b"resolved-commit\n"
                if args[0] == "ls-tree":
                    return ("\0".join((names[0], names[2], names[3])) + "\0").encode()
                self.assertEqual("show", args[0])
                self.assertTrue(args[1].startswith("resolved-commit:"))
                return old

            output = root / "report"
            with patch.object(sheet, "changed_references", return_value=names), \
                    patch.object(sheet, "git", side_effect=fake_git), redirect_stdout(StringIO()):
                sheet.generate(root, "base", output, True)
            self.assertEqual(2, len(list(output.glob("contact-*.png"))))
            with Image.open(output / "contact-001.png") as image:
                self.assertEqual((1020, 2330), image.size)
            self.assertIn("4 références", (output / "index.html").read_text())
            self.assertEqual(old, (output / "originals/0001-before.png").read_bytes())
            with Image.open(output / "originals/0001-after.png") as image:
                self.assertEqual((800, 200), image.size)


if __name__ == "__main__":
    unittest.main()
