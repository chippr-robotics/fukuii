# TUI design proposal — "the worm goes to the brain"

Status: proposal, 2026-10-07. Baseline is the `--tui` code as of commit `4fdf6dc`
(`fix(tui): address review: independent teardown steps, fitted banner, test scoping`).
No source files were changed for this document.

The TUI is the front end for anyone running `fukuii <network> --tui`. It has to be
two things at once: a delightful first impression, and the fastest way to answer
"is my node OK, and if not, why?". This document grounds every claim in the code as
it exists today (file:line), proposes exact screens per terminal tier, and splits the
work into five independently shippable increments.

---

## 1. Design principles

1. **Charm is a lens, never a curtain.** The worm (`🪱`) crawling to the brain
   (`🧠`) is the progress bar — the *same* bar already written to logs by
   `WormToBrainBar.scala:12-33` and `SyncProgressMonitor.scala:386-445`. Fun
   elements only ever *encode* real state (progress, phase, freshness). When
   something is wrong the alert row wins: it is always the first body row on every
   view, it is never animated, and it is never hidden by the logo.
2. **Five-second rule.** On any view, within five seconds a user must be able to
   read: network, peers, head vs. best block, sync phase + ETA, and whether anything
   is wrong. That is the header + the first four body rows at every tier.
3. **Degrade, never break.** 80×24 is a first-class target, not a fallback. Every
   frame is exactly `height × width` columns (the contract `TuiRenderer.render`
   already enforces, `TuiRenderer.scala:14-43`). Emoji, colour and motion are each
   independently removable (§5, §4.5) without changing layout.
4. **Pure renderer, deterministic animation.** `TuiRenderer` stays
   `state × width × height → lines`. Motion is a function of a `tick: Long` field in
   `TuiState`, never wall-clock or randomness, so every frame is a golden-file test.
5. **Never block the frame.** The probe's `Await`s (`TuiStatusProbe.scala:417-425`,
   500 ms each) must not sit on the frame loop once we animate. A slow actor shows
   `Unresponsive`, the rest of the screen keeps updating (§7.2).
6. **No consensus coupling.** Every panel reads existing actors, `MetricsContainer`
   objects or config. New plumbing is limited to metrics objects and a logback
   appender (§6).

---

## 2. Information architecture

### 2.1 Views

| Key | View | Purpose | What it answers |
|-----|------|---------|-----------------|
| `1` | **Overview** (default) | Everything on one screen, tier-scaled | Is the node healthy? |
| `2` | **Sync** | SNAP phase rows with animated worms, or regular-sync worm + rate/ETA | Is sync moving, and where is it? |
| `3` | **Peers** | Handshaked peers: direction, client, best block, score with ↑↓→ trend | Am I connected to good peers? |
| `4` | **Chain** | Fork timeline (block-number for ETC, timestamp for ETH), genesis, chain id, current rule set | Which rules am I running, what's next? |
| `5` | **Logs** | Tail of the last N log lines (ring buffer), level-coloured | What just happened? |
| `?` | **Help** | Key bindings + glyph legend (shows the ASCII mapping if active) | How do I drive this? |

### 2.2 Key bindings

Existing bindings are kept exactly (`Tui.scala:158-173`). Input is a single
lower-cased char read with a bounded timeout (`Tui.scala:147-155`), so all new
bindings are single printable characters or `\t`; escape sequences (arrow keys)
are deliberately **not** used because a half-read escape sequence would be
mis-dispatched as `[`.

| Key | Action | Notes |
|-----|--------|-------|
| `q` | Quit node | unchanged |
| `r` | Force full redraw | unchanged; also recomputes tier |
| `d` | Detach TUI, node keeps running | unchanged (renamed "Detach" in footer) |
| `1`–`5` | Jump to view | |
| `\t` | Next view (wraps) | Shift-Tab is an escape sequence → not supported |
| `l` | Jump to Logs (alias of `5`) | |
| `p` | Pause/resume animation | state flag; polling continues |
| `?` / `h` | Help overlay | any key returns |
| `g` | Cycle glyph set (emoji → ascii) at runtime | for terminals that render emoji badly |
| `+` / `-` | Logs view: scroll tail | Overview: no-op |

### 2.3 What shows first / when something is wrong

Row order on every view: **header → alert row → view body → footer**. The alert
row (`⚠`/`!` badge, red or yellow) is populated from the conditions in the table
below and reads `✓ no alerts` when empty. It cannot be scrolled off and it is never
animated.

| Condition | Severity | Source today |
|-----------|----------|--------------|
| 0 handshaked peers for > 30 s | error | `NodeStatusSnapshot.peerCount` |
| Peer manager / sync controller unresponsive | error | `TuiStatusProbe.scala:429,439` fold branches |
| ETH: no `engine_forkchoiceUpdated` for > 60 s (5 slots) | error | needs plumbing (§6, E1) |
| ETH: no `engine_newPayload` for > 5 min while peers say chain advanced | warn | needs plumbing (E1) |
| Sync stalled: `currentBlock` unchanged > 120 s while `blocksRemaining > 0` | warn | `TuiState.syncBaseline` + new `lastAdvanceTick` |
| SNAP sample older than 90 s during sync | warn | `TuiStatusProbe.SnapProgressMaxAgeMillis` |
| Disk free < 20 GB (error < 5 GB) | warn/error | `File(dataDir).getUsableSpace` (new, trivial) |
| Peers < 25 % of max | warn | existing `createPeerStatusLine` thresholds, `TuiRenderer.scala:265-268` |
| ETC mining enabled but hashrate 0 for > 10 min | warn | `PoWMiningMetrics` (§6, M1) |

---

## 3. Text mockups

Conventions: every mockup is the exact frame; emoji occupy two columns; the last
row is the pinned footer. Tiers (§3.1): **S** = anything < 100 cols or < 32 rows
(reference 80×24); **M** = 100–139 cols and ≥ 32 rows (reference 120×40);
**L** = ≥ 140 cols and ≥ 45 rows (reference 160×48).

### 3.1 Tier contents

| Element | S (80×24) | M (120×40) | L (160×48) |
|---------|-----------|------------|------------|
| Header band | network · chain id · consensus · uptime · view name | + head block, peers inline | + git version |
| Logo | none in dashboard; wordmark on splash only | none in dashboard; 12-line logo on splash | 12-line logo (`TuiRenderer.scala:298-320`) in a right-hand column on Overview |
| Overview body | peers/block strip, SNAP 5 worm rows, mining/CL one-liner, forks one-liner, 6-line recent-log strip | + per-row rates, fork timeline (3 rows), 10-line log strip, resources row | + peers mini-table (top 8), txpool, two-column layout |
| Sync view | worm rows (track 20 cells) | track 40 cells + rates + ETA per row | track 60 cells + sparkline of recent rate (ASCII `▁▂▃▅▇`) |
| Peers view | 1 line per peer, 18 peers max | + client id, best block, score | + direction, age, score trend |
| Chain view | next fork + last fork | full timeline, 1 row per fork | timeline + per-fork EIP list |

### 3.2 Overview — S tier, 80×24, ETC mainnet, SNAP sync in progress

Width 80, height 24:
```text
 ◆ FUKUII  etc (61) · PoW · up 1h 23m                                  Overview 
────────────────────────────────────────────────────────────────────────────────
 ✓ no alerts                                                                    
 Peers   18/50 ◆◆◆◆◆◆◆◆◆◆         Block  15,234,567 → 15,234,890 (−323)         
 RPC     :8545 on                 Disk   412G free      Txpool  142             
────────────────────────────────────────────────────────────────────────────────
 SNAP 💾 Downloading bytecodes & storage                       elapsed 2h 11m   
   Overall  [==========🪱.........]🧠 52%                         ETA 1h 58m    
   Accounts 🪱[COMPLETE ✓]🧠              34.1M                                 
   Storage  [=======🪱............]🧠 37%  119.6M slots @ 21.4K/s               
   Bytecode [=============🪱......]🧠 68%  412K @ 310/s                         
   Healing  🪱[QUEUED]🧠                                                        
────────────────────────────────────────────────────────────────────────────────
 Mining  off · coinbase 0x4f2a…c91e · DAG epoch 512 ✓         Hashrate —        
 Forks   Mystique ✓ ════════════════════════▶ Olympia @ 21,000,000 (5.7M away)  
────────────────────────────────────────────────────────────────────────────────
 Recent                                                                         
 12:04:51 INFO  SNAP: storage 119.6M slots (37%) @ 21.4K/s                      
 12:04:49 INFO  Peer 0x3fa1…e2 score ↑ 0.612 → 0.640 after valid range          
 12:04:45 WARN  Peer 0x9c0d…77 timed out GetStorageRanges, retrying             
 12:04:40 INFO  [=======🪱............]🧠 37% — Storage                         
 12:04:31 INFO  Peer 0x3fa1…e2 score ↑ 0.590 → 0.612 after valid range          
 12:04:21 INFO  SNAP: bytecodes 412K/~600K (68%) @ 310/s                        
 1 Over 2 Sync 3 Peers 4 Chain 5 Logs │ p Pause ? Help r Redraw d Detach q Quit 
```

### 3.3 Overview — M tier, 120×40, Sepolia (PoS), regular sync near head

Width 120, height 40:
```text
 ◆ FUKUII  sepolia (11155111) · PoS · head 7,412,009 · 23/50 peers · up 3d 4h                                  Overview 
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 ✓ no alerts                                                                                                            
 Peers   23/50 ◆◆◆◆◆◆◆◆◆◆                     Block   7,412,009 → 7,412,011 (−2)         Txpool  1,204 (3 blob)         
 RPC     :8545 on   Engine :8551 on            Disk    1.2T free                           JVM     2.1G / 8.0G heap     
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Sync    regular                                                                            rate 1.00 blk/s · ETA 2s    
   Blocks   [======================================🪱.]🧠 99%   7,412,009 / 7,412,011                                   
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Consensus layer 🔗 connected · last forkchoiceUpdated 4s ago (VALID) · last newPayload 4s ago (VALID)                  
   head 0x7b3e…a91c   safe 7,411,977   finalized 7,411,945   payloads 184,203 (VALID 184,199 · SYNCING 4 · INVALID 0)   
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Forks (timestamp)                                                                                                      
   Cancun ✓ ──── Prague ✓ ──── Osaka ✓ ════════════════════════════════════════▶ Amsterdam @ 1791294816 (in 0d 0h 00m)  
   now 1791294816 · Amsterdam activates 2026-10-06T14:33:36Z                                                            
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Recent                                                                                                                 
 14:33:31 INFO  Imported block 7,412,009 (0x7b3e…a91c) 142 txs 18.2M gas in 61ms                                        
 14:33:31 INFO  engine_forkchoiceUpdated head=7,412,009 safe=7,411,977 finalized=7,411,945 → VALID                      
 14:33:31 INFO  engine_newPayload block=7,412,009 → VALID                                                               
 14:33:19 INFO  Imported block 7,412,008 (0x2c11…04fe) 98 txs 12.7M gas in 48ms                                         
 14:33:19 INFO  engine_forkchoiceUpdated head=7,412,008 → VALID                                                         
 14:33:19 INFO  engine_newPayload block=7,412,008 → VALID                                                               
 14:33:07 INFO  Imported block 7,412,007 (0x91aa…77d3) 161 txs 21.0M gas in 70ms                                        
 14:33:07 INFO  engine_forkchoiceUpdated head=7,412,007 → VALID                                                         
 14:33:07 DEBUG Peer 0x55c0…1b score → 0.812 → 0.812 after new block                                                    
 14:32:55 INFO  Imported block 7,412,006 (0x0e4d…b2a0) 77 txs 9.9M gas in 39ms                                          
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
 1 Overview  2 Sync  3 Peers  4 Chain  5 Logs │ p Pause  ? Help  r Redraw  d Detach  q Quit                             
```

### 3.4 Overview — L tier, 160×48, ETC mainnet, synced, mining on

Width 160, height 48:
```text
 ◆ FUKUII  etc (61) · PoW · head 21,884,102 · 41/50 peers · up 12d 7h · v0.8.21                                                                        Overview 
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 ✓ no alerts                                                                                                                                                    
 Peers   41/50 ◆◆◆◆◆◆◆◆◆◆       Block  21,884,102 (at head)       Txpool  31       Disk  388G free       JVM  3.4G / 8.0G heap       GC  0.3%                   
 RPC     :8545 on   WS :8546 on   IPC on                           Discovery  1,912 known · 14 pending · 3 blacklisted                                          
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Sync    🪱[COMPLETE ✓]🧠  synced 11h 02m ago · regular sync importing at head                                   │                 --                           
   Blocks   [=========================================================🪱]🧠 100%   21,884,102 / 21,884,102          │               .=+#+.                      
                                                                                                                 │              .+++*#*.                        
 Mining  ⛏ on · coinbase 0x4f2a0b9c…c91e · DAG epoch 731 ✓ loaded                                                  │             :++++*###-                     
   Hashrate  48.2 MH/s      blocks mined 3 (last 2h 14m ago, 4m 31s)      stale 0      getWork 1,204/min         │           .=+++++*####+.                     
                                                                                                                 │          .=++++++*#####+.                    
 Forks (block)                                                                                                   │         :++++++++*#######:                   
   Atlantis ✓ ─ Agharta ✓ ─ Phoenix ✓ ─ Thanos ✓ ─ Magneto ✓ ─ Mystique ✓ ─ Spiral ✓ ════════▶ Olympia @ —        │        :+++++++++*########-                 
   Olympia not scheduled on this network (block Long.MaxValue)                                                   │       =++++++++++*#########+                 
                                                                                                                 │     .++++++++++++*##########*.               
 Peers (top 8 by score)                                                                                          │   .:+++++++++++++*############:              
   id          dir  client                     best        score  trend  age                                     │   -++++++++++++++*#############=             
   0x3fa1…e2   out  core-geth/1.12.19          21,884,102  0.912  ↑      4h 10m                                  │                                              
   0x55c0…1b   in   besu/24.1.2                21,884,102  0.871  →      2d 1h                                   │                                              
   0x9c0d…77   out  fukuii/0.8.21              21,884,101  0.844  ↑      11h 40m                                 │                                              
   0x1e22…40   out  core-geth/1.12.19          21,884,102  0.802  →      3d 5h                                   │                                              
   0xa0b7…9f   in   nethermind/1.25.4          21,884,100  0.711  ↓      19m                                     │                                              
   0xc4d5…03   out  core-geth/1.12.17          21,884,102  0.690  →      1d 2h                                   │                                              
   0x7788…ee   in   besu/24.1.2                21,884,099  0.655  ↓      6m                                      │                                              
   0x02fb…c1   out  core-geth/1.12.19          21,884,102  0.640  ↑      8h 3m                                   │                                              
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Recent                                                                                                                                                         
 09:12:04 INFO  Imported block 21,884,102 (0x6d21…1a0e) 14 txs 1.1M gas in 22ms                                                                                 
 09:12:04 INFO  Peer 0x3fa1…e2 score ↑ 0.904 → 0.912 after new block                                                                                            
 09:11:51 INFO  Imported block 21,884,101 (0x9b0c…ff31) 9 txs 0.6M gas in 17ms                                                                                  
 09:11:51 INFO  Peer 0x9c0d…77 score ↑ 0.838 → 0.844 after new block                                                                                            
 09:11:38 INFO  Imported block 21,884,100 (0x3301…2e9d) 21 txs 1.9M gas in 31ms                                                                                 
 09:11:38 INFO  eth_getWork served (block 21,884,101, difficulty 1.21e14)                                                                                       
 09:11:25 INFO  Imported block 21,884,099 (0x7ab2…d4c7) 6 txs 0.4M gas in 14ms                                                                                  
 09:11:25 INFO  Peer 0xa0b7…9f score ↓ 0.720 → 0.711 after stale block                                                                                          
 09:11:12 INFO  Imported block 21,884,098 (0x1f09…88a1) 17 txs 1.4M gas in 26ms                                                                                 
 09:11:12 INFO  Peer 0x7788…ee score ↓ 0.661 → 0.655 after late response                                                                                        
 09:10:59 INFO  Imported block 21,884,097 (0xcc4e…0b10) 11 txs 0.8M gas in 19ms                                                                                 
 09:10:59 INFO  eth_getWork served (block 21,884,098, difficulty 1.21e14)                                                                                       
                                                                                                                                                                
                                                                                                                                                                
                                                                                                                                                                
                                                                                                                                                                
                                                                                                                                                                
                                                                                                                                                                
                                                                                                                                                                
 1 Overview  2 Sync  3 Peers  4 Chain  5 Logs │ p Pause  ? Help  r Redraw  d Detach  q Quit                                                                     
```

### 3.5 Sync view — M tier, 120×40, SNAP in progress (four animated worms)

Each worm row's position is progress; the crawl ripple (§4.1) is the `~` just
behind the worm; `tick` here is 7 so rows with motion show the ripple at cell
`wormAt − 1 − (tick mod 3)`.

Width 120, height 40:
```text
 ◆ FUKUII  mordor (63) · PoW · head 9,118,004 · 11/50 peers · up 0h 41m                                            Sync 
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 ⚠ 1 warn   SNAP sample is 48s old (monitor samples every 30s)                                                          
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 SNAP sync   💾 Downloading bytecodes & storage              pivot 9,118,000          elapsed 2h 11m        ETA 1h 58m  
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Overall    [=====================~🪱..................]🧠 52%                                                          
 Accounts   🪱[COMPLETE ✓]🧠                                      34.1M / ~34.1M              avg 4.8K/s    phase 1h 03m
 Storage    [===============~🪱........................]🧠 37%      119.6M slots · 2,114/5,702 contracts   now 21.4K/s  
 Bytecodes  [===========================~🪱............]🧠 68%      412K / ~600K bytecodes                    now 310/s 
 Healing    🪱[QUEUED]🧠                                            waiting for storage phase                           
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Rates (recent / overall)        accounts —/4.8K  ·  slots 21.4K/18.9K  ·  bytecodes 310/288  ·  nodes —/—              
 Requests                        account 48,112 ✓ 31 ✗   storage 210,004 ✓ 402 ✗   bytecode 9,331 ✓ 12 ✗   timeouts 118 
 Peers (snap-capable)            9 of 11 · blacklisted this run 2                                                       
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Phase timeline                                                                                                         
   AccountRange ✓ 1h 03m ──── ByteCodeAndStorage ● 1h 08m ──── StateHealing · ──── StateValidation · ─── ChainDownload ·
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Recent (sync)                                                                                                          
 12:04:51 INFO  SNAP: storage 119.6M slots (37%) @ 21.4K/s                                                              
 12:04:40 INFO  [===============🪱........................]🧠 37% — Storage                                             
 12:04:21 INFO  SNAP: bytecodes 412K/~600K (68%) @ 310/s                                                                
 12:03:50 INFO  SNAP: storage 118.3M slots (36%) @ 20.9K/s                                                              
 12:03:21 INFO  SNAP: bytecodes 403K/~600K (67%) @ 301/s                                                                
 12:02:50 INFO  SNAP: storage 117.1M slots (36%) @ 21.1K/s                                                              
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
                                                                                                                        
 1 Overview  2 Sync  3 Peers  4 Chain  5 Logs │ p Pause  ? Help  r Redraw  d Detach  q Quit                             
```

### 3.6 Sync view — S tier, 80×24, regular sync (block-by-block)

Width 80, height 24:
```text
 ◆ FUKUII  etc (61) · PoW · up 0h 09m                                      Sync 
────────────────────────────────────────────────────────────────────────────────
 ✓ no alerts                                                                    
────────────────────────────────────────────────────────────────────────────────
 Regular sync                                    rate 184.2 blk/s · ETA 3h 41m  
   Blocks   [=====~🪱.............]🧠 28%   18,402,113 / 21,884,102             
   Session  imported 101,220 blocks since 18,300,893 (9m ago)                   
   Lag      3,481,989 blocks behind best known                                  
────────────────────────────────────────────────────────────────────────────────
 Import     last block 0x6d21…1a0e · 14 txs · 1.1M gas · 22ms                   
 Fetcher    headers 18,402,500 · bodies 18,402,300 · queue 387                  
────────────────────────────────────────────────────────────────────────────────
 Recent (sync)                                                                  
 09:12:04 INFO  RegularSync: current=18402113 best=21884102 lag=3481989 rate=184
 09:12:04 INFO  [=====🪱..............]🧠 28% — RegularSync                     
 09:11:34 INFO  RegularSync: current=18396591 best=21884101 lag=3487510 rate=181
 09:11:34 INFO  [=====🪱..............]🧠 28% — RegularSync                     
 09:11:04 INFO  RegularSync: current=18391159 best=21884100 lag=3492941 rate=179
 09:11:04 INFO  [=====🪱..............]🧠 28% — RegularSync                     
                                                                                
                                                                                
                                                                                
                                                                                
 1 Over 2 Sync 3 Peers 4 Chain 5 Logs │ p Pause ? Help r Redraw d Detach q Quit 
```

### 3.7 Startup splash → dashboard

Today `showStartupBanner` paints a figlet "FOOO" (sic — `TuiRenderer.scala:172-176`
spells F-O-O-O, not FUKUII) for `bannerDisplayDurationMs = 1000` with a
`Thread.sleep` (`Tui.scala:199-205`). Proposed: the splash is a *view* rendered by
the same pure renderer for the first `splashTicks` ticks, so it is testable and
interruptible by any key.

- **L tier (≥ 131 cols, ≥ 66 rows):** the full 129-col logo from
  `Fukuii.printBanner()` (`Fukuii.scala:177-249`), centred, with the wordmark rows
  (its last 11 non-blank rows) drawn one row per tick (a "reveal"), then the
  worm crawls once across the bottom and the dashboard fades in by replacing
  rows top-down over 6 ticks.
- **M tier:** the 12-line small logo (`TuiRenderer.scala:299-311`) + a 6-row
  figlet wordmark (new, spelling FUKUII) + one worm bar.
- **S tier (80×24):** wordmark + worm bar only.

Width 80, height 24 (S-tier splash at tick 3 of 12):
```text
                                                                                
                                                                                
                                                                                
                    ______ __  __ _  __ __  __ ___  ___                         
                   / ____// / / // |/ // / / //   |/   |                        
                  / /_   / / / //    // / / /  /|  /| |                         
                 / __/  / /_/ // /|  // /_/ /  / |_/ | |                        
                /_/     \____//_/ |_/ \____/  /_/    |_|                        
                                                                                
                         multi-network EVM client · v0.8.21                     
                                                                                
                            etc (61) · PoW · snap sync                          
                                                                                
                    [======🪱.............]🧠  starting node…                   
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
                                                                                
 any key to skip                                                                
```

The transition into the dashboard: each tick, the top `k` rows of the splash are
replaced by the top `k` rows of the Overview frame (`k = 4 × ticksSinceSplashEnd`),
so the header band "lands" first. No partial-row tricks, so widths hold.

### 3.8 Synced — the worm reaches the brain (brief celebration)

Triggered once on the transition `isSynchronized: false → true`
(`TuiState.scala:385-386`). 12 ticks (3 s at 4 fps), then the Sync panel collapses
to its one-line synced form (see §3.4 row 7). Reduced motion: frame 3 only, 1 tick.

Width 80, rows 6–11 of the Overview during frames 0 / 2 / 4 / 6 / 8 / 10:
```text
   Blocks   [==================🪱.]🧠 99%     21,884,101 / 21,884,102           
   Blocks   [===================🪱]🧠 100%    21,884,102 / 21,884,102           
   Blocks   [====================]🪱🧠 100%   21,884,102 / 21,884,102           
   Blocks   [====================]✨🧠✨ synced  21,884,102 · 1h 48m total      
   Blocks   🪱[COMPLETE ✓]🧠 ✨ synced          21,884,102 · 1h 48m total       
   Blocks   🪱[COMPLETE ✓]🧠  synced           21,884,102 · 1h 48m total        
```

The header band flashes to the accent colour for frames 2–6, then returns. Nothing
else on screen moves during the celebration.

### 3.9 Error and warning states

Alert rows are red (error) / yellow (warn) and list up to three conditions; the
Overview body keeps rendering under them. Width 80, representative rows:

```text
 ✗ 2 errors  0 peers for 4m 12s · peer manager Unresponsive (ask timeout 500ms)
```
```text
 ✗ 1 error   CL disconnected: no engine_forkchoiceUpdated for 6m 03s (last VALID)
 Consensus layer 🔗 DISCONNECTED · last forkchoiceUpdated 6m 03s ago · newPayload 6m 05s
```
```text
 ⚠ 1 warn    sync stalled: head 18,402,113 unchanged for 3m 10s (peers 11/50)
   Blocks   [=====🪱💤.............]🧠 28%   18,402,113 / 21,884,102   stalled 3m
```
```text
 ⚠ 2 warns   disk 14.2G free on /data (warn <20G) · peers 6/50 below 25%
 RPC     :8545 on                 Disk   14.2G free ⚠   Txpool  142
```

Stalled worm: the ripple stops, `💤` (ASCII: `z`) appears in the cell after the
worm, and the bar colour drops from healthy to warn. Position never moves
backwards; a reorg/rewind that lowers `currentBlock` resets the baseline as today
(`TuiState.scala:413-416`) and the worm jumps, which is correct information.

### 3.10 ASCII fallback — the 80×24 Overview from §3.2

Selected by `FUKUII_TUI_GLYPHS=ascii`, by `tui.glyphs = ascii`, automatically when
the JVM's `sun.jnu.encoding`/`LANG` is not UTF-8, or at runtime with `g`.
Column budgets are identical; only glyphs change (§5.2).

Width 80, height 24:
```text
 * FUKUII  etc (61) . PoW . up 1h 23m                                  Overview 
--------------------------------------------------------------------------------
 ok no alerts                                                                   
 Peers   18/50 ##########         Block  15,234,567 -> 15,234,890 (-323)        
 RPC     :8545 on                 Disk   412G free      Txpool  142             
--------------------------------------------------------------------------------
 SNAP [S] Downloading bytecodes & storage                      elapsed 2h 11m   
   Overall  [==========~o.........]@@ 52%                         ETA 1h 58m    
   Accounts ~o[COMPLETE +]@@              34.1M                                 
   Storage  [=======~o............]@@ 37%  119.6M slots @ 21.4K/s               
   Bytecode [=============~o......]@@ 68%  412K @ 310/s                         
   Healing  ~o[QUEUED]@@                                                        
--------------------------------------------------------------------------------
 Mining  off . coinbase 0x4f2a..c91e . DAG epoch 512 +         Hashrate -       
 Forks   Mystique + ==========================> Olympia @ 21,000,000 (5.7M away)
--------------------------------------------------------------------------------
 Recent                                                                         
 12:04:51 INFO  SNAP: storage 119.6M slots (37%) @ 21.4K/s                      
 12:04:49 INFO  Peer 0x3fa1..e2 score ^ 0.612 -> 0.640 after valid range        
 12:04:45 WARN  Peer 0x9c0d..77 timed out GetStorageRanges, retrying            
 12:04:40 INFO  [=======~o............]@@ 37% -- Storage                        
 12:04:31 INFO  Peer 0x3fa1..e2 score ^ 0.590 -> 0.612 after valid range        
 12:04:21 INFO  SNAP: bytecodes 412K/~600K (68%) @ 310/s                        
 1 Over 2 Sync 3 Peers 4 Chain 5 Logs | p Pause ? Help r Redraw d Detach q Quit 
```

The ASCII worm is `~o` and the brain `@@` so both stay two columns wide; log lines
captured from the ring buffer are passed through the same glyph mapper
(`🪱→~o`, `🧠→@@`, `✓→+`, `↑↓→` → `^ v -`, `…→..`), so logs never widen a row.

---

## 4. Animation spec

### 4.1 Worm rules

- **Position** = progress, exactly as `WormToBrainBar.renderKnown`
  (`WormToBrainBar.scala:16`: `wormAt = (p × (slots − 1)).toInt`). Cells before the
  worm are `=`, after are `.`. The worm never moves except because progress moved.
- **Crawl ripple** (the only per-tick motion): one `=` behind the worm is drawn as
  `~`, at cell `wormAt − 1 − (tick mod 3)`, clamped to ≥ 0. Suppressed when
  `wormAt < 2`, when stalled, and under reduced motion. It reads as the worm
  inching forward without changing its column.
- **Stalled**: progress unchanged for `stallAfterTicks` (default 120 s): ripple
  off, `💤`/`z` in the cell after the worm, bar styled `warn`. Clears on the next
  advance.
- **Unknown total**: `🪱[ACTIVE]🧠` pulses by alternating the bracket text
  `[ACTIVE]` / `[ACTIVE.]` / `[ACTIVE..]` / `[ACTIVE...]` padded to a fixed 11 cols
  (`[ACTIVE...]`), so width is constant. `[QUEUED]` and `[COMPLETE ✓]` never move.
- **Track length** is allocated by the layout (S 20 cells, M 40, L 60) and the bar
  builder takes a *column budget*, not a slot count: `cells = budget − 2 (brackets)
  − wormCols − brainCols − 5 (" 100%")`. This is the only place widths are decided.

### 4.2 Spinners and freshness

- A 4-frame ASCII spinner `|/-\` (same in both glyph sets; 1 col) next to any
  "in flight" status (`starting node…`, `waiting for peers`, DAG generating).
  Frame = `tick mod 4`.
- Freshness ages (`4s ago`) are computed from `sampledAt` in the snapshot vs. the
  snapshot's own `now` field, both captured by the probe — the renderer never
  calls `Instant.now()`. (`TuiState.uptimeSeconds` and `syncSpeedBlocksPerSec`
  call `Instant.now()` today, `TuiState.scala:389,401`; both move to the probe.)

### 4.3 Celebration

§3.8: 12-frame sequence driven by `celebrationStartedTick: Option[Long]`. It is
the only sequence longer than 4 frames, it runs once per process, and `p` or any
view change cancels it.

### 4.4 Frame rate and CPU

- Animation tick: 250 ms (4 fps). Status poll: 1000 ms (every 4th tick), unchanged
  from `TuiConfig.DefaultUpdateIntervalMs`.
- Justification: the ripple and spinner are unreadable at 1 fps (they look like
  flicker), and 4 fps is the lowest rate at which motion reads as motion. Cost:
  `render` is pure string assembly over ≤ 48 × 160 cells (~8 k chars) — well under
  a millisecond — and a 4 fps write of ≤ 20 KB/s to the TTY is negligible. The
  frame loop skips the write when the rendered lines equal the previous frame
  (cheap `Seq[AttributedString]` equality), so a synced, idle node redraws only
  when a value changes, which is at most once a second.
- Pause (`p`) freezes `tick` but polling continues, so values still update.

### 4.5 Reduced motion

`FUKUII_TUI_MOTION=reduced`, `tui.reduced-motion = true`, or `p` at runtime.
Effects: no ripple, no spinner (static `·`), no splash reveal (one static frame
for 1 s), celebration is the single "COMPLETE" frame, header never flashes. Worm
position still tracks progress. `NO_COLOR` does not imply reduced motion.

---

## 5. Colour and glyph system

### 5.1 Palette roles

| Role | 16-colour | 256/truecolour | Monochrome (`NO_COLOR` / `TERM=*-m`) |
|------|-----------|----------------|--------------------------------------|
| `healthy` | green | #3fb950 | bold |
| `warn` | yellow | #d29922 | bold + `⚠`/`!` prefix |
| `error` | red bold | #f85149 | reverse video |
| `accent` (header/footer band, worm track) | black on green | #0d1117 on #3fb950 | reverse video |
| `label` | cyan | #58a6ff | normal |
| `value` | white bold | #e6edf3 | bold |
| `muted` (separators, queued rows, log DEBUG) | default dim | #6e7681 | normal |
| `log.WARN` / `log.ERROR` | yellow / red | as warn/error | `W`/`E` column stays |

Selection: JLine reports colour depth via `terminal.getNumericCapability(
Capability.max_colors)`; `AttributedStyle` already resolves to the right escape
sequences in `toAnsi(term)` (`Tui.scala:135`). `NO_COLOR` set (any value, per
no-color.org) or `max_colors < 8` → monochrome: `AttributedStyle.DEFAULT` plus
bold/reverse only. The palette lives in one `TuiTheme` value passed to the
renderer, so tests can render with the monochrome theme and assert zero colour
attributes.

### 5.2 Glyph sets

| Meaning | `emoji` (default, UTF-8) | `ascii` | Cols |
|---------|--------------------------|---------|------|
| worm | `🪱` | `~o` | 2 |
| brain | `🧠` | `@@` | 2 |
| stalled | `💤` | `z ` | 2 |
| phase: accounts / storage / healing / validate / done | `📦 💾 🔧 ✓ ✅` | `[A] [S] [H] + [ok]` | 2 / 1 / 2 (padded to 4 in both) |
| CL link | `🔗` | `CL` | 2 |
| celebration | `✨` | `*` ×2 | 2 |
| check / dot / diamond / warn / error | `✓ · ◆ ⚠ ✗` | `+ . # ! x` | 1 |
| separator / track done / arrow | `─ ═ ▶` | `- = >` | 1 |
| ellipsis / score trend | `… ↑ ↓ →` | `.. ^ v -` | 1 |

Rules: every glyph is a `(String, cols)` pair in `TuiGlyphs`; the layout never
calls `.length` and always uses `AttributedString.columnLength()` — the oracle the
renderer already uses (`TuiRenderer.scala:53`). Glyphs whose East-Asian width is
*ambiguous* (`◆ ● ⚠ ▶`) are pinned to 1 col and verified by a test that calls
`org.jline.utils.WCWidth` for each entry (§8). The current `phaseDescription`
string `"⏸️  Idle"` (`TuiState.scala:503`) contains U+FE0F, which JLine counts as
width 0 but many terminals draw as width 2 — it is replaced by `·` in the table.

---

## 6. Data inventory

Legend: **existing** = already in `NodeStatusSnapshot`/`TuiState`; **actor** =
new ask to an existing message; **metrics** = read an existing `MetricsContainer`
object (all are `object`s with `AtomicLong`s or gauges, no actor hop);
**config** = read at start; **new** = plumbing needed, with a sketch and size.

| Field | View | Source | Effort |
|-------|------|--------|--------|
| network name, chain id | header | `Config.blockchains.network` (passed in `TuiUpdater`, `StdNode.scala:294`); `blockchainConfig.chainId` (`BlockchainConfig.scala:48`) | config |
| consensus family (PoW/PoS) | header | `forkTimestamps.*` any defined ⇒ PoS; else PoW (`BlockchainConfig.scala:30-37`) | config |
| version | header (L) | `Config.clientVersion` (`Fukuii.scala:40`) | config |
| uptime | header | existing `startTime` → move `now` into snapshot (§4.2) | existing |
| peers count / max | all | existing `peerCount`, `maxPeers` (`TuiStatusProbe.scala:417-419`) | existing |
| current / best block, sync status | all | existing (`TuiStatusProbe.describe`, `:465-472`) | existing |
| SNAP phase, counts, rates, estimates, contracts, chain download, bytecodeComplete | Sync | `SNAPSyncMetrics.latestProgress` (`SNAPSyncMetrics.scala:392`) → `SyncProgress` (`SyncProgressMonitor.scala:265-295`). `TuiStatusProbe.toSnapSyncState` drops `storageContractsCompleted/Total`, `chainHeaders/Bodies/Receipts/Target`, `bytecodeComplete`, `phaseElapsedSeconds` — add them to `SnapSyncState` | existing (+ 8 fields copied) |
| SNAP sample age | Sync alert | `SNAPSyncMetrics.latest` timestamp is private; expose `latestProgressAt: Option[Long]` | new, ~5 LOC |
| SNAP request/failure counters, timeouts, snap-capable peers | Sync (M/L) | `SNAPSyncMetrics` counters (`:71-75,:101-105,:131-135,:292-304`) are `private`; expose read-only accessors or read via `Metrics.get().registry.find(name).counter()` | metrics, ~30 LOC |
| regular-sync rate / ETA / session imported | Sync | existing `syncBaseline` (`TuiState.scala:399-405`); `RegularSync` keeps `initialBlock`/`lastPrintBlock` privately (`RegularSync.scala:280-304`) — the TUI's own baseline is equivalent | existing |
| fetcher queue / last import stats | Sync (S row "Import", "Fetcher") | `RegularSyncMetrics`, `BlockMetrics` (`ledger/BlockMetrics.scala`) — verify gauges exist for last-import gas/time; if not, **new** gauge in `BlockMetrics` | metrics / new ~20 LOC |
| txpool size (+ blob count) | Overview | `PendingTransactionsManager.GetPendingTransactionsReq` → `PendingTransactionsResponse.pendingTransactions.size`, `blobTxNetworkBytes.size` (`PendingTransactionsManager.scala:72-81`) | actor (one more ask in probe) |
| RPC / WS / IPC / Engine ports | Overview | `instanceConfig.config` keys as in `tuiNodeSettings` (`StdNode.scala:298-310`) | config |
| disk free | Overview, alert | `new File(dataDir).getUsableSpace` on the probe thread | new, ~5 LOC |
| JVM heap / GC % | Overview (M/L) | `Runtime.getRuntime`, `ManagementFactory.getGarbageCollectorMXBeans` | new, ~15 LOC |
| discovery known / pending / blacklisted | Overview (L) | `NetworkMetrics.DiscoveredPeersSize`, `PendingPeersSize`, `BlacklistedPeersSize` (`NetworkMetrics.scala:20-34`) | metrics |
| per-peer id, address, direction, age | Peers | `PeerManagerActor.GetPeersCmd` → `Peers.handshaked: Seq[Peer]` (`PeerManagerActor.scala:1064-1065`; `Peer.scala:18-26` has `remoteAddress`, `incomingConnection`, `createTimeMillis`) | existing message, more fields |
| per-peer client id, best block | Peers | `NetworkPeerManagerActor.GetHandshakedPeersCmd` → `HandshakedPeers` with `PeerInfo.maxBlockNumber`, `remoteStatus` (`NetworkPeerManagerActor.scala:51,1273-1278`) | actor |
| per-peer score + trend ↑↓→ | Peers | `PeerScoringManager.getAllScores` (`PeerScoringManager.scala:21`); trend = sign of delta vs. previous snapshot, computed in `TuiState` (the arrows exist only in a debug log today, `:105`). Needs the `PeerScoringManager` instance handed to the probe from `StdNode` | new wiring, ~10 LOC |
| fork timeline (ETC) | Chain, Overview | `MilestoneLog.namedMilestones` (`MilestoneLog.scala:21-45`, currently `private` → `private[ethereum]`) + `forkBlockNumbers` | config, 1-line visibility change |
| fork timeline (ETH) | Chain, Overview | `ForkTimestamps` (`BlockchainConfig.scala:30-37`) + current head timestamp (needs best block header: `blockchainReader.getBestBlock` on probe thread) | config + 1 read |
| **E1** CL connected / last FCU / last newPayload age + status | Overview (ETH), alert | `EngineApiMetrics` has counts and `latestPayloadBlock/Timestamp` (`EngineApiMetrics.scala:13-22`) but **no call timestamps**. Add `_lastForkchoiceUpdatedAt`, `_lastNewPayloadAt`, `_lastForkchoiceStatus`, `_lastNewPayloadStatus` set in `recordForkchoiceUpdated`/`recordNewPayload` (`:53-72`) | new, ~25 LOC, metrics object only |
| E2 head / safe / finalized hashes | Overview (ETH) | `ForkChoiceManager.getState` (`ForkChoiceManager.scala:52`) — needs the instance passed to the probe; safe/finalized *numbers* need a header lookup | new wiring ~15 LOC |
| E3 JWT auth failures | ETH alert | `EngineApiHttpServer` authenticates at `:100`; no counter. Add `EngineApiMetrics.recordAuthFailure` | new ~10 LOC |
| **M1** hashrate, blocks mined, last mined at, stale shares, getWork rate | Overview (ETC) | `PoWMiningMetrics` (`PoWMiningMetrics.scala:16-21`) — `AtomicLong`s are private; add read accessors. Hashrate is only populated via `submitHashRate` (`Miner.scala:51-52`, external miners via RPC `EthMiningService.scala:184-191`) | metrics + ~10 LOC accessors |
| coinbase | Overview (ETC) | `EthMiningService.getCoinbase` / `coinbaseProvider` (`EthMiningService.scala:181-182`) — pass the `CoinbaseProvider` to the probe | new wiring ~5 LOC |
| M2 DAG epoch / generation % / loaded | Overview (ETC) | `EthashDAGManager` logs `Generating DAG n%` (`EthashDAGManager.scala:75`) and `Loading DAG from file n%` (`:99`); no state exposed. Add `PoWMiningMetrics.dagProgress`/`dagEpoch` gauges updated at those two sites | new ~20 LOC (mining path, non-consensus: it only reports) |
| log tail | Logs, Recent strips | **new** `TuiLogRingAppender` (§7.4) | new ~80 LOC |

---

## 7. Architecture changes

### 7.1 State

```scala
case class TuiState(
    tick: Long = 0,                       // frame counter; the only animation input
    view: TuiView = TuiView.Overview,
    motion: Motion = Motion.Full,         // Full | Reduced | Paused
    glyphs: GlyphSet = GlyphSet.Emoji,
    splashUntilTick: Option[Long],
    celebrationStartedTick: Option[Long],
    lastAdvanceTick: Long,                // for stall detection
    snapshot: NodeStatusSnapshot,         // everything polled, incl. `now`, `sampledAt`
    history: TuiHistory,                  // bounded: recent rates, peer-score deltas
    logs: Vector[LogLine],                // from the ring appender, newest last
    nodeSettings: NodeSettings, chain: ChainFacts /* forks, ids, consensus */)
```

`NodeStatusSnapshot` grows per-source freshness so each panel can show
`Unresponsive` independently: `peers: Sourced[PeerSample]`,
`sync: Sourced[SyncSample]`, `txpool: Sourced[Int]`, `cl: Sourced[ClSample]`,
`mining: Sourced[MiningSample]`, where `Sourced[A] = Fresh(a, at) | Stale(lastA,
since) | Unresponsive(err)`. The existing string fields `connectionStatus` and
`syncStatus` (`TuiState.scala:440-448`) become derived.

### 7.2 Loop: decouple polling from frames

Today `TuiUpdater.tick` is poll → render → wait-for-key (`TuiUpdater.scala:324-340`),
and the poll can take up to 2 × 500 ms (`TuiStatusProbe.scala:417-425`). With a
250 ms frame that is unacceptable, so:

- `TuiProbeThread` (daemon): every 1000 ms runs the probe (all asks in parallel
  with `Future.sequence` + one `Await` of 500 ms), publishes to an
  `AtomicReference[NodeStatusSnapshot]`.
- `TuiUpdater` frame loop: `state = state.copy(tick = tick + 1,
  snapshot = ref.get) → render → checkInput(250 ms)`. Key handling as today.
- `Tui.render()` keeps its lock (`Tui.scala:123-140`) and additionally skips the
  write when `lines == lastLines`.
- `Thread.sleep` in `showStartupBanner` (`Tui.scala:204`) is removed; the splash is
  a view (§3.7).

### 7.3 Renderer: panels + layout engine

`TuiRenderer.render` becomes:

```
render(state, w, h) =
  tier      = Tier.of(w, h)
  theme     = Theme.of(config, env)
  panels    = View.panels(state.view, tier)          // ordered list of Panel
  rows      = Layout.allocate(panels, h - 3)         // header, alert, footer are fixed
  frame     = header ++ alert ++ panels.flatMap(p => p.render(state, tier, rows(p), w)) ++ footer
  frame.map(fitToWidth(_, w))                        // the existing invariant, TuiRenderer.scala:52-60
```

- `Panel` = `(minRows, preferredRows, priority, render)`. `Layout.allocate` gives
  each panel `minRows` in priority order, then distributes the remainder up to
  `preferredRows`; the lowest-priority panel (Recent logs) absorbs leftover rows
  and `fitToHeight` pads. This replaces the single `renderBody`
  (`TuiRenderer.scala:62-166`) and the "logo only if it fits" special case
  (`:26-33`).
- Two-column layouts (L tier) are a `Columns(left, right, splitAt)` panel that
  zips two row lists and pads the shorter — widths are checked per cell with
  `columnLength`.
- `WormBar.render(budgetCols, progress, status, tick, glyphs, theme)` lives in
  `console/` and is the TUI twin of `WormToBrainBar`; the log version is untouched
  (it is consumed by `SyncProgressMonitor` and `RegularSync`).

### 7.4 Log tail without touching suppression

`TuiLogSuppressor` detaches `ConsoleAppender`s from the root logger and re-attaches
them on exit (`TuiLogSuppressor.scala:571-604,611-635`). The ring buffer is a
*separate* appender, so neither path changes:

- `TuiLogRingAppender extends AppenderBase[ILoggingEvent]` with a
  `PatternLayout` (`%d{HH:mm:ss} %-5level %msg`), a lock-free ring of 1,000
  formatted lines (`AtomicReferenceArray` + sequence counter), and a filter that
  drops events below INFO unless `tui.log-level` says otherwise.
- Attached to the root logger by `Tui.initialize()` right after suppression
  succeeds; detached in `shutdown()` before `restoreConsoleLogs`. Both steps go
  through the same `attempt(step)` ladder as `releaseTerminal`
  (`Tui.scala:186-197`) so a failure cannot leave raw mode.
- Lines are sanitised on capture: control chars stripped, first line of a
  multi-line message kept with `…`, length capped at 512 chars. Width fitting
  happens at render with `columnSubSequence`. Glyph mapping to ASCII happens at
  render (§3.10).
- The "Recent (sync)" strip filters by logger name prefix
  (`com.chipprbots.ethereum.blockchain.sync`) — the appender keeps the logger
  name alongside the formatted line.

### 7.5 Config and environment

`TuiConfig` gains `animationIntervalMs = 250`, `statusPollIntervalMs = 1000`,
`glyphs: Emoji|Ascii|Auto`, `motion: Full|Reduced`, `logRingSize = 1000`,
`splashMs = 3000`. Environment overrides (checked in `Fukuii.main` where `--tui`
is parsed, `Fukuii.scala:35`): `NO_COLOR`, `FUKUII_TUI_GLYPHS`,
`FUKUII_TUI_MOTION`, `FUKUII_TUI_NO_SPLASH`. HOCON mirrors under `fukuii.tui.*`.

---

## 8. Testing strategy

All tests stay deterministic (no `Thread.sleep`; the renderer takes `tick`).

1. **Golden frames.** `TuiGoldenSpec` renders fixed `TuiState` fixtures (synced,
   SNAP mid-storage, regular sync, 0 peers, CL disconnected, stalled, low disk,
   splash tick 3, celebration frames 0/2/4/6/8/10) at 80×24, 120×40, 160×48, in
   both glyph sets and the monochrome theme, and compares the *visible text*
   (`TuiTestTerminal.visible`, `TuiTestTerminal.scala:56`) against
   `src/test/resources/tui/golden/<view>-<tier>-<state>-<glyphs>.txt`.
   `-Dtui.golden.update=true` rewrites them. The mockups in §3 are the first
   golden files.
2. **Width invariant (property).** For random states (ScalaCheck generators over
   the snapshot ADT including `Unresponsive` arms, random log lines containing
   emoji and CJK), widths 20–200, heights 3–80, every rendered line has
   `columnLength == width` and `lines.size == height`. This generalises
   `TuiSpec` "never draw a row wider than the terminal" (`TuiSpec.scala:80`).
3. **Glyph table.** For every `TuiGlyphs` entry, `WCWidth.wcwidth` summed over
   code points equals the declared `cols`, in both sets. Catches a future `⏸️`.
4. **Animation determinism.** `render(s.copy(tick = n))` is referentially
   transparent (render twice, equal); the ripple cell equals
   `wormAt − 1 − (n mod 3)`; `Motion.Reduced` renders identically for all `n`
   except the celebration frame; `Paused` renders identically across ticks.
5. **Loop behaviour** (extends `TuiUpdaterSpec`): a probe that blocks 5 s does
   not delay frames (frame counter advances ≥ 8 times in 2.5 s wall time with a
   fake clock-driven key wait), and the peer panel shows `Unresponsive` while the
   sync panel shows fresh data.
6. **Ring appender.** Attach/detach leaves root appender set unchanged
   (`suppressedAppenderNames` before/after), multi-line and ANSI-laden messages
   are sanitised, ring overflow keeps the newest.
7. **Existing specs** (`TuiRendererSpec`, `TuiStateSpec` — note they live in
   `src/test/scala/com/chipprbots/ethereum/`, not under `console/`) keep passing;
   `TuiRendererSpec` "render startup banner" (`:28-36`) asserts `FUKUII` appears,
   which the current figlet (`FOOO`) only satisfies via the "FUKUII EVM CLIENT"
   line — the new wordmark keeps that line.

---

## 9. Phased plan

| Phase | Scope | Ships alone? | Size |
|-------|-------|--------------|------|
| **1. Worms, glyphs, tiers** | `TuiGlyphs` + `TuiTheme` (NO_COLOR, ascii, auto-detect), `WormBar` with column budgets replacing `createProgressBar` (`TuiRenderer.scala:222-242`), SNAP 5 worm rows + regular-sync worm in the existing layout, `Tier` with the logo decision table (§3.1), fix the FOOO wordmark, footer with new key names. No new keys yet. Golden tests for 3 tiers × 2 glyph sets. | Yes — pure renderer change | ~350 LOC + ~150 test |
| **2. Tick, motion, non-blocking loop** | `tick` in state, `TuiProbeThread` + `AtomicReference`, 250 ms frames with diff-skip, ripple/spinner/stall/celebration, `p` key, reduced-motion env, splash as a view (removes `Thread.sleep`), `Sourced[A]` freshness. | Yes | ~450 LOC + ~200 test |
| **3. Views + layout engine** | `Panel`/`Layout`, views 1–4 and Help, keys `1-5 \t ? g`, Chain view from `MilestoneLog`/`ForkTimestamps`, alert row with the §2.3 table (disk, stall, peers). Peers view limited to `GetPeersCmd` data. | Yes | ~600 LOC + ~250 test |
| **4. Logs + rich peers** | `TuiLogRingAppender`, Logs view with `+/-` scroll, Recent strips, `GetHandshakedPeersCmd` + `PeerScoringManager` wiring for client/best/score/trend, txpool ask. | Yes | ~450 LOC + ~200 test |
| **5. Per-chain panels** | ETH: E1–E3 in `EngineApiMetrics`/`EngineApiHttpServer`, CL panel + alerts. ETC: M1–M2 in `PoWMiningMetrics`/`EthashDAGManager`, mining panel, coinbase. `beacon` reviews E*, `forge` reviews M2 (reporting only; no consensus semantics). | Yes, ETH and ETC halves can land separately | ~400 LOC + ~150 test |

Each phase ends with `sbt "testOnly *Tui*"`, `sbt scalafmtAll`, and a manual run
in a 80×24 `tmux` pane and a 160×48 pane. Phase 1 is deliberately the smallest
visible win: it changes the dashboard people already see today.

---

## 10. Open questions for the maintainer

1. **Default frame rate.** 4 fps when anything animates, 1 fps otherwise — or
   make 1 fps the default and 4 fps opt-in (`tui.animate = true`)? The CPU case for
   4 fps is solid (§4.4), but some operators run the TUI inside `screen` over SSH
   where every byte counts.
2. **Big logo on splash only?** The 129-col logo needs ≥ 131×66 to show whole;
   cropping it (creature only, no wordmark) fits 131×52. Is a cropped logo
   acceptable, or should the splash use the 12-line logo everywhere below 66 rows?
3. **Glyph auto-detection.** Proposed trigger for ASCII is "not UTF-8 locale".
   Should `TERM=linux` (console) or `tmux` without `-u` also force ASCII, or is
   `g` at runtime enough?
4. **Log level in the ring.** INFO and above by default; DEBUG floods the 1,000
   line ring in seconds during SNAP. Expose `tui.log-level`, or follow the file
   appender's level?
5. **Peers view scope.** `GetHandshakedPeersCmd` gives `PeerInfo` per peer and
   `PeerScoringManager.getAllScores` gives scores — but the scoring manager is not
   an actor. Is handing the instance to the probe acceptable, or should scores be
   exported through `NetworkMetrics` instead?
6. **E1 placement.** Adding "last call at" timestamps to `EngineApiMetrics` is the
   smallest change; alternatively `EngineApiService` could own a `ClLiveness`
   object the TUI and `eth_syncing` both read. Preference?
7. **`d` naming.** "Disable UI" today; proposed footer label "Detach" (node keeps
   running, like `tmux`). Keep "Disable"?
8. **Celebration once per process, or once per sync?** A node that falls behind
   after a network hiccup and catches up again would celebrate twice; proposed:
   once per process, with a quieter one-frame "✓ back at head" afterwards.
