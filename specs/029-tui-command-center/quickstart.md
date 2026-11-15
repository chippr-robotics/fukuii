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
3. With DEBUG logging, enter and leave the Mempool view: the log has one line when the whole-pool reading starts
   and one when it stops, and `Enter` on a transaction (the explorer over the view) stops it too.
4. `Enter` on a pending transaction: the explorer says it is pending and for how long; `:receipt <its hash>` says a
   pending transaction has no receipt. Once it is mined, `r` shows it in its block.

## Slice 5: RPC and the consensus-client link

1. `5`. Each transport says whether it listens, where and what it serves. Run `curl` against `eth_blockNumber` 100
   times, `eth_getBlockByNumber` with a bad argument 3 times and a method that does not exist twice: the table shows
   100 calls, 3 failed with their latency, and 2 under `(no such method)`; the HTTP row's calls went up by 105.
2. A node with a consensus client: the monitor shows the client's name and version, the link's marker moves one
   place a call and not otherwise, the call log gains a row a call with what was told and how long it took. Stop
   the consensus client: after 60 s the link says DISCONNECTED and the alert row says so.
3. Start the consensus client with a wrong JWT secret: the link waits, and the Auth row counts the refusals under
   "wrong signature (another secret)".
4. A node that mines (Mordor, or a private PoW chain): the mining monitor with its two charts, and no
   consensus-client element.
5. Bind HTTP to `0.0.0.0` with `admin` among its namespaces: the warning stands above the transports, as at start-up.

## Slice 6: storage, system, health

1. `6`. The `Size` row agrees with `du -sh <datadir>/rocksdb` within 5 % (the write-ahead log is the difference),
   and the `Disk` row with `df` on the datadir. The stores' sizes sum to the size, and the row says how old the
   reading is. With `db.rocksdb.enable-statistics` off (the default) the `Cache` row says hit rates are not counted;
   with it on, a rate appears after two readings.
2. `7`. The meters fill from the left, a point every five seconds. `Net in`/`Net out` move with the peers' traffic
   and stand at nothing with no peer. `Process` is the JVM's uptime (compare `ps -o etime`), not the dashboard's:
   attach late, or restart only the TUI, and it does not start again.
3. Fill the disk past the warning threshold (a large temporary file on the datadir's file system): the Disk bar,
   the Overview's Disk cell and the alert row change at the same poll. `Down` to the Disk bar, `Enter`: the check,
   what it reads, its threshold, its state and since when. Remove the file: all three clear together.
4. `fukuii.tui.alerts.disk-warn = "500 GiB"` (above what is free): the warning stands from the start. An invalid
   value (`disk-warn = lots`), a key set to `null`, or a key that is no threshold (`disk-warning = 50 GiB`): the
   node refuses to start and names the key.
5. While a SNAP sync or a long compaction writes: the dashboard stays responsive on every view, the `Size` row's
   age grows and resets, and nothing in the node's log says a write waited on the dashboard.
6. A node whose background audit runs (after a SNAP sync hands over): the `Audit` row counts nodes, and reads
   `not running now` once the audit has ended by itself after a clean pass, not `did not answer`.
7. 80×24: the System view lists the bars and as many meters as fit, and counts the rest; the Storage view counts
   the stores and marks it has no rows for.

## Slice 7: actions

Run these on a side instance, never on a node whose uptime matters: steps 6, 9 and 10 stop things.

1. `9`. The Ops view lists every action with its class. `:loglevel DEBUG`: the dialog shows the level now and after,
   with Cancel focused; `Enter` alone cancels. Again, `Tab`, `Enter`: the log gains DEBUG lines and one
   `fukuii.tui.ops` line with `outcome=confirmed`. `:loglevel reset` puts it back. `:loglevel DEBUG no.such.logger`
   is refused and names the nearest loggers.
2. On Peers, `a` on a peer: the prompt holds `peer drop <id>`. `Enter`: the preview names the peer, its client and
   address; `y`; it is gone and may return. Check with `admin_peers` on another terminal if the namespace is on.
3. `:peer block <address of a connected peer>`: the preview lists the peers at that address; `y`; they are gone and
   do not return while the block stands (B-277: before this slice `admin_blockIP` blocked nothing).
   `:peer unblock <address>`; the peer may return. `:tunables` shows the block while it stands.
4. `:rpc stop http`: the preview names the address, the namespaces and what fails; `y`; `curl` to the port is
   refused, and the RPC view says `stopped here`. `:rpc start http`; `curl` answers. With WebSocket enabled and a
   client subscribed to `newHeads`: `:rpc stop ws` closes the client at once. `:rpc start ws` on a node started
   with WebSocket off is refused and says the dashboard enables nothing.
5. `:test-rpc`: one row for each enabled endpoint, with what answered and how long it took; the Engine API row
   says an unauthenticated request was refused. `:net`: the listening address, what was detected and how.
6. `:config jwt`: the path is shown, never the secret; `:config password` shows `<hidden>` for each. `:bundle`: the
   preview lists the files and their sizes; `y`; under `<datadir>/support/bundle-<time>/`, `grep -r` for the JWT
   secret's hex and the node key finds neither, and `ls -l` shows the files readable by the owner only.
7. `:dbcheck` on a node that synced block by block reports the points read and none missing; on a node that took
   its state by SNAP, or an Engine API node, it says why the check means nothing there. The dashboard stays
   responsive while it runs, and the Ops view shows its progress.
8. `:threshold disk-warn 500GiB` (above what is free): the preview names the alert that would fire; apply; the
   alert row and the Disk bar change at once. `:threshold reset`.
9. ETC node mining with its own miner: `a` on the RPC view, `Enter`: the dialog asks for the word. `y` does
   nothing; `stop`, `Enter`: the miner stops, the mining panel says `STOPPED until the node restarts`, and
   `:mining start` is refused with the reason. On an ETH node `:mining stop` says there is no miner.
10. `q`: the dialog asks for the word. `y` does nothing. `stop`, `Enter`: the node stops.
11. `FUKUII_TUI_READ_ONLY=1`: every action above but `config`, `tunables`, `deps`, `test-rpc` and `net` is refused
    with one line and no dialog, and the Ops view says the dashboard is read-only.
12. Paste `q`, a newline and `y` into the window: the node keeps running. 80×24: a dialog whose preview does not
    fit says so and cannot be confirmed; widen the terminal and it can.
