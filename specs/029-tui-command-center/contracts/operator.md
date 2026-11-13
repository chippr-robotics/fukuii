# Contract: keys, prompt commands and settings

What an operator can press, type and configure. `docs/architecture/console-ui.md` is rewritten from this file in the
closing task. Changes from spec 027 are marked **(changed)**.

## Keys

A letter is read the same in either case outside a text field, as today, so Caps Lock breaks nothing. Every key in
the "Key" column that is an escape sequence has a single-byte equivalent in the next column (spec FR-003).

### Everywhere

| Key | Also | Does |
| --- | --- | --- |
| `1` … `9` | | Open a view: Overview, Chain, Peers, Mempool, RPC, Storage, System, Logs, Ops **(changed: 027 had five)** |
| `Tab` | | Focus the next panel of the view; wraps **(changed: 027 cycled views)** |
| `Shift-Tab` | | Focus the previous panel |
| `]` / `[` | | Next / previous view |
| `Up` / `Down` | `k` / `j` | Move the selection |
| `Left` / `Right` | `,` / `.` | Move across (the Observatory's blocks, a dialog's buttons) |
| `Page Up` / `Page Down` | `-` / `+` | A page up / down (027's log keys keep their direction) |
| `Home` / `End` | `<` / `>` | First / last row |
| `Enter` | | Open the selection; accept a dialog's focused button |
| `Esc` | `Backspace` | Back; close a dialog, the prompt or a filter; cancel a lookup |
| `:` | | Open the prompt |
| `/` | | Filter the list in focus |
| `a` | | Actions for the thing in focus (a peer, a transport, mining) |
| `?` | `F1`, `h` | Help: every key active here |
| `l` | | Logs (same as `8`) |
| `g` | | Glyphs: emoji ↔ ASCII |
| `v` | | Graphics mode: pixel → block → text |
| `p` | | Pause motion |
| `r` | | Redraw; in pixel mode, place every image again |
| `d` | | Detach the TUI; the node keeps running |
| `q` | | Ask to stop the node **(changed: 027 stopped it at once)** |
| `Ctrl-C` | | Stop the node, as a signal, as today |

### In a dialog

| Class | Keys |
| --- | --- |
| Adjust | Cancel has the focus when the dialog opens. `Tab` or `Right` moves to Apply; `Enter` accepts the focused button; `Esc` cancels. |
| Disrupt | `y` runs it; `n` or `Esc` cancels |
| Halt | type the word shown, then `Enter`; `Esc` cancels |

Nothing else is accepted in a dialog. Pasted text is ignored (spec FR-009).

### In the prompt or a filter

Printable characters insert. `Left`, `Right`, `Home`, `End`, `Backspace` edit. `Up` and `Down` walk the session's
history. `Tab` completes. `Enter` runs. `Esc` closes. Pasted text is inserted with control characters removed.

## Prompt commands

A command that starts an action opens that action's dialog. The prompt never runs an action by itself.

| Command | Does |
| --- | --- |
| `overview`, `chain`, `peers`, `mempool`, `rpc`, `storage`, `system`, `logs`, `ops` | Open the view |
| `block <number \| hash \| latest>` | Open a block |
| `tx <hash>` | Open a transaction, pending or mined |
| `receipt <hash>` | Open a transaction's receipt |
| `account <address>` | Balance, nonce and code size at the head |
| `peer <id prefix>` | Open a peer's details |
| `config [filter]` | The effective configuration, secrets hidden |
| `tunables` | The settings that can change without a restart |
| `deps` | Subsystems, what each depends on, and their health |
| `test-rpc` | Call each enabled RPC endpoint and report |
| `net` | Listening and detected external addresses, inbound peers |
| `art` | Every sprite, pose and frame in the current graphics mode |
| `loglevel <level> [logger]`, `loglevel reset` | Adjust |
| `maxpeers <n>` | Adjust |
| `peer trust <id>`, `peer untrust <id>` | Adjust |
| `threshold <name> <value>`, `threshold reset` | Adjust, for the session |
| `peer add <enode>`, `peer drop <id>`, `peer block <address>`, `peer unblock <address>` | Disrupt |
| `mining start`, `mining stop`, `coinbase <address>` | Disrupt (ETC) |
| `rpc stop <http \| ws>`, `rpc start <http \| ws>` | Disrupt |
| `dbcheck` | Disrupt: a database consistency check |
| `compact` | Disrupt: a database compaction |
| `detect-ip` | Disrupt: asks outside services for this node's address |
| `bundle` | Disrupt: writes a support bundle |
| `quit` | Halt |
| `detach` | Detach the TUI |
| `graphics <pixel \| block \| text>`, `glyphs <emoji \| ascii>` | Display |
| `help [command]` | The commands, or one command's usage |

## Settings

Under `fukuii.tui` in `conf/base/tui.conf`. An invalid value fails at start with an error naming the key. The keys of
spec 027 are unchanged.

| Key | Default | Meaning |
| --- | --- | --- |
| `graphics` | `auto` | `auto`, `pixel`, `block` or `text`. `auto` asks the terminal (spec FR-013). `pixel` on a terminal that does not answer falls back to `block` and says so in the log. |
| `read-only` | `false` | No action beyond Observe can run (spec FR-053) |
| `art-dir` | unset | A directory of sprite sheets used instead of the bundled ones ([art.md](./art.md)) |
| `history-seconds` | `600` | How much history each meter keeps; 60 to 3600 |
| `observatory-blocks` | `128` | Recent blocks kept for the Block Observatory; 32 to 1024 |
| `alerts.zero-peers-after` | `30s` | |
| `alerts.low-peers-fraction` | `0.25` | Warn below this share of the peer limit |
| `alerts.stall-after` | `120s` | |
| `alerts.snap-sample-max-age` | `90s` | |
| `alerts.disk-warn`, `alerts.disk-error` | `20 GiB`, `5 GiB` | |
| `alerts.cl-silent-after`, `alerts.cl-first-call-grace` | `60s`, `60s` | ETH |
| `alerts.new-payload-silent-after` | `5m` | ETH |
| `alerts.hashrate-zero-after` | `10m` | ETC |
| `alerts.reorg-depth` | `3` | Warn on a reorg at least this deep |
| `alerts.heap-warn-fraction` | `0.90` | New in this spec |
| `alerts.gc-share-warn` | `0.20` | New in this spec |
| `alerts.rpc-error-rate-warn` | `0.10` | New: errors over calls in the last minute, with at least 20 calls |
| `alerts.write-stall` | `true` | New: warn while the database has writes stopped |

## Environment

As in spec 027, a bad value logs a warning naming the variable and the accepted values, and the setting is used.

| Variable | Overrides |
| --- | --- |
| `FUKUII_TUI_GRAPHICS` | `graphics` |
| `FUKUII_TUI_READ_ONLY` | `read-only` (any non-empty value sets it) |
| `FUKUII_TUI_ART_DIR` | `art-dir` |
| `NO_COLOR`, `FUKUII_TUI_GLYPHS`, `FUKUII_TUI_MOTION`, `FUKUII_TUI_NO_SPLASH` | unchanged from spec 027. `NO_COLOR` also selects text graphics. |
| `TMUX`, `TERM` beginning `screen` or `tmux` | read, not set: under a multiplexer `auto` is `block` |
