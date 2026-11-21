#!/usr/bin/env python3
"""Post an announcement (text plus an optional image) to a Discord channel through a webhook.

Usage:
    # Dry run (the default): checks the post and prints what would be sent. Nothing leaves the host.
    python3 -I scripts/marketing/post_discord.py marketing/announcements/v0.12.1/discord.md \
        --image marketing/announcements/v0.12.1/card.png

    # Send it, after the maintainer has approved the exact text and image.
    DISCORD_WEBHOOK_URL=... python3 -I scripts/marketing/post_discord.py <post.md> --image <card.png> --send

The webhook URL is a secret: whoever holds it can post as the webhook. It is read from an
environment variable (DISCORD_WEBHOOK_URL by default, --webhook-env to pick another, for
example DISCORD_WEBHOOK_URL_BLOG for a separate blog channel). It is never accepted on the
command line, never printed, and never committed.

Mentions are disabled (allowed_mentions.parse = []), so a stray @everyone in the text does not
ping the server. Pass --ping-role <role id> to ping one role deliberately.

Stdlib only. HTTPS_PROXY is honoured.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

DISCORD_LIMIT = 2000  # characters of message content for a webhook (no Nitro on webhooks)
MAX_IMAGE_BYTES = 10 * 1024 * 1024  # the attachment cap for a server without boosts
WEBHOOK_PREFIXES = (
    "https://discord.com/api/webhooks/",
    "https://discordapp.com/api/webhooks/",
    "https://ptb.discord.com/api/webhooks/",
    "https://canary.discord.com/api/webhooks/",
)


def multipart(payload: dict, image: Path | None) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts: list[bytes] = []
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="payload_json"\r\n'
        f"Content-Type: application/json\r\n\r\n".encode()
        + json.dumps(payload).encode()
        + b"\r\n"
    )
    if image is not None:
        ctype = mimetypes.guess_type(image.name)[0] or "application/octet-stream"
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="files[0]"; filename="{image.name}"\r\n'
            f"Content-Type: {ctype}\r\n\r\n".encode()
            + image.read_bytes()
            + b"\r\n"
        )
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("post", type=Path, help="the message text (Discord markdown), at most 2000 characters")
    ap.add_argument("--image", type=Path, help="an image to attach (PNG or JPEG; Discord does not preview SVG)")
    ap.add_argument("--webhook-env", default="DISCORD_WEBHOOK_URL", help="env var holding the webhook URL")
    ap.add_argument("--username", default=None, help="override the webhook's display name")
    ap.add_argument("--ping-role", default=None, help="a role id to ping; otherwise no mention pings anyone")
    ap.add_argument("--send", action="store_true", help="actually post; without it this is a dry run")
    args = ap.parse_args()

    content = args.post.read_text(encoding="utf-8").strip()
    problems: list[str] = []
    if len(content) > DISCORD_LIMIT:
        problems.append(f"post is {len(content)} characters; Discord's webhook limit is {DISCORD_LIMIT}")
    if args.image is not None:
        if not args.image.is_file():
            problems.append(f"image not found: {args.image}")
        elif args.image.suffix.lower() == ".svg":
            problems.append("Discord does not preview SVG; render the PNG (render_card.py --png) and attach that")
        elif args.image.stat().st_size > MAX_IMAGE_BYTES:
            problems.append(f"image is {args.image.stat().st_size} bytes; the cap is {MAX_IMAGE_BYTES}")
    if problems:
        for p in problems:
            print(f"error: {p}", file=sys.stderr)
        return 1

    payload: dict = {"content": content, "allowed_mentions": {"parse": []}}
    if args.ping_role:
        payload["content"] = f"<@&{args.ping_role}>\n{content}"
        payload["allowed_mentions"] = {"roles": [args.ping_role]}
        if len(payload["content"]) > DISCORD_LIMIT:
            print("error: the role ping pushes the post over the limit", file=sys.stderr)
            return 1
    if args.username:
        payload["username"] = args.username
    if args.image is not None:
        payload["attachments"] = [{"id": 0, "filename": args.image.name}]

    print(f"post: {args.post} ({len(payload['content'])} / {DISCORD_LIMIT} characters)")
    print(f"image: {args.image or '(none)'}")
    print(f"mentions: {'role ' + args.ping_role if args.ping_role else 'none'}")
    if not args.send:
        print("dry run: nothing sent. Re-run with --send once the post is approved.")
        return 0

    url = os.environ.get(args.webhook_env, "").strip()
    if not url:
        print(f"error: {args.webhook_env} is not set", file=sys.stderr)
        return 2
    if not url.startswith(WEBHOOK_PREFIXES):
        print(f"error: {args.webhook_env} does not look like a Discord webhook URL", file=sys.stderr)
        return 2

    body, ctype = multipart(payload, args.image)
    sep = "&" if "?" in url else "?"
    req = urllib.request.Request(
        f"{url}{sep}wait=true",
        data=body,
        method="POST",
        headers={"Content-Type": ctype, "User-Agent": "fukuii-announcer (github.com/chippr-robotics/fukuii, 1.0)"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            msg = json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        # The response body names the field Discord rejected; it never echoes the webhook token.
        print(f"error: Discord answered {e.code}: {e.read().decode(errors='replace')[:500]}", file=sys.stderr)
        return 3
    print(f"posted: message {msg.get('id')} in channel {msg.get('channel_id')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
