#!/usr/bin/env python3
"""Spec 016 move verification (S0f, T008). Run through verify.sh; see that file for usage.

Implements plan.md "Move verification" steps 1-7 and FR-016 (a), (c), (d), per commit of BASE..HEAD:

  1  moved blocks only (git --color-moved=plain) .............. commits with a `# moved:` trailer
  2  identical bodies of the `# moved:` symbols ............... commits with a `# moved:` trailer
  3  test-hunk guard / `# test-files:` trailer ............... refactor/snap-016-* | test/snap-016-* branches
  4  no src/main/resources change, protected paths untouched .. refactor/test snap-016 branches; on fix/*,
                                                                hotfix/* only resources, and pins need `# pins:`
  5  FR-016 (a) impl-class mention, (d) member-level init rules  every commit (module dirs only)
  6  narrowing commit touches signatures only ................ commits that remove the last impl mention
  7  FR-016 (c) counts, and (b) a stub test is added ......... narrowing commits (counts: module commits)
     (a later slice that narrows code moved into an existing module may instead extend that module's stub suite,
     named in an `# extended-tests:` trailer: the narrowing commit may add new cases to it, but not remove or change an
     assertion, add an assertion inside an existing case, or add a skip/scheduling token; see `check_test_hunks`)
  +  `sbt compile` on each move commit (--compile).
  8  storage ratchet (env freeze, 2026-10-11 decision), once on the PR head against storage-baseline.json:
     [env-freeze]   no new storage-typed SnapControllerEnv member; its member count and header only go down / stay
     [storage-refs] storage-reference total over snap/controller and snap/actors only goes down (files may trade)
     [raw-import]   no new raw RocksDB / DataSource / WriteBatch import in those directories
     See `Ratchet` for the exact predicates and the baseline-file rules.

fix/* and hotfix/* branches (not containing `snap-016`) may edit existing module files. They are never moves:
`# moved:`, a narrowing, an added or deleted module file, and (without a `# fix:` trailer) a large net deletion are
`[fix-branch]` errors, and steps 1, 2, 3, 6 are skipped. A fix named like a split slice is kind "other" (fails closed).

Standard library only; needs git and python3, no JVM.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

SNAP = "src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap"
MODULE_DIRS = [f"{SNAP}/controller"] + [f"{SNAP}/actors/{d}" for d in ("account", "storage", "healing", "bytecode")]
RESOURCES = "src/main/resources/"
IMPL_RE = re.compile(r"SNAPSyncControllerImpl|CoordinatorImpl")
def impl_mentions(text: str) -> list[str]:
    """Mentions of the impl classes in a module file. There is no exemption: the impl classes are defined
    outside the module directories, so a stray definition inside one is itself reported by FR-016 (a)."""
    return IMPL_RE.findall(text)
FIX_MAX_NET_DELETIONS = 150
CAPABILITY_TRAITS = ("SnapSharedState", "SnapControllerEnv", "CoordinatorHandles", "PhaseFlags")
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
SCRIPT_DIR = Path(__file__).resolve().parent
CONFIG_DIR = Path(os.environ.get("SNAP_VERIFY_CONFIG_DIR", SCRIPT_DIR))

MODIFIER = r"(?:(?:private|protected)(?:\[\w+\])?|final|override|implicit|inline|transparent|abstract|sealed|open|infix|opaque)"
VIS_RE = re.compile(r"\b(?:private|protected)(?:\[\w+\])?\s+")
TOKEN_RE = re.compile(
    r"\b(?:should|must)\w*|\w*[Aa]ssert\w*|\bassume\w*|\bexpect\w*|\bintercept\w*|\bfishFor\w*|\breceive\w*|"
    r"\bwithin\b|\beventually\b|\bverify\w*|\btaggedAs\b|\bignore\b|\bpending\b|\bcancel\w*|\btimeout\b|"
    r"\binterval\b|===|==|!=|Thread\.sleep"
)


# In a suite named by `# extended-tests:`, added lines may carry positive-assertion tokens (`should*`, `assert*`,
# `expect*`, `==`, ...) in new cases, but never these: skipping, assumption, retagging and scheduling tokens. The one tag
# admitted is `taggedAs UnitTest`, the Tier 1 tag every stub-suite case carries; any other tag (e.g. `SlowTest`,
# `DisabledTest`) would move a case out of Tier 1.
EXT_FORBIDDEN_RE = re.compile(
    r"\bignore\b|\bpending\b|\bcancel\w*|\bassume\w*|\btaggedAs\b(?!\s+UnitTest\b)|Thread\.sleep|\beventually\b|"
    r"\bwithin\b|\btimeout\b|\binterval\b"
)
STUB_SUITE_RE = re.compile(r"new\s+Stub\w*State\b")
CASE_OPENER_RE = re.compile(r"\bin\s*\{\s*$")


def is_module(path: str) -> bool:
    return path.endswith(".scala") and any(path.startswith(d + "/") for d in MODULE_DIRS)


# --------------------------------------------------------------------------- git helpers

class Git:
    def __init__(self, repo: Path):
        self.repo = repo

    def run(self, *args: str, ok=(0,)) -> str:
        p = subprocess.run(
            ["git", "-c", "core.quotepath=off", *args], cwd=self.repo, capture_output=True, text=True, errors="replace"
        )
        if p.returncode not in ok:
            raise RuntimeError(f"git {' '.join(args)} failed: {p.stderr.strip()}")
        return p.stdout

    def show(self, rev: str, path: str):
        p = subprocess.run(["git", "show", f"{rev}:{path}"], cwd=self.repo, capture_output=True, text=True, errors="replace")
        return p.stdout if p.returncode == 0 else None

    def parent(self, c: str) -> str:
        p = subprocess.run(["git", "rev-parse", "--verify", "-q", f"{c}^"], cwd=self.repo, capture_output=True, text=True)
        return p.stdout.strip() if p.returncode == 0 else EMPTY_TREE

    def message(self, c: str) -> str:
        return self.run("log", "-1", "--format=%B", c)

    def changed(self, p: str, c: str) -> dict[str, str]:
        out = {}
        for line in self.run("diff", "--no-renames", "--name-status", p, c).splitlines():
            st, _, path = line.partition("\t")
            out[path] = st[0]
        return out

    def tree_files(self, c: str, dirs) -> list[str]:
        return [f for f in self.run("ls-tree", "-r", "--name-only", c, "--", *dirs).splitlines() if f.endswith(".scala")]

    def diff_numbered(self, p: str, c: str, path: str):
        """-U0 diff of one file as ([(old_line_no, text)], [(new_line_no, text)]), blank lines dropped."""
        removed, added, old, new, in_hunk = [], [], 0, 0, False
        for l in self.run("diff", "--no-renames", "-U0", p, c, "--", path).splitlines():
            m = re.match(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@", l)
            if m:
                old, new, in_hunk = int(m.group(1)), int(m.group(2)), True
            elif in_hunk and l.startswith("-"):
                if l[1:].strip():
                    removed.append((old, l[1:]))
                old += 1
            elif in_hunk and l.startswith("+"):
                if l[1:].strip():
                    added.append((new, l[1:]))
                new += 1
        return removed, added

    def added_hunks(self, p: str, c: str, path: str) -> list[list[str]]:
        """Added (non-blank) lines of one file, grouped by -U0 hunk."""
        out: list[list[str]] = []
        for l in self.run("diff", "--no-renames", "-U0", p, c, "--", path).splitlines():
            if l.startswith("@@"):
                out.append([])
            elif out and l.startswith("+") and not l.startswith("+++") and l[1:].strip():
                out[-1].append(l[1:])
        return [h for h in out if h]

    def is_merge(self, c: str) -> bool:
        return len(self.run("rev-list", "--parents", "-n", "1", c).split()) > 2

    def diff_lines(self, p: str, c: str, path_spec) -> tuple[list[str], list[str]]:
        removed, added = [], []
        for l in self.run("diff", "--no-renames", "-U0", p, c, "--", *path_spec).splitlines():
            if l.startswith("+++") or l.startswith("---"):
                continue
            if l.startswith("-") and l[1:].strip():
                removed.append(l[1:])
            elif l.startswith("+") and l[1:].strip():
                added.append(l[1:])
        return removed, added


def trailer(msg: str, key: str) -> list[str] | None:
    vals = []
    for line in msg.splitlines():
        m = re.match(rf"^#\s*{re.escape(key)}:\s*(.*)$", line.strip())
        if m:
            vals.append(m.group(1))
    return vals or None


def split_list(vals: list[str]) -> list[str]:
    return [t for v in vals for t in re.split(r"[,\s]+", v) if t]


# --------------------------------------------------------------------------- text normalisation

def strip_result_type(text: str) -> str:
    """Drop an explicit `: Type` between the declared name and the first top-level ` =`."""
    depth, colon, in_str = 0, None, False
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            in_str = not in_str
        elif not in_str:
            if ch in "([":
                depth += 1
            elif ch in ")]":
                depth -= 1
            elif depth == 0 and ch == ":" and colon is None and text[i + 1 : i + 2] != ":" and text[i - 1 : i] != ":":
                colon = i
            elif depth == 0 and text[i : i + 2] == " =" and (i + 2 >= n or text[i + 2] in " \n") and text[i - 1 : i] not in "=!<>":
                if colon is not None and colon < i:
                    return text[:colon] + text[i:]
                return text
        i += 1
    return text


def norm_line(l: str) -> str:
    s = VIS_RE.sub("", l.strip())
    return re.sub(r"\s+", " ", strip_result_type(s))


def norm_sig(l: str) -> str:
    s = norm_line(l)
    s = re.sub(r"\blazy\s+", "", s)
    s = re.sub(r"^(override\s+)?val\b", "def", s)
    return s


def indent_of(l: str) -> int:
    return len(l) - len(l.lstrip(" "))


# --------------------------------------------------------------------------- step 2: symbol bodies

def decl_re(name: str):
    return re.compile(
        r"^(\s*)(?:" + MODIFIER + r"\s+|lazy\s+|case\s+)*(?:def|val|var|type|given|trait|class|object|enum)\s+"
        + re.escape(name) + r"(?![\w$])"
    )


def extract_bodies(text: str, name: str) -> list[str]:
    lines = text.splitlines()
    rx, out = decl_re(name), []
    for i, l in enumerate(lines):
        m = rx.match(l)
        if not m:
            continue
        d = len(m.group(1))
        start = i
        while start > 0 and lines[start - 1].strip().startswith("@"):
            start -= 1
        j = i + 1
        while j < len(lines):
            nl = lines[j]
            if not nl.strip() or indent_of(nl) > d:
                j += 1
            elif indent_of(nl) == d and (nl.strip()[0] in ")}]" or nl.strip().startswith("end ")):
                j += 1
            else:
                break
        block = lines[start:j]
        while block and not block[-1].strip():
            block.pop()
        block = [(x[d:] if indent_of(x) >= d else x.lstrip()).rstrip() for x in block]
        k = next(x for x, b in enumerate(block) if decl_re(name).match(b))
        block[k] = VIS_RE.sub("", block[k], count=1)
        out.append(strip_result_type("\n".join(block)))
    return out


# --------------------------------------------------------------------------- (d) member-level rules

TRAIT_RE = re.compile(r"^(?:" + MODIFIER + r"\s+)*trait\s+\w+")
LITERAL_RE = re.compile(
    r"""^(?:-?\d[\d_]*(?:\.\d+)?(?:[eE][-+]?\d+)?[LlFfDd]?|0[xX][0-9a-fA-F_]+[Ll]?|true|false|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)')$"""
)
EMPTY_RE = re.compile(r"^[A-Za-z_][\w.]*\.empty(?:\[.*\])?(?:\(\))?$")
CONST_RE = re.compile(r"^[A-Z]\w*(?:\.[A-Z]\w*)*$")  # Foo.MaxLimit, not Foo.compute


def var_init_ok(init: str) -> bool:
    init = re.sub(r"\s*//.*$", "", init).strip()
    return bool(
        init in ("None", "Nil") or LITERAL_RE.match(init) or EMPTY_RE.match(init) or CONST_RE.match(init)
    )


def top_level_initializer(t: str):
    """Text after the first top-level `=` of a declaration (not `=>`, `==`, `<=`...), or None if there is none."""
    depth, in_str = 0, False
    for i, ch in enumerate(t):
        if ch == '"':
            in_str = not in_str
        elif in_str:
            continue
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and ch == "=" and t[i - 1 : i] == " " and t[i + 1 : i + 2] in ("", " "):
            return t[i + 1 :].strip()
    return None


def member_issue(s: str) -> str | None:
    """Return a problem description for one member-level line, or None if allowed."""
    if s.startswith(("//", "/*", "*", "@", ")", "}", "]", "end ", "extends ", "with ")) or s == "end":
        return None
    if re.match(r"^\w+\s*:\s*\S", s) and not re.match(r"^(def|val|var|type|given|import|lazy)\b", s):
        return None  # self-type
    t = s
    while True:
        m = re.match(r"^" + MODIFIER + r"\s+", t)
        if not m:
            break
        t = t[m.end():]
    if re.match(r"^(def|type|given|import)\b", t) or re.match(r"^lazy\s+val\b", t):
        return None
    if re.match(r"^val\b", t):
        if re.match(r"^val\s+\w+\s*:", t) and top_level_initializer(t) is None:
            return None  # abstract val: no initializer to run, so no init-order hazard; a stable path for imports
        return "concrete non-lazy `val` at member level"
    if re.match(r"^var\b", t):
        if not re.match(r"^var\s+\w+", t):
            return "unparseable `var` declaration"
        init = top_level_initializer(t)
        if init is None:
            return None  # abstract var
        if not var_init_ok(init):
            return f"`var` initializer is not a literal, None/Nil, empty collection or companion constant: {init!r}"
        return None
    return "top-level statement in a module trait"


def member_check(text: str) -> list[tuple[int, str, str]]:
    problems, trait_ind, in_block = [], None, False
    for no, l in enumerate(text.splitlines(), 1):
        s = l.strip()
        if not s:
            continue
        if in_block:
            if "*/" in s:
                in_block = False
            continue
        if s.startswith("/*"):
            in_block = "*/" not in s
            continue
        ind = indent_of(l)
        if TRAIT_RE.match(s):
            trait_ind = ind  # also traits nested in objects
        elif trait_ind is not None and ind <= trait_ind and not re.match(r"^(end\b|import\b|package\b|//|@|[)}\]])", s):
            trait_ind = None
        elif trait_ind is not None and ind == trait_ind + 2:
            why = member_issue(s)
            if why:
                problems.append((no, s, why))
    return problems


# --------------------------------------------------------------------------- counts (c)

MEMBER_RE = re.compile(r"^(?:" + MODIFIER + r"\s+)*(?:lazy\s+)?(?:def|val|var|type|given)\b")


def trait_members(text: str) -> dict[str, int]:
    out, cur = {}, None
    for l in text.splitlines():
        s = l.strip()
        if indent_of(l) == 0 and TRAIT_RE.match(s):
            cur = re.search(r"trait\s+(\w+)", s).group(1)
            out[cur] = 0
        elif indent_of(l) == 0 and s and not s.startswith(("//", "@", "end", "}")):
            cur = None
        elif cur and indent_of(l) == 2 and MEMBER_RE.match(s):
            out[cur] += 1
    return out


def self_type_names(text: str, trait: str) -> list[str]:
    lines, names, on = text.splitlines(), [], False
    for i, l in enumerate(lines):
        if indent_of(l) == 0 and re.search(rf"\btrait\s+{trait}\b", l):
            on = True
            continue
        if on:
            if indent_of(l) == 0 and l.strip() and not l.strip().startswith(("//", "@")):
                break
            if indent_of(l) >= 2 and (l.strip().startswith("self") or names is not None):
                chunk = []
                k = i
                while k < len(lines):
                    chunk.append(lines[k].strip())
                    if lines[k].rstrip().endswith("=>"):
                        break
                    k += 1
                return re.findall(r"\b(\w+)\b", " ".join(chunk).split(":", 1)[-1].rsplit("=>", 1)[0])
    return names


def counts_report(g: Git, c: str) -> list[str]:
    files = g.tree_files(c, MODULE_DIRS)
    texts = {f: g.show(c, f) or "" for f in files}
    members: dict[str, int] = {}
    for t in texts.values():
        members.update(trait_members(t))
    out = []
    for f, t in sorted(texts.items()):
        for tr in trait_members(t):
            if tr.endswith(("State", "Api")) or tr in CAPABILITY_TRAITS:
                continue
            names = self_type_names(t, tr)
            states = [n for n in names if n.endswith("State") and n != "SnapSharedState"]
            apis = [n for n in names if n.endswith("Api")]
            st = sum(members.get(n, 0) for n in states)
            api = sum(members.get(n, 0) for n in apis)
            out.append(
                f"[counts] {tr}: State={'+'.join(states) or '-'}({st}) Api={'+'.join(apis) or '-'}({api})"
            )
    caps = " ".join(f"{n}={members[n]}" for n in CAPABILITY_TRAITS if n in members)
    if caps:
        out.append(f"[counts] capability traits: {caps}")
    return out


# --------------------------------------------------------------------------- the verifier

MEMBER_DECL_RE = re.compile(
    r"^  (?:(?:" + MODIFIER + r")\s+|lazy\s+|case\s+)*(?:def|val|var|type|given|trait|class|object|enum)\s+(\w+)"
)
TYPE_HEADER_RE = re.compile(r"^\s*(?:(?:" + MODIFIER + r")\s+)*(?:case\s+)?(?:class|trait|object)\b")
SELF_TYPE_RE = re.compile(r"^\s*\w+\s*:\s*\S")
DECL_KW_RE = re.compile(r"^\s*(?:(?:" + MODIFIER + r")\s+)*(?:lazy\s+)?(?:def|val|var|type|given|import|case)\b")


def member_names(text: str) -> Counter:
    return Counter(m.group(1) for l in text.splitlines() if (m := MEMBER_DECL_RE.match(l)))


HEADER_SCAN_MAX = 80
EXTENDS_LINE_RE = re.compile(r"^\s*(?:extends|with)\b")


def header_regions(text: str) -> set[int]:
    """1-based line numbers of trait/class/object headers and of a trait's self-type lines."""
    lines, reg = text.splitlines(), set()
    for i, l in enumerate(lines):
        if not TYPE_HEADER_RE.match(l):
            continue
        j, done = i, False
        while j < len(lines) and j < i + 12:
            reg.add(j + 1)
            if lines[j].rstrip().endswith((":", "{", "=")):
                done = True
                break
            j += 1
        # A long constructor (the controller core's runs to ~30 lines) pushes its `extends`/`with` clause past the
        # first 12 lines. Keep scanning to the line that ends the header, but admit only the clause's own lines, so a
        # narrowing commit can add `with <Module>State` to the core while the constructor parameters stay frozen.
        while not done and j < len(lines) and j < i + HEADER_SCAN_MAX:
            if EXTENDS_LINE_RE.match(lines[j]):
                reg.add(j + 1)
            if lines[j].rstrip().endswith((":", "{", "=")):
                done = True
                break
            j += 1
        k = j + 1
        if k < len(lines) and SELF_TYPE_RE.match(lines[k]) and not DECL_KW_RE.match(lines[k]):
            m = k
            while m < len(lines) and m < k + 12:
                reg.add(m + 1)
                if lines[m].rstrip().endswith("=>"):
                    break
                m += 1
    return reg


def declaration_only_regions(text: str) -> set[int]:
    """Lines inside <X>State / <X>Api / capability traits, which hold abstract declarations only."""
    lines, reg, i = text.splitlines(), set(), 0
    while i < len(lines):
        m = re.match(r"^(\s*)(?:(?:" + MODIFIER + r")\s+)*trait\s+(\w+)", lines[i])
        if m and (m.group(2).endswith(("State", "Api")) or m.group(2) in CAPABILITY_TRAITS):
            d, j = len(m.group(1)), i + 1
            while j < len(lines) and (not lines[j].strip() or indent_of(lines[j]) > d):
                reg.add(j + 1)
                j += 1
            i = j
        else:
            i += 1
    return reg


HEADER_EDIT_RE = re.compile(
    r"\b(?:extends|with)\b|^\s*(?:(?:" + MODIFIER + r")\s+)*(?:case\s+)?(?:class|trait|object)\b|^\s*self\s*:|^\s*&|=>\s*$|&\s*$|^\s*\w+\s*:\s*[\w\[\]., &]+$"
)


# A one-line export clause re-exporting moved symbols (plan.md D2): `export a.b.Obj.name` or
# `export a.b.Obj.{n1, n2}`. A wildcard (`*`), a rename or a hiding (`=>`) and a multi-line clause do not match.
# Group 1 (the object path) must also resolve to the object that now holds each moved name: see `destinations`.
EXPORT_RE = re.compile(r"^\s*export\s+([A-Za-z_][\w.]*?)\.(?:(\w+)|\{\s*(\w+(?:\s*,\s*\w+)*)\s*\})\s*$")


def destinations(text: str, name: str) -> list[tuple[str, str]]:
    """(package, enclosing object/trait/class) of each declaration of `name` in a Scala file's text."""
    lines = text.splitlines()
    pm = next((m for l in lines if (m := re.match(r"^package\s+([\w.]+)\s*$", l))), None)
    pkg, rx, out = (pm.group(1) if pm else ""), decl_re(name), []
    for i, l in enumerate(lines):
        m = rx.match(l)
        if not m:
            continue
        d = len(m.group(1))
        for j in range(i - 1, -1, -1):
            h = lines[j]
            if h.strip() and indent_of(h) < d and TYPE_HEADER_RE.match(h):
                t = re.search(r"\b(?:class|trait|object)\s+(\w+)", h)
                if t:
                    out.append((pkg, t.group(1)))
                break
    return out


def export_prefix_ok(prefix: str, dests: list[tuple[str, str]], other_pkgs: set[str]) -> bool:
    """The export's object path names one of `dests`: `Obj`, `rel.pkg.Obj` (a suffix of the package) or the full path."""
    *qual, obj = prefix.split(".")
    pre = ".".join(qual)
    for pkg, o in dests:
        if o != obj:
            continue
        if pre == "":
            if pkg in other_pkgs:  # bare `Obj` only from a file in the same package
                return True
        elif pkg == pre or pkg.endswith("." + pre):
            return True
    return False


def abstract_decl(s: str) -> bool:
    t = s.strip()
    return bool(re.match(r"^(?:(?:" + MODIFIER + r")\s+)*(?:def|var|type)\s+\w", t)) and top_level_initializer(t) is None and not t.endswith(("(", ","))


class Verifier:
    def __init__(self, g: Git, branch: str, compile_cmd: str | None):
        self.g, self.branch, self.compile_cmd = g, branch, compile_cmd
        self.errors: list[str] = []
        self.kind = (
            "refactor" if re.match(r"^refactor/snap-016-", branch)
            else "test" if re.match(r"^test/snap-016-", branch)
            else "fix" if re.match(r"^(?:fix|hotfix)/", branch) and "snap-016" not in branch
            else "other"
        )

    def err(self, c: str, check: str, msg: str):
        self.errors.append(f"FAIL {c[:9]} [{check}] {msg}")
        print(self.errors[-1])

    def info(self, c: str, msg: str):
        print(f"     {c[:9]} {msg}")

    def patterns(self, name: str) -> list[str]:
        f = CONFIG_DIR / name
        if not f.exists():
            return []
        return [l.strip() for l in f.read_text().splitlines() if l.strip() and not l.strip().startswith("#")]

    def exact_paths(self, c: str, check: str, vals, trailer_name: str) -> list[str]:
        out = []
        for t in split_list(vals):
            if t == "none":
                continue
            if re.search(r"[*?\[\]]", t):
                self.err(c, check, f"`# {trailer_name}:` takes exact paths only, got a wildcard: {t}")
            else:
                out.append(t)
        return out

    def commit(self, c: str):
        g = self.g
        if g.is_merge(c):
            if self.kind in ("refactor", "test", "fix"):
                self.err(c, "merge", "merge commit on a spec-016 branch: rebase onto staging instead")
            return
        p = g.parent(c)
        msg = g.message(c)
        changed = g.changed(p, c)
        moved = trailer(msg, "moved")
        is_move = moved is not None
        self.info(c, f"({self.kind} branch{', move commit' if is_move else ''}) {g.run('log', '-1', '--format=%s', c).strip()}")

        # branch-name guard: spec-016 work on a non-conforming branch would silently skip steps 3 and 4
        if self.kind == "other":
            mod_changes = [f for f in changed if is_module(f)]
            marked = is_move or trailer(msg, "test-files") is not None or "#1401" in msg
            snap_tests = [f for f in changed if f.startswith("src/test/") and "/sync/snap/" in f]
            if mod_changes or (marked and snap_tests):
                self.err(
                    c, "branch",
                    f"changes {(mod_changes or snap_tests)[0]} on branch '{self.branch}', which is neither "
                    "refactor/snap-016-* nor test/snap-016-*, so the test-hunk and drift checks would not run",
                )

        # fix branches (fix/*, hotfix/*) may change module files, but a fix is never a move or a narrowing:
        # `# moved:` is rejected outright, and steps 1, 2 and 6 below are skipped for them (CQ-SNAP-016-23).
        if self.kind == "fix":
            if is_move:
                self.err(c, "fix-branch", f"`# moved:` trailer on fix branch '{self.branch}': moves belong on refactor/snap-016-*")
            for f, st in changed.items():
                if is_module(f) and st in ("A", "D"):
                    self.err(c, "fix-branch", f"{'adds' if st == 'A' else 'deletes'} module file {f}: a fix edits existing modules only")
            if trailer(msg, "fix") is None:
                rem, add = g.diff_lines(p, c, [f for f in changed if is_module(f)]) if any(is_module(f) for f in changed) else ([], [])
                if len(rem) - len(add) > FIX_MAX_NET_DELETIONS:
                    self.err(c, "fix-branch", f"net deletion of {len(rem) - len(add)} lines in module files exceeds {FIX_MAX_NET_DELETIONS}: "
                             "split-like change; add a `# fix: #<issue>` trailer if it is a genuine fix")

        # step 5 (a): impl-class mention
        narrowed = False
        for f in g.tree_files(c, MODULE_DIRS):
            text = g.show(c, f) or ""
            now = impl_mentions(text)
            before = impl_mentions((g.show(p, f) or "") if p != EMPTY_TREE else "")
            if before and not now:
                narrowed = True
            if not now:
                continue
            if is_move and self.kind != "fix" and len(now) == 1:
                line = next(l for l in text.splitlines() if IMPL_RE.search(l))
                if re.match(r"^\s*\w+\s*:\s*\w*Impl\s*=>\s*$", line):
                    continue
                self.err(c, "FR-016a", f"{f}: the one allowed mention must be the temporary self-type line, found: {line.strip()}")
            elif is_move and self.kind != "fix":
                self.err(c, "FR-016a", f"{f}: {len(now)} mentions of the impl class; a move commit allows exactly one (the self-type)")
            else:
                self.err(c, "FR-016a", f"{f}: mentions the impl class ({len(now)}x); only a `# moved:` commit may, once per file")

        # step 5 (d)
        for f in g.tree_files(c, MODULE_DIRS):
            for no, s, why in member_check(g.show(c, f) or ""):
                self.err(c, "FR-016d", f"{f}:{no}: {why}: `{s[:90]}`")

        # steps 1-2: move commit
        if is_move and self.kind != "fix":
            self.check_moved_blocks(c, p, changed, split_list(moved))
            self.check_bodies(c, p, changed, split_list(moved))
            if narrowed:
                self.err(c, "step6", "commit both removes the last impl-class mention (narrowing) and carries `# moved:`; split it into two commits")

        # step 6 + (b): every narrowing commit, whatever its trailers
        if narrowed and self.kind == "fix":
            self.err(c, "fix-branch", "fix commit removes the last impl-class mention (a narrowing): narrowing belongs on refactor/snap-016-*")
        if narrowed and self.kind != "fix":
            self.check_signature_only(c, p, changed)
            self.check_stub_test(c, p, changed, msg)
        elif trailer(msg, "extended-tests") is not None:
            self.err(c, "step3", "`# extended-tests:` is allowed only on a narrowing commit (one that removes the last impl-class mention)")
        if any(is_module(f) for f in changed) or narrowed:
            for line in counts_report(g, c):
                self.info(c, line)

        # steps 3-4
        if self.kind in ("refactor", "test", "fix"):
            for f in changed:
                if f.startswith(RESOURCES):
                    self.err(c, "step4", f"changes {f} (no format/config drift: src/main/resources must be untouched)")
        if self.kind == "fix":
            self.check_pins(c, msg, changed)
        if self.kind == "refactor":
            self.check_test_hunks(c, p, changed, msg)
            prot = self.patterns("protected-paths.txt")
            for f in changed:
                if any(fnmatch.fnmatch(f, pat) for pat in prot):
                    self.err(c, "step4", f"touches protected golden/pin file {f}")
        elif self.kind == "test":
            self.check_test_files_trailer(c, msg, changed)

    # -- step 1
    def check_moved_blocks(self, c, p, changed, symbols=()):
        out = self.g.run(
            "-c", "color.diff.oldMoved=magenta", "-c", "color.diff.newMoved=cyan",
            "-c", "color.diff.old=red", "-c", "color.diff.new=green",
            "diff", "--no-renames", "--color=always", "--color-moved=plain",
            "--color-moved-ws=allow-indentation-change", p, c, "--", "src/main/**/*.scala",
        )
        unmoved_rm, unmoved_add = [], []
        for raw in out.splitlines():
            m = re.match(r"^((?:\x1b\[[0-9;]*m)*)([-+])(.*)$", raw)
            if not m or raw.lstrip("\x1b[0123456789;m").startswith(("---", "+++")):
                continue
            codes, sign, body = m.groups()
            body = re.sub(r"\x1b\[[0-9;]*m", "", body)
            if not body.strip():
                continue
            if sign == "-" and "35" not in codes:
                unmoved_rm.append(body)
            elif sign == "+" and "36" not in codes:
                unmoved_add.append(body)
        header_ok = re.compile(
            r"^\s*(?:import|package)\b|^\s*(?:(?:private|protected)(?:\[\w+\])?\s+|final\s+|sealed\s+|abstract\s+)*(?:case\s+)?(?:class|trait|object)\b"
            r"|^\s*(?:extends|with)\b|\b(?:extends|with)\b.*[{:]\s*$"
        )
        added_ok = re.compile(header_ok.pattern + r"|^\s*\w+\s*:\s*\w*Impl\s*=>\s*$|^\s*end\b|^\s*//|^\s*/?\*")
        pool: dict[str, list[str]] = {}
        for a in unmoved_add:
            pool.setdefault(norm_line(a), []).append(a)
        bad = []
        for r in unmoved_rm:
            n = norm_line(r)
            if pool.get(n):
                pool[n].pop()
            elif not header_ok.search(r):
                bad.append(r.strip())
        for b in bad[:10]:
            self.err(c, "step1", f"removed line is not shown as moved: `{b[:100]}`")
        if len(bad) > 10:
            self.err(c, "step1", f"... and {len(bad) - 10} more removed lines not moved")
        extra = []
        dest: dict[str, list[tuple[str, str]]] = {}
        pkgs: dict[str, str] = {}
        for f in changed:
            if f.startswith("src/main/") and f.endswith(".scala"):
                t = self.g.show(c, f)
                if t is not None:
                    pm = next((m for l in t.splitlines() if (m := re.match(r"^package\s+([\w.]+)\s*$", l))), None)
                    pkgs[f] = pm.group(1) if pm else ""
                    found = False
                    for sym in symbols:
                        ds = destinations(t, sym)
                        dest.setdefault(sym, []).extend(ds)
                        found = found or bool(ds)
                    if found:
                        del pkgs[f]  # a file that now holds a moved symbol is a destination, not an exporter
        for a in (a for lines in pool.values() for a in lines if not added_ok.search(a)):
            m = EXPORT_RE.match(a)
            if m is None:
                extra.append(a.strip())
                continue
            # plan.md D2: a moved helper may be re-exported from its old object, so its call sites stay unchanged.
            # Only a one-line export of `# moved:` symbols, by name: no wildcard, no rename, nothing else.
            names = [n.strip() for n in (m.group(2) or m.group(3)).split(",")]
            for n in names:
                if n not in symbols:
                    self.err(c, "step1", f"export of `{n}`, which is not a `# moved:` symbol: `{a.strip()[:100]}`")
                elif not export_prefix_ok(m.group(1), dest.get(n, []), set(pkgs.values())):
                    where = ", ".join(sorted({f"{pk}.{o}" for pk, o in dest.get(n, [])})) or "not found in the commit's touched files"
                    self.err(c, "step1", f"export of `{n}` forwards to `{m.group(1)}`, but the moved `{n}` now lives in: {where}: `{a.strip()[:100]}`")
        for a in extra[:10]:
            self.err(c, "step1", f"added line is neither moved nor a header/import/visibility change: `{a[:100]}`")
        if len(extra) > 10:
            self.err(c, "step1", f"... and {len(extra) - 10} more added lines not moved")

    # -- step 2
    def check_bodies(self, c, p, changed, symbols):
        files = [f for f in changed if f.endswith(".scala") and f.startswith("src/main/")]
        if not symbols:
            self.err(c, "step2", "`# moved:` trailer lists no symbols")
        removed_members: Counter = Counter()
        for f in files:
            old = (self.g.show(p, f) or "") if p != EMPTY_TREE else ""
            removed_members += member_names(old) - member_names(self.g.show(c, f) or "")
        unlisted = sorted(set(removed_members) - set(symbols))
        if unlisted:
            self.err(c, "step2", f"members removed from the parent are not listed in `# moved:` (their bodies are unverified): {', '.join(unlisted[:12])}")
        for sym in symbols:
            before, after = [], []
            for f in files:
                b = self.g.show(p, f) if p != EMPTY_TREE else None
                a = self.g.show(c, f)
                before += extract_bodies(b or "", sym)
                after += extract_bodies(a or "", sym)
            if not before:
                self.err(c, "step2", f"symbol `{sym}` not found in the parent, in the files this commit touches")
            elif not after:
                self.err(c, "step2", f"symbol `{sym}` not found in the commit's touched files (deleted instead of moved?)")
            elif Counter(before) != Counter(after):
                self.err(c, "step2", f"body of `{sym}` differs between parent and commit (after stripping visibility modifiers and result types)")

    # -- step 6
    def check_signature_only(self, c, p, changed):
        rem_left, add_left = [], []
        for f, st in changed.items():
            if not (f.startswith("src/main/") and f.endswith(".scala")):
                continue
            old = (self.g.show(p, f) or "") if p != EMPTY_TREE else ""
            new = self.g.show(c, f) or ""
            rem, add = self.g.diff_numbered(p, c, f)
            for lines, text, out in ((rem, old, rem_left), (add, new, add_left)):
                hdr, decl = header_regions(text), declaration_only_regions(text)
                for no, l in lines:
                    if re.match(r"^\s*import\b", l):
                        continue
                    if no in hdr and HEADER_EDIT_RE.search(l):
                        continue
                    if no in decl and abstract_decl(l):
                        continue
                    out.append(l)
        pool: dict[str, list[str]] = {}
        for a in add_left:
            pool.setdefault(norm_sig(a), []).append(a)
        for r in rem_left:
            n = norm_sig(r)
            if pool.get(n):
                pool[n].pop()
            else:
                self.err(c, "step6", f"narrowing commit changes a non-signature line: -`{r.strip()[:90]}`")
        for lines in pool.values():
            for a in lines:
                self.err(c, "step6", f"narrowing commit adds a non-signature line: +`{a.strip()[:90]}`")

    def extended_tests(self, c, msg) -> list[str]:
        """Existing stub suites a narrowing commit extends (`# extended-tests:`, exact paths): a later slice that moves
        more code into an existing module (e.g. M8c into M8's PivotSelector) adds its cases to that module's suite."""
        vals = trailer(msg, "extended-tests")
        return self.exact_paths(c, "step3", vals, "extended-tests") if vals else []

    # -- (b)
    def check_stub_test(self, c, p, changed, msg=""):
        excluded = re.compile(r"\b(?:SyncTest|IntegrationTest|SlowTest|DisabledTest)\b")
        extended = self.extended_tests(c, msg)
        for f, st in changed.items():
            if not f.startswith("src/test/"):
                continue
            if st == "A" or (st == "M" and f in extended):
                t = self.g.show(c, f) or ""
                if not (re.search(r"new\s+Stub\w*State\b", t) and not excluded.search(t)):
                    continue
                if st == "M" and not any(TOKEN_RE.search(l) for l in self.g.diff_lines(p, c, [f])[1]):
                    continue  # an extended suite counts only if the commit adds at least one assertion to it
                return
        self.err(c, "FR-016b", "narrowing commit adds no new Tier 1 test of the form `new Stub<Module>State with ...` (untagged by SyncTest/IntegrationTest/SlowTest/DisabledTest), and extends no such suite named in `# extended-tests:`")

    # -- step 4, fix branches: protected pins only with an exact `# pins:` trailer
    def check_pins(self, c, msg, changed):
        vals = trailer(msg, "pins")
        listed = self.exact_paths(c, "step4", vals, "pins") if vals else []
        prot = self.patterns("protected-paths.txt")
        for f in changed:
            if any(fnmatch.fnmatch(f, pat) for pat in prot) and f not in listed:
                self.err(c, "step4", f"touches protected golden/pin file {f} without listing it in a `# pins:` trailer")
        for f in listed:
            if f not in changed:
                self.err(c, "step4", f"`# pins:` lists {f}, which this commit does not modify")

    # -- step 3, refactor branches
    def check_test_hunks(self, c, p, changed, msg):
        new_tests = trailer(msg, "new-tests")
        allowed_new = self.exact_paths(c, "step3", new_tests, "new-tests") if new_tests else []
        extended = self.extended_tests(c, msg)
        for f in extended:
            if changed.get(f) != "M":
                self.err(c, "step3", f"`# extended-tests:` names {f}, which this commit does not modify")
            elif not STUB_SUITE_RE.search(self.g.show(c, f) or ""):
                self.err(c, "step3", f"`# extended-tests:` names {f}, which is not a module stub suite (no `new Stub<Module>State`)")
        for f, st in changed.items():
            if not f.startswith("src/test/"):
                continue
            if st == "A":
                if f not in allowed_new:
                    self.err(c, "step3", f"adds test file {f}, which is not listed in a `# new-tests:` trailer (new suites must be named by the slice)")
                continue
            if st == "D":
                self.err(c, "step3", f"deletes existing test file {f}")
                continue
            removed, added = self.g.diff_lines(p, c, [f])
            if f in extended:
                self.check_extended_additions(c, p, f)
            # An extended stub suite may gain assertions in new cases (checked above); removing one is still a change.
            hunks = (("-", removed),) if f in extended else (("-", removed), ("+", added))
            for sign, lines in hunks:
                for l in lines:
                    if re.match(r"^\s*import\b", l):
                        continue
                    if TOKEN_RE.search(l):
                        self.err(c, "step3", f"{f}: {sign} line with an assertion/scheduling token: `{l.strip()[:90]}`")

    def check_extended_additions(self, c, p, f):
        """Added lines of an extended stub suite: no skip/assumption/retagging/scheduling token anywhere, and every added
        assertion inside a case this commit adds (an added `... in {` opener earlier in the same hunk), so an existing
        case cannot gain or change an assertion."""
        for hunk in self.g.added_hunks(p, c, f):
            opened = False
            for l in hunk:
                if re.match(r"^\s*import\b", l):
                    continue
                if EXT_FORBIDDEN_RE.search(l):
                    self.err(c, "step3", f"{f}: + line with a skip/scheduling token, not allowed even in an extended suite: `{l.strip()[:90]}`")
                    continue
                if CASE_OPENER_RE.search(l):
                    opened = True
                elif TOKEN_RE.search(l) and not opened:
                    self.err(c, "step3", f"{f}: + assertion outside a new case (extended suites may only add whole cases): `{l.strip()[:90]}`")

    # -- step 3, test branches
    def check_test_files_trailer(self, c, msg, changed):
        tf = trailer(msg, "test-files")
        if tf is None:
            self.err(c, "step3", "test/snap-016-* commit has no `# test-files:` trailer (list every src/test file it may touch by exact path, or `none`)")
            return
        allowed = self.exact_paths(c, "step3", tf, "test-files")
        # NOTE: test-branch-main-allow.txt is trust-based: it admits the whole seam file, not just the T012 seams.
        main_ok = self.patterns("test-branch-main-allow.txt")
        for f in changed:
            if f.startswith("src/test/") and f not in allowed:
                self.err(c, "step3", f"touches {f}, which is not listed in the `# test-files:` trailer")
            if f.startswith("src/main/") and not any(fnmatch.fnmatch(f, a) for a in main_ok):
                self.err(c, "step3", f"test branch touches src/main file {f} (only the S0b seams in test-branch-main-allow.txt are allowed)")

    # -- final state of the PR head
    def check_final(self, head: str, commits: list[str]):
        marked = False
        for c in commits:
            vals = trailer(self.g.message(c), "partial")
            if vals is None:
                continue
            if c != commits[-1]:
                self.err(c, "final", "`# partial:` is allowed only on the PR's last commit")
            elif not any(v.strip() for v in vals):
                self.err(c, "final", "`# partial:` needs a non-empty reason")
            else:
                marked = True
        if marked:
            return
        for f in self.g.tree_files(head, MODULE_DIRS):
            if impl_mentions(self.g.show(head, f) or ""):
                self.err(head, "final", f"{f} still mentions the impl class at the PR head (move without narrowing). "
                         "Narrow it, or mark the series with a `# partial: <reason>` trailer")

    # -- compile the move commit
    def compile_move(self, c: str):
        repo = self.g.repo
        tmp = Path(tempfile.mkdtemp(prefix="snap-verify-wt-"))
        wt = tmp / "src"
        try:
            # A local `--shared` clone, not `git worktree add`: a linked worktree has a `.git` *file*, which JGit
            # (sbt-git, loaded by the build) rejects with "Bare Repository has neither a working tree, nor an index".
            self.g.run("clone", "-q", "--shared", "--no-checkout", str(repo), str(wt))
            subprocess.run(["git", "checkout", "-q", "--detach", c], cwd=wt, check=True, capture_output=True)
            if (repo / ".gitmodules").exists():
                subprocess.run(["git", "submodule", "update", "--init", "--recursive"], cwd=wt, capture_output=True)
            self.info(c, f"compiling move commit: {self.compile_cmd}")
            r = subprocess.run(self.compile_cmd, shell=True, cwd=wt, stdin=subprocess.DEVNULL)
            if r.returncode != 0:
                self.err(c, "compile", f"`{self.compile_cmd}` failed on the move commit (exit {r.returncode})")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


# --------------------------------------------------------------------------- step 8: storage ratchet (env freeze)
#
# User decision 2026-10-11 ("state capabilities"): a later `StateIngest` capability removes the storage handles from
# SnapControllerEnv. Until then the env must not grow (in members, storage handles or parents), module and coordinator
# code must not take new raw RocksDB/DataSource imports, and the storage-reference count is a metric that only goes
# down. The count is a tripwire, not a proof: it is a regex over identifiers, so it catches the usual ways storage
# reaches a module (types, handles, `.fooStorage` member accesses), not every alias.
#
# The baseline is BASELINE_PATH, read from git at two revisions:
#   - the merge-base of BASE and HEAD is the ceiling (so a PR cannot raise a value and the baseline together);
#   - HEAD's copy must equal the measured values exactly (absent per-file entry = 0), so a decrease is recorded by
#     lowering the baseline in the same PR, and a deleted file drops its entry.
# What may go up, and how:
#   - env member count, pinned storage members, env header (parents/self-type), raw imports: never.
#   - a per-file storage-reference count: freely, as long as the TOTAL over RATCHET_DIRS does not go up (code moving
#     between module files during the split). A higher total needs a `justifications["storage_refs.total"]` line that
#     this PR adds or changes.
# Bootstrap: only when the baseline is absent at the merge-base AND at BASE. Once BASE (staging) has it, a merge-base
# without it is an error (rebase). Present at the merge-base but not at HEAD: error. Absent everywhere: empty baseline.

BASELINE_PATH = "scripts/snap-split/storage-baseline.json"
RATCHET_DIRS = [f"{SNAP}/controller", f"{SNAP}/actors"]  # recursive: subdirectory modules are included
ENV_FILE = f"{SNAP}/controller/SnapControllerEnv.scala"
ENV_TRAIT = "SnapControllerEnv"
TOTAL_KEY = "storage_refs.total"

# The actor and protocol types on staging whose names contain `Storage`/`Store` but are not stores (enumerated
# 2026-10-11 from the class/object/trait definitions in src/main). Nothing else is exempt: a `FooStorageWorker` or an
# `ImmutableStorageActor` is flagged by Rule 1 and counted by STORAGE_REF_RE. `StorageRecoveryActor` (blockchain/sync)
# is deliberately not listed: it does storage recovery, and no module file references it today. Adding a name here is a
# reviewed tooling change.
STORAGE_NAME_ALLOW = frozenset({
    "StorageRangeCoordinator",     # actors/: the storage-ranges phase coordinator (its `.Command` protocol)
    "StorageRangeWorker",          # actors/: its worker
    "StorageRangesResponseMsg",    # StorageRangeCoordinator's worker-message wrapper
    "StorageCoordinatorProgress",  # SNAPSyncController's progress message from that coordinator
    "GetStorageRanges",            # SNAP wire protocol request (network/p2p/messages/SNAP.scala)
    "StorageRanges",               # SNAP wire protocol response
})

# Rule 1 (env freeze): a member is storage-typed when an identifier in its declaration text after the name (its type,
# including inside function types, Option[...] and so on, and any initializer) matches ENV_STORAGE_IDENT_RE and is not
# in STORAGE_NAME_ALLOW.
ENV_STORAGE_IDENT_RE = re.compile(r"Storage|Store|DataSource|(?i:rocksdb)|^Blockchain(?:Reader|Writer)$|WriteBatch")

# The storage-reference count: occurrences of this in code (comments removed by `strip_comments`), per .scala file
# under RATCHET_DIRS. Alternatives, in order: a lower-case `.xStorageY` member access (`.stateStorageOf(...)`,
# `.putSnapSyncStorageComplete`; a capitalised `.StorageRanges` is a message or type selector and does not count unless
# it ends in `Storage`), a capitalised `*Storage*|*Store*` name ending in Coordinator/Worker/Actor (a storage wrapper
# dressed as an actor), an `xStorage` / `xStorages` / `xStorageOpt` identifier or type, `DataSource*` / `dataSource*`, `BlockchainReader|Writer` and the
# lower-case handles, `WriteBatch`, `RocksDb*`, `org.rocksdb`, `.commit()`. Bare `storage` / `Storage` do not count, and
# a match that is a STORAGE_NAME_ALLOW name is dropped.
STORAGE_REF_RE = re.compile(
    r"\.[a-z]\w*Storage\w*"
    r"|\b[A-Z]\w*Stor(?:age|e)\w*(?:Coordinator|Worker|Actor)\b"
    r"|\b[A-Za-z]\w*Storages?(?:Opt)?\b|\b[Dd]ataSource\w*|\b[bB]lockchain(?:Reader|Writer)\b|\bWriteBatch\b|\bRocksDb\w*"
    r"|\borg\.rocksdb\b|\.commit\(\)"
)

# Rule 2 (raw storage imports): an imported name is raw when its path is in `org.rocksdb` or `...db.dataSource`, or the
# imported name contains `WriteBatch`. A fully qualified `org.rocksdb.X` / `db.dataSource.X` in code counts too.
# Domain storages (`db.storage.*`, including `RocksDbBfsQueueStorage`) are not raw: they are counted by STORAGE_REF_RE.
RAW_PKG_RE = re.compile(r"^(?:_root_\.)?org\.rocksdb(?:\.|$)|(?:^|\.)db\.dataSource(?:\.|$)")
RAW_INLINE_RE = re.compile(r"\borg\.rocksdb\.\w+|\bdb\.dataSource\.\w+")
CHAR_LIT_RE = re.compile(r"'(?:\\(?:u[0-9a-fA-F]{4}|.)|[^\\'\n])'")


def strip_comments(text: str) -> str:
    """The text with every comment replaced by spaces (newlines kept), from a small Scala tokenizer: `"..."`, `\"\"\"...\"\"\"`
    and `'c'` literals (an interpolated string's `${...}` is code), `//` line comments and nested `/* */` blocks. String
    contents are kept: a storage name in a literal still counts (fails closed)."""
    out, i, n = [], 0, len(text)
    stack = []  # modes inside the top-level code: "s" string, "t" triple string, "i" interpolation (brace depth)
    depth_stack = []

    def emit_blank(s: str):
        out.append("".join("\n" if ch == "\n" else " " for ch in s))

    while i < n:
        mode = stack[-1] if stack else "c"
        ch = text[i]
        if mode in ("c", "i"):
            if text.startswith("//", i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                emit_blank(text[i:j])
                i = j
                continue
            if text.startswith("/*", i):
                depth, j = 1, i + 2
                while j < n and depth:
                    if text.startswith("/*", j):
                        depth, j = depth + 1, j + 2
                    elif text.startswith("*/", j):
                        depth, j = depth - 1, j + 2
                    else:
                        j += 1
                emit_blank(text[i:j])
                i = j
                continue
            if text.startswith('"""', i):
                interp = i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_")
                stack.append(("t", interp))
                out.append('"""')
                i += 3
                continue
            if ch == '"':
                interp = i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_")
                stack.append(("s", interp))
                out.append(ch)
                i += 1
                continue
            if ch == "'":
                m = CHAR_LIT_RE.match(text, i)
                if m:
                    out.append(m.group(0))
                    i = m.end()
                    continue
            if mode == "i":
                if ch == "{":
                    depth_stack[-1] += 1
                elif ch == "}":
                    if depth_stack[-1] == 0:
                        stack.pop()
                        depth_stack.pop()
                        out.append(ch)
                        i += 1
                        continue
                    depth_stack[-1] -= 1
            out.append(ch)
            i += 1
            continue
        kind, interp = mode
        if interp and text.startswith("${", i):
            stack.append("i")
            depth_stack.append(0)
            out.append("${")
            i += 2
            continue
        if kind == "t":
            if text.startswith('"""', i):
                j = i + 3
                while j < n and text[j] == '"':  # `""""` ends with the last three quotes
                    j += 1
                out.append(text[i:j])
                stack.pop()
                i = j
                continue
        else:
            if ch == "\\":
                out.append(text[i : i + 2])
                i += 2
                continue
            if ch == '"' or ch == "\n":
                stack.pop()
        out.append(ch)
        i += 1
    return "".join(out)


def code_lines(text: str) -> list[str]:
    """The file's lines with comments blanked (see `strip_comments`)."""
    return strip_comments(text).split("\n")


def storage_ref_count(text: str) -> int:
    return sum(1 for m in STORAGE_REF_RE.findall(strip_comments(text)) if m.lstrip(".") not in STORAGE_NAME_ALLOW)


def split_top_commas(s: str) -> list[str]:
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    return [x.strip() for x in out + [cur] if x.strip()]


def expand_import(clause: str) -> list[str]:
    """`a.b.{C, D => E, *}` -> [a.b.C, a.b.D, a.b.*]; `a.b.C as D` -> [a.b.C]."""
    m = re.match(r"^([\w.]+?)\.\{(.*)\}$", clause)
    if m:
        names = [re.split(r"\s*=>\s*|\s+as\s+", n.strip())[0] for n in m.group(2).split(",") if n.strip()]
        return [f"{m.group(1)}.{n}" for n in names]
    return [re.split(r"\s+as\s+", clause)[0].strip()]


def raw_imports(text: str) -> list[str]:
    """Rule 2 entries of a file: raw imported names, and `inline <fqn>` for fully qualified raw references in code."""
    out, buf = [], None
    for l in code_lines(text):
        s = l.strip()
        if buf is not None:
            buf += " " + s
        elif re.match(r"^import\s", s):
            buf = s[len("import"):].strip()
        else:
            out += [f"inline {m}" for m in RAW_INLINE_RE.findall(l)]
            continue
        if buf.count("{") > buf.count("}"):
            continue
        for clause in split_top_commas(buf):
            for e in expand_import(clause):
                if RAW_PKG_RE.search(e) or "WriteBatch" in e.rsplit(".", 1)[-1]:
                    out.append(e)
        buf = None
    return out


def env_trait_re(trait: str):
    return re.compile(r"^(?:(?:" + MODIFIER + r")\s+)*trait\s+" + re.escape(trait) + r"\b(.*)$")


def env_header(text: str, trait: str = ENV_TRAIT) -> str | None:
    """The env trait's header after its name (type parameters, `extends`/`with` parents, up to the `:`/`{`), plus its
    self-type line if any, whitespace-normalised. None if the trait is absent."""
    lines = code_lines(text)
    for i, l in enumerate(lines):
        m = env_trait_re(trait).match(l.strip()) if indent_of(l) == 0 else None
        if not m:
            continue
        parts, j = [m.group(1).strip()], i
        while not parts[-1].rstrip().endswith((":", "{")) and j + 1 < len(lines) and j < i + HEADER_SCAN_MAX:
            j += 1
            parts.append(lines[j].strip())
        k = j + 1
        while k < len(lines) and not lines[k].strip():
            k += 1
        if k < len(lines) and SELF_TYPE_RE.match(lines[k]) and not DECL_KW_RE.match(lines[k]):
            while k < len(lines):
                parts.append(lines[k].strip())
                if lines[k].rstrip().endswith("=>"):
                    break
                k += 1
        return re.sub(r"\s+", " ", " ".join(p for p in parts if p)).strip()
    return None


def env_members(text: str, trait: str = ENV_TRAIT) -> dict[str, str]:
    """name -> declaration text after the name, for each member of `trait` (continuation lines joined)."""
    out, on, cur = {}, False, None
    for l in code_lines(text):
        if not l.strip():
            continue
        ind = indent_of(l)
        if ind == 0:
            if env_trait_re(trait).match(l.strip()):
                on, cur = True, None
            elif on and not l.strip().startswith(("end", ")", "}", "@")):
                on, cur = False, None
            continue
        if not on:
            continue
        m = MEMBER_DECL_RE.match(l)
        if ind == 2 and m:
            cur = m.group(1)
            out[cur] = l[m.end():].strip()
        elif ind > 2 and cur:
            out[cur] += " " + l.strip()
        else:
            cur = None
    return out


def storage_typed(decl: str) -> bool:
    return any(ENV_STORAGE_IDENT_RE.search(t) and t not in STORAGE_NAME_ALLOW for t in re.findall(r"\w+", decl))


def load_baseline(text: str | None) -> dict | None:
    if text is None:
        return None
    b = json.loads(text)
    b.setdefault("env", {})
    b["env"].setdefault("members", 0)
    b["env"].setdefault("header", "")
    b["env"].setdefault("storage_members", [])
    for k in ("storage_refs", "raw_imports", "justifications"):
        b.setdefault(k, {})
    b.setdefault("storage_refs_total", sum(int(x) for x in b["storage_refs"].values()))
    return b


class Ratchet:
    """Step 8. `old` is the ceiling (merge-base copy), `new` the PR head's copy, which must match the measurement."""

    def __init__(self, v: "Verifier", base: str, head: str):
        self.v, self.g, self.head = v, v.g, head
        self.old = self.new = None
        self.ok = self.load(base)

    def err(self, check: str, msg: str):
        self.v.err(self.head, check, msg)

    def info(self, msg: str):
        self.v.info(self.head, f"[storage] {msg}")

    def load(self, base: str) -> bool:
        g = self.g
        try:
            mb = g.run("merge-base", base, self.head).strip()
        except RuntimeError:
            mb = base
        try:
            old = load_baseline(g.show(mb, BASELINE_PATH))
            new = load_baseline(g.show(self.head, BASELINE_PATH))
            at_base = g.show(base, BASELINE_PATH) is not None
        except ValueError as e:
            self.err("storage-refs", f"{BASELINE_PATH} is not valid JSON: {e}")
            return False
        if old is not None and new is None:
            self.err("storage-refs", f"{BASELINE_PATH} exists at the merge-base but not at the PR head: the ratchet cannot be deleted")
            return False
        if old is None and at_base:
            self.err("storage-refs", f"{BASELINE_PATH} exists at {base} but not at the merge-base {mb[:9]}: rebase onto {base} "
                                     "(the ratchet bootstraps only once)")
            return False
        if new is None:
            new = load_baseline("{}")
        if old is None:
            old = new
            self.info(f"{BASELINE_PATH} is new in this range and absent at {base}: bootstrap, its values are the ceiling")
        self.old, self.new = old, new
        return True

    def fresh_justification(self, key: str) -> bool:
        j = str(self.new["justifications"].get(key, "")).strip()
        return bool(j) and j != str(self.old["justifications"].get(key, "")).strip()

    def exact(self, check: str, label: str, actual: int, recorded: int, what: str):
        if actual != recorded:
            self.err(check, f"{label}: measured {actual}, but {BASELINE_PATH} records {recorded}: set {what} to {actual} in this PR"
                     + (" (the ratchet only goes down: a decrease is kept by lowering the baseline)" if actual < recorded else ""))

    # Rule 1
    def check_env(self):
        text = self.g.show(self.head, ENV_FILE)
        members = env_members(text or "")
        typed = {n: d for n, d in members.items() if storage_typed(d)}
        header = env_header(text or "") or ""
        old_env, new_env = self.old["env"], self.new["env"]
        pinned_old, pinned_new = set(old_env["storage_members"]), set(new_env["storage_members"])
        self.info(f"{ENV_TRAIT}: members={len(members)} (baseline {new_env['members']}), storage-typed={len(typed)} "
                  f"(pinned {len(pinned_old)}), header={header!r}")
        if len(members) > int(old_env["members"]):
            self.err("env-freeze", f"{ENV_TRAIT} has {len(members)} members, above the ceiling {old_env['members']}: "
                                   "the env only shrinks until StateIngest (2026-10-11 decision); no justification escape")
        if int(new_env["members"]) > int(old_env["members"]):
            self.err("env-freeze", f"{BASELINE_PATH} raises env.members from {old_env['members']} to {new_env['members']}: it may only go down")
        self.exact("env-freeze", f"{ENV_TRAIT} member count", len(members), int(new_env["members"]), "env.members")
        if header != old_env["header"] or header != new_env["header"]:
            self.err("env-freeze", f"{ENV_TRAIT} header is {header!r}; pinned {old_env['header']!r}: its parents and self-type "
                                   "may not change (a parent would add members past this check)")
        for n in sorted(set(typed) - pinned_old):
            self.err("env-freeze", f"{ENV_TRAIT} gains storage-typed member `{n}{typed[n][:80]}`: the env is frozen until "
                                   "StateIngest removes its storage handles (2026-10-11 decision)")
        for n in sorted(pinned_new - pinned_old):
            self.err("env-freeze", f"{BASELINE_PATH} adds `{n}` to env.storage_members: the pinned list may only shrink")
        for n in sorted(pinned_new - set(typed)):
            self.info(f"WARN pinned env member `{n}` is no longer storage-typed: drop it from env.storage_members")

    # per-file counts, move-aware
    def check_refs(self, files: dict[str, str]):
        counts = {rel: storage_ref_count(t) for rel, t in files.items()}
        total = sum(counts.values())
        old_total, new_total = int(self.old["storage_refs_total"]), int(self.new["storage_refs_total"])
        keys = sorted(set(k for k, n in counts.items() if n) | set(self.new["storage_refs"]) | set(self.old["storage_refs"]))
        for k in sorted(keys, key=lambda k: (-counts.get(k, 0), k)):
            ob = int(self.old["storage_refs"].get(k, 0))
            moved = f", ceiling {ob}" if counts.get(k, 0) > ob else ""
            self.info(f"{k}: {counts.get(k, 0)} (baseline {self.new['storage_refs'].get(k, 0)}{moved})")
        self.info(f"total: {total} (baseline {new_total}, ceiling {old_total})")
        for k in keys:
            self.exact("storage-refs", k, counts.get(k, 0), int(self.new["storage_refs"].get(k, 0)),
                       f"storage_refs[\"{k}\"]" + (" (remove the entry)" if counts.get(k, 0) == 0 else ""))
        self.exact("storage-refs", "total", total, new_total, "storage_refs_total")
        if total > old_total and not self.fresh_justification(TOTAL_KEY):
            self.err("storage-refs", f"total storage references {total}, above the ceiling {old_total}: per-file moves are free, but "
                                     f"a higher total needs a new `justifications[\"{TOTAL_KEY}\"]` line in {BASELINE_PATH}")

    # Rule 2
    def check_raw(self, files: dict[str, str]):
        raws = {rel: r for rel, t in files.items() if (r := raw_imports(t))}
        for k in sorted(set(raws) | set(self.new["raw_imports"])):
            allowed, listed = set(self.old["raw_imports"].get(k, [])), set(self.new["raw_imports"].get(k, []))
            for e in raws.get(k, []):
                if e not in allowed:
                    self.err("raw-import", f"{k}: raw storage import `{e}`; module and coordinator code goes through "
                                           "domain storages / capabilities, not RocksDB or DataSource types")
            for e in sorted(listed - allowed):
                self.err("raw-import", f"{BASELINE_PATH} adds `{e}` to raw_imports[{k}]: the list may only shrink")
            for e in sorted(listed - set(raws.get(k, []))):
                self.info(f"WARN {k} no longer has raw import `{e}`: drop it from raw_imports")


def check_storage_ratchet(v: "Verifier", base: str, head: str):
    r = Ratchet(v, base, head)
    if not r.ok:
        return
    files = {f[len(SNAP) + 1:]: v.g.show(head, f) or "" for f in v.g.tree_files(head, RATCHET_DIRS)}
    r.check_env()
    r.check_refs(files)
    r.check_raw(files)



def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("head", nargs="?", default="HEAD")
    ap.add_argument("--branch", default=os.environ.get("SNAP_VERIFY_BRANCH"))
    ap.add_argument("--compile", action="store_true", help="run --compile-cmd on every move commit")
    ap.add_argument("--compile-cmd", default="sbt compile")
    ap.add_argument("--list-move-commits", action="store_true")
    a = ap.parse_args(argv)
    g = Git(Path.cwd())
    toplevel = Path(g.run("rev-parse", "--show-toplevel").strip())
    g = Git(toplevel)
    branch = a.branch or g.run("rev-parse", "--abbrev-ref", "HEAD").strip()
    commits = g.run("rev-list", "--reverse", f"{a.base}..{a.head}").split()
    if a.list_move_commits:
        for c in commits:
            if not g.is_merge(c) and trailer(g.message(c), "moved") is not None:
                print(c)
        return 0
    v = Verifier(g, branch, a.compile_cmd if a.compile else None)
    print(f"snap-split verify: {len(commits)} commit(s) in {a.base}..{a.head}, branch '{branch}' ({v.kind})")
    for c in commits:
        v.commit(c)
        if v.compile_cmd and not g.is_merge(c) and trailer(g.message(c), "moved") is not None:
            v.compile_move(c)
    if commits:
        v.check_final(commits[-1], commits)
        check_storage_ratchet(v, a.base, commits[-1])
    if v.errors:
        print(f"\nsnap-split verify: {len(v.errors)} failure(s)")
        return 1
    print("\nsnap-split verify: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
