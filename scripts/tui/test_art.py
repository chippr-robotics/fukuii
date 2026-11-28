"""Self-tests for the TUI art scripts: `python3 -I -m unittest discover -s scripts/tui -v`."""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_art  # noqa: E402
import hi_art  # noqa: E402
import lo_art  # noqa: E402
import make_art  # noqa: E402


class ArtTest(unittest.TestCase):
    def test_committed_sheets_are_what_the_scripts_draw(self):
        for name, pixels in make_art.sheets().items():
            path = make_art.ART_DIR / name
            self.assertTrue(path.exists(), name)
            self.assertTrue(make_art.same(make_art.load(path), pixels), f"{name} is stale: run make_art.py")
        self.assertEqual((make_art.ART_DIR / "art.conf").read_text(), make_art.manifest())
        self.assertEqual(make_art.PIXEL_HASHES.read_text(), make_art.pixel_hashes(make_art.ART_DIR))

    def test_committed_sheets_keep_the_contract(self):
        self.assertEqual(check_art.check(make_art.ART_DIR), [])

    def test_lo_sheets_use_only_sprite_colours_and_hi_sheets_are_rgba(self):
        for name, pixels in make_art.sheets().items():
            if name.endswith(".lo.png"):
                used = set(np.unique(pixels).tolist())
                self.assertTrue(used <= {0} | set(range(16, 256)), name)
            else:
                self.assertEqual(pixels.ndim, 3, name)
                self.assertEqual(pixels.shape[2], 4, name)

    def test_every_pose_has_its_own_block_mode_drawing(self):
        big = [lo_art.mascot(p).a for p in make_art.POSES]
        small = [lo_art.mascot16(p).a for p in make_art.POSES]
        for drawings in (big, small):
            for i in range(len(drawings)):
                for j in range(i + 1, len(drawings)):
                    self.assertFalse(np.array_equal(drawings[i], drawings[j]), (make_art.POSES[i], make_art.POSES[j]))

    def test_frames_of_a_pose_differ(self):
        sheet, frame = make_art.sheets()["mascot.hi.png"], 32 * make_art.CELL
        for r, pose in enumerate(make_art.POSES):
            first = sheet[r * frame : (r + 1) * frame, 0:frame]
            for f in range(1, make_art.MASCOT_FRAMES[pose]):
                other = sheet[r * frame : (r + 1) * frame, f * frame : (f + 1) * frame]
                self.assertFalse(np.array_equal(first, other), (pose, f))

    def test_the_hi_check_tolerates_rounding_but_not_a_change(self):
        drawn = make_art.sheets()["badge.hi.png"]
        nudged = drawn.copy()
        nudged[::97, ::89, 0] = np.clip(nudged[::97, ::89, 0].astype(int) + 1, 0, 255).astype(np.uint8)
        self.assertTrue(make_art.same(nudged, drawn))
        changed = drawn.copy()
        changed[40:120, 40:120] = (255, 0, 0, 255)
        self.assertFalse(make_art.same(changed, drawn))

    def test_resampling_keeps_transparent_pixels_from_colouring_an_edge(self):
        im = Image.new("RGBA", (2, 1))
        im.putpixel((0, 0), (255, 0, 0, 255))
        im.putpixel((1, 0), (0, 0, 255, 0))
        r, g, b, a = hi_art.resample(im, 1, 1).getpixel((0, 0))
        self.assertGreater(r, 250)
        self.assertEqual(b, 0)
        self.assertTrue(100 <= a <= 160, a)

    def _copy(self) -> Path:
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        for f in make_art.ART_DIR.iterdir():
            shutil.copy(f, d / f.name)
        return d

    def test_checker_rejects_a_colour_outside_the_palette_in_a_lo_sheet_only(self):
        d = self._copy()
        im = Image.open(d / "badge.lo.png").convert("RGBA")
        im.putpixel((8, 8), (1, 2, 3, 255))
        im.save(d / "badge.lo.png")
        hi = Image.open(d / "badge.hi.png").convert("RGBA")
        hi.putpixel((120, 120), (1, 2, 3, 200))
        hi.save(d / "badge.hi.png")
        problems = check_art.check(d)
        self.assertTrue(any(p.startswith("badge.lo.png") and "outside xterm-240" in p for p in problems), problems)
        self.assertFalse(any(p.startswith("badge.hi.png") for p in problems), problems)

    def test_checker_rejects_a_soft_edge_a_wrong_size_and_a_missing_sheet(self):
        d = self._copy()
        im = Image.open(d / "badge.lo.png").convert("RGBA")
        im.putpixel((8, 8), (0, 255, 0, 128))
        im.save(d / "badge.lo.png")
        Image.open(d / "mascot.lo.png").convert("RGBA").resize((31, 224)).save(d / "mascot.lo.png")
        Image.open(d / "network-eth.hi.png").resize((200, 200)).save(d / "network-eth.hi.png")
        (d / "badge-large.hi.png").unlink()
        problems = check_art.check(d)
        self.assertTrue(any("half-transparent" in p for p in problems))
        self.assertTrue(any("mascot.lo.png: is 31x224" in p for p in problems))
        self.assertTrue(any("network-eth.hi.png: is 200x200, must be 128x128 or 256x256" in p for p in problems))
        self.assertTrue(any("badge-large.hi.png: missing" in p for p in problems))

    def test_checker_takes_a_hi_sheet_at_either_density(self):
        d = self._copy()
        Image.open(d / "network-etc.hi.png").resize((128, 128), Image.LANCZOS).save(d / "network-etc.hi.png")
        self.assertEqual(check_art.check(d), [])

    def test_the_optional_scene_may_be_absent(self):
        d = self._copy()
        (d / "splash-scene.hi.png").unlink()
        (d / "splash-scene.lo.png").unlink()
        self.assertEqual(check_art.check(d), [])


if __name__ == "__main__":
    unittest.main()
