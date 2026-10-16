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
     [storage-refs] storage-reference total over snap/controller, snap/actors and the controller core
                    (SNAPSyncController.scala, the split's move source) only goes down (files may trade)
     [raw-import]   no new raw RocksDB / DataSource / WriteBatch import in those files
     [capability]   each capability trait (T054b) has a `capabilities` row: its file, member count (down only, growth needs
                    a justification) and raw imports (fixed at introduction, down only); its file is not a storage_refs row,
                    and `<accessor>.<call>` is not a storage reference elsewhere
     See `Ratchet` for the exact predicates and the baseline-file rules.

Capability mode (T054b, see "capability mode" below): a commit with `# capability: <Trait>` on refactor/snap-016-* introduces
a capability trait and moves call sites onto it. Steps 1, 2 and 6 are replaced by a token-level substitution check (every
change in a snap/ src/main file replaces a storage handle with the matching capability member, per the map declared in
the capability file, whose forwarders must be pure); every other check still applies. A PR with the trailer on one commit
needs it on every commit that touches snap/ code, and may contain no move or narrowing.

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
# T060b (characterization G9): a per-coordinator env trait (`ByteCodeEnv`, `AccountRangeCoordinatorEnv`, ...) is a
# capability-like trait: declaration-only for step 6 and reported on the `[counts] capability traits` line, not as a
# module.
COORD_ENV_RE = re.compile(r"^(?:ByteCode|AccountRange|StorageRange|TrieNodeHealing)(?:Coordinator)?Env$")
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


def counts_report(g: Git, c: str, extra_caps=()) -> list[str]:
    files = g.tree_files(c, MODULE_DIRS)
    texts = {f: g.show(c, f) or "" for f in files}
    members: dict[str, int] = {}
    for t in texts.values():
        members.update(trait_members(t))
    envs = tuple(sorted(n for n in members if COORD_ENV_RE.match(n)))
    caps_all = CAPABILITY_TRAITS + tuple(n for n in sorted(extra_caps) + list(envs) if n not in CAPABILITY_TRAITS)
    out = []
    for f, t in sorted(texts.items()):
        for tr in trait_members(t):
            if tr.endswith(("State", "Api")) or tr in caps_all:
                continue
            names = self_type_names(t, tr)
            states = [n for n in names if n.endswith("State") and n != "SnapSharedState"]
            apis = [n for n in names if n.endswith("Api")]
            st = sum(members.get(n, 0) for n in states)
            api = sum(members.get(n, 0) for n in apis)
            out.append(
                f"[counts] {tr}: State={'+'.join(states) or '-'}({st}) Api={'+'.join(apis) or '-'}({api})"
            )
    caps = " ".join(f"{n}={members[n]}" for n in caps_all if n in members)
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
        if m and (m.group(2).endswith(("State", "Api")) or m.group(2) in CAPABILITY_TRAITS or COORD_ENV_RE.match(m.group(2))):
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


def header_parents_ok(l: str, ident_args: bool = False) -> bool:
    """T060b (prism): a header line's `extends`/`with` clause names parents only (identifiers, dotted paths, type
    arguments), never a constructor call such as `with Audit(sideEffect())`, which would run code. Used by the adoption
    check only: steps 1 and 6 keep admitting parent arguments, because a merged range relies on a trait parameter
    (M9, #1560: `with SyncLifecycle(heapWatchdogStart)`), and the replay of merged ranges must stay byte-identical."""
    code = strip_comments(l).strip()
    if not code or re.match(r"^(?:import|package)\b|^\*", code):
        return True
    m = re.search(r"\b(?:extends|with)\b", code)
    if not m:
        return True
    args = r"(?:\(\s*\w+(?:\s*,\s*\w+)*\s*\))?" if ident_args else ""
    return bool(re.fullmatch(r"(?:(?:extends|with)\s+[\w.]+(?:\[[\w., \[\]]*\])?" + args + r"\s*)+[:{]?\s*", code[m.start():]))


def abstract_decl(s: str) -> bool:
    t = s.strip()
    return bool(re.match(r"^(?:(?:" + MODIFIER + r")\s+)*(?:def|var|type)\s+\w", t)) and top_level_initializer(t) is None and not t.endswith(("(", ","))


class Verifier:
    def __init__(self, g: Git, branch: str, compile_cmd: str | None):
        self.g, self.branch, self.compile_cmd = g, branch, compile_cmd
        self.errors: list[str] = []
        self.cap_names: set[str] = set()  # capability traits named by `# capability:` trailers in the range (T054b)
        self.pinned: set[str] = set()     # SnapControllerEnv storage members pinned at the merge-base (T054b map roots)
        self.base_caps: dict[str, str] = {}  # capability -> its file's text at the merge-base (T060b extension)
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

    def prepare(self, base: str, commits: list[str]):
        """PR-level facts for the capability mode (T054b): the pinned env storage handles at the merge-base, and the
        capability named by the range's `# capability:` trailers (at most one, on refactor/snap-016-* only)."""
        g = self.g
        try:
            mb = g.run("merge-base", base, commits[-1]).strip() if commits else base
            b = load_baseline(g.show(mb, BASELINE_PATH))
        except (RuntimeError, ValueError):
            b = None
        self.pinned = set(b["env"]["storage_members"]) if b else set()
        # T060b: a capability already in the merge-base baseline is extended, not introduced (D-T060-1)
        self.base_caps = {}
        for name, e in ((b or {}).get("capabilities") or {}).items():
            self.base_caps[name] = g.show(mb, f"{SNAP}/{e.get('file', '')}") or ""
        for c in commits:
            for v in trailer(g.message(c), "capability") or []:
                name = v.strip()
                if not IDENT_RE.match(name) or not name[:1].isupper():
                    self.err(c, "capability", f"`# capability:` takes one trait name (e.g. `StateIngest`), got `{v}`")
                    continue
                self.cap_names.add(name)
        if len(self.cap_names) > 1:
            self.err(commits[-1], "capability", f"one capability per PR, the trailers name {', '.join(sorted(self.cap_names))}")

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
        adopts = trailer(msg, "adopts")
        if narrowed and self.kind == "fix":
            self.err(c, "fix-branch", "fix commit removes the last impl-class mention (a narrowing): narrowing belongs on refactor/snap-016-*")
        if narrowed and self.kind != "fix":
            if adopts is None:
                self.check_signature_only(c, p, changed)
            self.check_stub_test(c, p, changed, msg)
        elif trailer(msg, "extended-tests") is not None:
            self.err(c, "step3", "`# extended-tests:` is allowed only on a narrowing commit (one that removes the last impl-class mention)")
        # adoption mode (T060b): replaces step 6 on an adopting narrowing commit, and checks a standalone adoption
        if adopts is not None:
            self.check_adoption_commit(c, p, changed, msg, adopts, is_move, narrowed)
        else:
            adoption_guard(self, c, p, changed)
        # capability mode (T054b): replaces steps 1, 2 and 6 for a capability commit
        cap = trailer(msg, "capability")
        if cap is not None:
            self.check_capability_commit(c, p, changed, cap[0].strip(), is_move, narrowed)
        elif self.cap_names:
            for f in changed:
                if f.startswith(f"{SNAP}/") and f.endswith(".scala"):
                    self.err(c, "capability", f"changes {f} in a capability PR without a `# capability:` trailer: every commit "
                                              "of a capability PR that touches snap/ code is substitution-checked")
                    break
        if any(is_module(f) for f in changed) or narrowed:
            for line in counts_report(g, c, self.cap_names | set(self.base_caps)):
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

    # -- adoption mode (T060b)
    def check_adoption_commit(self, c, p, changed, msg, vals, is_move, narrowed):
        if self.kind != "refactor":
            self.err(c, "adopts", f"`# adopts:` on branch '{self.branch}': an adoption is a refactor/snap-016-* commit")
            return
        if len(vals) != 1:
            self.err(c, "adopts", "one `# adopts:` trailer per commit")
            return
        parts = vals[0].split()
        units = sorted(ADOPT_COORDINATORS) + [RECOVERY_UNIT]
        if len(parts) != 2 or parts[0] != ADOPT_CAP or parts[1] not in units:
            self.err(c, "adopts", f"`# adopts:` takes `{ADOPT_CAP} <Unit>`, Unit one of {', '.join(units)}; got `{vals[0].strip()}`")
            return
        if is_move:
            self.err(c, "adopts", "`# adopts:` on a `# moved:` commit: adopt in the narrowing commit or in a commit of its own")
            return
        if trailer(msg, "capability") is not None:
            self.err(c, "adopts", "`# adopts:` and `# capability:` on one commit: an adoption is not a capability change")
            return
        if narrowed and parts[1] == RECOVERY_UNIT:
            self.err(c, "adopts", f"`# adopts: {ADOPT_CAP} {RECOVERY_UNIT}` on a narrowing commit: the recovery wiring is a commit of its own")
            return
        self.info(c, f"[adopts] {ADOPT_CAP} {parts[1]} ({'narrowing commit: step 6 runs inside the adoption check' if narrowed else 'standalone commit'})")
        Adoption(self, c, p, changed, parts[1], narrowed).run()

    # -- capability mode (T054b)
    def check_capability_commit(self, c, p, changed, name, is_move, narrowed):
        g = self.g
        if self.kind != "refactor":
            self.err(c, "capability", f"`# capability:` on branch '{self.branch}': a capability PR is a refactor/snap-016-* branch")
            return
        if is_move:
            self.err(c, "capability", "`# capability:` and `# moved:` on one commit: a capability commit is not a move")
        if narrowed:
            self.err(c, "capability", "capability commit removes the last impl-class mention (a narrowing): narrow in its own PR")
        if not IDENT_RE.match(name) or name not in self.cap_names:
            return  # reported by prepare()
        paths = find_capability_file(g, c, name)
        if len(paths) != 1:
            self.err(c, "capability", f"`trait {name}` must be declared at the top level of exactly one file under "
                                      f"snap/controller or snap/actors at this commit, found {len(paths) or 'none'}{': ' + ', '.join(paths) if paths else ''}")
            return
        base_spec = None
        if name in self.base_caps:  # T060b: extending a capability that exists at the merge-base (D-T060-1)
            base_text = self.base_caps[name]
            base_spec = CapabilitySpec(name, paths[0], base_text, None)
        grand = {(m.group(1), m.group(2)) for l in (base_spec.text.splitlines() if base_spec else []) if (m := CAP_MAP_RE.match(l))}
        spec = CapabilitySpec(name, paths[0], g.show(c, paths[0]) or "", self.pinned, grand)
        for prob in spec.problems:
            self.err(c, "capability", prob)
        self.info(c, f"[capability] {name} in {paths[0]}: accessor `{spec.accessor}`, {len(spec.maps)} map entries, "
                     f"{len(spec.members)} members" + (" (extension)" if base_spec else ""))
        lazy = {}
        if base_spec:
            self.capability_extension(c, spec, base_spec)
            lazy, lprobs = lazy_handles(g, c, spec)  # #1584 rule (a)
            for prob in lprobs:
                self.err(c, "capability", prob)
            for h, info in sorted(lazy.items()):
                self.info(c, f"[capability] lazy healing handle `{h}` ({info['file']}) feeds by-name `{info['param']}` -> "
                             f"`{spec.accessor}.{info['member']}`")
            sc_text = g.show(c, SYNC_CONTROLLER) or ""
            if changed.get(SYNC_CONTROLLER) != "M" and accessor_init(sc_text):
                # SyncController's own capability (D-T060-2) must keep mirroring the core's accessor
                probs = mirror_accessor(SYNC_CONTROLLER, sc_text, sc_text, g.show(c, RATCHET_FILES[0]) or "", spec, lazy)[0]
                for prob in probs:
                    self.err(c, "capability", prob)
        for f, st in sorted(changed.items()):
            if not f.endswith(".scala") or not f.startswith("src/main/"):
                continue
            if base_spec and f == SYNC_CONTROLLER and st == "M":
                old, new = g.show(p, f) or "", g.show(c, f) or ""
                if accessor_init(new) or accessor_init(old):
                    probs, do, dn, _eq = mirror_accessor(f, old, new, g.show(c, RATCHET_FILES[0]) or "", spec, lazy)
                    for prob in probs:
                        self.err(c, "capability", prob)
                    probs, subs = substitution_check(f, old, new, None, do, dn)
                    for prob in probs:
                        self.err(c, "capability", prob)
                    if not probs:
                        self.info(c, f"[capability] {f}: mirrors the core's `{spec.accessor}`")
                    continue
            if not f.startswith(f"{SNAP}/"):
                self.err(c, "capability", f"changes {f}, outside snap/: a capability commit changes no storage or other code")
                continue
            if f == spec.path:
                continue  # the capability itself: checked through CapabilitySpec
            if st != "M":
                self.err(c, "capability", f"{'adds' if st == 'A' else 'deletes'} {f}: a capability commit adds only the capability file")
                continue
            old, new = g.show(p, f) or "", g.show(c, f) or ""
            drop_old, drop_new = set(), set()
            if f == ENV_FILE:
                drop_old, drop_new = self.capability_env(c, spec, old, new)
            elif f in RATCHET_FILES:
                drop_old, drop_new = self.capability_core(c, f, spec, old, new, base_spec)
            elif is_module(f):
                drop_old = self.removed_state_handles(c, f, old, new)
            extra = []
            if lazy:  # #1584 rule (b)
                lp, lo, ln, extra, notes = lazy_handle_edits(f, old, new, lazy, spec.accessor)
                for prob in lp:
                    self.err(c, "capability", prob)
                for note in notes:
                    self.info(c, f"[capability] {f}: {note}")
                drop_old, drop_new = set(drop_old) | lo, set(drop_new) | ln
            for e in sorted(import_entries(new) - import_entries(old)):
                if is_wild_or_given_import(e):
                    self.err(c, "capability", f"{f}: adds wildcard or given import `{e}`: a capability commit adds named imports only")
            probs, subs = substitution_check(f, old, new, spec, drop_old, drop_new, extra)
            for prob in probs:
                self.err(c, "capability", prob)
            if not probs:
                self.info(c, f"[capability] {f}: {subs} substitution(s)")

    def capability_env(self, c, spec, old, new):
        """SnapControllerEnv in a capability commit: it may lose pinned storage handles and gain the accessor (declared
        with exactly the capability type); every other member and line stays."""
        om, nm = env_members(old), env_members(new)
        for n in sorted(set(om) - set(nm)):
            if n not in self.pinned:
                self.err(c, "capability", f"{ENV_FILE}: removes `{n}`, which is not a pinned storage member (env.storage_members)")
        for n in sorted(set(nm) - set(om)):
            if n != spec.accessor:
                self.err(c, "capability", f"{ENV_FILE}: adds `{n}`; a capability commit adds only the accessor `{spec.accessor}`")
            elif re.sub(r"\s+", "", nm[n]) != f":{spec.name}":
                self.err(c, "capability", f"{ENV_FILE}: accessor `{n}{nm[n]}` must be declared `: {spec.name}`")
        for n in sorted(set(om) & set(nm)):
            if re.sub(r"\s+", " ", om[n]).strip() != re.sub(r"\s+", " ", nm[n]).strip():
                self.err(c, "capability", f"{ENV_FILE}: changes member `{n}`")
        rem = [b for n in set(om) - set(nm) for b in member_blocks(old, ENV_TRAIT, n)]
        add = [b for n in set(nm) - set(om) for b in member_blocks(new, ENV_TRAIT, n)]
        self.alone(c, ENV_FILE, rem + add)
        return set().union(*(ls for ls, _ in rem)), set().union(*(ls for ls, _ in add))

    def alone(self, c, f, blocks):
        """A removed or added declaration is excused from the token check by its lines, so it must be alone on them."""
        for _ls, decl in blocks:
            if ";" in decl:
                self.err(c, "capability", f"{f}: `{decl[:80]}` shares its line with another statement: a dropped or added "
                                          "declaration stands alone on its lines")

    def capability_extension(self, c, spec, base):
        """T060b (D-T060-1): a later capability PR may add members, map lines, forwarders and `apply` parameters. The
        members and `apply` parameters it already had keep their declarations and order; the member cap is the ratchet's
        (`capabilities.<Trait>.members`, growth needs a justification)."""
        for n, d in base.members.items():
            if n in spec.members and re.sub(r"\s+", " ", spec.members[n]).strip() != re.sub(r"\s+", " ", d).strip():
                self.err(c, "capability", f"{spec.path}: changes existing member `{n}{d.strip()[:60]}`")
        kept = [(n, t) for n, t in spec.apply_params if n in dict(base.apply_params)]
        if kept != [(n, t) for n, t in base.apply_params if n in dict(spec.apply_params)]:
            self.err(c, "capability", f"{spec.path}: `apply` changes or reorders an existing parameter")
        gone = [n for n, _t in base.apply_params if n not in dict(spec.apply_params)]
        if gone:
            self.err(c, "capability", f"{spec.path}: `apply` drops existing parameter(s) {', '.join(gone)}")
        added = [n for n, _t in spec.apply_params if n not in dict(base.apply_params)]
        self.info(c, f"[capability] extension: {len(spec.members) - len(base.members):+d} member(s), new `apply` parameter(s): "
                     f"{', '.join(added) or 'none'}")

    def capability_core(self, c, f, spec, old, new, base_spec=None):
        """The controller core may add one declaration of the accessor (its production default). Its initializer may name
        only the capability, the handles, names the capability file declares, and constructor keywords. Extending an
        existing capability (T060b), the existing declaration may change only by the arguments of new `apply`
        parameters: each existing argument stays as it was, and each new one is checked like an introduction's."""
        blocks_new, blocks_old = decl_blocks(new, spec.accessor), decl_blocks(old, spec.accessor)
        if len(blocks_new) > 1:
            self.err(c, "capability", f"{f}: declares `{spec.accessor}` {len(blocks_new)} times")
        self.alone(c, f, blocks_new)
        drop_new = set().union(*(ls for ls, _ in blocks_new)) if blocks_new else set()
        drop_old = set().union(*(ls for ls, _ in blocks_old)) if blocks_old else set()
        for _ls, decl in blocks_new:
            if not re.match(r"^(?:(?:" + MODIFIER + r")\s+)*(?:lazy\s+)?(?:val|def)\s+" + re.escape(spec.accessor) + r"\s*:\s*"
                            + re.escape(spec.name) + r"\b", decl):
                self.err(c, "capability", f"{f}: the accessor is declared `val|def {spec.accessor}: {spec.name} = ...`, found `{decl[:90]}`")
            init = top_level_initializer(decl) or ""
            if base_spec is not None and blocks_old:
                old_init = top_level_initializer(blocks_old[0][1]) or ""
                why = core_init_extension_problem([t.t for t in scala_tokens(init)], [t.t for t in scala_tokens(old_init)],
                                                  spec, base_spec, self.pinned)
            else:
                why = core_init_problem([t.t for t in scala_tokens(init)], spec, self.pinned)
            if why:
                self.err(c, "capability", f"{f}: the `{spec.accessor}` initializer `{init[:90]}` {why}")
        if blocks_old and [d for _, d in blocks_old] != [d for _, d in blocks_new] and base_spec is None:
            self.err(c, "capability", f"{f}: changes the existing `{spec.accessor}` declaration")
        return drop_old, drop_new

    def removed_state_handles(self, c, f, old, new) -> set[int]:
        """Lines of storage-typed abstract members removed from a module's `<X>State` trait (the handle now comes from the
        capability); any other State or Api change is not a substitution and fails the token check."""
        drop = set()
        for tr in toplevel_traits(old):
            if not tr.endswith("State"):
                continue
            om, nm = env_members(old, tr), env_members(new, tr)
            for n in set(om) - set(nm):
                if storage_typed(om[n]) and top_level_initializer(om[n]) is None:
                    blocks = member_blocks(old, tr, n)
                    self.alone(c, f, blocks)
                    for ls, _ in blocks:
                        drop |= ls
        return drop

    # -- step 1
    def check_moved_blocks(self, c, p, changed, symbols=()):
        out = self.g.run(
            "-c", "color.diff.oldMoved=magenta", "-c", "color.diff.newMoved=cyan",
            "-c", "color.diff.old=red", "-c", "color.diff.new=green",
            "diff", "--no-renames", "--color=always", "--color-moved=plain",
            "--color-moved-ws=allow-indentation-change", p, c, "--", "src/main/**/*.scala",
        )
        unmoved_rm, unmoved_add = [], []
        cur = None
        for raw in out.splitlines():
            plain = re.sub(r"\x1b\[[0-9;]*m", "", raw)
            if plain.startswith("+++ "):
                cur = plain[6:] if plain.startswith("+++ b/") else None
            m = re.match(r"^((?:\x1b\[[0-9;]*m)*)([-+])(.*)$", raw)
            if not m or raw.lstrip("\x1b[0123456789;m").startswith(("---", "+++")):
                continue
            codes, sign, body = m.groups()
            body = re.sub(r"\x1b\[[0-9;]*m", "", body)
            if not body.strip():
                continue
            if self.coordinator_closer(c, cur, sign, body):
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
            elif not (header_ok.search(r) and header_parents_ok(r, ident_args=True)):
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
        for a in (a for lines in pool.values() for a in lines if not (added_ok.search(a) and header_parents_ok(a, ident_args=True))):
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

    def coordinator_closer(self, c, path, sign, body) -> bool:
        """T060b: the first move into a coordinator's modules gives its Impl an `extends` clause, so the multi-line
        constructor's closer `):` becomes `) extends <Module>` (identifier parents only, each a trait declared in the
        coordinator's `actors/<dir>/` at this commit). Only in the four coordinator Impl files."""
        unit = next((x for x in ADOPT_COORDINATORS if path == f"{SNAP}/actors/{x}.scala"), None)
        if unit is None:
            return False
        mod_dir = f"{SNAP}/actors/{ADOPT_COORDINATORS[unit]}"
        declared = {t for f in self.g.tree_files(c, [mod_dir]) for t in toplevel_traits(self.g.show(c, f) or "")}

        def valid(line):
            m = re.match(r"^\s*\)\s*extends\s+(\w+(?:\s+with\s+\w+)*)\s*:?\s*$", line)
            return bool(m) and all(x in declared for x in re.split(r"\s+with\s+", m.group(1)))
        if sign == "-":
            # the old closer goes only where the file gains a valid `) extends <Module>` closer
            return bool(re.match(r"^\s*\)\s*:\s*$", body)) and any(valid(l) for l in (self.g.show(c, path) or "").splitlines())
        return valid(body)

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
                    if no in hdr and HEADER_EDIT_RE.search(l) and header_parents_ok(l, ident_args=True):
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
#   - a per-file storage-reference count: freely, as long as the TOTAL over RATCHET_DIRS + RATCHET_FILES does not go
#     up (code moving between module files, or from the core into a module, during the split). A higher total needs a `justifications["storage_refs.total"]` line that
#     this PR adds or changes.
# Bootstrap: only when the baseline is absent at the merge-base AND at BASE. Once BASE (staging) has it, a merge-base
# without it is an error (rebase). Present at the merge-base but not at HEAD: error. Absent everywhere: empty baseline.

BASELINE_PATH = "scripts/snap-split/storage-baseline.json"
RATCHET_DIRS = [f"{SNAP}/controller", f"{SNAP}/actors"]  # recursive: subdirectory modules are included
# Top-level snap/ files that are move sources of the split, listed one by one (scope extension, spec 016 T054c). The
# controller core loses code to the modules in the remaining slices (M5c, M7b, M9b); without it in the measured set, a
# byte-identical move of a storage reference out of the core read as new coupling. With it, such a move is a per-file
# trade and the total is unchanged. No other top-level snap/ file is a move source (tasks.md, the routing doc), so none
# is listed; adding one is a reviewed tooling change with a re-baseline.
RATCHET_FILES = [f"{SNAP}/SNAPSyncController.scala"]
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
# under RATCHET_DIRS and in RATCHET_FILES. Alternatives, in order: a lower-case `.xStorageY` member access (`.stateStorageOf(...)`,
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

# Counter version 2 (spec 016 T060b, coordinator-characterization.md §4.2 and G10). The baseline file selects the
# counter with its `counter` field (absent = 1), so a replay of a range whose baseline predates version 2 measures exactly
# what it measured then. Version 2 changes two things, each a reviewed tooling change:
#   - it drops names that are not stores but match STORAGE_REF_RE (the coordinators' false-positive floors). Each was
#     checked against its definition on staging@b9e4e8968;
#   - it counts `storageDone`, AccountRangeCoordinator's `Option[SnapStorageDoneStorage]` parameter, which is a store but
#     whose name the pattern misses (only its type counted).
# A baseline may raise `counter` only with a fresh `justifications["storage_refs.total"]` line (the re-baseline
# explanation), and may never lower it.
COUNTER_VERSIONS = (1, 2)
STORAGE_NAME_ALLOW_V2 = STORAGE_NAME_ALLOW | frozenset({
    "ForceCompleteStorage",        # StorageRangeCoordinator `Command` object (the force-complete message)
    "isNodeInStorage",             # TrieNodeHealingCoordinator method name; its store reads count in its body
    "isStorage",                   # BfsEntry / healing-entry Boolean field (a storage-trie node), not a store
    "replayQueuedStorage",         # AccountRangeCoordinator `Long` counter var
    "createStorageTask",           # StorageTask companion factory (a task value, not a store)
    "validateStorageRanges",       # SNAPRequestTracker response validation (pure)
    "verifyStorageRange",          # MerkleProofVerifier proof check over the response's own nodes (pure)
    "attachStorageConsumer",       # SnapIntakeBudget admission counter
    "pendingStorageTasks",         # SnapIntakeBudget admission counter
    "setStoragePendingTries",      # SNAPSyncMetrics gauge setters
    "setStorageQueueDepth",
    "setStorageInFlightRequests",
    "setStorageActivePeers",
    "setStorageBackpressure",
    "incrementStorageSlotsSynced", # SyncProgressMonitor progress counters
    "getStorageSlotsSynced",
    "updateStorageContracts",
    "maxConcurrentStorageAccounts",  # SNAPSyncConfig / coordinator `Int` settings
    "maxPendingStorageTasks",
    "evaluateStorageTail",         # StagnationPolicy pure decision
    "sampledStorageRoots",         # PathToHashExporter result field (an Int)
})
STORAGE_REF_RE_V2 = re.compile(STORAGE_REF_RE.pattern + r"|\bstorageDone\b")

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


def storage_ref_count(text: str, exemptions=(), counter: int = 1) -> int:
    """Storage references in `text`. `exemptions` is [(accessor, {calls}, {handles})] of the baselined capabilities
    (`cap_exemption`): a selection `accessor.call` of a non-storage-typed member is a call on the capability, not a
    storage reference (T054); a selection `accessor.handle` of a storage-typed member counts exactly once, whatever the
    member's name (a raw handle reached through the capability is still one). `counter` is the baseline's counter
    version (`COUNTER_VERSIONS`)."""
    code = strip_comments(text)
    for acc, calls, handles in exemptions:
        for names, repl in ((calls, "capabilityCall"), (handles, "capabilityStorage")):
            if names:
                code = re.sub(r"\b" + re.escape(acc) + r"(\s*\.\s*)(?:" + "|".join(map(re.escape, sorted(names))) + r")\b",
                              lambda m, r=repl: acc + m.group(1) + r, code)
    if counter >= 2:
        return sum(1 for m in STORAGE_REF_RE_V2.findall(code) if m.lstrip(".") not in STORAGE_NAME_ALLOW_V2)
    return sum(1 for m in STORAGE_REF_RE.findall(code) if m.lstrip(".") not in STORAGE_NAME_ALLOW)


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
    for k in ("storage_refs", "raw_imports", "justifications", "capabilities"):
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
        self.mb = mb
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
            if self.v.cap_names and n not in members:
                self.err("env-freeze", f"capability PR removes pinned env member `{n}` but {BASELINE_PATH} still lists it in "
                                       "env.storage_members: drop it in the same PR")
            else:
                self.info(f"WARN pinned env member `{n}` is no longer storage-typed: drop it from env.storage_members")

    # per-file counts, move-aware
    def check_refs(self, files: dict[str, str], exemptions=()):
        counter, old_counter = int(self.new.get("counter", 1)), int(self.old.get("counter", 1))
        if counter not in COUNTER_VERSIONS:
            self.err("storage-refs", f"{BASELINE_PATH} names counter {counter}; known versions: {COUNTER_VERSIONS}")
            counter = COUNTER_VERSIONS[-1]
        if counter < old_counter:
            self.err("storage-refs", f"{BASELINE_PATH} lowers counter from {old_counter} to {counter}: the counter version only goes up")
        bump_ceiling = None
        if counter > old_counter:
            bump_ceiling = self.counter_bump(old_counter, counter)
        counts = {rel: storage_ref_count(t, exemptions, counter) for rel, t in files.items()}
        total = sum(counts.values())
        old_total, new_total = int(self.old["storage_refs_total"]), int(self.new["storage_refs_total"])
        if bump_ceiling is not None:
            old_total = bump_ceiling
            if total > bump_ceiling:
                self.err("storage-refs", f"total storage references {total} under counter {counter}, above the merge-base "
                                         f"re-measured under counter {counter} ({bump_ceiling}): a counter change may not "
                                         "raise the total (no justification escape; raise it in a PR of its own)")
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

    def counter_bump(self, old_counter: int, counter: int) -> int:
        """A counter change (T060b, prism): the ceiling is the merge-base re-measured with the new counter, so the gap
        between the versions cannot hide new references; the commit that changes `counter` changes no `src/main` file;
        and the justification is substantive (names the new version, 40+ characters)."""
        g = self.g
        self.info(f"counter version {old_counter} -> {counter} (re-baseline)")
        j = str(self.new["justifications"].get(TOTAL_KEY, "")).strip()
        if not self.fresh_justification(TOTAL_KEY) or len(j) < 40 or not re.search(
                rf"\bcounter\s*(?:v|version\s*){counter}\b", j):
            self.err("storage-refs", f"{BASELINE_PATH} raises counter from {old_counter} to {counter}: a counter change "
                                     f"re-baselines every row, so it needs a new `justifications[\"{TOTAL_KEY}\"]` line of "
                                     f"40+ characters naming `counter v{counter}`")
        for c in g.run("rev-list", "--reverse", f"{self.mb}..{self.head}").split():
            if g.is_merge(c):
                continue
            p = g.parent(c)
            try:
                before = load_baseline(g.show(p, BASELINE_PATH)) if p != EMPTY_TREE else None
                after = load_baseline(g.show(c, BASELINE_PATH))
            except ValueError:
                continue
            if after and int(after.get("counter", 1)) != int((before or {}).get("counter", 1)):
                touched = [f for f in g.changed(p, c) if f.startswith("src/main/")]
                if touched:
                    self.v.err(c, "storage-refs", f"commit changes `counter` and also {touched[0]}"
                                                  f"{f' (+{len(touched) - 1} more)' if len(touched) > 1 else ''}: a counter change "
                                                  "is a commit of its own, with no src/main change")
        files = {f[len(SNAP) + 1:]: g.show(self.mb, f) or "" for f in g.tree_files(self.mb, RATCHET_DIRS + RATCHET_FILES)}
        caps = {e.get("file"): n for n, e in self.old["capabilities"].items() if e.get("file") in files}
        ex = [cap_exemption(n, files[k]) for k, n in caps.items()]
        ceiling = sum(storage_ref_count(t, ex, counter) for k, t in files.items() if k not in caps)
        self.info(f"merge-base re-measured under counter {counter}: {ceiling} (was {self.old['storage_refs_total']} under counter {old_counter})")
        return ceiling

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


    # capability traits (T054b): their own member-cap rows; the capability file is the storage boundary
    def check_capabilities(self, files: dict[str, str]) -> dict[str, str]:
        """Validate `capabilities` ({TraitName: {file, members, raw_imports}}) and return {file key: TraitName} of the
        capability files at the PR head, which [storage-refs] does not measure (it is where storage belongs); their raw
        imports are checked against the entry's own list by `check_capability_raw`."""
        old, new, out = self.old["capabilities"], self.new["capabilities"], {}
        for name in sorted(set(old) - set(new)):
            self.err("capability", f"{BASELINE_PATH} drops capability `{name}`: a capability entry is never removed")
        for name in sorted(self.v.cap_names - set(new)):
            self.err("capability", f"capability PR for `{name}` has no `capabilities[\"{name}\"]` entry in {BASELINE_PATH}: "
                                   "record its file, member count and raw imports in this PR")
        for name, e in sorted(new.items()):
            key, members = e.get("file", ""), e.get("members")
            if name not in old and name not in self.v.cap_names:
                self.err("capability", f"{BASELINE_PATH} adds capability `{name}` outside a `# capability: {name}` PR")
            if name in old and old[name].get("file") != key:
                self.err("capability", f"capability `{name}` moves from {old[name].get('file')} to {key}: the file is pinned")
            text = files.get(key)
            if text is None or name not in toplevel_traits(text):
                self.err("capability", f"capability `{name}`: {key or '<no file>'} is not a measured file declaring `trait {name}`")
                continue
            out[key] = name
            if not self.v.cap_names:  # in a capability PR every commit already checks it; elsewhere, check the head
                for prob in CapabilitySpec(name, key, text, None).problems:
                    self.err("capability", prob)
            n = len(env_members(text, name))
            self.info(f"capability {name} ({key}): members={n} (baseline {members}"
                      + (f", ceiling {old[name].get('members')}" if name in old else ", new") + ")")
            self.exact("capability", f"{name} member count", n, int(members or 0), f"capabilities[\"{name}\"].members")
            jkey = f"capabilities.{name}.members"
            if name in old and n > int(old[name].get("members", 0)) and not self.fresh_justification(jkey):
                self.err("capability", f"{name} has {n} members, above the ceiling {old[name].get('members')}: growth needs a new "
                                       f"`justifications[\"{jkey}\"]` line")
        return out

    def check_capability_raw(self, files: dict[str, str], caps: dict[str, str]):
        """[raw-import] still measures a capability file: its raw imports are allowed only as listed in its entry, and the
        list is fixed when the capability is introduced and may only shrink afterwards."""
        old, new = self.old["capabilities"], self.new["capabilities"]
        for key, name in caps.items():
            listed = set(new[name].get("raw_imports", []))
            allowed = set(old[name].get("raw_imports", [])) if name in old else listed
            for e in raw_imports(files[key]):
                if e not in allowed:
                    self.err("raw-import", f"{key}: raw storage import `{e}` in capability {name}, not listed in "
                                           f"`capabilities[\"{name}\"].raw_imports`" + (" (the list may only shrink)" if name in old else ""))
            for e in sorted(listed - allowed):
                self.err("raw-import", f"{BASELINE_PATH} adds `{e}` to capabilities[{name}].raw_imports: the list may only shrink")
            for e in sorted(listed - set(raw_imports(files[key]))):
                self.info(f"WARN {key} no longer has raw import `{e}`: drop it from capabilities[{name}].raw_imports")


def check_storage_ratchet(v: "Verifier", base: str, head: str):
    r = Ratchet(v, base, head)
    if not r.ok:
        return
    files = {f[len(SNAP) + 1:]: v.g.show(head, f) or "" for f in v.g.tree_files(head, RATCHET_DIRS + RATCHET_FILES)}
    caps = r.check_capabilities(files)
    r.check_env()
    measured = {k: t for k, t in files.items() if k not in caps}
    r.check_refs(measured, exemptions=[cap_exemption(n, files[k]) for k, n in caps.items()])
    r.check_raw(measured)
    r.check_capability_raw({k: files[k] for k in caps}, caps)


# --------------------------------------------------------------------------- capability mode (spec 016 T054b)
#
# A behaviour-neutral PR that introduces a capability trait (T055: `StateIngest`) and moves call sites onto it is
# neither a move (steps 1-2) nor a narrowing (step 6). It is selected per commit by the trailer
#     # capability: <TraitName>
# on a refactor/snap-016-* branch only. A PR with the trailer on any commit is a capability PR: every commit that
# touches a src/main .scala file under snap/ must carry the same trailer, and no commit may be a move or a narrowing.
# In a capability commit, steps 1, 2 and 6 are replaced by the substitution check below; (a), (d), [counts], the
# test-hunk guard, pin and golden protection and step 8 still apply.
#
# The capability file is the one .scala file under snap/controller or snap/actors that declares `trait <TraitName>` at
# the top level. Its accessor is the trait name in lower camel case (`StateIngest` -> `stateIngest`). It declares the
# call-site map in comment lines, one entry per line:
#     // capability-map: appStateStorage.putSnapSyncPivotBlock -> putSnapSyncPivotBlock
#     // capability-map: blockchainReader -> blockchainReader
# The left side is a storage handle (a pinned SnapControllerEnv storage member at the merge-base, or an identifier the
# ratchet counts as a storage reference) optionally followed by member selections; the right side is a member of the
# trait. At a call site, the token sequence of the left side may be replaced by `<accessor>.<right side>`. The
# production implementation of every mapped member lives in the capability file and is a pure forwarder: its body is
# exactly the left side applied to the member's own parameters, in order. The file itself has an allow-listed shape
# (`capability_structure`), `apply` passes its own parameters to the production class by name and in order, and the
# core's accessor passes, position by position, the handle mapped to each `apply` parameter or a case of a declared
# enum (`core_init_problem`). See `CapabilitySpec`.

CAP_MAP_RE = re.compile(r"^\s*(?://+|/?\*+)\s*capability-map:\s*(\S+)\s*->\s*(\S+)\s*$")
CAP_LHS_RE = re.compile(r"^[a-z]\w*(?:\.[A-Za-z_]\w*)*$")
IDENT_RE = re.compile(r"^[A-Za-z_]\w*$")
# An identifier the ratchet counts as a storage reference (see STORAGE_REF_RE), as a handle name.
HANDLE_NAME_RE = re.compile(r"^(?:[a-z]\w*Storages?(?:Opt)?|blockchain(?:Reader|Writer)|dataSource\w*)$")
CONT_START = {".", ")", "]", ","}
CONT_END = {"(", "[", ",", "."}
OPCHARS = set("!#%&*+-/:<=>?@\\^|~")
NUM_RE = re.compile(r"0[xX][0-9a-fA-F_]+[Ll]?|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][-+]?\d+)?[LlFfDd]?")
WORD_RE = re.compile(r"[A-Za-z_][\w]*|`[^`\n]+`")


def lower_camel(name: str) -> str:
    return name[:1].lower() + name[1:]


class Tok:
    __slots__ = ("t", "line")

    def __init__(self, t: str, line: int):
        self.t, self.line = t, line

    def __repr__(self):
        return f"{self.t}@{self.line}"


def scala_tokens(text: str) -> list[Tok]:
    """Scala tokens of `text` with their 1-based line: comments dropped, whitespace dropped. A plain string literal is one
    token; an interpolated string is split into its literal chunks and the tokens of each `${...}` / `$name`, so a
    handle inside an interpolation is substituted like any other."""
    out: list[Tok] = []
    n = len(text)

    def lex(i: int, line: int, interp: bool) -> tuple[int, int]:
        depth = 0
        while i < n:
            ch = text[i]
            if ch == "\n":
                line += 1
                i += 1
            elif ch.isspace():
                i += 1
            elif text.startswith("//", i):
                j = text.find("\n", i)
                i = n if j < 0 else j
            elif text.startswith("/*", i):
                d, j = 1, i + 2
                while j < n and d:
                    if text.startswith("/*", j):
                        d, j = d + 1, j + 2
                    elif text.startswith("*/", j):
                        d, j = d - 1, j + 2
                    else:
                        j += 1
                line += text.count("\n", i, j)
                i = j
            elif ch == '"':
                i, line = lex_string(i, line)
            elif ch == "'" and (m := CHAR_LIT_RE.match(text, i)):
                out.append(Tok(m.group(0), line))
                i = m.end()
            elif interp and ch == "{":
                depth += 1
                out.append(Tok(ch, line))
                i += 1
            elif interp and ch == "}":
                if depth == 0:
                    return i + 1, line
                depth -= 1
                out.append(Tok(ch, line))
                i += 1
            elif ch.isdigit():
                m = NUM_RE.match(text, i)
                out.append(Tok(m.group(0), line))
                i = m.end()
            elif (m := WORD_RE.match(text, i)):
                out.append(Tok(m.group(0), line))
                i = m.end()
            elif ch in OPCHARS:
                j = i
                while j < n and text[j] in OPCHARS and not text.startswith("//", j) and not text.startswith("/*", j):
                    j += 1
                out.append(Tok(text[i:j], line))
                i = j
            else:
                out.append(Tok(ch, line))
                i += 1
        return i, line

    def lex_string(i: int, line: int) -> tuple[int, int]:
        interp = i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_")
        triple = text.startswith('"""', i)
        q = '"""' if triple else '"'
        start_line, buf, j = line, q, i + len(q)
        while j < n:
            if triple and text.startswith('"""', j):
                k = j + 3
                while k < n and text[k] == '"':
                    k += 1
                buf += text[j:k]
                j = k
                break
            if not triple and text[j] == "\\":
                buf += text[j : j + 2]
                j += 2
                continue
            if not triple and text[j] in '"\n':
                buf += text[j]
                j += 1
                break
            if interp and text.startswith("$$", j):
                buf += "$$"
                j += 2
                continue
            if interp and text.startswith("${", j):
                out.append(Tok(buf, start_line))
                out.append(Tok("${", line))
                j, line = lex(j + 2, line, True)
                out.append(Tok("}", line))
                buf, start_line = "", line
                continue
            if interp and text[j] == "$" and (m := re.compile(r"[A-Za-z_]\w*").match(text, j + 1)):
                out.append(Tok(buf, start_line))
                out.append(Tok("$", line))
                out.append(Tok(m.group(0), line))
                buf, start_line, j = "", line, m.end()
                continue
            if text[j] == "\n":
                line += 1
            buf += text[j]
            j += 1
        out.append(Tok(buf, start_line))
        return j, line

    lex(0, 1, False)
    return out


def statement_starts(toks: list[Tok], text: str) -> dict[int, int]:
    """token index -> indent, for the first token of each line that starts a statement. A line is a continuation (not
    a statement start) when it starts with `.`, `)`, `]` or `,`, follows a line ending in `(`, `[`, `,` or `.`, or is the
    single-expression body after a line ending in `=` (the next statement line is not indented past the `=` line, so the
    body has one statement and may be joined onto the `=` line). Lines inside one expression may be re-wrapped; a
    statement may not move into or out of a line start, or change its indentation."""
    src = text.splitlines()
    rows = []  # [first token index, indent, last token text]
    for k, tk in enumerate(toks):
        if k == 0 or toks[k - 1].line != tk.line:
            rows.append([k, indent_of(src[tk.line - 1]) if tk.line - 1 < len(src) else 0, tk.t])
        else:
            rows[-1][2] = tk.t
    cont = [r > 0 and (toks[rows[r][0]].t in CONT_START or rows[r - 1][2] in CONT_END) for r in range(len(rows))]
    for r in range(1, len(rows)):
        if cont[r] or rows[r - 1][2] != "=":
            continue
        q = r - 1
        while q > 0 and cont[q]:
            q -= 1  # the line that starts the `=` definition
        nxt = next((m for m in range(r + 1, len(rows)) if not cont[m]), None)
        if nxt is None or rows[nxt][1] <= rows[q][1]:
            cont[r] = True
    return {rows[r][0]: rows[r][1] for r in range(len(rows)) if not cont[r]}


def drop_lines(toks: list[Tok], drop: set[int]) -> list[Tok]:
    return [t for t in toks if t.line not in drop]


def import_lines(text: str) -> set[int]:
    """1-based lines of import statements (multi-line brace imports included), comments ignored."""
    out, open_braces = set(), 0
    for no, l in enumerate(code_lines(text), 1):
        s = l.strip()
        if open_braces > 0:
            out.add(no)
            open_braces += s.count("{") - s.count("}")
        elif re.match(r"^import\s", s):
            out.add(no)
            open_braces = s.count("{") - s.count("}")
    return out


def import_entries(text: str) -> set[str]:
    out, buf = set(), None
    for l in code_lines(text):
        s = l.strip()
        if buf is not None:
            buf += " " + s
        elif re.match(r"^import\s", s):
            buf = s[len("import"):].strip()
        else:
            continue
        if buf.count("{") > buf.count("}"):
            continue
        for clause in split_top_commas(buf):
            out.update(e.strip() for e in expand_import(clause))
        buf = None
    return out


def decl_blocks(text: str, name: str) -> list[tuple[set[int], str]]:
    """(1-based lines, flattened code text) of each declaration of `name` in comment-stripped `text`: its line plus the
    more-indented continuation lines (and a closing bracket line at the same indent)."""
    lines, rx, out = code_lines(text), decl_re(name), []
    for i, l in enumerate(lines):
        m = rx.match(l)
        if not m:
            continue
        d, j = len(m.group(1)), i + 1
        while j < len(lines) and (not lines[j].strip() or indent_of(lines[j]) > d or
                                  (indent_of(lines[j]) == d and lines[j].strip()[:1] in ")]}")):
            j += 1
        while j > i + 1 and not lines[j - 1].strip():
            j -= 1
        out.append((set(range(i + 1, j + 1)), " ".join(x.strip() for x in lines[i:j] if x.strip())))
    return out


def param_lists(decl: str, name: str) -> list[tuple[bool, list[str], list[str]]]:
    """The parameter lists of a `def name[...](...)(using ...): T = ...` declaration: [(is_using, [names], [raw params])]."""
    m = re.search(r"\b(?:def|val|var)\s+" + re.escape(name) + r"(?![\w$])", decl)
    if not m:
        return []
    s, i, groups = decl, m.end(), []
    while i < len(s) and s[i] == " ":
        i += 1
    if i < len(s) and s[i] == "[":
        d = 0
        while i < len(s):
            d += s[i] == "["
            d -= s[i] == "]"
            i += 1
            if d == 0:
                break
    while i < len(s) and s[i] == "(":
        d, j = 0, i
        while j < len(s):
            d += s[j] in "(["
            d -= s[j] in ")]"
            j += 1
            if d == 0:
                break
        inner = s[i + 1 : j - 1].strip()
        using = bool(re.match(r"^(?:using|implicit)\b", inner))
        inner = re.sub(r"^(?:using|implicit)\s+", "", inner)
        raw = split_top_level(inner)
        names = [pm.group(1) for p in raw if (pm := re.match(r"^\s*(?:(?:val|var)\s+)?(\w+)\s*:", p))]
        groups.append((using, names, raw))
        i = j
        while i < len(s) and s[i] == " ":
            i += 1
    return groups


def has_default_param(decl: str, name: str) -> bool:
    """True if a parameter of `def name(...)` in the flattened `decl` has a default value (` = ` at its top level)."""
    return any(top_level_initializer(p.strip()) is not None for _u, _n, raw in param_lists(decl, name) for p in raw)


def split_top_level(s: str) -> list[str]:
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    return [x for x in out + [cur] if x.strip()]


def toplevel_traits(text: str) -> list[str]:
    return [m.group(1) for l in code_lines(text) if indent_of(l) == 0 and (m := re.match(r"^(?:" + MODIFIER + r"\s+)*trait\s+(\w+)", l.strip()))]


def find_capability_file(g: "Git", rev: str, name: str) -> list[str]:
    return [f for f in g.tree_files(rev, RATCHET_DIRS) if name in toplevel_traits(g.show(rev, f) or "")]


def is_handle_root(name: str, pinned: set[str]) -> bool:
    return name in pinned or (bool(HANDLE_NAME_RE.match(name)) and name not in STORAGE_NAME_ALLOW)


class CapabilitySpec:
    """The capability trait `name` as declared at one revision in `path`: its accessor, members, call-site map, and the
    problems found in the file (an unparseable or unknown map entry, a mapped member without a pure forwarder)."""

    def __init__(self, name: str, path: str, text: str, pinned: set[str] | None, grandfathered=frozenset()):
        """`pinned` is the env's pinned storage handles at the merge-base, the roots a map entry may start from; None
        skips that check (the PR-head re-check of an existing capability, after T055 emptied the pinned list).
        `grandfathered` holds the (lhs, rhs) map entries the capability file already had at the merge-base (T060b,
        capability extension): they were root-checked when they were introduced, so a later PR does not re-check them
        against today's (empty) pinned list."""
        self.name, self.path, self.text = name, path, text
        self.accessor = lower_camel(name)
        self.members = env_members(text, name)  # name -> declaration text after the name
        self.problems: list[str] = []
        self.apply_params: list[tuple[str, str]] = []  # (name, type) of `apply`, in order
        self.enums: dict[str, set[str]] = {}           # enum name -> its cases
        self._apply_body: tuple[int, list[str]] = (0, [])
        self.maps: dict[tuple[str, ...], tuple[str, ...]] = {}
        code = strip_comments(text)
        targets: dict[str, str] = {}
        for no, l in enumerate(text.splitlines(), 1):
            m = CAP_MAP_RE.match(l)
            if not m:
                if "capability-map:" in l:
                    self.problems.append(f"{path}:{no}: unparseable map line (want `// capability-map: handle[.member] -> member`): `{l.strip()[:90]}`")
                continue
            lhs, rhs = m.group(1), m.group(2)
            if not CAP_LHS_RE.match(lhs) or not IDENT_RE.match(rhs):
                self.problems.append(f"{path}:{no}: map entry `{lhs} -> {rhs}`: the left side is `handle[.member...]`, the right side one member name")
                continue
            root = lhs.split(".")[0]
            if pinned is not None and (lhs, rhs) not in grandfathered and not is_handle_root(root, pinned):
                self.problems.append(f"{path}:{no}: map entry `{lhs} -> {rhs}`: `{root}` is not a storage handle (a pinned "
                                     "SnapControllerEnv storage member, or a name the ratchet counts as a storage reference)")
                continue
            if rhs not in self.members:
                self.problems.append(f"{path}:{no}: map entry `{lhs} -> {rhs}`: `{rhs}` is not a member of trait {name}")
                continue
            key = tuple(t.t for t in scala_tokens(lhs))
            if key in self.maps:
                self.problems.append(f"{path}:{no}: `{lhs}` is mapped twice")
                continue
            if rhs in targets:
                self.problems.append(f"{path}:{no}: `{rhs}` is the target of both `{targets[rhs]}` and `{lhs}`: one member forwards one operation")
                continue
            targets[rhs] = lhs
            self.maps[key] = (self.accessor, ".", rhs)
        # a bare `handle -> member` line makes `member` the production class's name for that handle (apply and the core
        # pass it by name and position, see capability_structure / core_init_problem), so a forwarder may read either
        self.aliases = {lhs[0]: rhs[2] for lhs, rhs in self.maps.items() if len(lhs) == 1}
        for member, handle_path in targets.items():
            self.check_forwarder(handle_path, member, code)
        # A handle the map reads from is wired in (a constructor parameter or an abstract member); the capability file may
        # not define it, or the forwarders would read something else under the handle's name.
        for root in sorted({k[0] for k in self.maps}):
            for _lines, decl in decl_blocks(text, root):
                if top_level_initializer(strip_result_type(VIS_RE.sub("", decl))) is not None:
                    self.problems.append(f"{path}: defines handle `{root}` with a body; a mapped handle is only wired in "
                                         f"(constructor parameter or abstract member): `{decl[:90]}`")
        self.problems += capability_structure(self)

    def alias_maps(self) -> list[tuple[tuple[str, ...], tuple[str, ...]]]:
        """T060b: the accessor-alias form of each method entry. When `h -> a` is a bare entry and `h.op -> m` a method
        entry, a call site already moved onto the capability (`<accessor>.a.op`) may become `<accessor>.m`: `m` is a
        pure forwarder of `h.op`, and `a` is `h` (checked by `check_forwarder` and `core_init_problem`)."""
        out = []
        for lhs, rhs in self.maps.items():
            if len(lhs) > 1 and lhs[0] in self.aliases:
                out.append(((self.accessor, ".", self.aliases[lhs[0]]) + lhs[1:], rhs))
        return out

    def check_forwarder(self, lhs: str, rhs: str, code: str):
        bodies = []
        for _lines, decl in decl_blocks(code, rhs):
            init = top_level_initializer(strip_result_type(VIS_RE.sub("", decl)))
            if init is not None:
                bodies.append((decl, init))
        lhs_toks = [t.t for t in scala_tokens(lhs)]
        decls = [d for _ls, d in decl_blocks(code, rhs)]
        if any(has_default_param(d, rhs) for d in decls):
            self.problems.append(f"{self.path}: mapped member `{rhs}` declares a default parameter: a call site that omits it "
                                 f"would get the capability's default instead of `{lhs}`'s")
        if not bodies:
            decl = self.members.get(rhs, "")
            if "." not in lhs and not decl.lstrip().startswith(("(", "[")):
                return  # a handle accessor implemented by a constructor parameter: wired by the core's accessor
            self.problems.append(f"{self.path}: mapped member `{rhs}` (`{lhs}`) has no forwarder body in this file: the "
                                 "production implementation of a mapped member lives in the capability file")
            return
        roots = [lhs_toks[0]] + ([self.aliases[lhs_toks[0]]] if lhs_toks[0] in self.aliases else [])
        for decl, init in bodies:
            want = list(lhs_toks)
            for using, names, _raw in param_lists(decl, rhs):
                want += ["("] + (["using"] if using else [])
                for k, p in enumerate(names):
                    want += ([","] if k else []) + [p]
                want += [")"]
            got = [t.t for t in scala_tokens(init)]
            if got[:1] and got[0] in roots:
                got = [lhs_toks[0]] + got[1:]
            if got != want:
                self.problems.append(f"{self.path}: forwarder `{rhs}` is not a pure forwarder of `{lhs}`: body `{init[:80]}`, "
                                     f"expected `{' '.join(want)}` (the mapped call with the member's own parameters, in order)")


def cap_exemption(name: str, text: str) -> tuple[str, set[str], set[str]]:
    """(accessor, calls, handles) of capability trait `name` in `text`. A handle is a parameterless storage-typed member
    (`def appStateStorage: AppStateStorage`, `def slotSource: DataSource`): a raw store reached through the capability.
    Every other member is a call, whatever it returns (`def putPivotBlock(b: BigInt): DataSourceBatchUpdate`)."""
    members = env_members(text, name)
    handles = {n for n, d in members.items() if not d.lstrip().startswith(("(", "[")) and storage_typed(d)}
    return lower_camel(name), set(members) - handles, handles


def is_wild_or_given_import(entry: str) -> bool:
    """An expanded import entry (`expand_import`) that is a wildcard (`*`, `_`) or a given import (`given`, `given T`)."""
    return bool(re.search(r"(?:\.\*|\._)$|(?:^|\.)given\b|\bimplicit\b", entry)) or " given" in entry


def item_blocks(lines: list[str], start: int, end: int, d: int) -> list[tuple[int, int]]:
    """[start, end) line ranges of the items at indent `d` in lines[start:end]: an item runs while the next lines are
    blank, more indented, or a closing bracket or `end` at the same indent."""
    out, i = [], start
    while i < end:
        if not lines[i].strip():
            i += 1
            continue
        j = i + 1
        while j < end and (not lines[j].strip() or indent_of(lines[j]) > d or
                           (indent_of(lines[j]) == d and (lines[j].strip()[:1] in ")]}" or lines[j].strip().startswith("end ")))):
            j += 1
        out.append((i, j))
        i = j
    return out


def flat(lines: list[str], i: int, j: int) -> str:
    return " ".join(x.strip() for x in lines[i:j] if x.strip())


DECL_HEAD_RE = re.compile(r"^(?:" + MODIFIER + r"\s+)*(?:case\s+)?(def|val|var|lazy|type|given|trait|class|object|enum)\b\s*(\w*)")


def ctor_params(header: str, cls: str) -> list[tuple[bool, str, str]] | None:
    """(is_val, name, raw) of `class cls(...)`'s one parameter list, from its flattened header; None if unparseable."""
    m = re.search(r"\bclass\s+" + re.escape(cls) + r"\s*\(", header)
    if not m:
        return None
    i, d = m.end() - 1, 0
    for j in range(i, len(header)):
        d += header[j] in "(["
        d -= header[j] in ")]"
        if d == 0:
            break
    out = []
    for p in split_top_level(header[i + 1 : j]):
        pm = re.match(r"^\s*(?:(?:" + MODIFIER + r")\s+)*(val\s+)?(\w+)\s*:(.*)$", p.strip())
        if not pm:
            return None
        out.append((bool(pm.group(1)), pm.group(2), p.strip()))
    if header[j + 1 :].strip() and not re.match(r"^extends\b", header[j + 1 :].strip()):
        return None
    return out


def capability_structure(spec: "CapabilitySpec") -> list[str]:
    """The capability file's allow-listed shape (T054b review B1/B2). Top level: package and imports, the trait, and its
    companion object, nothing else. The trait holds abstract `def`/`val` members only. The object holds only:
      - `enum E:` whose body is `case` lines without parameters or bodies, and `type T = ...` aliases;
      - one `def apply(<params, no defaults>): <Trait> = new <Prod>(<the params, by name, in order>)`;
      - one `class <Prod>(<params>) extends <Trait>:`, whose parameter names are apply's, in order, and whose body holds only
        forwarder `def`s of mapped members (each checked as a pure forwarder). A member that is not forwarded (a handle,
        a policy such as `durability`) is a `val` constructor parameter, never a body `val` with an initializer.
    Records `spec.apply_params` and `spec.enums` for the core accessor check."""
    path, name, out = spec.path, spec.name, []
    lines = code_lines(spec.text)
    # Imports only at the top of the file (indent 0) and named only: a nested import, a wildcard or a `given` import
    # could change how a forwarder's arguments resolve (T054b review F1). Only top-level import statements are blanked
    # here, so a nested one is also reported by the item checks below.
    skip, top = import_lines(spec.text), set()
    starts = lambda k: k in skip and lines[k - 1].strip().startswith("import")
    for k in sorted(skip):
        if not starts(k):
            continue  # a continuation line of a multi-line `import a.{...}`
        end = k
        while end + 1 in skip and not starts(end + 1):
            end += 1
        stmt = flat(lines, k - 1, end)
        if indent_of(lines[k - 1]) != 0:
            out.append(f"{path}:{k}: `{stmt[:80]}`: the capability file imports only at the top of the file")
        else:
            top |= set(range(k, end + 1))
        for e in sorted(import_entries(stmt)):
            if is_wild_or_given_import(e):
                out.append(f"{path}:{k}: wildcard or given import `{e}`: the capability file imports named members only")
    lines = ["" if (k + 1) in top or re.match(r"^package\s", l.strip()) else l for k, l in enumerate(lines)]
    mapped = set(t[2] for t in spec.maps.values())
    traits = objects = 0
    prod, apply_seen = None, False
    for i, j in item_blocks(lines, 0, len(lines), 0):
        head = lines[i].strip()
        m = DECL_HEAD_RE.match(head)
        kind, nm = (m.group(1), m.group(2)) if m else (None, None)
        if indent_of(lines[i]) != 0 or kind not in ("trait", "object") or nm != name:
            out.append(f"{path}:{i + 1}: top-level `{head[:70]}`: the capability file holds only `trait {name}` and "
                       f"`object {name}` (plus package and imports)")
            continue
        if kind == "trait":
            traits += 1
            if not re.match(r"^(?:" + MODIFIER + r"\s+)*trait\s+" + name + r"\s*:\s*$", head):
                out.append(f"{path}:{i + 1}: the trait header is `trait {name}:` (no parents, no self-type): `{head[:70]}`")
            for a, b2 in item_blocks(lines, i + 1, j, 2):
                d = flat(lines, a, b2)
                dm = DECL_HEAD_RE.match(d)
                if indent_of(lines[a]) != 2 or not dm or dm.group(1) not in ("def", "val") or top_level_initializer(d) is not None \
                        or ";" in d:
                    out.append(f"{path}:{a + 1}: trait {name} holds abstract `def`/`val` members only: `{d[:80]}`")
            continue
        objects += 1
        if not re.match(r"^(?:" + MODIFIER + r"\s+)*object\s+" + name + r"\s*:\s*$", head):
            out.append(f"{path}:{i + 1}: the companion header is `object {name}:`: `{head[:70]}`")
        for a, b2 in item_blocks(lines, i + 1, j, 2):
            d = flat(lines, a, b2)
            dm = DECL_HEAD_RE.match(d)
            kind2, nm2 = (dm.group(1), dm.group(2)) if dm else (None, None)
            if indent_of(lines[a]) != 2 or ";" in d:
                out.append(f"{path}:{a + 1}: unexpected line in object {name}: `{d[:80]}`")
            elif kind2 == "enum":
                cases = set()
                if not re.match(r"^(?:" + MODIFIER + r"\s+)*enum\s+\w+\s*:\s*$", lines[a].strip()):
                    out.append(f"{path}:{a + 1}: enum header is `enum {nm2}:` (no parameters or parents): `{lines[a].strip()[:80]}`")
                for x in range(a + 1, b2):
                    l = lines[x].strip()
                    if not l:
                        continue
                    cm = re.match(r"^case\s+(\w+(?:\s*,\s*\w+)*)\s*$", l)
                    if not cm:
                        out.append(f"{path}:{x + 1}: enum {nm2} holds plain `case A, B` lines only: `{l[:80]}`")
                    else:
                        cases |= {c.strip() for c in cm.group(1).split(",")}
                spec.enums[nm2] = cases
            elif kind2 == "type" and re.match(r"^(?:" + MODIFIER + r"\s+)*type\s+\w+(?:\[[^\]]*\])?\s*=\s*[\w.\[\], =>]+$", d):
                pass
            elif kind2 == "def" and nm2 == "apply" and not apply_seen:
                apply_seen = True
                groups = param_lists(d, "apply")
                if len(groups) != 1 or groups[0][0]:
                    out.append(f"{path}:{a + 1}: `apply` takes one plain parameter list: `{d[:80]}`")
                    continue
                _u, names, raw = groups[0]
                if any(top_level_initializer(r.strip()) is not None for r in raw):
                    out.append(f"{path}:{a + 1}: `apply` parameters take no defaults (the core passes every argument)")
                spec.apply_params = [(n, r.split(":", 1)[1].strip()) for n, r in zip(names, (r.strip() for r in raw))]
                sig = d[: len(d) - len(top_level_initializer(d) or "")]
                if not re.search(r"\)\s*:\s*" + re.escape(name) + r"\s*=$", sig.strip()):
                    out.append(f"{path}:{a + 1}: `apply` is declared `def apply(...): {name} = new <Prod>(...)`")
                body = [t.t for t in scala_tokens(top_level_initializer(d) or "")]
                spec._apply_body = (a + 1, body)
            elif kind2 == "class" and prod is None:
                hdr_end = a
                while hdr_end < b2 - 1 and not (lines[hdr_end].rstrip().endswith(":") and
                                                flat(lines, a, hdr_end + 1).count("(") == flat(lines, a, hdr_end + 1).count(")")):
                    hdr_end += 1  # the header ends at its `:`, or at the block's last line when the class has no body
                header = flat(lines, a, hdr_end + 1)
                params = ctor_params(header, nm2)
                if params is None or not re.search(r"\)\s*extends\s+" + re.escape(name) + r"\s*:?$", header):
                    out.append(f"{path}:{a + 1}: the production class is `class <Prod>(<params>) extends {name}:`, with no "
                               f"other parent and no default: `{header[:90]}`")
                    params = params or []
                if any(top_level_initializer(r) is not None for _v, _n, r in params):
                    out.append(f"{path}:{a + 1}: production class parameters take no defaults")
                prod = (nm2, a + 1, params)
                for v, pn, _r in params:
                    if v and pn not in spec.members:
                        out.append(f"{path}:{a + 1}: `val {pn}` is not a member of trait {name}")
                for x, y in item_blocks(lines, hdr_end + 1, b2, 4):
                    dd = flat(lines, x, y)
                    m3 = DECL_HEAD_RE.match(dd)
                    if indent_of(lines[x]) != 4 or not m3 or m3.group(1) != "def" or ";" in dd:
                        out.append(f"{path}:{x + 1}: production class body holds only forwarder `def`s of mapped members; "
                                   f"`{dd[:80]}` is not one (an unforwarded member is a `val` constructor parameter)")
                    elif m3.group(2) not in mapped:
                        out.append(f"{path}:{x + 1}: `def {m3.group(2)}` in the production class is not a mapped member "
                                   "(declare it with a `capability-map:` line, or make it a `val` constructor parameter)")
            else:
                out.append(f"{path}:{a + 1}: object {name} holds only enums, type aliases, one `apply` and one production "
                           f"class: `{d[:80]}`")
    if traits != 1:
        out.append(f"{path}: declares `trait {name}` {traits} times")
    if objects > 1:
        out.append(f"{path}: declares `object {name}` {objects} times")
    if not apply_seen or prod is None:
        out.append(f"{path}: object {name} must declare `apply` and one production class")
    else:
        pname, _line, params = prod
        want = ["new", pname, "("] + [t for k, (n, _t) in enumerate(spec.apply_params) for t in ([","] if k else []) + [n]] + [")"]
        line, body = spec._apply_body
        if body != want:
            out.append(f"{path}:{line}: `apply` body must be exactly `{' '.join(want)}` (its own parameters, by name, in "
                       f"order), found `{' '.join(body)[:90]}`")
        if [n for _v, n, _r in params] != [n for n, _t in spec.apply_params]:
            out.append(f"{path}: production class `{pname}` parameters {[n for _v, n, _r in params]} must be `apply`'s, "
                       f"in order: {[n for n, _t in spec.apply_params]}")
    return out


def core_init_problem(toks: list[str], spec: "CapabilitySpec", pinned: set[str]) -> str | None:
    """None if `toks` is exactly `<Trait>(a1, ..., an)` (or `<Trait>.apply(...)`) where the i-th argument fits `apply`'s
    i-th parameter p (T054b review B3): the handle mapped to member p by a bare `capability-map: <handle> -> p` line,
    else the handle named p; or, for a parameter typed by an enum the capability file declares, a path to one of its
    cases (`<Trait>.E.Case`). A named argument must name p."""
    params, n = spec.apply_params, len(toks)
    head = [spec.name, "("] if toks[1:2] == ["("] else [spec.name, ".", "apply", "("]
    if toks[: len(head)] != head or not toks or toks[-1] != ")":
        return f"must be exactly `{spec.name}(<one argument per apply parameter>)`"
    args, cur, depth = [], [], 0
    for t in toks[len(head) : -1]:
        if t in "([{":
            depth += 1
        elif t in ")]}":
            depth -= 1
            if depth < 0:
                return "continues after the constructor call"
        if t == "," and depth == 0:
            args.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        args.append(cur)
    if depth != 0:
        return "is unbalanced"
    if len(args) != len(params):
        return f"passes {len(args)} arguments; `{spec.name}.apply` takes {len(params)}: {', '.join(p for p, _ in params)}"
    bare = {lhs[0]: rhs[2] for lhs, rhs in spec.maps.items() if len(lhs) == 1}
    by_member = {m: h for h, m in bare.items()}
    for k, ((p, ptype), arg) in enumerate(zip(params, args)):
        if len(arg) >= 2 and arg[1] == "=":
            if arg[0] != p:
                return f"names argument {k + 1} `{arg[0]}`, but `apply`'s parameter {k + 1} is `{p}`"
            arg = arg[2:]
        enum = next((e for e in spec.enums if re.fullmatch(r"(?:" + re.escape(spec.name) + r"\.)?" + re.escape(e), ptype)), None)
        if enum:
            ok = [[spec.name, ".", enum, ".", c] for c in spec.enums[enum]] + [[enum, ".", c] for c in spec.enums[enum]]
            if arg not in ok:
                return f"passes `{''.join(arg)}` for `{p}: {ptype}`: it must be a case of the declared enum ({spec.name}.{enum}.<Case>)"
            continue
        want = by_member.get(p, p)
        if not is_handle_root(want, pinned):
            return f"has `apply` parameter `{p}`, which is neither mapped from a storage handle nor typed by a declared enum"
        if arg != [want]:
            return f"passes `{''.join(arg)}` as argument {k + 1} (`{p}`); it must be the handle `{want}`"
    return None


def core_init_extension_problem(toks: list[str], old_toks: list[str], spec: "CapabilitySpec", base: "CapabilitySpec",
                                pinned: set[str]) -> str | None:
    """T060b (D-T060-1): the core's accessor after an extension. Each argument of an `apply` parameter the capability
    already had is token-identical to the old accessor's argument for it; each new parameter's argument follows
    `core_init_problem`'s rule (its mapped handle or a declared enum case)."""
    def args_of(ts):
        head = [spec.name, "("] if ts[1:2] == ["("] else [spec.name, ".", "apply", "("]
        if ts[: len(head)] != head or ts[-1:] != [")"]:
            return None
        return group_items(ts[len(head):-1])
    new_args, old_args = args_of(toks), args_of(old_toks)
    if new_args is None or old_args is None:
        return f"must be exactly `{spec.name}(<one argument per apply parameter>)`"
    if len(new_args) != len(spec.apply_params) or len(old_args) != len(base.apply_params):
        return f"passes {len(new_args)} arguments; `{spec.name}.apply` takes {len(spec.apply_params)}"
    strip = lambda a, p: a[2:] if len(a) >= 2 and a[1] == "=" and a[0] == p else a
    old_by = {p: strip(a, p) for (p, _t), a in zip(base.apply_params, old_args)}
    only_new = []
    for (p, _t), a in zip(spec.apply_params, new_args):
        if len(a) >= 2 and a[1] == "=" and a[0] != p:
            return f"names argument `{a[0]}` where `apply`'s parameter is `{p}`"
        if p in old_by:
            if strip(a, p) != old_by[p]:
                return f"changes the existing argument for `{p}` (`{' '.join(old_by[p])}` -> `{' '.join(strip(a, p))}`)"
        else:
            only_new.append(p)
    sub = CapabilitySpec.__new__(CapabilitySpec)
    sub.__dict__.update(spec.__dict__)
    sub.apply_params = [(p, t) for p, t in spec.apply_params if p in only_new]
    picked = [a for (p, _t), a in zip(spec.apply_params, new_args) if p in only_new]
    if not picked:
        return None
    return core_init_problem([spec.name, "("] + [t for k, a in enumerate(picked) for t in ([","] if k else []) + a] + [")"],
                             sub, pinned)


def substitution_check(path: str, old: str, new: str, cap: "CapabilitySpec | None", drop_old=frozenset(),
                       drop_new=frozenset(), extra=()) -> tuple[list[str], int]:
    """Whole-file token alignment of `old` and `new` (comments, whitespace and import statements ignored, plus the
    excised lines), where a mapped handle sequence in `old` may stand as `<accessor>.<member>` in `new`, and the
    statement skeleton (which tokens start a statement line, at which indentation) is the same. Returns (problems,
    number of substitutions)."""
    ot = drop_lines(scala_tokens(old), set(drop_old) | import_lines(old))
    nt = drop_lines(scala_tokens(new), set(drop_new) | import_lines(new))
    return align_tokens(path, ot, nt, old, new, list(cap.maps.items()) if cap else [],
                        (getattr(cap, "alias_maps", list)() if cap else []) + list(extra))


def align_tokens(path: str, ot: list, nt: list, old: str, new: str, map_items, extra_maps=(), sites=None) -> tuple[list[str], int]:
    """The alignment behind `substitution_check` (T054b), on token lists the caller has already filtered: equal tokens,
    or a mapped handle sequence `lhs` in `old` standing as `rhs` in `new`, with the same statement skeleton. `extra_maps`
    are matched like `map_items` but not listed in the error message (T060b: the accessor-alias forms)."""
    ost, nst = statement_starts(ot, old), statement_starts(nt, new)
    ol, nl = old.splitlines(), new.splitlines()
    maps = sorted(list(map_items) + list(extra_maps), key=lambda kv: -len(kv[0]))
    shown = sorted({lhs[0] for lhs, _rhs in map_items})
    by_root: dict[str, list] = {}
    for lhs, rhs in maps:
        by_root.setdefault(lhs[0], []).append((lhs, rhs))

    def where(i, j):
        o = ol[ot[i].line - 1].strip() if i < len(ot) else "<end of file>"
        nw = nl[nt[j].line - 1].strip() if j < len(nt) else "<end of file>"
        return (f"old line {ot[i].line if i < len(ot) else '-'} `{o[:90]}` / new line {nt[j].line if j < len(nt) else '-'} "
                f"`{nw[:90]}`")

    def skeleton(i, j, ilen, jlen):
        so, sn = ost.get(i), nst.get(j)
        if (so is None) != (sn is None):
            return "a statement starts a line on one side only (re-wrapped or merged statements)"
        if so is not None and so != sn:
            return f"statement indentation changed ({so} -> {sn})"
        if any(k in ost for k in range(i + 1, i + ilen)) or any(k in nst for k in range(j + 1, j + jlen)):
            return "a statement starts inside a substituted handle expression"
        return None

    i = j = subs = 0
    while i < len(ot) and j < len(nt):
        step = None
        # A map is tried first: an accessor-alias entry (T060b) starts with the same token on both sides. For a handle
        # entry the first tokens differ (`handle` vs the accessor), so this order changes nothing for T054b's maps.
        if ot[i].t in by_root and (i == 0 or ot[i - 1].t != "."):
            for lhs, rhs in by_root[ot[i].t]:
                if tuple(t.t for t in ot[i : i + len(lhs)]) == lhs and tuple(t.t for t in nt[j : j + len(rhs)]) == rhs:
                    step = (len(lhs), len(rhs))
                    subs += 1
                    if sites is not None:
                        sites.append((lhs[0], nt[j].line))
                    break
        if step is None and ot[i].t == nt[j].t:
            step = (1, 1)
        if step is None:
            mapped = f"; mapped handles: {', '.join(shown)}" if shown else "; no capability map applies to this file"
            return [f"{path}: change is not a mapped handle substitution: {where(i, j)}{mapped}"], subs
        why = skeleton(i, j, *step)
        if why:
            return [f"{path}: {why}: {where(i, j)}"], subs
        i, j = i + step[0], j + step[1]
    if i < len(ot) or j < len(nt):
        return [f"{path}: {'removed' if i < len(ot) else 'added'} code that is not a mapped handle substitution: {where(i, j)}"], subs
    return [], subs



def member_blocks(text: str, trait: str, name: str) -> list[tuple[set[int], str]]:
    """decl_blocks of `name`, limited to direct members (indent 2) of the top-level `trait`."""
    lines, start, end = code_lines(text), None, None
    for i, l in enumerate(lines):
        if indent_of(l) == 0 and l.strip():
            if env_trait_re(trait).match(l.strip()):
                start = i
            elif start is not None and end is None and not l.strip().startswith(("end", ")", "}", "@")):
                end = i
    if start is None:
        return []
    end = end if end is not None else len(lines)
    return [(ls, d) for ls, d in decl_blocks(text, name) if start < min(ls) <= end and indent_of(lines[min(ls) - 1]) == 2]


# --------------------------------------------------------------------------- adoption mode (spec 016 T060b)
#
# A coordinator narrowing (C-*) moves the coordinator from raw storage parameters onto the `StateIngest` capability.
# That changes lines steps 1, 2 and 6 cannot admit: the Impl constructor, the companion `apply`, its `new <X>Impl(...)`
# call, the `ChildFactories` method, the spawn sites and the in-coordinator call sites (coordinator-characterization.md
# §6.2, G1-G6). The commit trailer
#     # adopts: StateIngest <Unit>
# on a refactor/snap-016-* commit selects this check. <Unit> is a coordinator (ByteCodeCoordinator, AccountRange...,
# StorageRange..., TrieNodeHealing...) or `RecoveryActors` (user decision D-T060-2: SyncController builds one
# `StateIngest` and hands it to BytecodeRecoveryActor and StorageRecoveryActor). The commit is either the slice's
# narrowing commit (step 6 then runs inside this check, see below) or a standalone commit (no move, no narrowing).
#
# Every changed src/main .scala file must belong to the unit, and changes only as follows (token level: comments,
# whitespace and import statements are ignored, and the statement skeleton must stay the same, as in capability mode):
#   - declarations: the unit's adoptable declarations (coordinator: `class <X>Impl`, the companion `apply`, the
#     `ChildFactories` method; RecoveryActors: the recovery objects' `def`s, and SyncController `def`s) may drop
#     storage-typed parameters and take one `stateIngest: StateIngest` at the position of the first dropped one. Kept
#     parameters keep their names, types, defaults and order. Each dropped parameter maps to a parameterless
#     `StateIngest` member of the same type: a `// adopt-map: <param> -> <member>` line in the unit's file, or a bare
#     `capability-map:` entry of the capability. The `ChildFactories` method keeps `apply`'s parameter list exactly
#     (names, defaults, the `stateIngest` type), so `ChildFactoriesSpec` (protected) holds by construction;
#   - calls to an adoptable declaration (positional or named): the dropped parameters' arguments go, all of them or
#     none, and one `stateIngest` argument takes the first one's place. A dropped argument must be `stateIngest.<member>`
#     for its parameter's member, or a name equal to it in that file (a dropped parameter of the enclosing declaration;
#     in SyncController a handle its own `stateIngest` is built from). Kept arguments are unchanged;
#   - substitutions: in the coordinator file and its module directory (or a recovery actor file), a dropped parameter
#     may stand as `stateIngest.<member>`;
#   - SyncController (RecoveryActors) adds one `stateIngest` declaration whose initializer is token-identical to
#     SNAPSyncController's, plus declarations of the handles that initializer names which SyncController lacks
#     (`pathNodeStorageOpt`, `validatorFactory`), each derived exactly as SNAPSyncController derives it (a `val`
#     initializer or a parameter default, token-identical). Its own `def`s may also gain a `stateIngest` parameter;
#   - StateIngest.scala (RecoveryActors only): the trait's and the object's `private[snap]` become `private[sync]`.
# In a narrowing commit, the lines step 6 admits (type headers and self-types, abstract members of State/Api/capability
# traits) are dropped first, and visibility modifiers may change; anything else must be one of the rewrites above. This
# is stricter than step 6's pairing of normalised lines (no result-type, `val`/`def` or line-order changes).
#
# A commit without the trailer that drops a storage-typed parameter from (or adds `StateIngest` to) one of these
# declarations, or widens the capability's visibility, fails `[adopts]` (G8: the commit that lowers the total is never
# the least-checked one).

SYNC_PKG = "src/main/scala/com/chipprbots/ethereum/blockchain/sync"
SYNC_CONTROLLER = f"{SYNC_PKG}/SyncController.scala"
RECOVERY_FILES = {f"{SYNC_PKG}/BytecodeRecoveryActor.scala": "BytecodeRecoveryActor",
                  f"{SYNC_PKG}/StorageRecoveryActor.scala": "StorageRecoveryActor"}
CHILD_FACTORIES = f"{SNAP}/controller/ChildFactories.scala"
SPAWN_FILES = (f"{SNAP}/controller/DownloadSupervisor.scala", f"{SNAP}/controller/HealingOrchestrator.scala")
ADOPT_COORDINATORS = {"ByteCodeCoordinator": "bytecode", "AccountRangeCoordinator": "account",
                      "StorageRangeCoordinator": "storage", "TrieNodeHealingCoordinator": "healing"}
RECOVERY_UNIT = "RecoveryActors"
ADOPT_CAP = "StateIngest"
ADOPT_ACCESSOR = "stateIngest"
ADOPT_MAP_RE = re.compile(r"^\s*(?://+|/?\*+)\s*adopt-map:\s*(\S+)\s*->\s*(\S+)\s*$")
PARAM_SKIP = {"val", "var", "using", "implicit", "final", "override", "inline", "erased"}
GROUP_MARK = "\x00adopt:"


def coordinator_file(x: str) -> str:
    return f"{SNAP}/actors/{x}.scala"


def match_close(toks, k: int):
    """Index of the `)` closing the `(` at toks[k], or None."""
    d = 0
    for j in range(k, len(toks)):
        t = toks[j].t
        if t == "(":
            d += 1
        elif t == ")":
            d -= 1
            if d == 0:
                return j
    return None


def group_items(ts: list[str]) -> list[list[str]]:
    """Top-level comma-separated items of a parenthesised group's contents (a trailing comma is ignored)."""
    out, cur, d = [], [], 0
    for t in ts:
        if t in ("(", "[", "{", "${"):
            d += 1
        elif t in (")", "]", "}"):
            d -= 1
        if t == "," and d == 0:
            out.append(cur)
            cur = []
        else:
            cur.append(t)
    out.append(cur)
    return [i for i in out if i]


def split_eq(ts: list[str]):
    """(before, after) the first top-level `=` token; after is None if there is none."""
    d = 0
    for k, t in enumerate(ts):
        if t in ("(", "[", "{", "${"):
            d += 1
        elif t in (")", "]", "}"):
            d -= 1
        elif t == "=" and d == 0:
            return ts[:k], ts[k + 1:]
    return ts, None


def parse_param(item: list[str]):
    """(name, type tokens, default tokens or None, item tokens) of one parameter, or None."""
    k = 0
    while k < len(item):
        if item[k] in PARAM_SKIP:
            k += 1
        elif item[k] in ("private", "protected"):
            k += 1
            if k < len(item) and item[k] == "[":
                while k < len(item) and item[k] != "]":
                    k += 1
                k += 1
        else:
            break
    if k + 1 >= len(item) or item[k + 1] != ":" or not IDENT_RE.match(item[k]):
        return None
    typ, default = split_eq(item[k + 2:])
    return (item[k], tuple(typ), tuple(default) if default is not None else None, tuple(item))


def parse_arg(item: list[str]):
    """(label or None, value tokens) of one argument."""
    if len(item) >= 3 and IDENT_RE.match(item[0]) and item[1] == "=":
        return item[0], tuple(item[2:])
    return None, tuple(item)


def bind_args(args, names: list[str]):
    """The parameter name each argument binds to (named, or by position), or None if one does not bind."""
    out = []
    for k, (label, _v) in enumerate(args):
        if label is None:
            if k >= len(names):
                return None
            out.append(names[k])
        elif label in names:
            out.append(label)
        else:
            return None
    return out


def vis_free(toks: list) -> list:
    """`toks` without visibility modifiers (`private`, `protected`, with an optional `[qualifier]`): step 6's norm_sig
    strips them, so a narrowing commit may change them."""
    out, k = [], 0
    while k < len(toks):
        if toks[k].t in ("private", "protected"):
            k += 1
            if k + 2 < len(toks) and toks[k].t == "[" and toks[k + 2].t == "]":
                k += 3
            continue
        out.append(toks[k])
        k += 1
    return out


def step6_lines(text: str) -> set[int]:
    """The lines step 6 admits in a narrowing commit wherever they change: type headers and self-types
    (`header_regions` + HEADER_EDIT_RE) and abstract declarations inside State/Api/capability traits."""
    hdr, decl, out = header_regions(text), declaration_only_regions(text), set()
    for no, l in enumerate(text.splitlines(), 1):
        if (no in hdr and HEADER_EDIT_RE.search(l) and header_parents_ok(l)) or (no in decl and abstract_decl(l)):
            out.add(no)
    return out


def derivations(text: str, name: str) -> list[tuple[str, ...]]:
    """How `text` (SNAPSyncController) derives `name`: each `val`/`def` initializer, and each parameter default
    (`name: T = d`), as token tuples."""
    out = []
    for _ls, decl in decl_blocks(text, name):
        init = top_level_initializer(strip_result_type(VIS_RE.sub("", decl)))
        if init is not None:
            out.append(tuple(t.t for t in scala_tokens(init)))
    toks = [t.t for t in scala_tokens(text)]
    for k in range(1, len(toks) - 1):
        if toks[k] == name and toks[k - 1] in ("(", ",") and toks[k + 1] == ":":
            d, j = 0, k + 2
            while j < len(toks):
                t = toks[j]
                if t in ("(", "[", "{"):
                    d += 1
                elif t in (")", "]", "}"):
                    if d == 0:
                        break
                    d -= 1
                elif d == 0 and t in (",", "="):
                    break
                j += 1
            if j < len(toks) and toks[j] == "=":
                e, dd = j + 1, 0
                while e < len(toks):
                    t = toks[e]
                    if t in ("(", "[", "{"):
                        dd += 1
                    elif t in (")", "]", "}"):
                        if dd == 0:
                            break
                        dd -= 1
                    elif dd == 0 and t == ",":
                        break
                    e += 1
                out.append(tuple(toks[j + 1:e]))
    return out


def accessor_init(text: str):
    """(lines, initializer tokens, declaration) of each `stateIngest` declaration in `text`."""
    out = []
    for ls, decl in decl_blocks(text, ADOPT_ACCESSOR):
        init = top_level_initializer(strip_result_type(VIS_RE.sub("", decl)))
        out.append((ls, tuple(t.t for t in scala_tokens(init)) if init is not None else None, decl))
    return out


def mirror_accessor(path: str, old: str, new: str, core: str, spec: "CapabilitySpec", lazy: dict | None = None):
    """SyncController's own `stateIngest` (D-T060-2): one declaration whose initializer is token-identical to the core's,
    plus declarations of the handles it names that the file lacks, each derived exactly as the core derives it. A lazy
    healing handle (`lazy`, from `lazy_handles`; #1584) of an `Option` type is instead declared `val h: <its type> =
    None`, and SyncController may not read its member. Returns (problems, drop_old, drop_new, handle -> member)."""
    probs, drop_old, drop_new, equiv = [], set(), set(), {}
    lazy = lazy or {}
    core_acc = [a for a in accessor_init(core) if a[1] is not None]
    if len(core_acc) != 1:
        return [f"{path}: SNAPSyncController must declare `{ADOPT_ACCESSOR}` exactly once to mirror it, found {len(core_acc)}"], \
            drop_old, drop_new, equiv
    want = core_acc[0][1]
    new_acc, old_acc = accessor_init(new), accessor_init(old)
    if len(new_acc) != 1:
        probs.append(f"{path}: declares `{ADOPT_ACCESSOR}` {len(new_acc)} times; it builds exactly one")
        return probs, drop_old, drop_new, equiv
    ls, init, decl = new_acc[0]
    if not re.match(r"^(?:(?:" + MODIFIER + r")\s+)*val\s+" + ADOPT_ACCESSOR + r"\s*:\s*" + ADOPT_CAP + r"\b", decl):
        probs.append(f"{path}: declare it a plain `val {ADOPT_ACCESSOR}: {ADOPT_CAP} = ...` as the core does (not `def`, "
                     f"`lazy val` or `var`), found `{decl[:90]}`")
    if init != want:
        probs.append(f"{path}: `{ADOPT_ACCESSOR}` initializer `{' '.join(init or ())[:90]}` is not SNAPSyncController's "
                     f"`{' '.join(want)[:90]}` (same handles, same order)")
    if ";" in decl:
        probs.append(f"{path}: `{decl[:80]}` shares its line with another statement")
    drop_new |= ls
    for ols, _i, _d in old_acc:
        drop_old |= ols
    # the handles the initializer names, by apply parameter (= member) position
    args = group_items(list(want[want.index("(") + 1: -1])) if "(" in want and want[-1:] == (")",) else []
    for (member, _t), arg in zip(spec.apply_params, args):
        if len(arg) != 1 or not IDENT_RE.match(arg[0]):
            continue
        h = arg[0]
        equiv[(h,)] = member
        nb, ob = decl_blocks(new, h), decl_blocks(old, h)
        old_texts = [d for _l, d in ob]
        for bls, bdecl in nb:
            if bdecl in old_texts:
                continue  # an existing declaration of the handle: unchanged code
            binit = top_level_initializer(strip_result_type(VIS_RE.sub("", bdecl)))
            got = tuple(t.t for t in scala_tokens(binit)) if binit is not None else None
            if h in lazy:
                parts = val_parts(bdecl)
                want_t = lazy[h]["type"]
                if parts is None or parts[1] != "val" or parts[0] or parts[3] != ("None",) or parts[2] != want_t \
                        or want_t[:2] != ("Option", "["):
                    probs.append(f"{path}: adds `{bdecl[:80]}`; the core's `{h}` is a lazy healing handle, mirrored as "
                                 f"`val {h}: {' '.join(want_t)} = None` (an `Option`: recovery builds no healing store)")
                elif ";" in bdecl:
                    probs.append(f"{path}: `{bdecl[:80]}` shares its line with another statement")
                drop_new |= bls
                continue
            if not re.match(r"^(?:(?:" + MODIFIER + r")\s+)*val\s+" + re.escape(h) + r"\b", bdecl):
                probs.append(f"{path}: adds `{bdecl[:80]}`; a mirrored handle is a plain `val` (not `def`, `lazy val` or "
                             "`var`: one instance, built with the capability)")
            elif got is None or got not in derivations(core, h):
                probs.append(f"{path}: adds `{bdecl[:80]}`, which is not derived exactly as SNAPSyncController derives `{h}`")
            elif ";" in bdecl:
                probs.append(f"{path}: `{bdecl[:80]}` shares its line with another statement")
            drop_new |= bls
        for bls, bdecl in ob:
            if bdecl not in [d for _l, d in nb]:
                probs.append(f"{path}: changes or removes `{bdecl[:80]}`")
    if lazy:
        nt = [t.t for t in scala_tokens(new)]
        for h, info in sorted(lazy.items()):
            m = info["member"]
            if any(nt[k:k + 3] == [ADOPT_ACCESSOR, ".", m] for k in range(len(nt) - 2)):
                probs.append(f"{path}: reads `{ADOPT_ACCESSOR}.{m}`, which its capability mirrors as `None` (`{h}` is a lazy "
                             "healing handle of the core): recovery builds and reads no healing store")
    return probs, drop_old, drop_new, equiv


# --------------------------------------------------------------------------- lazy healing handles (C-H1, #1584)
#
# The mirror/lazy-handle limit (#1584): a capability extension may add a member whose production value is a `lazy val`
# of a controller trait (HealingOrchestrator's healing stores). Building the capability must not build the store, and
# recovery's mirrored capability must not build one either. In a `# capability:` extension:
#   (a) a handle the core's accessor passes that is declared as a `lazy val` in the core or a snap/controller file (one
#       declaration) feeds a by-name `apply` parameter (`p: => T`, T exactly the handle's declared type): a strict one
#       would build the store with the capability. The capability's forwarder then reads the lazy val on each call;
#   (b) in its file the handle's declaration may only widen `private` to `private[snap]` (the core must name it), or it
#       is the Option lift of a removed `private lazy val g: T = e` into `private[snap] lazy val h: Option[T] = Some(e)`,
#       where each `Some(g)` in that file then stands as `<accessor>.<member>`: the same instance, built on the same
#       first read. Any other use of `g` fails the token check;
#   (c) SyncController's mirror declares such a handle `val h: <its declared type> = None` when the type is an
#       `Option` (recovery never spawns the healing coordinator, so its capability hands out none), and may not read
#       that member.

VAL_DECL_RE = re.compile(r"^((?:(?:" + MODIFIER + r")\s+)*)(lazy\s+val|val)\s+(\w+)\s*:\s*(.+)$")


def val_parts(decl: str):
    """(modifiers, "val" | "lazy val", type tokens, initializer tokens, name) of a flattened `<mods> [lazy] val name: T =
    e`, or None (no type, no initializer)."""
    init = top_level_initializer(decl)
    if init is None:
        return None
    head = decl[: len(decl) - len(init)].rstrip()
    if not head.endswith("="):
        return None
    m = VAL_DECL_RE.match(head[:-1].strip())
    if not m:
        return None
    return (m.group(1).split(), re.sub(r"\s+", " ", m.group(2)), tuple(t.t for t in scala_tokens(m.group(4))),
            tuple(t.t for t in scala_tokens(init)), m.group(3))


def wraps_in(toks: tuple, fn: str, brackets: str = "()"):
    """The argument tokens if `toks` is exactly `fn(<arg>)` (or `fn[<arg>]`, one balanced group), else None."""
    if toks[:2] != (fn, brackets[0]) or toks[-1:] != (brackets[1],):
        return None
    depth = 0
    for k, t in enumerate(toks[1:], 1):
        depth += t in ("(", "[", "{")
        depth -= t in (")", "]", "}")
        if depth == 0 and k != len(toks) - 1:
            return None
    return toks[2:-1]


def lazy_handles(g: "Git", rev: str, spec: "CapabilitySpec") -> tuple[dict, list[str]]:
    """Rule (a): the handles the core's `stateIngest` initializer passes that are `lazy val`s of the core or a
    snap/controller file at `rev`, as {handle: {file, member, type, param}}, and the problems found."""
    core = g.show(rev, RATCHET_FILES[0]) or ""
    acc = [a for a in accessor_init(core) if a[1] is not None]
    if len(acc) != 1 or "(" not in acc[0][1] or acc[0][1][-1:] != (")",):
        return {}, []
    want = acc[0][1]
    args = group_items(list(want[want.index("(") + 1: -1]))
    texts = {f: g.show(rev, f) or "" for f in [RATCHET_FILES[0]] + g.tree_files(rev, [f"{SNAP}/controller"])}
    out, probs = {}, []
    for (p, ptype), arg in zip(spec.apply_params, args):
        if len(arg) != 1 or not IDENT_RE.match(arg[0]):
            continue
        h = arg[0]
        decls = [(f, d) for f, t in texts.items() for _ls, d in decl_blocks(t, h)]
        lazies = [(f, parts) for f, d in decls if (parts := val_parts(d)) and parts[1] == "lazy val" and parts[4] == h]
        if not lazies:
            continue
        f, parts = lazies[0]
        if len(decls) != 1:
            probs.append(f"`{h}` (the core's argument for `{p}`) is a `lazy val` declared {len(decls)} times in the core and "
                         "snap/controller: a lazy healing handle is declared once")
            continue
        pt = tuple(t.t for t in scala_tokens(ptype))
        if pt[:1] != ("=>",):
            probs.append(f"{spec.path}: `apply` parameter `{p}: {ptype}` is fed by the lazy val `{h}` ({f}); make it by-name "
                         f"(`{p}: => {' '.join(parts[2])}`), or building the capability builds the store")
            continue
        if pt[1:] != parts[2]:
            probs.append(f"{spec.path}: `apply` parameter `{p}: {ptype}` does not have the type of the lazy val `{h}` "
                         f"(`{' '.join(parts[2])}`, {f})")
            continue
        member = spec.aliases.get(h)
        if member is None:
            probs.append(f"{spec.path}: the lazy healing handle `{h}` needs a bare `// capability-map: {h} -> <member>` line")
            continue
        out[h] = {"file": f, "member": member, "type": parts[2], "param": p}
    return out, probs


def lazy_handle_edits(path: str, old: str, new: str, lazy: dict, accessor: str):
    """Rule (b) for one changed file: (problems, drop_old, drop_new, extra maps, notes)."""
    probs, drop_old, drop_new, extra, notes = [], set(), set(), [], []
    for h, info in sorted(lazy.items()):
        if info["file"] != path:
            continue
        nb, ob = decl_blocks(new, h), decl_blocks(old, h)
        if len(nb) != 1 or len(ob) > 1:
            probs.append(f"{path}: the lazy healing handle `{h}` is declared {len(ob)} time(s) before and {len(nb)} after")
            continue
        nls, nd = nb[0]
        np_ = val_parts(nd)
        if ob:
            ols, od = ob[0]
            if od == nd:
                continue
            op = val_parts(od)
            if op and np_ and op[1:] == np_[1:] and op[0] == ["private"] and np_[0] == ["private[snap]"]:
                drop_old |= ols
                drop_new |= nls
                notes.append(f"`{h}` widens from `private` to `private[snap]` (the core names it)")
            else:
                probs.append(f"{path}: changes the lazy healing handle `{h}` beyond widening `private` to `private[snap]`: "
                             f"`{od[:70]}` -> `{nd[:70]}`")
            continue
        inner_t = wraps_in(np_[2], "Option", "[]") if np_ else None
        inner_e = wraps_in(np_[3], "Some") if np_ else None
        if np_ is None or np_[0] != ["private[snap]"] or inner_t is None or inner_e is None:
            probs.append(f"{path}: adds the lazy healing handle `{nd[:80]}`; a new one is the Option lift "
                         f"`private[snap] lazy val {h}: Option[T] = Some(e)` of a removed `private lazy val g: T = e`")
            continue
        cands = []
        for gname in sorted(set(re.findall(r"\blazy\s+val\s+(\w+)", strip_comments(old)))):
            if gname == h or decl_blocks(new, gname):
                continue
            for gls, gd in decl_blocks(old, gname):
                gp = val_parts(gd)
                if gp and gp[0] == ["private"] and gp[1] == "lazy val" and gp[2] == inner_t and gp[3] == inner_e:
                    cands.append((gname, gls))
        if len(cands) != 1:
            probs.append(f"{path}: `{h}` = `Some(...)` lifts {len(cands)} removed `private lazy val`s of type "
                         f"`{' '.join(inner_t)}` with that initializer; the Option lift replaces exactly one")
            continue
        gname, gls = cands[0]
        drop_old |= gls
        drop_new |= nls
        extra.append((("Some", "(", gname, ")"), (accessor, ".", info["member"])))
        notes.append(f"`{gname}` lifts into `{h}`; `Some({gname})` stands as `{accessor}.{info['member']}`")
    return probs, drop_old, drop_new, extra, notes


PREDEF_NAMES = frozenset({
    "Some", "None", "Option", "List", "Nil", "Seq", "IndexedSeq", "Vector", "Map", "Set", "Array", "Iterator", "Iterable",
    "String", "Int", "Long", "Short", "Byte", "Char", "Boolean", "Double", "Float", "Unit", "Any", "AnyRef", "AnyVal",
    "Nothing", "Null", "BigInt", "BigDecimal", "Either", "Left", "Right", "Tuple2", "Tuple3", "Function1", "Ordering",
    "Numeric", "Range", "StringContext", "Predef", "println", "require", "assert", "identity", "implicitly", "summon",
})


def adoption_import_problems(path: str, old: str, new: str, spec: "CapabilitySpec", extra=frozenset()) -> list[str]:
    """T060b (prism): an adopted file may add a named import of the capability itself, or a named import of a name the
    file referred to only qualified before (so no existing bare use is rebound). No wildcard, `given`, rename, or
    other import: any of them can change what an unchanged name in the file resolves to."""
    probs = []
    pm = re.search(r"^package\s+([\w.]+)\s*$", spec.text, re.M)
    cap_path = f"{pm.group(1)}.{spec.name}" if pm else spec.name
    code = [t.t for t in drop_lines(scala_tokens(old), import_lines(old))]
    old_stmts = {re.sub(r"\s+", " ", l.strip()) for l in code_lines(old) if l.strip().startswith("import ")}
    for l in code_lines(new):
        st = re.sub(r"\s+", " ", l.strip())
        if st.startswith("import ") and st not in old_stmts and re.search(r"=>|\bas\b", st):
            probs.append(f"{path}: adds renaming import `{st[:80]}`; an adoption adds named imports only")
    for e in sorted(import_entries(new) - import_entries(old)):
        if is_wild_or_given_import(e):
            probs.append(f"{path}: adds wildcard or given import `{e}`; an adoption adds named imports only")
            continue
        segs = e.split(".")
        name = segs[-1]
        if name == spec.name:
            if not (e == cap_path or cap_path.endswith("." + e)):
                probs.append(f"{path}: imports `{e}`, which is not the capability `{cap_path}`")
            continue
        bare = any(t == name and (k == 0 or code[k - 1] != ".") for k, t in enumerate(code))
        if name in PREDEF_NAMES:
            probs.append(f"{path}: adds import `{e}`, which rebinds the standard name `{name}`")
            continue
        if e in extra and not bare:
            continue  # the core's own import of a type the mirrored declarations name (SyncController, D-T060-2)
        qualified = any(code[k:k + 2 * n - 1] == [x for s in segs[-n:] for x in (s, ".")][:-1]
                        for n in range(2, len(segs) + 1) for k in range(len(code)))
        if bare or not qualified:
            probs.append(f"{path}: adds import `{e}`: an adoption may add only the capability's import, or one of a name "
                         "the file referred to only qualified before (this one " +
                         ("would rebind its existing bare uses)" if bare else "is new to the file)"))
    return probs


BINDING_STARTERS = ("case", "val", "var", "for", "given")


def binding_lines(toks, name: str) -> list[int]:
    """Lines of `toks` that bind `name`, counting every form (over-approximate on purpose, T060b prism round 2): a
    parameter or typed binding (`name:`, including `using` and class `val` parameters), `val`/`var`/`def`/`lazy`/
    `given`/`case name`, a generator `name <-`, a lambda parameter `name =>`, and a pattern or tuple position (inside
    a `case`/`val`/`var`/`for` statement, before its `=>`, `=` or `<-`)."""
    out = []
    for k, t in enumerate(toks):
        if t.t != name or (k and toks[k - 1].t == "."):
            continue
        prev = toks[k - 1].t if k else ""
        nxt = toks[k + 1].t if k + 1 < len(toks) else ""
        hit = nxt in (":", "<-") or prev in ("val", "var", "def", "lazy", "given", "case") or \
            (nxt == "=>" and prev in ("(", ",", "{"))
        if not hit and prev in ("(", ","):
            j = k - 1  # a pattern position: back to the statement keyword on the same line, before any `=`/`=>`/`<-`
            while j >= 0 and toks[j].line == t.line and toks[j].t not in ("=", "=>", "<-"):
                if toks[j].t in BINDING_STARTERS:
                    hit = True
                    break
                j -= 1
        if hit:
            out.append(t.line)
    return out


def shadow_problems(path: str, new: str, sites, scope: str = "file") -> list[str]:
    """T060b (prism): a substitution `<dropped>` -> `stateIngest.<member>` is only sound where `<dropped>` meant the
    dropped parameter. With scope "file" (the coordinator and its modules) any remaining binding of a dropped name in
    the new file rejects the rewrites of that name; with scope "member" (a recovery actor, whose unchanged helpers keep
    their own raw parameters) a binding inside the same top-level member (indent <= 2) as a rewrite rejects it. No
    finer scope analysis: over-rejecting is the intended failure mode."""
    if not sites:
        return []
    toks, lines = scala_tokens(new), code_lines(new)

    def member(line: int) -> int:
        for no in range(line, 0, -1):
            l = lines[no - 1]
            if l.strip() and indent_of(l) <= 2 and not l.strip().startswith((")", "}", "]")):
                return no
        return 0

    probs, done = [], set()
    for n in sorted({n for n, _l in sites}):
        bl = binding_lines(toks, n)
        for _n, line in (x for x in sites if x[0] == n):
            hit = bl[0] if scope == "file" and bl else next((b for b in bl if member(b) == member(line)), None)
            if hit is not None and (n, hit) not in done:
                done.add((n, hit))
                probs.append(f"{path}:{line}: `{n}` -> `{ADOPT_ACCESSOR}.<member>`, but line {hit} binds another `{n}` "
                             f"{'in this file' if scope == 'file' else 'in the same member'}; the rewrite could change "
                             "which value is read (rename the binding first)")
    return probs


def companion_members(text: str, obj: str) -> set[str]:
    """Names declared as members (indent 2) of the top-level `object <obj>:` in `text`."""
    out, inside = set(), False
    for l in code_lines(text):
        if re.match(r"^(?:(?:" + MODIFIER + r")\s+)*object\s+" + re.escape(obj) + r"\b", l):
            inside = True
            continue
        if inside and l.strip() and indent_of(l) == 0:
            inside = False
        if inside and (m := re.match(r"^  (?:(?:" + MODIFIER + r")\s+|lazy\s+|inline\s+)*(?:val|def|var)\s+(\w+)", l)):
            out.add(m.group(1))
    return out


def qualified_default(tokens, obj: str, members: set[str]):
    """A default's tokens with `<obj>.` dropped from a reference to one of its companion members: `apply`, declared in
    the companion, writes `DefaultVisitedCap` where ChildFactories writes `TrieNodeHealingCoordinator.DefaultVisitedCap`
    (spec 016 C-H1). Both name the same constant, so ChildFactoriesSpec's comparison by value holds."""
    if tokens and len(tokens) >= 3 and tokens[0] == obj and tokens[1] == "." and tokens[2] in members:
        return tuple(tokens[2:])
    return tuple(tokens) if tokens is not None else None


class AdoptDecl:
    """One adoptable declaration's old and new parameter lists, as validated by `Adoption.declare`."""

    def __init__(self, key, old, new):
        self.key, self.old, self.new = key, old, new
        names_new = [p[0] for p in new]
        names_old = [p[0] for p in old]
        self.removed = [p for p in old if p[0] not in names_new]
        self.added = [p for p in new if p[0] not in names_old]
        self.member: dict[str, str] = {}

    @property
    def changed(self) -> bool:
        return bool(self.removed or self.added)


class Adoption:
    """`# adopts: StateIngest <Unit>` on one commit (see the section comment)."""

    def __init__(self, v: "Verifier", c: str, p: str, changed: dict[str, str], unit: str, narrowed: bool):
        self.v, self.g, self.c, self.p, self.changed, self.unit, self.narrowed = v, v.g, c, p, changed, unit, narrowed
        self.decls: dict = {}      # (path, decl name) -> AdoptDecl, for declarations whose parameters change
        self.seen: dict = {}       # (path, decl name) -> AdoptDecl, every adoptable declaration found
        self.cands: dict = {}      # (path, decl name) -> required indent of the declaration line, or None (any)
        self.objects: dict = {}    # object name -> path (its `apply` and members)
        self.classes: dict = {}    # class name -> key
        self.methods: dict = {}    # method name -> key (selected as `x.name(`)
        self.maps: dict = {}       # dropped parameter -> member (adopt-map lines and bare capability-map entries)
        self.texts: dict = {}

    def err(self, msg: str):
        self.v.err(self.c, "adopts", msg)

    def info(self, msg: str):
        self.v.info(self.c, f"[adopts] {msg}")

    def old(self, f):
        if f not in self.texts:
            self.texts[f] = (self.g.show(self.p, f) or "" if self.p != EMPTY_TREE else "", self.g.show(self.c, f) or "")
        return self.texts[f][0]

    def new(self, f):
        self.old(f)
        return self.texts[f][1]

    # -- the unit's files, declarations and map
    def run(self):
        g, c = self.g, self.c
        paths = find_capability_file(g, c, ADOPT_CAP)
        if len(paths) != 1:
            self.err(f"`trait {ADOPT_CAP}` must be declared in exactly one file under snap/ at this commit, found {len(paths)}")
            return
        self.cap_path = paths[0]
        self.spec = CapabilitySpec(ADOPT_CAP, paths[0], g.show(c, paths[0]) or "", None)
        for prob in self.spec.problems:
            self.err(prob)
        self.members = {n: d for n, d in self.spec.members.items() if not d.lstrip().startswith(("(", "["))}
        bare = {lhs[0]: rhs[2] for lhs, rhs in self.spec.maps.items() if len(lhs) == 1}
        self.maps.update(bare)
        coord = self.unit if self.unit in ADOPT_COORDINATORS else None
        if coord:
            cf, mod_dir = coordinator_file(coord), f"{SNAP}/actors/{ADOPT_COORDINATORS[coord]}/"
            roles = {cf: "coordinator", CHILD_FACTORIES: "factories", **{f: "spawn" for f in SPAWN_FILES},
                     **{f: "spawn" for f in RECOVERY_FILES}}
            self.cands = {(cf, f"class {coord}Impl"): None, (cf, "apply"): 2, (CHILD_FACTORIES, lower_camel(coord)): 2}
            self.objects = {coord: cf}
            self.classes = {f"{coord}Impl": (cf, f"class {coord}Impl")}
            self.methods = {lower_camel(coord): (CHILD_FACTORIES, lower_camel(coord))}
            map_files = [cf]
        else:
            mod_dir = None
            roles = {**{f: "recovery" for f in RECOVERY_FILES}, SYNC_CONTROLLER: "synccontroller", self.cap_path: "capvis"}
            self.objects = {o: f for f, o in RECOVERY_FILES.items()}
            for f in RECOVERY_FILES:
                for t in (self.old(f), self.new(f)):
                    for k, l in enumerate(t.splitlines()):
                        m = re.match(r"^  (?:(?:" + MODIFIER + r")\s+)*def\s+(\w+)\s*\(", l)
                        if m:
                            self.cands[(f, m.group(1))] = 2
            sc_old, sc_new = self.old(SYNC_CONTROLLER), self.new(SYNC_CONTROLLER)
            for t in (sc_old, sc_new):
                for m in re.finditer(r"^\s*(?:(?:" + MODIFIER + r")\s+)*def\s+(\w+)\s*\(", t, re.M):
                    self.cands.setdefault((SYNC_CONTROLLER, m.group(1)), None)
            map_files = list(RECOVERY_FILES)
        for f in map_files:
            for no, l in enumerate(self.new(f).splitlines(), 1):
                m = ADOPT_MAP_RE.match(l)
                if not m:
                    if "adopt-map:" in l:
                        self.err(f"{f}:{no}: unparseable map line (want `// adopt-map: <parameter> -> <member>`): `{l.strip()[:80]}`")
                    continue
                lhs, rhs = m.group(1), m.group(2)
                if lhs in bare and bare[lhs] != rhs:
                    self.err(f"{f}:{no}: adopt-map `{lhs} -> {rhs}` overrides the capability's bare entry "
                             f"`capability-map: {lhs} -> {bare[lhs]}`; a parameter with a handle's name maps as the handle does")
                    continue
                if not IDENT_RE.match(lhs) or rhs not in self.members:
                    self.err(f"{f}:{no}: adopt-map `{lhs} -> {rhs}`: the left side is a parameter name, the right side a "
                             f"parameterless member of {ADOPT_CAP} ({', '.join(sorted(self.members))})")
                    continue
                self.maps[lhs] = rhs
        for f, st in sorted(self.changed.items()):
            if not (f.startswith("src/main/") and f.endswith(".scala")):
                continue
            if f in roles or (mod_dir and f.startswith(mod_dir) and is_module(f)):
                if st != "M":
                    self.err(f"{'adds' if st == 'A' else 'deletes'} {f}: an adoption only edits existing files")
                continue
            self.err(f"changes {f}, which is not part of the `{self.unit}` adoption (allowed: "
                     f"{', '.join(sorted(roles))}{', ' + mod_dir + '*' if mod_dir else ''})")
        # declarations first: calls in any file need their callee's old and new parameter lists
        for f in sorted({k[0] for k in self.cands}):
            if self.changed.get(f) == "M" or f in (CHILD_FACTORIES,) or (coord and f == coordinator_file(coord)):
                self.declare(f, allow_pure_add=(f == SYNC_CONTROLLER))
        if coord:
            self.check_coordinator_decls(coord)
        elif not any(d.changed for d in self.decls.values()):
            self.err(f"`# adopts: {ADOPT_CAP} {self.unit}` but no recovery actor declaration takes `{ADOPT_ACCESSOR}`")
        for f, st in sorted(self.changed.items()):
            if st != "M" or not (f.startswith("src/main/") and f.endswith(".scala")):
                continue
            role = roles.get(f) or ("module" if mod_dir and f.startswith(mod_dir) else None)
            if role:
                self.check_file(f, role)

    def declare(self, f: str, allow_pure_add: bool):
        """Pair the adoptable declarations of `f` (old/new, by name and order) and validate each pair."""
        found = {}
        for side, text in (("old", self.old(f)), ("new", self.new(f))):
            toks = scala_tokens(text)
            lines = text.splitlines()
            for kind, key, _k, o, cl in self.find_groups(toks, f, lines, decl_only=True):
                found.setdefault(key, {"old": [], "new": []})[side].append(group_items([t.t for t in toks[o + 1:cl]]))
        for key, sides in sorted(found.items()):
            if len(sides["old"]) != len(sides["new"]):
                self.err(f"{f}: `{key[1]}` is declared {len(sides['old'])} time(s) before and {len(sides['new'])} after")
                continue
            entries = []
            for o_items, n_items in zip(sides["old"], sides["new"]):
                O, N = [parse_param(i) for i in o_items], [parse_param(i) for i in n_items]
                if None in O or None in N:
                    if o_items != n_items:
                        self.err(f"{f}: cannot parse the parameters of `{key[1]}`")
                    continue
                d = AdoptDecl(key, O, N)
                if O != N:
                    self.validate_decl(f, d, allow_pure_add)
                entries.append(d)
            if len(entries) > 1 and len({(tuple(d.old), tuple(d.new)) for d in entries}) > 1:
                self.err(f"{f}: `{key[1]}` is declared more than once with different adoptions; adopt one overload per commit")
            if entries:
                self.seen[key] = entries[0]
                if entries[0].old != entries[0].new:
                    self.decls[key] = entries[0]  # only a changed declaration's calls are checked as adopted lists

    def validate_decl(self, f: str, d: AdoptDecl, allow_pure_add: bool):
        name = d.key[1]
        on, nn = [p[0] for p in d.old], [p[0] for p in d.new]
        kept_o = [p for p in d.old if p[0] in nn]
        kept_n = [p for p in d.new if p[0] in on]
        if [p[3] for p in kept_o] != [p[3] for p in kept_n]:
            diff = next(((a, b) for a, b in zip(kept_o, kept_n) if a[3] != b[3]), (None, None))
            self.err(f"{f}: `{name}` changes or reorders a parameter it keeps"
                     + (f": `{' '.join(diff[0][3])[:70]}` -> `{' '.join(diff[1][3])[:70]}`" if diff[0] else ""))
        if d.added:
            a = d.added[0]
            if len(d.added) != 1 or a[0] != ADOPT_ACCESSOR:
                self.err(f"{f}: `{name}` adds {', '.join(p[0] for p in d.added)}; an adoption adds only `{ADOPT_ACCESSOR}: {ADOPT_CAP}`")
            elif a[1] != (ADOPT_CAP,):
                self.err(f"{f}: `{name}` declares `{ADOPT_ACCESSOR}: {' '.join(a[1])}`; the adopted parameter is "
                         f"`{ADOPT_ACCESSOR}: {ADOPT_CAP}` (an `Option[{ADOPT_CAP}]`, T075's healed-code check, is not supported yet)")
            elif a[2] is not None:
                self.err(f"{f}: `{name}`'s `{ADOPT_ACCESSOR}` parameter takes no default")
            if d.removed and len(d.added) == 1 and nn.index(a[0]) != on.index(d.removed[0][0]):
                self.err(f"{f}: `{name}` takes `{ADOPT_ACCESSOR}` at position {nn.index(a[0]) + 1}; it goes where the first "
                         f"dropped parameter `{d.removed[0][0]}` was (position {on.index(d.removed[0][0]) + 1})")
            if not d.removed and not allow_pure_add:
                self.err(f"{f}: `{name}` takes `{ADOPT_ACCESSOR}` but drops no storage parameter")
        elif d.removed and ADOPT_ACCESSOR not in on:
            self.err(f"{f}: `{name}` drops {', '.join(p[0] for p in d.removed)} without taking `{ADOPT_ACCESSOR}`")
        for p in d.removed:
            if p[0] == ADOPT_ACCESSOR:
                self.err(f"{f}: `{name}` drops `{ADOPT_ACCESSOR}`")
                continue
            if not storage_typed(" ".join(p[1])):
                self.err(f"{f}: `{name}` drops `{p[0]}: {' '.join(p[1])}`, which is not storage-typed; an adoption drops "
                         "storage parameters only")
                continue
            m = self.maps.get(p[0])
            if m is None or m not in self.members:
                self.err(f"{f}: `{name}` drops `{p[0]}`, which no `// adopt-map:` line or bare `capability-map:` entry maps "
                         f"to a parameterless {ADOPT_CAP} member")
                continue
            mtype = tuple(t.t for t in scala_tokens(self.members[m].strip()[1:]))
            if mtype != p[1]:
                self.err(f"{f}: `{name}` drops `{p[0]}: {' '.join(p[1])}`, but `{ADOPT_ACCESSOR}.{m}` is `{' '.join(mtype)}`")
                continue
            d.member[p[0]] = m

    def check_coordinator_decls(self, coord: str):
        cf = coordinator_file(coord)
        keys = [(cf, f"class {coord}Impl"), (cf, "apply"), (CHILD_FACTORIES, lower_camel(coord))]
        ds = [self.seen.get(k) for k in keys]
        for k, d in zip(keys, ds):
            if d is None:
                self.err(f"{k[0]}: `{k[1]}` not found; the `{coord}` adoption changes the Impl constructor, `apply` and the "
                         "ChildFactories method together")
        if None in ds:
            return
        if not any(d.changed for d in ds):
            self.err(f"`# adopts: {ADOPT_CAP} {coord}` but `{coord}` takes no `{ADOPT_ACCESSOR}`")
            return
        sets = [sorted(p[0] for p in d.removed) for d in ds]
        if sets[0] != sets[1] or sets[1] != sets[2]:
            self.err(f"the Impl constructor, `apply` and the ChildFactories method drop different parameters: {sets}")
        apply_d, fac_d = ds[1], ds[2]
        an, fn = [p[0] for p in apply_d.new], [p[0] for p in fac_d.new]
        if an != fn:
            self.err(f"{CHILD_FACTORIES}: `{lower_camel(coord)}` parameters {fn} must be `{coord}.apply`'s, in order: {an}")
        else:
            members = companion_members(self.new(coordinator_file(coord)), coord)
            for a, b in zip(apply_d.new, fac_d.new):
                if qualified_default(a[2], coord, members) != qualified_default(b[2], coord, members):
                    self.err(f"{CHILD_FACTORIES}: `{lower_camel(coord)}` default of `{a[0]}` differs from `apply`'s "
                             "(ChildFactoriesSpec compares them by position)")
                if a[0] == ADOPT_ACCESSOR and a[1] != b[1]:
                    self.err(f"{CHILD_FACTORIES}: `{lower_camel(coord)}` types `{ADOPT_ACCESSOR}` differently from `apply`")
        self.info(f"{coord}: drops " + ", ".join(f"{p[0]} -> {ADOPT_ACCESSOR}.{apply_d.member.get(p[0], '?')}"
                                                for p in apply_d.removed))

    # -- groups
    def find_groups(self, toks, path, lines, decl_only=False):
        out, k = [], 0
        while k < len(toks) - 1:
            if toks[k + 1].t == "(" and WORD_RE.fullmatch(toks[k].t):
                r = self.resolve(toks, k, path, lines, decl_only)
                if r and (not decl_only or r[0] == "decl"):
                    close = match_close(toks, k + 1)
                    if close is None:
                        break
                    out.append((r[0], r[1], k, k + 1, close))
                    k = close + 1
                    continue
            k += 1
        return out

    def resolve(self, toks, k, path, lines, decl_only=False):
        name, prev = toks[k].t, (toks[k - 1].t if k else "")
        if prev in ("def", "class"):
            key = (path, (f"class {name}" if prev == "class" else name))
            if key not in self.cands or (not decl_only and key not in self.decls):
                return None
            want = self.cands[key]
            ind = indent_of(lines[toks[k].line - 1]) if toks[k].line - 1 < len(lines) else -1
            return ("decl", key) if want is None or ind == want else None
        if prev == "new":
            key = self.classes.get(name)
        elif prev == ".":
            recv = toks[k - 2].t if k >= 2 else ""
            if name == "apply" and recv in self.objects:
                key = (self.objects[recv], "apply")
            elif recv in self.objects and (self.objects[recv], name) in self.cands:
                key = (self.objects[recv], name)
            elif name in self.objects:
                key = (self.objects[name], "apply")
            else:
                key = self.methods.get(name)
        elif name in self.objects:
            key = (self.objects[name], "apply")
        else:
            key = (path, name) if (path, name) in self.cands else None
        return ("call", key) if key in self.decls else None

    def label(self, key) -> str:
        if key[1] == "apply":
            return next((f"{o}.apply" for o, f in getattr(self, "objects", {}).items() if f == key[0]), "apply")
        return key[1].replace("class ", "new ")

    def call_problem(self, d: AdoptDecl, o_items, n_items, equiv) -> str | None:
        name = self.label(d.key)
        if not d.changed:
            return None if o_items == n_items else f"changes a call to `{name}`, whose parameters do not change"
        A, B = [parse_arg(i) for i in o_items], [parse_arg(i) for i in n_items]
        ba, bb = bind_args(A, [p[0] for p in d.old]), bind_args(B, [p[0] for p in d.new])
        if ba is None or bb is None:
            return f"a call to `{name}` whose arguments do not bind to its parameters"
        removed = {p[0] for p in d.removed}
        new_si = bool(d.added)
        rem = [(n, a) for n, a in zip(ba, A) if n in removed]
        kept_a = [(n, a) for n, a in zip(ba, A) if n not in removed]
        si_b = [(k, a) for k, (n, a) in enumerate(zip(bb, B)) if new_si and n == ADOPT_ACCESSOR]
        kept_b = [(n, a) for n, a in zip(bb, B) if not (new_si and n == ADOPT_ACCESSOR)]
        if kept_a != kept_b:
            return f"a call to `{name}` changes an argument it keeps"
        if new_si:
            if rem and len(rem) != len(removed):
                return (f"a call to `{name}` passes {', '.join(n for n, _ in rem)} but not "
                        f"{', '.join(sorted(removed - {n for n, _ in rem}))}: those took their defaults, which "
                        f"`{ADOPT_ACCESSOR}` would replace")
            if rem or not removed:
                if len(si_b) != 1:
                    return f"a call to `{name}` must pass `{ADOPT_ACCESSOR}` exactly once"
                if rem and si_b[0][0] != next(k for k, n in enumerate(ba) if n in removed):
                    return f"a call to `{name}` passes `{ADOPT_ACCESSOR}` away from the first dropped argument's place"
            elif si_b:
                return (f"a call to `{name}` passed none of {', '.join(sorted(removed))} (it used their defaults) and now "
                        f"passes `{ADOPT_ACCESSOR}`: a behaviour change")
            s = si_b[0][1][1] if si_b else None
        else:
            s = next((a[1] for n, a in zip(ba, A) if n == ADOPT_ACCESSOR), None)
            if rem and s is None:
                return f"a call to `{name}` drops arguments but passes no `{ADOPT_ACCESSOR}`"
        if s is not None and s != (ADOPT_ACCESSOR,):
            return f"a call to `{name}` passes `{' '.join(s)[:60]}` as `{ADOPT_ACCESSOR}`; pass the capability `{ADOPT_ACCESSOR}` itself"
        for n, a in rem:
            m = d.member.get(n)
            if a[1] != (ADOPT_ACCESSOR, ".", m) and equiv.get(a[1]) != m:
                return (f"a call to `{name}` drops `{n} = {' '.join(a[1])[:60]}`, which is not `{ADOPT_ACCESSOR}.{m}` "
                        "(nor a name equal to it in this file)")
        return None

    # -- one file
    def check_file(self, f: str, role: str):
        old, new = self.old(f), self.new(f)
        if role == "capvis":
            self.check_visibility(f, old, new)
            return
        drop_old, drop_new = import_lines(old), import_lines(new)
        equiv: dict = {}
        free: list = []
        if role in ("coordinator", "module"):
            cf = coordinator_file(self.unit)
            for key in ((cf, f"class {self.unit}Impl"), (cf, "apply")):
                d = self.decls.get(key)
                for p in (d.removed if d else []):
                    if p[0] in d.member:
                        equiv[(p[0],)] = d.member[p[0]]
        elif role in ("factories", "recovery"):
            for key, d in self.decls.items():
                if key[0] == f:
                    for p in d.removed:
                        if p[0] in d.member:
                            equiv[(p[0],)] = d.member[p[0]]
        if role in ("coordinator", "module", "recovery"):
            free = [((h,), (ADOPT_ACCESSOR, ".", m)) for (h,), m in equiv.items()]
        if role == "synccontroller":
            core = self.g.show(self.c, RATCHET_FILES[0]) or ""
            lazy, _lprobs = lazy_handles(self.g, self.c, self.spec)
            probs, do, dn, equiv = mirror_accessor(f, old, new, core, self.spec, lazy)
            for prob in probs:
                self.err(prob)
            drop_old |= do
            drop_new |= dn
        if role in ("coordinator", "module") and self.narrowed:
            drop_old |= step6_lines(old)
            drop_new |= step6_lines(new)
        ot, nt = scala_tokens(old), scala_tokens(new)
        lo, ln = old.splitlines(), new.splitlines()
        go, gn = self.find_groups(ot, f, lo), self.find_groups(nt, f, ln)
        ko = [g[1] for g in go]
        kn = [g[1] for g in gn]
        if ko != kn:
            self.err(f"{f}: the adopted declarations and calls differ in number or order before and after: "
                     f"{[k[1] for k in ko][:6]} vs {[k[1] for k in kn][:6]}")
            return
        groups = 0
        for (kind, key, _k, oo, oc), (_kind, _key, _k2, no, nc) in zip(go, gn):
            o_items = group_items([t.t for t in ot[oo + 1:oc]])
            n_items = group_items([t.t for t in nt[no + 1:nc]])
            if kind == "call":
                why = self.call_problem(self.decls[key], o_items, n_items, equiv)
                if why:
                    self.err(f"{f}:{nt[no].line}: {why}")
            groups += 1

        def collapse(toks, gs):
            out, k, n = [], 0, 0
            for _kind, key, _nk, o, cl in gs:
                out += toks[k:o]
                out.append(Tok(f"{GROUP_MARK}{key[1]}#{n}", toks[o].line))
                n += 1
                k = cl + 1
            return out + toks[k:]

        ot2 = [t for t in collapse(ot, go) if t.t.startswith(GROUP_MARK) or t.line not in drop_old]
        nt2 = [t for t in collapse(nt, gn) if t.t.startswith(GROUP_MARK) or t.line not in drop_new]
        if self.narrowed and role in ("coordinator", "module"):
            ot2, nt2 = vis_free(ot2), vis_free(nt2)
        mirrored = set()
        if role == "synccontroller":
            nl_ = new.splitlines()
            names = {t.t for t in scala_tokens("\n".join(nl_[no - 1] for no in sorted(drop_new - import_lines(new))))
                     if t.t[:1].isupper()}
            # prism round 2: the same fully-qualified entry the core imports, never a Scala/Predef name
            core_text = self.g.show(self.c, RATCHET_FILES[0]) or ""
            core_entries = import_entries(core_text)
            pm = re.search(r"^package\s+([\w.]+)\s*$", core_text, re.M)
            # a name the core resolves without an import is its own package's member (StateValidator in `snap`)
            same_pkg = {f"{pm.group(1)}.{n}" for n in names if pm and not any(e.rsplit(".", 1)[-1] == n for e in core_entries)}
            mirrored = {e for e in core_entries | same_pkg
                        if e.rsplit(".", 1)[-1] in names and e.rsplit(".", 1)[-1] not in PREDEF_NAMES}
        for prob in adoption_import_problems(f, old, new, self.spec, mirrored):
            self.err(prob)
        sites: list = []
        probs, subs = align_tokens(f, ot2, nt2, old, new, free, sites=sites)
        for prob in shadow_problems(f, new, sites, "member" if role == "recovery" else "file"):
            self.err(prob)
        for prob in probs:
            self.err(prob.replace("not a mapped handle substitution", "not an adoption rewrite (a dropped parameter "
                                  f"standing as `{ADOPT_ACCESSOR}.<member>`, or an adopted parameter or argument list)"))
        if not probs:
            self.info(f"{f}: {groups} adopted declaration/call list(s), {subs} substitution(s)")

    def check_visibility(self, f, old, new):
        ol, nl = old.splitlines(), new.splitlines()
        if len(ol) != len(nl):
            self.err(f"{f}: changes {ADOPT_CAP} beyond its visibility (line count {len(ol)} -> {len(nl)})")
            return
        n = 0
        for a, b in zip(ol, nl):
            if a == b:
                continue
            if re.match(r"^private\[snap\] (trait|object) " + ADOPT_CAP + r":\s*$", a) and b == a.replace("private[snap]", "private[sync]", 1):
                n += 1
                continue
            self.err(f"{f}: changes `{a.strip()[:70]}` -> `{b.strip()[:70]}`; a RecoveryActors adoption only widens the "
                     f"trait's and object's `private[snap]` to `private[sync]`")
        if n == 1:
            self.err(f"{f}: widens only one of `trait {ADOPT_CAP}` and `object {ADOPT_CAP}`; widen both together")
        self.info(f"{f}: visibility widened on {n} line(s)")


def adoption_guard(v: "Verifier", c: str, p: str, changed: dict[str, str]):
    """G8: outside an `# adopts:` commit, no coordinator, ChildFactories or recovery-actor declaration may drop a
    storage-typed parameter or take a `StateIngest`, and the capability's visibility may not change."""
    g = v.g
    checks = []
    for x in ADOPT_COORDINATORS:
        cf = coordinator_file(x)
        checks.append((cf, {(cf, f"class {x}Impl"): None, (cf, "apply"): 2}))
    checks.append((CHILD_FACTORIES, {(CHILD_FACTORIES, lower_camel(x)): 2 for x in ADOPT_COORDINATORS}))
    for f in RECOVERY_FILES:
        checks.append((f, "defs"))
    for f, cands in checks:
        if changed.get(f) != "M":
            continue
        old, new = (g.show(p, f) or "") if p != EMPTY_TREE else "", g.show(c, f) or ""
        if cands == "defs":
            cands = {(f, m.group(1)): 2 for t in (old, new)
                     for m in re.finditer(r"^  (?:(?:" + MODIFIER + r")\s+)*def\s+(\w+)\s*\(", t, re.M)}
        probe = Adoption(v, c, p, changed, "", False)
        probe.cands = cands
        params = {}
        for side, text in (("old", old), ("new", new)):
            toks, lines = scala_tokens(text), text.splitlines()
            for _kind, key, _k, o, cl in probe.find_groups(toks, f, lines, decl_only=True):
                ps = [parse_param(i) for i in group_items([t.t for t in toks[o + 1:cl]])]
                params.setdefault(key, {"old": [], "new": []})[side].append([q for q in ps if q])
        for key, sides in params.items():
            for O, N in zip(sides["old"], sides["new"]):
                nn, on = {q[0] for q in N}, {q[0] for q in O}
                dropped = [q[0] for q in O if q[0] not in nn and storage_typed(" ".join(q[1]))]
                took = [q[0] for q in N if q[0] not in on and ADOPT_CAP in q[1]]
                if dropped or took:
                    v.err(c, "adopts", f"{f}: `{key[1]}` " + (f"drops storage parameter(s) {', '.join(dropped)}" if dropped else
                                                              f"takes {', '.join(took)}: {ADOPT_CAP}")
                          + f" outside an adoption commit: carry `# adopts: {ADOPT_CAP} <Unit>` (T060b)")
    cap_files = [f for f in changed if changed[f] == "M" and f.endswith(".scala") and f.startswith(f"{SNAP}/")]
    for f in cap_files:
        old, new = (g.show(p, f) or ""), g.show(c, f) or ""
        if ADOPT_CAP not in toplevel_traits(new):
            continue
        hdr = re.compile(r"^(.*?)\b(trait|object) " + ADOPT_CAP + r":\s*$", re.M)
        if [m.group(1) for m in hdr.finditer(old)] != [m.group(1) for m in hdr.finditer(new)]:
            v.err(c, "adopts", f"{f}: changes the visibility of {ADOPT_CAP} outside a `# adopts: {ADOPT_CAP} {RECOVERY_UNIT}` "
                               "commit (D-T060-2)")


def check_capability_required(v: "Verifier", base: str, head: str):
    """Removing a pinned storage handle from SnapControllerEnv is what a capability PR does; outside one it would move
    call sites with no substitution check. So a range that removes one must be a `# capability:` PR."""
    if v.cap_names:
        return
    try:
        mb = v.g.run("merge-base", base, head).strip()
    except RuntimeError:
        return
    before, after = env_members(v.g.show(mb, ENV_FILE) or ""), env_members(v.g.show(head, ENV_FILE) or "")
    for n in sorted((set(before) - set(after)) & v.pinned):
        v.err(head, "capability", f"removes pinned storage handle `{n}` from {ENV_TRAIT} outside a capability PR: a PR that moves "
                                  "storage handles onto a capability carries `# capability: <Trait>` (refactor/snap-016-* only)")


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
    v.prepare(a.base, commits)
    for c in commits:
        v.commit(c)
        if v.compile_cmd and not g.is_merge(c) and trailer(g.message(c), "moved") is not None:
            v.compile_move(c)
    if commits:
        v.check_final(commits[-1], commits)
        check_capability_required(v, a.base, commits[-1])
        check_storage_ratchet(v, a.base, commits[-1])
    if v.errors:
        print(f"\nsnap-split verify: {len(v.errors)} failure(s)")
        return 1
    print("\nsnap-split verify: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
