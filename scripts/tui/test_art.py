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
import make_art  # noqa: E402


class ArtTest(unittest.TestCase):
    def test_committed_sheets_are_what_the_script_draws(self):
        for name, pixels in make_art.sheets().items():
            path = make_art.ART_DIR / name
            self.assertTrue(path.exists(), name)
            self.assertTrue(np.array_equal(make_art.load(path), pixels), f"{name} is stale: run make_art.py")
        self.assertEqual((make_art.ART_DIR / "art.conf").read_text(), make_art.manifest())
        self.assertEqual(make_art.PIXEL_HASHES.read_text(), make_art.pixel_hashes())

    def test_committed_sheets_keep_the_contract(self):
        self.assertEqual(check_art.check(make_art.ART_DIR), [])

    def test_every_pose_and_frame_draws_at_every_size(self):
        for pose in make_art.POSES:
            for frame in range(make_art.MASCOT_FRAMES[pose]):
                for size in (64, 32):
                    self.assertTrue(np.any(make_art.mascot(size, pose, frame).a != 0), (pose, frame, size))
            self.assertEqual(make_art.mascot16(pose).a.shape, (16, 16))

    def test_only_sprite_colours_are_drawn(self):
        for name, pixels in make_art.sheets().items():
            used = set(np.unique(pixels).tolist())
            self.assertTrue(used <= {0} | set(range(16, 256)), name)

    def test_frames_of_a_pose_differ(self):
        for pose in make_art.POSES:
            first = make_art.mascot(64, pose, 0).a
            for frame in range(1, make_art.MASCOT_FRAMES[pose]):
                self.assertFalse(np.array_equal(first, make_art.mascot(64, pose, frame).a), (pose, frame))

    def _copy(self) -> Path:
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        for f in make_art.ART_DIR.iterdir():
            shutil.copy(f, d / f.name)
        return d

    def test_checker_rejects_a_colour_outside_the_palette(self):
        d = self._copy()
        im = Image.open(d / "badge.hi.png").convert("RGBA")
        im.putpixel((60, 60), (1, 2, 3, 255))
        im.save(d / "badge.hi.png")
        self.assertTrue(any("outside xterm-240" in p for p in check_art.check(d)))

    def test_checker_rejects_a_soft_edge_a_wrong_size_and_a_missing_sheet(self):
        d = self._copy()
        im = Image.open(d / "badge.lo.png").convert("RGBA")
        im.putpixel((8, 8), (0, 255, 0, 128))
        im.save(d / "badge.lo.png")
        Image.open(d / "mascot.lo.png").convert("RGBA").resize((31, 224)).save(d / "mascot.lo.png")
        (d / "badge-large.hi.png").unlink()
        problems = check_art.check(d)
        self.assertTrue(any("half-transparent" in p for p in problems))
        self.assertTrue(any("mascot.lo.png: is 31x224" in p for p in problems))
        self.assertTrue(any("badge-large.hi.png: missing" in p for p in problems))

    def test_the_optional_scene_may_be_absent(self):
        d = self._copy()
        (d / "splash-scene.hi.png").unlink()
        (d / "splash-scene.lo.png").unlink()
        self.assertEqual(check_art.check(d), [])


if __name__ == "__main__":
    unittest.main()
