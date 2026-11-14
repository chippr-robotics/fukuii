# Quickstart: the manual gate

How to see the TUI command center work on a real terminal. Each slice's PR carries the captures its section asks for
(spec SC-014). Run these with no sbt running: one heavy JVM at a time.

Nothing here has been run yet. The spec, the plan and the art were written in a session with no display; the art was
judged on enlarged renders and mock screens ([art-preview/](./art-preview/)).

## Prerequisites

- A built distribution: `sbt dist`, then the launcher under `target/universal/`.
- A Mordor node that can sync (ETC family), and for slice 5's Engine half a Sepolia node with a consensus client.
- Terminals: **kitty** and **Ghostty** for pixel mode; **tmux** inside any terminal for block mode; any terminal with
  `LANG=C NO_COLOR=1` for text mode.
- `tmux`, `pidstat` (sysstat), `script` (util-linux).

## Captures

| What | How |
| --- | --- |
| A text or block frame | `tmux new-session -d -x 160 -y 48 'fukuii mordor --tui'`, then `tmux capture-pane -p -e`. Also at `-x 80 -y 24`. |
| A pixel frame | A screenshot of the kitty or Ghostty window. `tmux capture-pane` cannot see images. |
| Bytes written | `script -q -c 'fukuii mordor --tui' out.typescript`, let it run 60 s on the busiest view, `wc -c out.typescript`; divide by 60. Under 20 KiB/s after the first second (SC-007). |
| CPU | `pidstat -t -p <pid> 1 60`; the `TuiUpdateThread`, `TuiProbeThread` and worker threads together under 3 % at 4 fps, 0.5 % idle. |

## Slice 1: input

1. `fukuii mordor --tui` in an 80×24 tmux pane. Press `3`. Walk the peer list with Down, `j`, Page Down, End, Home.
   Enter on a peer, Esc back: the same peer is selected.
2. Press `:`, type `pe`, Tab, Enter: the Peers view. Type `:nonsense`, Enter: the closest commands are named.
3. Press `/` in Logs and type a word: the list narrows as typed. Esc clears it.
4. Press Esc alone, wait a second, press `d`... and expect the TUI to detach: Esc and `d` were two keys. Start again.
5. Hold an arrow key over a slow SSH link (`tc qdisc add dev lo root netem delay 200ms` on a test host): no stray
   `[`, `A`, `D`, no view change, no detach.
6. Paste a paragraph of text into the window: nothing happens outside the prompt.
7. Press `q`: a dialog, not an exit. Esc. Ctrl-C: the node stops and the terminal is restored.
8. `?`: every key in [contracts/operator.md](./contracts/operator.md) that applies is listed.

## Slice 2: graphics

1. **kitty**, 160×48: the splash shows the badge and the node's start-up phases ticking off; then Overview with the
   network icon in the sidebar and the mascot. The art is sharp, not blurred. Screenshot.
2. Press `v`: block mode, then text mode, then pixel again. No text moves. Screenshots of each.
3. Resize the window and change the font size: the art is placed again at the new cell size, still sharp.
4. Press `d`, then start again and press `q` and confirm, then start again and press Ctrl-C: after each, no image is
   left in the terminal (scroll back to check).
5. **Ghostty**: steps 1 and 4.
6. **tmux** in kitty: block mode is chosen without asking. `FUKUII_TUI_GRAPHICS=pixel`: the log says it fell back to
   block.
7. `LANG=C NO_COLOR=1`: text mode, spec 027's frame.
8. `FUKUII_TUI_MOTION=reduced`: no sprite moves; the pose still follows the node's state.
9. `:art`: every sprite, pose and frame. Compare with `art-preview/mascot.png`.
10. Stop the node's peers (firewall the port): within the alert's threshold the mascot shows the error pose and the
    alert row says why. Let sync stall: it sleeps.
11. Bytes and CPU, as in the table above, in pixel mode and in block mode.

## Slice 3: chain

1. On a synced Mordor node, `2`: a new block appears in the Observatory within 2 s of the log line that imports it.
2. Enter on a block; Enter on a transaction; its receipt. `:account <address>`. `:block 1`. `:tx <unknown hash>`: a
   sentence, not a stack trace.
3. During a fresh sync: the Observatory says it is sampled; CPU stays inside the budget.
4. A reorg, on a private two-node network or by replaying a fixture chain: the history shows depth and both tips,
   and the alert row reports it.
5. `3`: the peer radar. Each blip's distance agrees with the latency in that peer's detail screen.

## Slice 4: mempool

1. `4` on a node receiving transactions: the tier counts sum to the pending count; the boundaries are in gwei.
2. Send a transaction, then replace it with a higher fee: one replacement is counted.
3. Leave the Mempool view: the debug log shows no further full pool reads.

## Slice 5: RPC and the consensus-client link

1. `5`. Run `curl` against `eth_blockNumber` 100 times and `eth_getBlockByNumber` with a bad argument 3 times: the
   table shows the calls, the errors and latencies.
2. Sepolia with a consensus client: the monitor shows the client's name and version, the link pulses with calls, the
   call log scrolls. Stop the consensus client: after 60 s the link is down and the alert row says so.
3. Start the node with a wrong JWT secret on the consensus client: authentication failures with their reason.
4. Mordor: the mining monitor, and no consensus-client element.

## Slice 6: storage, system, health

1. `6` and `7`: the database size agrees with `du -sh <datadir>/rocksdb` within 5 %; disk free with `df`.
2. Fill the disk past the warning threshold (a large temporary file): the Disk bar and the alert row change
   together. Enter on the bar: the check, its value, its threshold, since when.
3. `fukuii.tui.alerts.disk-warn = "50 GiB"`: the warning fires earlier. An invalid value: the node refuses to start
   and names the key.

## Slice 7: actions

1. `9`. `:loglevel DEBUG`: the dialog shows before and after; apply; the log gains DEBUG lines and one
   `fukuii.tui.ops` line. `:loglevel reset`.
2. On Peers, `a` on a peer, disconnect: the preview names the peer; `y`; it is gone and may return.
3. `:rpc stop http`, then `curl` fails; `:rpc start http`, then it answers.
4. `:config jwt`: the path is shown, never the secret. `:bundle`: the preview lists the files; unpack the bundle and
   search it for the JWT secret and the node key: neither is there.
5. `q`: type `stop`. The node stops.
6. `FUKUII_TUI_READ_ONLY=1`: every action above is refused, and the Ops view says the TUI is read-only.
7. Paste `q`, a newline and `stop` into the window: the node keeps running.
