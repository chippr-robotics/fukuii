#!/usr/bin/env python3
"""Fetch part of an execution-specs fixture release without downloading all of it.

Fixture releases are single ~1 GB `fixtures.tar.gz` assets whose members are stored in sorted
order, so the members under a directory are contiguous. This streams the archive, extracts only
the members under the given prefixes, and stops reading at the first member that sorts past the
last prefix. The Amsterdam blockchain tests end about a quarter of the way into tests@v21.0.0.

    scripts/eest/fetch_fixtures.py DEST [--release tests@v21.0.0] [--prefix fixtures/blockchain_tests/for_amsterdam/ ...]

Without --prefix it fetches the Amsterdam-fork blockchain tests, the BPO2->Amsterdam transition
tests, and the release index. DEST receives the archive's own paths (DEST/fixtures/...).
"""
import argparse
import sys
import tarfile
import time
import urllib.request

DEFAULT_RELEASE = "tests@v21.0.0"
DEFAULT_PREFIXES = [
    "fixtures/.meta/",
    "fixtures/blockchain_tests/for_amsterdam/",
    "fixtures/blockchain_tests/for_bpo2toamsterdamattime15k/",
]


class CountingReader:
    def __init__(self, raw):
        self.raw, self.count = raw, 0

    def read(self, size=-1):
        chunk = self.raw.read(size)
        self.count += len(chunk)
        return chunk


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dest")
    parser.add_argument("--release", default=DEFAULT_RELEASE)
    parser.add_argument("--asset", default="fixtures.tar.gz")
    parser.add_argument("--prefix", action="append", dest="prefixes")
    args = parser.parse_args()
    prefixes = sorted(args.prefixes or DEFAULT_PREFIXES)
    url = f"https://github.com/ethereum/execution-specs/releases/download/{args.release}/{args.asset}"

    started = time.time()
    response = urllib.request.urlopen(url)
    reader = CountingReader(response)
    extracted, seen = 0, set()
    with tarfile.open(fileobj=reader, mode="r|gz") as archive:
        for member in archive:
            matched = next((p for p in prefixes if member.name.startswith(p)), None)
            if matched is not None:
                seen.add(matched)
                if member.isfile():
                    archive.extract(member, args.dest, filter="data")
                    extracted += 1
            elif member.name > prefixes[-1]:
                break
    response.close()

    missing = [p for p in prefixes if p not in seen]
    print(
        f"{args.release}: extracted {extracted} files, read {reader.count / 1e6:.1f} MB "
        f"of the archive in {time.time() - started:.0f}s"
    )
    if missing:
        # A prefix with no members is a wrong path or a release without that fork, never an empty test set.
        print("no members under: " + ", ".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
