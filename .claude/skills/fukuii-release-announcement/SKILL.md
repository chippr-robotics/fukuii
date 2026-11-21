---
name: fukuii-release-announcement
description: >-
  After a fukuii release is cut, write its Discord announcement, render its
  release card (SVG plus a PNG for Discord), write the matching blog post on the
  docs site, and post the announcement to Discord through a webhook once the
  maintainer approves it. Use when a release tag is published, when asked to
  "announce" a release or write release notes for the community, or when a
  release went out without an announcement. Drafting is read-only research plus
  local files; posting to Discord is outward-facing and irreversible, so it is
  never done without explicit approval of the exact text and image.
---

# Release announcement

Usually run by the `crier` agent (`.claude/agents/crier.md`); its rules (verified claims, shipped vs groundwork,
security, approval before posting, the webhook as a secret) apply here. The worked example is
`marketing/announcements/v0.12.1/` with `docs/blog/posts/2026-11-17-fukuii-0-12-1.md`.

## Inputs

- `TAG`, the release to announce (for example `v0.12.1`).
- `BASE`, the last release the community already heard about. Ask the maintainer if it is not obvious. fukuii tags
  every version bump, so the previous tag is often a single commit: v0.12.1 was one PR on top of v0.12.0, and v0.12.0
  was a bare bump on top of 0.11.136. The real scope was `v0.11.0..v0.12.1`. Pick the last *milestone* release, and
  say in the post what range it covers.

## 1. Gather facts (🟢 read-only)

```bash
git fetch origin --tags
git fetch --shallow-since=<date of BASE> origin main     # only if the clone is shallow: `git rev-list --count TAG` looks too small
git log --format='%h %ad %s' --date=short BASE..TAG | grep -v 'bump version'
git log --oneline BASE..TAG | grep -v 'bump version' | wc -l     # the "N changes" number
```

Also read:
- the GitHub release body (`mcp__github__get_release_by_tag`, or `gh release view TAG`) for the assets, and whether a
  Docker image or SBOM was published;
- `CHANGELOG.md`, the sections that cover the range;
- `specs/README.md`, to see which specs closed in the range and what is still in flight;
- for every candidate headline, the config or code **at the tag**: `git show TAG:<path>`. An `enabled = false`, an
  "IN-DEVELOPMENT GUARD" comment, or a missing default makes it groundwork, not a feature.

Sort the commits into three buckets, and drop `test`, `docs`, `chore(tracker)` and `build` items unless an operator
would notice them (new install paths do count):
1. **Operators can use it today**: new commands, networks, defaults, install paths, UI.
2. **Correctness and hardening**: reference-client parity, sync fixes, security already in the CHANGELOG.
3. **Groundwork**: large internal tracks that are merged but not switched on. Say what they will make possible.

## 2. Write the Discord post

`marketing/announcements/TAG/discord.md`, at most **2,000 characters** (the script checks it):

```
## 🦗 fukuii TAG is out
<one line: the range it covers and the shape of the release>

**What's new for operators**
<emoji> **<Feature>**: <what it does and the command or flag to use>   (3 to 5 lines)

**Correctness and hardening**
• ...                                                                   (3 to 5 bullets, a short clause each)

**Groundwork for what's next**
<one sentence on the track> then 2 to 4 bullets, then one line saying it is off by default

⬇️ https://github.com/chippr-robotics/fukuii/releases/tag/TAG
<artifacts and JDK requirement> · Verify with `SHA256SUMS.txt`.
```

When the draft is over the limit, cut adjectives first, then merge bullets, then drop the least operator-visible
hardening item. Do not drop the groundwork's "off by default" line.

## 3. Render the card

Write `marketing/announcements/TAG/card.json` (the schema is in the docstring of `scripts/marketing/render_card.py`):
the four tiles are the four strongest "use it today" items, each a heading and two short lines; the dashed
`groundwork` box is the main track that is not switched on yet.

```bash
python3 -I scripts/marketing/render_card.py marketing/announcements/TAG/card.json --check   # line lengths fit
python3 -I scripts/marketing/render_card.py marketing/announcements/TAG/card.json --png
```

Then **look at the PNG** (Read the image). SVG text does not wrap, and `--check` only counts characters, so confirm
that nothing runs out of its box and nothing overlaps the mascot. `card.png` is gitignored: attach it to the post,
and commit `card.json` and `card.svg`.

## 4. Write the blog post

`docs/blog/posts/<release date>-fukuii-<version with dashes>.md`, category `Releases`, author `chippr`. Copy the card
SVG to `docs/blog/images/TAG-card.svg` and put it under the title. Expand each Discord section into prose, link the
specs (`https://github.com/chippr-robotics/fukuii/blob/main/specs/<NNN>-<name>/spec.md`), and put `<!-- more -->`
after the first paragraph. Front matter:

```yaml
---
date: YYYY-MM-DD
authors:
  - chippr
categories:
  - Releases
slug: fukuii-0-12-1
description: <one sentence, used for search and link previews>
---
```

Check the build. `mkdocs build --strict` is the CI gate (`gh-pages.yml`):

```bash
python3 -m venv /tmp/docs-venv && /tmp/docs-venv/bin/pip install -q -r requirements-docs.txt
GOOGLE_ANALYTICS_KEY=x /tmp/docs-venv/bin/mkdocs build --strict --site-dir /tmp/site
```

## 5. Review with the maintainer

Show them the Discord text, the PNG, the blog post, and a **check before posting** list (claims you could not verify
at the tag, for example a package still in a third-party moderation queue). Then run the dry run:

```bash
python3 -I scripts/marketing/post_discord.py marketing/announcements/TAG/discord.md \
  --image marketing/announcements/TAG/card.png
```

Commit `discord.md`, `card.json`, `card.svg`, the blog post and its image on a branch, and open a PR with a
`Backlog: B-xxx` line. The maintainer merges it; publishing the docs site follows from the merge.

## 6. Post (🔴 outward-facing, needs explicit approval)

Only after the maintainer approves **this** text and image in the conversation:

```bash
# DISCORD_WEBHOOK_URL is set in the maintainer's shell or the environment's secrets; never in a file or the chat.
python3 -I scripts/marketing/post_discord.py marketing/announcements/TAG/discord.md \
  --image marketing/announcements/TAG/card.png --send
```

If `DISCORD_WEBHOOK_URL` is not set, stop and say so. The maintainer creates the webhook (Discord: Server Settings →
Integrations → Webhooks → New Webhook, pick the channel, Copy Webhook URL) and exports it. Do not ask for the URL in
the chat. A cloud session cannot use the network-secret form for it, because a Discord webhook's token is in the URL
path, not a header. The maintainer can also post the text and PNG by hand.

The maintainer may post by hand instead. Either way, record it: mark the backlog item done, and note the message id
that the script printed in the PR.
