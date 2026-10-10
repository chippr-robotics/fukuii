#!/usr/bin/env python3
"""Spec 016 move verification (S0f, T008). Run through verify.sh; see that file for usage.

Implements plan.md "Move verification" steps 1-7 and FR-016 (a), (c), (d), per commit of BASE..HEAD:

  1  moved blocks only (git --color-moved=plain) .............. commits with a `# moved:` trailer
  2  identical bodies of the `# moved:` symbols ............... commits with a `# moved:` trailer
  3  test-hunk guard / `# test-files:` trailer ............... refactor/snap-016-* | test/snap-016-* branches
  4  no src/main/resources change, protected paths untouched .. refactor/test snap-016 branches
  5  FR-016 (a) impl-class mention, (d) member-level init rules  every commit (module dirs only)
  6  narrowing commit touches signatures only ................ commits that remove the last impl mention
  7  FR-016 (c) counts, and (b) a stub test is added ......... narrowing commits (counts: module commits)
  +  `sbt compile` on each move commit (--compile).

Standard library only; needs git and python3, no JVM.
"""

from __future__ import annotations

import argparse
import fnmatch
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


def header_regions(text: str) -> set[int]:
    """1-based line numbers of trait/class/object headers and of a trait's self-type lines."""
    lines, reg = text.splitlines(), set()
    for i, l in enumerate(lines):
        if not TYPE_HEADER_RE.match(l):
            continue
        j = i
        while j < len(lines) and j < i + 12:
            reg.add(j + 1)
            if lines[j].rstrip().endswith((":", "{", "=")):
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
            if self.kind in ("refactor", "test"):
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
            if is_move and len(now) == 1:
                line = next(l for l in text.splitlines() if IMPL_RE.search(l))
                if re.match(r"^\s*\w+\s*:\s*\w*Impl\s*=>\s*$", line):
                    continue
                self.err(c, "FR-016a", f"{f}: the one allowed mention must be the temporary self-type line, found: {line.strip()}")
            elif is_move:
                self.err(c, "FR-016a", f"{f}: {len(now)} mentions of the impl class; a move commit allows exactly one (the self-type)")
            else:
                self.err(c, "FR-016a", f"{f}: mentions the impl class ({len(now)}x); only a `# moved:` commit may, once per file")

        # step 5 (d)
        for f in g.tree_files(c, MODULE_DIRS):
            for no, s, why in member_check(g.show(c, f) or ""):
                self.err(c, "FR-016d", f"{f}:{no}: {why}: `{s[:90]}`")

        # steps 1-2: move commit
        if is_move:
            self.check_moved_blocks(c, p, changed, split_list(moved))
            self.check_bodies(c, p, changed, split_list(moved))
            if narrowed:
                self.err(c, "step6", "commit both removes the last impl-class mention (narrowing) and carries `# moved:`; split it into two commits")

        # step 6 + (b): every narrowing commit, whatever its trailers
        if narrowed:
            self.check_signature_only(c, p, changed)
            self.check_stub_test(c, p, changed)
        if any(is_module(f) for f in changed) or narrowed:
            for line in counts_report(g, c):
                self.info(c, line)

        # steps 3-4
        if self.kind in ("refactor", "test"):
            for f in changed:
                if f.startswith(RESOURCES):
                    self.err(c, "step4", f"changes {f} (no format/config drift: src/main/resources must be untouched)")
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

    # -- (b)
    def check_stub_test(self, c, p, changed):
        excluded = re.compile(r"\b(?:SyncTest|IntegrationTest|SlowTest|DisabledTest)\b")
        for f, st in changed.items():
            if f.startswith("src/test/") and st == "A":
                t = self.g.show(c, f) or ""
                if re.search(r"new\s+Stub\w*State\b", t) and not excluded.search(t):
                    return
        self.err(c, "FR-016b", "narrowing commit adds no new Tier 1 test of the form `new Stub<Module>State with ...` (untagged by SyncTest/IntegrationTest/SlowTest/DisabledTest)")

    # -- step 3, refactor branches
    def check_test_hunks(self, c, p, changed, msg):
        new_tests = trailer(msg, "new-tests")
        allowed_new = self.exact_paths(c, "step3", new_tests, "new-tests") if new_tests else []
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
            for sign, lines in (("-", removed), ("+", added)):
                for l in lines:
                    if re.match(r"^\s*import\b", l):
                        continue
                    if TOKEN_RE.search(l):
                        self.err(c, "step3", f"{f}: {sign} line with an assertion/scheduling token: `{l.strip()[:90]}`")

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
    if v.errors:
        print(f"\nsnap-split verify: {len(v.errors)} failure(s)")
        return 1
    print("\nsnap-split verify: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
