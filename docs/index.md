---
title: Fukuii — EVM execution layer in Scala 3
description: A modern, dual ETC/ETH execution layer client with pluggable consensus, full Prague/Electra EVM, SNAP/fast/regular sync, and Engine API V1–V4.
hide:
  - navigation
  - toc
---

# Home

<div class="fk-landing" markdown="0">

<!-- ============================================================
     HERO
     ============================================================ -->
<section class="fk-hero">
  <canvas class="fk-hex-canvas" aria-hidden="true"></canvas>
  <div class="fk-hero__inner">
    <div class="fk-hero__copy fk-reveal">
      <span class="fk-eyebrow"><span class="fk-pulse"></span>Alpha · Olympia · Engine API on Sepolia</span>
      <h1>The world's first <span class="fk-accent">vibe-coded</span> Ethereum client.</h1>
      <p class="fk-lede">
        Fukuii is an EVM-compliant execution layer written in Scala&nbsp;3. It runs Ethereum&nbsp;Classic
        natively with Ethash, and pairs with any consensus client (Lighthouse, Prysm, Teku, Lodestar,
        Nimbus) to follow Ethereum&nbsp;mainnet through Prague/Electra via Engine&nbsp;API V1–V4.
      </p>
      <div class="fk-cta-row">
        <a class="fk-btn fk-btn--primary" href="getting-started/quickstart/">Quick Start</a>
        <a class="fk-btn fk-btn--ghost" href="https://github.com/chippr-robotics/fukuii" target="_blank" rel="noopener">View on GitHub</a>
        <a class="fk-btn fk-btn--ghost" href="architecture/architecture-overview/">Architecture</a>
      </div>
    </div>
    <div class="fk-hero__art fk-reveal">
      <img src="images/fukuii-hex-logo.png" alt="Fukuii hex logo" loading="eager" decoding="async">
    </div>
  </div>
</section>

<!-- ============================================================
     STATUS STRIP
     ============================================================ -->
<section class="fk-section">
  <div class="fk-strip fk-reveal-stagger" aria-label="Project status at a glance">
    <div class="fk-strip__item">
      <div class="fk-strip__num"><span data-countup="2314">0</span></div>
      <div class="fk-strip__label">Tests passing</div>
    </div>
    <div class="fk-strip__item">
      <div class="fk-strip__num"><span data-countup="12">0</span></div>
      <div class="fk-strip__label">Hive simulators</div>
    </div>
    <div class="fk-strip__item">
      <div class="fk-strip__num"><span data-countup="4">0</span></div>
      <div class="fk-strip__label">Networks</div>
    </div>
    <div class="fk-strip__item">
      <div class="fk-strip__num"><span data-countup="20">0</span></div>
      <div class="fk-strip__label">Alpha bugs squashed</div>
    </div>
    <div class="fk-strip__item">
      <div class="fk-strip__num">JDK&nbsp;21</div>
      <div class="fk-strip__label">LTS runtime</div>
    </div>
    <div class="fk-strip__item">
      <div class="fk-strip__num">Scala&nbsp;3.3</div>
      <div class="fk-strip__label">LTS language</div>
    </div>
  </div>
</section>

<!-- ============================================================
     WHAT IS FUKUII
     ============================================================ -->
<section class="fk-section">
  <div class="fk-reveal">
    <h2><span class="fk-h2-num">01.</span>What is Fukuii?</h2>
    <p class="fk-section__lede">
      A continuation of IOHK's Mantis client, rewritten on a modern stack and re-aimed at being a
      general-purpose EVM engine. Three concentric layers: a chain-agnostic core, an environment
      adapter, and a swappable consensus module.
    </p>
  </div>

  <div class="fk-grid fk-grid--3 fk-reveal-stagger">
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">⟁</div>
      <h3>Dual EL client</h3>
      <p>
        Native Ethash mining for Ethereum&nbsp;Classic and Mordor. Engine&nbsp;API V1–V4 for
        post-Merge Ethereum, validated on Sepolia with 21+&nbsp;EL peers and a Lighthouse CL.
      </p>
      <a class="fk-card__link" href="api/README/">Engine API reference</a>
    </article>
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">◇</div>
      <h3>Pluggable consensus</h3>
      <p>
        <code>fukuii-core</code> + <code>fukuii-env</code> + a consensus module. Drop in PoW,
        PoS, PBFT, or anything that produces blocks the EVM can execute.
      </p>
      <a class="fk-card__link" href="architecture/architecture-overview/">Architecture overview</a>
    </article>
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">⌬</div>
      <h3>Vibe-coded origin</h3>
      <p>
        <em>Chordodes Fukuii</em> is a worm that hijacks a mantis. Fukuii hijacks Mantis,
        rewires it, and drives it toward Olympia (ECIP-1111/1112/1121).
      </p>
      <a class="fk-card__link" href="https://github.com/chippr-robotics/fukuii">Read the README</a>
    </article>
  </div>
</section>

<!-- ============================================================
     NETWORKS
     ============================================================ -->
<section class="fk-section fk-section--alt">
  <div class="fk-section__inner">
    <div class="fk-reveal">
      <h2><span class="fk-h2-num">02.</span>Supported networks</h2>
      <p class="fk-section__lede">Two PoW chains run today; two PoS chains via Engine&nbsp;API.</p>
    </div>

    <div class="fk-grid fk-grid--4 fk-reveal-stagger">
      <article class="fk-card fk-net">
        <div class="fk-net__head">
          <span class="fk-net__name">Ethereum Classic</span>
          <span class="fk-net__id">id 61</span>
        </div>
        <div class="fk-net__row"><span>Consensus</span><b>PoW · Ethash</b></div>
        <div class="fk-net__row"><span>Sync</span><b>SNAP · fast · regular</b></div>
        <span class="fk-net__status fk-net__status--ok"><span class="dot"></span>Full sync validated</span>
      </article>
      <article class="fk-card fk-net">
        <div class="fk-net__head">
          <span class="fk-net__name">Mordor</span>
          <span class="fk-net__id">id 63</span>
        </div>
        <div class="fk-net__row"><span>Consensus</span><b>PoW · Ethash</b></div>
        <div class="fk-net__row"><span>Sync</span><b>SNAP · fast · regular</b></div>
        <span class="fk-net__status fk-net__status--ok"><span class="dot"></span>Full sync validated</span>
      </article>
      <article class="fk-card fk-net">
        <div class="fk-net__head">
          <span class="fk-net__name">Sepolia</span>
          <span class="fk-net__id">id 11155111</span>
        </div>
        <div class="fk-net__row"><span>Consensus</span><b>PoS · Engine API</b></div>
        <div class="fk-net__row"><span>CL pairings</span><b>Lighthouse +&nbsp;4</b></div>
        <span class="fk-net__status fk-net__status--beta"><span class="dot"></span>21+ EL peers, validated</span>
      </article>
      <article class="fk-card fk-net">
        <div class="fk-net__head">
          <span class="fk-net__name">Ethereum Mainnet</span>
          <span class="fk-net__id">id 1</span>
        </div>
        <div class="fk-net__row"><span>Consensus</span><b>PoS · Engine API</b></div>
        <div class="fk-net__row"><span>CL pairings</span><b>any standard CL</b></div>
        <span class="fk-net__status fk-net__status--config"><span class="dot"></span>Configuration available</span>
      </article>
    </div>
  </div>
</section>

<!-- ============================================================
     FEATURE TABS
     ============================================================ -->
<section class="fk-section">
  <div class="fk-reveal">
    <h2><span class="fk-h2-num">03.</span>What's under the hood</h2>
    <p class="fk-section__lede">
      Browse by capability — sync, EVM, the consensus-layer interface, the JSON-RPC surface, and
      the Hive compliance suite.
    </p>
  </div>

  <div class="fk-tabs fk-reveal" role="tablist" aria-label="Feature areas">
    <div class="fk-tabs__list">
      <button class="fk-tab" role="tab" aria-selected="true"  tabindex="0"  data-tab="0"><span class="fk-tab__dot"></span>Sync modes</button>
      <button class="fk-tab" role="tab" aria-selected="false" tabindex="-1" data-tab="1"><span class="fk-tab__dot"></span>EVM (Prague/Electra)</button>
      <button class="fk-tab" role="tab" aria-selected="false" tabindex="-1" data-tab="2"><span class="fk-tab__dot"></span>Engine API</button>
      <button class="fk-tab" role="tab" aria-selected="false" tabindex="-1" data-tab="3"><span class="fk-tab__dot"></span>JSON-RPC + MCP</button>
      <button class="fk-tab" role="tab" aria-selected="false" tabindex="-1" data-tab="4"><span class="fk-tab__dot"></span>Hive compliance</button>
      <button class="fk-tab" role="tab" aria-selected="false" tabindex="-1" data-tab="5"><span class="fk-tab__dot"></span>Operations</button>
    </div>

    <div class="fk-tabs__panels">
      <div class="fk-tabs__panel" role="tabpanel" data-active="true">
        <h3>Three sync paths, one finished tip</h3>
        <p>
          SNAP&nbsp;sync where peers support it — fast&nbsp;sync as the proven fallback —
          regular sync when you need every block. Bootstrap checkpoints let new nodes start
          immediately without waiting for peer consensus.
        </p>
        <ul class="fk-feature-list">
          <li><b>SNAP&nbsp;sync</b> with file-backed contract buffers and crash-safe progress.</li>
          <li><b>Fast&nbsp;sync</b> with peer-quorum pivot consensus (<code>min-peers-to-choose-pivot-block</code>).</li>
          <li><b>Regular sync</b> from genesis, block-by-block.</li>
          <li><b>Bootstrap checkpoints</b> at known fork blocks (Spiral block 19,250,000 for ETC).</li>
          <li><b>Automatic SNAP&nbsp;→ fast fallback</b> after 3 empty pivot refreshes.</li>
          <li><b>In-place pivot refresh</b> preserves account-range progress across resets.</li>
        </ul>
      </div>

      <div class="fk-tabs__panel" role="tabpanel" data-active="false">
        <h3>Full mainstream EIP coverage</h3>
        <p>
          Through Prague/Electra, with ETC's ECIP-1066 schedule layered alongside. Every EIP
          below has a dedicated ADR.
        </p>
        <ul class="fk-feature-list">
          <li>EIP-1559 fee market</li>
          <li>EIP-3541 reject <code>0xEF</code> contracts</li>
          <li>EIP-3529 refund reduction</li>
          <li>EIP-3651 warm <code>COINBASE</code></li>
          <li>EIP-3855 <code>PUSH0</code></li>
          <li>EIP-3860 initcode size limit</li>
          <li>EIP-4844 blob transactions</li>
          <li>EIP-4895 beacon withdrawals</li>
          <li>EIP-4788 beacon root</li>
          <li>EIP-6049 <code>SELFDESTRUCT</code> deprecation notice</li>
          <li>EIP-7685 execution requests</li>
          <li>ECIP-1111 / 1112 / 1121 (Olympia)</li>
        </ul>
      </div>

      <div class="fk-tabs__panel" role="tabpanel" data-active="false">
        <h3>Engine API V1–V4 (authrpc)</h3>
        <p>
          The standard execution-layer interface for any consensus client. Optimistic block
          import, payload building, and forkchoice state — all spec-compliant.
        </p>
        <ul class="fk-feature-list">
          <li><code>engine_newPayloadV1..V4</code></li>
          <li><code>engine_forkchoiceUpdatedV1..V3</code></li>
          <li><code>engine_getPayloadV1..V4</code></li>
          <li><code>engine_exchangeCapabilities</code></li>
          <li><code>engine_getPayloadBodiesByHashV1</code></li>
          <li><code>engine_getPayloadBodiesByRangeV1</code></li>
          <li>JWT auth on a synchronous bind (10s timeout, fails loudly).</li>
          <li>Validated Sepolia with Lighthouse, 21+ EL peers.</li>
        </ul>
      </div>

      <div class="fk-tabs__panel" role="tabpanel" data-active="false">
        <h3>Full RPC surface, plus an agent control channel</h3>
        <p>
          All the standard namespaces, plus an MCP server so AI agents can drive the node
          using the 2025-11-25 Model Context Protocol.
        </p>
        <ul class="fk-feature-list">
          <li><code>eth_*</code>, <code>net_*</code>, <code>web3_*</code></li>
          <li><code>debug_*</code>, <code>trace_*</code></li>
          <li><code>admin_*</code>, <code>txpool_*</code></li>
          <li><code>personal_*</code></li>
          <li><code>engine_*</code> on the auth port</li>
          <li><b>MCP 2025-11-25</b> for agentic AI control</li>
          <li>Health: <code>/health</code> · <code>/readiness</code> · <code>/buildinfo</code></li>
          <li>Cached <code>net_listPeers</code> — sub-millisecond responses with 30+ peers.</li>
        </ul>
      </div>

      <div class="fk-tabs__panel" role="tabpanel" data-active="false">
        <h3>Ethereum Foundation Hive — split per simulator</h3>
        <p>
          Each Hive simulator runs in its own GitHub Actions workflow, so a single failing
          suite is visible at a glance. <code>hive-prague</code> is the threshold-gated PR
          check for Pectra-era regressions.
        </p>
        <ul class="fk-feature-list">
          <li><code>smoke-genesis</code></li>
          <li><code>smoke-network</code></li>
          <li><code>rpc</code> · <code>rpc-compat</code></li>
          <li><code>graphql</code></li>
          <li><code>devp2p</code></li>
          <li><code>sync</code></li>
          <li><code>consensus</code></li>
          <li><code>pyspec</code></li>
          <li><code>engine</code></li>
          <li><code>consume-engine</code></li>
          <li><code>consume-rlp</code></li>
          <li><code>hive-prague</code> (gated)</li>
        </ul>
      </div>

      <div class="fk-tabs__panel" role="tabpanel" data-active="false">
        <h3>Production-shaped operations</h3>
        <p>
          Built to be run, not just compiled. Dedicated dispatchers prevent RPC starvation;
          metrics and dashboards ship with the node.
        </p>
        <ul class="fk-feature-list">
          <li>Apache Pekko 1.1 actors on three isolated dispatchers.</li>
          <li><code>sync-dispatcher</code> isolates SNAP/fast/regular from RPC.</li>
          <li>Prometheus metrics + Grafana dashboards.</li>
          <li>Health and readiness endpoints.</li>
          <li>Resilient log appender — survives log-file deletion.</li>
          <li>Signed Docker images with SLSA provenance.</li>
        </ul>
      </div>
    </div>
  </div>
</section>

<!-- ============================================================
     RECENT DEVELOPMENTS — THE ALPHA BUG-HUNT TIMELINE
     ============================================================ -->
<section class="fk-section fk-section--alt">
  <div class="fk-section__inner">
    <div class="fk-reveal">
      <h2><span class="fk-h2-num">04.</span>Recent developments</h2>
      <p class="fk-section__lede">
        Twenty stabilization fixes shipped on the <code>alpha</code> branch — the work that
        carried Fukuii from "compiles" to "syncs the entire ETC chain reliably."
      </p>
    </div>

    <ol class="fk-timeline fk-reveal-stagger">
      <li class="fk-timeline__item fk-timeline__item--critical">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bug 18 — SNAP sync OOM eliminated</span>
          <span class="fk-timeline__sev fk-timeline__sev--critical">Critical</span>
        </div>
        <p class="fk-timeline__body">
          Three unbounded memory sources during account download.
          <code>DeferredWriteMptStorage</code> now flushes per response batch,
          <code>contractAccounts</code> moved to file-backed 64-byte entries (~45M on ETC),
          and progress is persisted via <code>AppStateStorage.putSnapSyncProgress</code> for crash recovery.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--critical">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bug 6 — RPC starvation under SNAP sync</span>
          <span class="fk-timeline__sev fk-timeline__sev--critical">Critical</span>
        </div>
        <p class="fk-timeline__body">
          All sync actors moved to a dedicated <code>sync-dispatcher</code>, freeing the default
          dispatcher for HTTP and TCP I/O. RPC stays responsive while sync workers saturate.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--critical">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">ETH68 peer connection failures</span>
          <span class="fk-timeline__sev fk-timeline__sev--critical">Critical</span>
        </div>
        <p class="fk-timeline__body">
          Network protocol messages (Hello, Disconnect, Ping, Pong) are now decoded before
          capability-specific messages. Stops the immediate post-handshake disconnect with
          <code>"Cannot decode Disconnect"</code>.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--high">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bug 20 — SNAP post-download pipeline</span>
          <span class="fk-timeline__sev fk-timeline__sev--high">High</span>
        </div>
        <p class="fk-timeline__body">
          Four related failures after account download: phase-handoff timeout (Bloom-filter
          dedup 73.5M&nbsp;→&nbsp;2M code hashes, streaming storage in 10K batches),
          <code>StorageConsistencyChecker</code> skipped when <code>SnapSyncDone=true</code>,
          recovery actors forwarding SNAP responses, and a <code>visited</code>-set fix in
          <code>MptStorage.decodeNode()</code> across all four trie-walk paths.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--high">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bug 2 — SNAP fallback resilience</span>
          <span class="fk-timeline__sev fk-timeline__sev--high">High</span>
        </div>
        <p class="fk-timeline__body">
          Two paths to <code>fallbackToFastSync()</code>: the consecutive pivot-refresh counter
          (no longer reset in <code>restartSnapSync()</code>), and a bootstrap retry loop with
          exponential backoff (2s → 60s cap, 5-minute timeout).
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--high">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bug 8 — block body download stall</span>
          <span class="fk-timeline__sev fk-timeline__sev--high">High</span>
        </div>
        <p class="fk-timeline__body">
          Re-queueing to a blacklisted peer caused stalls. Fixed with an
          <code>ExcludingPeers</code> selector, exponential backoff, and a
          <code>maxBodyFetchRetries</code> ceiling.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--med">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bugs 7 &amp; 10 — MissingNodeException in state RPCs</span>
          <span class="fk-timeline__sev fk-timeline__sev--med">Medium</span>
        </div>
        <p class="fk-timeline__body">
          <code>eth_call</code>, <code>eth_estimateGas</code>, <code>eth_getCode</code>,
          and <code>personal_sendTransaction</code> all gained the same <code>.recover</code>
          pattern that <code>eth_getBalance</code> uses, so partial state during sync no longer
          throws to callers.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--med">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bug 9 — net_listPeers timeout</span>
          <span class="fk-timeline__sev fk-timeline__sev--med">Medium</span>
        </div>
        <p class="fk-timeline__body">
          With 30+ peers, <code>parTraverse</code> exceeded the 20s RPC timeout. Replaced with
          an in-process <code>peerStatusCache</code> updated reactively — sub-millisecond response.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--med">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bugs 11–17 — SNAP coordinator hardening</span>
          <span class="fk-timeline__sev fk-timeline__sev--med">Medium</span>
        </div>
        <p class="fk-timeline__body">
          Capability check before account sync · stagnation watchdog on
          <code>accountsDownloaded</code> · partial-range resume across pivot refreshes ·
          dynamic worker concurrency capped to snap-peer count · in-place pivot refresh ·
          stale-peer dedup by <code>remoteAddress</code> · stale-root guard on task failures.
        </p>
      </li>
      <li class="fk-timeline__item fk-timeline__item--low">
        <div class="fk-timeline__header">
          <span class="fk-timeline__title">Bugs 1, 3, 4, 5, 19 — quality-of-life</span>
          <span class="fk-timeline__sev fk-timeline__sev--low">Low</span>
        </div>
        <p class="fk-timeline__body">
          Config cache poisoning (<code>ConfigFactory.invalidateCaches()</code>) ·
          FastSync best-block-hash tracking · JSON-RPC null-id coercion ·
          actor name collision on sync restart · <code>ResilientRollingFileAppender</code>
          to recreate log files deleted while running.
        </p>
      </li>
    </ol>

    <p class="fk-section__lede fk-reveal" style="margin-top:1.5rem">
      Full detail in the <a href="https://github.com/chippr-robotics/fukuii/blob/develop/CHANGELOG.md">CHANGELOG</a>
      and the <a href="adr/README/">ADR index</a>.
    </p>
  </div>
</section>

<!-- ============================================================
     ARCHITECTURE
     ============================================================ -->
<section class="fk-section">
  <div class="fk-reveal">
    <h2><span class="fk-h2-num">05.</span>Pluggable consensus, three layers</h2>
    <p class="fk-section__lede">
      The EVM doesn't care how blocks were chosen. Fukuii makes that explicit by separating
      execution from environment from consensus.
    </p>
  </div>

  <div class="fk-arch fk-reveal-stagger">
    <div class="fk-arch__layer">
      <span class="fk-arch__tag">consensus</span>
      <div>
        <h4>Consensus module</h4>
        <p>Ethash mining · Engine API V1–V4 (PoS) · or your own. Produces ordered blocks.</p>
      </div>
    </div>
    <div class="fk-arch__connector"></div>
    <div class="fk-arch__layer">
      <span class="fk-arch__tag">fukuii-env</span>
      <div>
        <h4>Environment adapter</h4>
        <p>Networking, peer management, JSON-RPC, sync controllers, metrics, storage glue.</p>
      </div>
    </div>
    <div class="fk-arch__connector"></div>
    <div class="fk-arch__layer">
      <span class="fk-arch__tag">fukuii-core</span>
      <div>
        <h4>Execution core</h4>
        <p>EVM, state trie, RLP, transaction pool — chain-agnostic and fully spec-compliant.</p>
      </div>
    </div>
  </div>
</section>

<!-- ============================================================
     QUICK START
     ============================================================ -->
<section class="fk-section fk-section--alt">
  <div class="fk-section__inner">
    <div class="fk-reveal">
      <h2><span class="fk-h2-num">06.</span>Run a node in five lines</h2>
      <p class="fk-section__lede">
        Build the assembly JAR, point a datadir at a fast disk, and pick a network.
        <code>sbt run</code> is fine for tests; for actual node operation use the JAR.
      </p>
    </div>

    <div class="fk-code fk-reveal">
      <div class="fk-code__header">
        <span class="fk-code__title">Mordor testnet · SNAP sync</span>
        <button class="fk-code__copy" type="button">Copy</button>
      </div>
<pre><span class="c-cmt"># 1. Build the assembly</span>
<span class="c-cmd">sbt</span> assembly

<span class="c-cmt"># 2. Run against Mordor with sane defaults</span>
<span class="c-cmd">java</span> -Xmx4g \
  <span class="c-flag">-Dfukuii.datadir</span>=/data/fukuii/mordor \
  <span class="c-flag">-Dfukuii.network</span>=mordor \
  <span class="c-flag">-Dfukuii.network.rpc.http.interface</span>=0.0.0.0 \
  <span class="c-flag">-Dfukuii.network.rpc.http.port</span>=8553 \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar mordor</pre>
    </div>

    <div class="fk-code fk-reveal">
      <div class="fk-code__header">
        <span class="fk-code__title">Docker · production-ready image</span>
        <button class="fk-code__copy" type="button">Copy</button>
      </div>
<pre><span class="c-cmt"># Run the signed image with persistent storage</span>
<span class="c-cmd">docker</span> run -d --name fukuii \
  -p 8553:8553 -p 30305:30305 \
  -v fukuii-data:/data \
  ghcr.io/chippr-robotics/fukuii:latest etc</pre>
    </div>

    <div class="fk-code fk-reveal">
      <div class="fk-code__header">
        <span class="fk-code__title">Engine API · pair with a CL on Sepolia</span>
        <button class="fk-code__copy" type="button">Copy</button>
      </div>
<pre><span class="c-cmt"># Bind the auth-RPC port; then point Lighthouse/Prysm/Teku at it</span>
<span class="c-cmd">java</span> -Xmx6g \
  <span class="c-flag">-Dfukuii.network</span>=sepolia \
  <span class="c-flag">-Dfukuii.engine.enabled</span>=true \
  <span class="c-flag">-Dfukuii.engine.port</span>=8551 \
  <span class="c-flag">-Dfukuii.engine.jwt-secret</span>=/etc/fukuii/jwt.hex \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar sepolia</pre>
    </div>
  </div>
</section>

<!-- ============================================================
     AUDIENCE-SPECIFIC ENTRY POINTS
     ============================================================ -->
<section class="fk-section">
  <div class="fk-reveal">
    <h2><span class="fk-h2-num">07.</span>Pick your path</h2>
    <p class="fk-section__lede">Documentation is organised by audience — start where you fit.</p>
  </div>

  <div class="fk-grid fk-aud-grid fk-reveal-stagger">
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">▶</div>
      <h3>Getting started</h3>
      <p>Install, first run, Docker quick start, GitHub Codespaces, and building from source.</p>
      <a class="fk-card__link" href="getting-started/">Open quick start</a>
    </article>
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">⚙</div>
      <h3>Node operators</h3>
      <p>Configuration, security, peering, disk management, backup &amp; restore, TLS, troubleshooting.</p>
      <a class="fk-card__link" href="for-node-operators/">Operator runbooks</a>
    </article>
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">⌗</div>
      <h3>SRE / production</h3>
      <p>Docker Compose, Kong gateway, Barad-dûr, metrics &amp; monitoring, log triage.</p>
      <a class="fk-card__link" href="for-operators/">Operations guides</a>
    </article>
    <article class="fk-card">
      <div class="fk-card__icon" aria-hidden="true">{ }</div>
      <h3>Developers</h3>
      <p>Architecture, ADRs, contributing, CI/CD, test tagging, KPI monitoring, performance baselines.</p>
      <a class="fk-card__link" href="for-developers/">Developer guide</a>
    </article>
  </div>
</section>

<!-- ============================================================
     CTA BANNER + FOOTNOTE
     ============================================================ -->
<section class="fk-section">
  <div class="fk-cta-banner fk-reveal">
    <div>
      <h3>Try it on a testnet first.</h3>
      <p>
        Fukuii is in <strong>alpha</strong> and not yet for production use. Mordor and Sepolia are
        excellent places to put it through its paces — and to file issues when it surprises you.
      </p>
    </div>
    <div class="fk-cta-row" style="margin-top:0">
      <a class="fk-btn fk-btn--primary" href="https://github.com/chippr-robotics/fukuii/issues" target="_blank" rel="noopener">Open an issue</a>
      <a class="fk-btn fk-btn--ghost" href="https://github.com/chippr-robotics/fukuii/discussions" target="_blank" rel="noopener">Join discussions</a>
    </div>
  </div>
</section>

<div class="fk-foot">
  Built with <strong>care</strong> by <strong>Chippr Robotics LLC</strong> · Apache 2.0 ·
  Forked from <em>Mantis</em> (IOHK) · <code>com.chipprbots.ethereum</code>
</div>

</div>
