---
name: fukuii-spec-blog-post
description: >-
  Write a blog post on the fukuii docs site that explains a spec (specs/NNN-*)
  to node operators and protocol engineers: the problem, the design, what
  shipped, and what it changes for them. Then draft a short Discord note that
  links to the post, and post it on approval. Use when a spec closes (Status
  Done), when a large track inside a spec lands, or when the maintainer asks for
  a write-up of a spec. Research and drafting are read-only plus local files;
  the post merges through a PR, and the Discord note is outward-facing, so it is
  sent only on explicit approval.
---

# Spec blog post

Usually run by the `crier` agent (`.claude/agents/crier.md`); its rules (verified claims, shipped vs groundwork,
ETC vs ETH, security, approval before posting, the webhook as a secret) apply here.

## When a spec is worth a post

- Its lifecycle header says **Done** (see `docs/development/spec-lifecycle.md`), or a named track or slice inside it
  is merged and changes what an operator sees.
- It changed behaviour an operator would notice: sync time, disk, a new command or network, a default.
- It is a design decision people outside the project will ask about: a storage engine, a sync strategy, a fork.

A spec that is Planned, or Active with nothing merged, gets no post. Mention it as "next" inside another post at
most.

## 1. Read (🟢)

- `specs/README.md`: the spec's row (status, in-flight, release).
- `specs/NNN-*/spec.md` all the way through: the problem statement, user stories, success criteria, the clarifications.
- `plan.md` and `research.md` for the design and the measurements. A number you quote comes from there or from a
  linked PR, with the context it was measured in (chain, host, datadir).
- The merged PRs: `git log --oneline --grep 'spec NNN'` (and the `spec-NNN` spelling), then read the PR bodies for the
  user-visible effect.
- The code or config at the release that carries it: `git show <tag>:<path>`. If the feature is behind a guard, the
  post is about groundwork and says so.

## 2. Write the post

`docs/blog/posts/YYYY-MM-DD-<slug>.md`, dated the day it is published. Category `Specs`, plus `Releases` if it is tied
to one. Author `chippr`.

```markdown
---
date: YYYY-MM-DD
authors:
  - chippr
categories:
  - Specs
slug: <short-kebab-title>
description: <one sentence: the problem and the outcome>
---

# <Title: the outcome, not the spec number>

<Paragraph 1: the problem in operator terms, what it cost them.>

<!-- more -->

## The problem
## What we built          (the design, one diagram if it earns its place: a mermaid block renders on the site)
## What changes for you   (commands, config keys with defaults, numbers before and after)
## What is next           (open slices, the release that carries them; no dates the spec does not give)

Spec: [NNN <name>](https://github.com/chippr-robotics/fukuii/blob/main/specs/NNN-<name>/spec.md)
```

Length: 500 to 1,500 words. Explain the mechanism at the level of a protocol engineer from another client, without
fukuii internals (actor names, class names) unless the reader needs them to configure something. Compare with
go-ethereum, core-geth, Besu, Nethermind or Erigon only where the spec itself does.

Images go in `docs/blog/images/` as SVG. Then check the build: `mkdocs build --strict` (setup in the
`fukuii-release-announcement` skill, step 4).

## 3. The Discord note

`marketing/blog/<slug>/discord.md`, 400 to 800 characters: a title line, two or three sentences on the outcome, and the
post's URL, which is `https://docs.fukuii.com/blog/YYYY/MM/DD/<slug>/`. Confirm the URL resolves after the docs site
deploys, and before posting.

An image is optional. A card with four "what you get" tiles can reuse `render_card.py` with
`"kicker": "CHIPPR ROBOTICS · SPEC NNN"` and a two-word title in `title` / `highlight`.

## 4. Review, merge, post

1. Show the maintainer the post, the note, and a **check before posting** list.
2. Commit on a branch and open a PR (`Backlog: B-xxx`). The maintainer merges it, and the docs site deploys from
   `main`.
3. Dry run, then, only on explicit approval of this note:

```bash
python3 -I scripts/marketing/post_discord.py marketing/blog/<slug>/discord.md                   # dry run
DISCORD_WEBHOOK_URL_BLOG=... python3 -I scripts/marketing/post_discord.py marketing/blog/<slug>/discord.md \
  --webhook-env DISCORD_WEBHOOK_URL_BLOG --send      # or DISCORD_WEBHOOK_URL when blog posts share the channel
```
