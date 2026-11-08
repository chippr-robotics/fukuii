# Tracker

The shared backlog: every piece of engineering work that is open, who holds it, and its state (constitution
Principle VIII).

!!! info "Rendered at deploy"

    The docs workflow builds this page from `backlog.yaml` on the
    [`tracker` branch](https://github.com/chippr-robotics/fukuii/tree/tracker): it runs
    `python3 -I scripts/backlog/backlog.py render --out docs/development/tracker`, which replaces this file and adds
    the burn-down chart. To see the page locally, run that command from the repository root, and restore this
    placeholder (`git checkout docs/development/tracker/index.md`) before you commit.

    If you read this on docs.fukuii.com, the render failed or the `tracker` branch was missing: see the log of the
    "Deploy Documentation to GitHub Pages" workflow.

The protocol (claim before work, keep the status current, heartbeat daily) is
`.claude/agent-protocols/backlog-tracker.md` in the repository.
