# LTS toolchain audit — Scala, sbt, JVM and the direct dependency chain

**Date**: 2026-10-17 · **Tree**: `staging` at v0.9.97 · **Method**: `.claude/skills/fukuii-dependency-audit` (read-only)
· **Sources**: pinned versions in `build.sbt`, `project/build.properties`, `project/plugins.sbt`,
`project/Dependencies.scala`, `.scalafmt.conf`, `.github/workflows/*.yml`, `docker/Dockerfile*`; latest versions from
Maven Central `maven-metadata.xml` and directory listings (authoritative, dated), `endoflife.date` for Scala and
Temurin, and the Scala 3.9 and sbt 2.0 release announcements. `sbt dependencyUpdates` (sbt-updates 0.6.4) ran on the
root project and reported 47 updates; its list agrees with the Maven Central survey below and added the rows marked †.
The weekly `dependency-check.yml` job runs `dependencyTree` only (recommendation R-7).

## 1. Verdict

| Component | Pinned | Latest on the same support line | Latest LTS line | Status | Action |
| --- | --- | --- | --- | --- | --- |
| **JDK** | 25 (CI `temurin` 25; Docker `eclipse-temurin:25-jdk/jre-noble`; distroless `java25`) | Temurin 25.0.4.1+1 (2026-08-19), EOL 2031-09-30; the `25` tag floats to it | **25 is the current LTS**; 26 and 27 are non-LTS; next LTS is 29 (2027-09) | ✅ current | none; fix the stale "JDK 21" docs (R-6) |
| **Scala** | 3.3.8 (2026-06-11) | 3.3.8 is the latest 3.3 patch; 3.3.9 expected within a month of 3.9.0 | **3.9.0 LTS, released 2026-09-03**, opens the new LTS line; 3.3 LTS stays maintained for one year after (to about 2027-09) with fewer backports | ⚠️ supported, one LTS line behind | plan the 3.3 → 3.9 migration as its own spec (R-1) |
| **sbt** | 1.10.7 | 1.12.15; 1.13.0 (2026-08-20) is the newest 1.x on Maven Central | **2.0.10** is the newest 2.x; sbt 2.0.0 shipped 2026-06-14 under SemVer, build definitions on Scala 3.8.4 | ⚠️ five minor versions behind on 1.x; 2.x is a migration | bump to 1.12.15 now (R-2); sbt 2 after the four missing plugins ship (R-3) |
| **scalafmt** | 3.8.3 (`.scalafmt.conf`), sbt-scalafmt 2.5.2 | scalafmt 3.11.5, sbt-scalafmt 2.6.2 | | ⚠️ the 3.8 dialect does not know 3.9 syntax | bump with the Scala migration; constitution names 3.8.3 (PATCH amendment) |
| **scalafix** | sbt-scalafix 0.13.0 | 0.14.9 | | ⚠️ | bump with the Scala migration (semantic rules need a scalafix that knows the compiler) |

**BSL guard**: zero `import akka.` in `src/`; no `com.typesafe.akka` or `com.lightbend:akka*` in `Dependencies.scala`.
✅ Apache-2.0 clean.

## 2. Scala: what the 3.3 → 3.9 LTS move involves

Facts from the 3.9 announcement (scala-lang.org/news/3.9, read 2026-10-17):

- 3.9 LTS is guaranteed maintenance for at least three years. 3.3 LTS is maintained for one year after 3.9.0, "future
  backports are expected to be fewer". Staying on 3.3 is safe until about 2027-09 and then is not.
- Minimum JDK 17. fukuii is on 25: no change.
- Artifacts built with 3.9 cannot be consumed by 3.3 projects. fukuii publishes `bytes`, `crypto` and `rlp`; all
  modules move together, and anything downstream that consumes them on 3.3 breaks. The vendored `scalanet` modules are
  compiled in-tree and follow.
- Recommended order: build tool, JDK, Scala, then libraries. Compiler rewrites: apply `-source:3.4-migration` through
  `-source:3.9-migration`, each with `-rewrite`, one step at a time, committing after each; later flags do not replay
  earlier patches. Some changes have no rewrite.
- Given prioritization changed in 3.7 (the most general matching instance wins). `-source:3.5` keeps the old rules;
  `-source:3.7-migration` warns where they differ. **This intersects the repo's own `given`/`using` migration
  (`.claude/agent-protocols/scala3-given-migration.md`, P3a done, P3b pending):** finish P3b before, or fold it into,
  the 3.9 migration so the prioritization change is reviewed once.
- Implicits from inaccessible companions warn under `-deprecation` in 3.9 and stop resolving in 3.10. fukuii compiles
  with `-deprecation`; new warnings land in the warning ratchet.

Things in `build.sbt` to re-verify against the 3.9 compiler during the migration, because the announcement does not
cover them: `-source:future` (the repo relies on it for `import x.*` enforcement), `-Ykind-projector` (a `-Y` flag;
confirm it is still accepted or has moved), `-Wconf:id=E198:error` (message ids can renumber between minors),
`-Xmax-inlines:64`, and the `-language:adhocExtensions` suppression.

Toolchain that must move with it: scalafmt 3.8.3 → 3.11.x (the `scala3` dialect must parse 3.9 syntax; the
constitution's text names 3.8.3, so a PATCH amendment accompanies the bump), sbt-scalafix 0.13.0 → 0.14.9,
sbt-scoverage 2.2.2 → 2.4.4, `semanticdbVersion` (follows `scalafixSemanticdb.revision`).

Why it matters for the roadmap specs: spec 017, 018 and 019 all add new code, and spec 019's Approach B reads TASTy.
TASTy format follows the compiler minor; a translator written against 3.3's TASTy is rewritten for 3.9's. The cheapest
sequence is **migrate before 017 starts**: after spec 016's remaining slices (which are byte-moves and should not
straddle a compiler change), as its own spec, and before any 017 code lands. Recommendation R-1.

## 3. sbt

- **1.x**: 1.10.7 → 1.12.15 is a drop-in bump (same plugin ABI `_2.12_1.0`). 1.13.0 exists on Maven Central
  (2026-08-20); take it only after reading its notes. The `dependency-check.yml` and CI "Set up SBT" steps follow
  `build.properties`.
- **2.x**: 2.0.0 released 2026-06-14, 2.0.10 current. Builds and plugins run on Scala 3 (3.8.4), bare settings apply to
  all subprojects (replaces the `ThisBuild` idiom the build uses), project matrix is built in, `compile` and `test` are
  cached tasks. Plugin availability for sbt 2 (`_sbt2_3` artifacts on Maven Central, checked 2026-10-17):

| Plugin | sbt 1 pinned | sbt 1 latest | sbt 2 artifact | Note |
| --- | --- | --- | --- | --- |
| sbt-scalafmt | 2.5.2 | 2.6.2 | 2.6.2 | ✅ |
| sbt-scalafix | 0.13.0 | 0.14.9 | 0.14.9 | ✅ |
| sbt-scoverage | 2.2.2 | 2.4.4 | 2.4.4 | ✅ |
| sbt-native-packager | 1.10.4 | 1.13.0 | 1.13.0 | ✅ |
| sbt-assembly | 2.3.0 | 2.5.0 | 2.5.0 | ✅ |
| sbt-buildinfo | 0.12.0 | 0.13.2 | present | ✅ |
| sbt-ci-release | 1.9.2 | 1.12.1 | present | ✅ |
| sbt-git | 2.0.1 | 2.2.0 | 3.0.0-RC1 | ⚠️ RC only |
| sbt-javaagent | `com.lightbend.sbt` 0.1.6 | moved to `com.github.sbt` 0.3.0 | present | ⚠️ organisation changed; the 0.1.6 artifact is orphaned |
| sbt-updates | 0.6.4 | 0.7.1 | present | ✅ |
| sbt-api-mappings | 3.0.2 | 3.1.0 | present | ✅ |
| sbt-kanela-runner | 2.1.0 | 2.1.1 | **none** | ❌ blocker (Kamon agent for metrics) |
| sbt-scapegoat | 1.2.13 | 1.2.13 | **none** | ❌ blocker, or drop the plugin (scalafix + the ratchet cover most of it) |

sbt 2 is therefore a migration, not a bump: two plugins have no sbt 2 build, one is an RC, and the build definition's
`ThisBuild` and `inConfig` usage needs review. Recommendation R-3.

## 4. JVM

JDK 25 is the current LTS and the repo is on it everywhere that matters: every workflow (`java-version: '25'`,
Temurin), every Docker builder and runtime image (`eclipse-temurin:25-*-noble`), the distroless runtime
(`gcr.io/distroless/java25-debian13`), and the release notes ("requires JDK 25+"). The floating `25` tags pick up Temurin
patches (25.0.4.1+1 as of 2026-08-19) without a repo change. JDK 26 and 27 are feature releases, not LTS; the next LTS is
29 in September 2027. Spec 018's FFM route needs `java.lang.foreign`, final since JDK 22, so 25 covers it.

Documentation drift: `docs/adr/infrastructure/INF-001-scala-3-migration.md` is titled "Migration to Scala 3 and JDK
21" and `docs/adr/README.md`, `docs/adr/infrastructure/README.md`, `INF-002`, `INF-003` and `docs/adr/MIGRATION_GUIDE.md`
still say JDK 21. The constitution and CLAUDE.md say JDK 25. Recommendation R-6.

## 5. Direct dependencies (pinned vs latest stable on Maven Central, 2026-10-17)

Patch and minor bumps that are low risk (same major, no API change expected; run `testEssential` once after the batch):

| Library | Pinned | Latest | Note |
| --- | --- | --- | --- |
| bcprov / bcpkix-jdk18on | 1.84 | 1.86 | security-relevant; take it |
| circe | 0.14.15 | 0.14.17 | |
| logback-classic | 1.5.34 | 1.6.5 | 1.6 line; check the logstash encoder pairing |
| guava | 33.6.0-jre | 33.7.2-jre | |
| caffeine | 3.2.4 | 3.3.1 | scaffeine 5.3.0 is current |
| scalamock | 7.3.3 | 7.6.0 | test only |
| scalacheck | 1.19.0 | 1.20.0 | test only; `scalacheck-1-18` bridge → check the `scalacheck-1-19`/`1-20` artifact |
| mockito-core | 5.23.0 | 5.24.0 | test only |
| snappy-java | 1.1.10.8 | 1.1.10.12 | |
| httpclient5 | 5.6.1 | 5.6.5 | |
| scopt | 4.1.0 | 4.2.0 | |
| scala-parser-combinators | 2.4.0 | 2.5.0 | |
| scala-xml (override) | 2.3.0 | 2.5.0 | |
| jc-kzg-4844 | 2.0.0 | 2.1.8 | consensus-adjacent (KZG, PeerDAS): `beacon` reviews the changelog before taking it |
| micrometer | 1.16.6 | 1.17.1 (1.16.7 on the pinned line) | the pin comment defers 1.17; take 1.16.7 now, re-evaluate 1.17 |
| jupnp (3 artifacts) † | 3.0.4 | 3.0.5 | |
| sangria † | 4.2.18 | 4.2.19 | |
| ipcsocket † | 1.6.3 | 1.8.0 | IPC transport (`conduit`); read the notes |
| web3j core (test) † | 4.14.0 | 6.0.0 | test only; major, defer unless a test needs it |

Evaluate with changelog and `testEssential` (minor versions of core runtime libraries):

| Library | Pinned | Latest | Note |
| --- | --- | --- | --- |
| pekko-actor / typed / stream / slf4j | 1.6.0 | 1.7.1 | minor; Typed API in active migration (`loom`), so read the notes |
| pekko-http / cors | 1.3.0 | 1.4.2 | minor |
| cats-effect | 3.6.3 | 3.7.1 | minor; fs2 3.14 requires it |
| fs2 | 3.12.2 | 3.14.0 | minor |
| cats-core / mouse | 2.13.0 / 1.4.0 | current | |

Major lines, each a decision (spec or deliberate defer), not a bump:

| Library | Pinned | Latest | Note |
| --- | --- | --- | --- |
| rocksdbjni | 10.10.1.1 | 11.8.1 | spec 018 retires RocksDB; bump only for a security fix in the transition window |
| netty | 4.1.131.Final | 4.2.19.Final (4.1 line: 4.1.139.Final) | 4.1 is still maintained: take 4.1.139 now; 4.2 is a `herald` decision (RLPx transport) |
| jline | 3.30.13 | 4.4.8 | the pin comment defers 4.x |
| prometheus-metrics | 1.3.10 | 1.10.0 | the pin comment defers; re-evaluate with micrometer |
| logstash-logback-encoder | 8.1 | 9.0 | blocked on Jackson 3 via json4s/circe, as the pin comment says |
| kanela-agent (`javaAgents`) † | 1.0.18 | 2.0.0 | major; pairs with sbt-kanela-runner and Kamon 2.8; evaluate with the metrics stack |
| bls12-381 (Besu artifactory) | 1.0.0 | 1.0.0 | current |

Current with no action: scalatest 3.2.20, json4s 4.0.7, boopickle 1.5.0, enumeratum 1.9.8, decline 2.6.2, jna 5.19.1,
commons-io 2.22.0, log4cats 2.8.0, scodec 2.3.3, scala-logging 3.9.6, kamon 2.8.1.

## 6. Recommendations

- **R-1 (spec-required, sequence it before spec 017; stubbed as `specs/020-scala-3-9-lts-migration/`).** Scala 3.3.8 → 3.9.x LTS as its own spec: build tool first
  (R-2), then the compiler with the `-source:3.4-migration` … `-source:3.9-migration` rewrites one step per commit,
  then scalafmt 3.11.x / sbt-scalafix 0.14.9 / sbt-scoverage 2.4.4, then libraries. Fold the pending P3b `given`
  migration into it. Gate on `testEssential`, `testStandard` with coverage, ethereum/tests on both chains, and the
  rpc-compat and graphql inertness oracles. Amend the constitution's scalafmt version (PATCH). Spec 019's TASTy
  translator and spec 018's bake-off harness then target one toolchain.
- **R-2 (zero-risk, now).** `sbt.version = 1.12.15`; plugin bumps on the sbt 1 line: sbt-scalafmt 2.6.2, sbt-scalafix
  0.14.9, sbt-scoverage 2.4.4, sbt-native-packager 1.13.0, sbt-assembly 2.5.0, sbt-buildinfo 0.13.2, sbt-ci-release
  1.12.1, sbt-git 2.2.0, sbt-updates 0.7.1, sbt-api-mappings 3.1.0, sbt-kanela-runner 2.1.1, and sbt-javaagent moved
  to `com.github.sbt` 0.3.0. One PR, `sbt pp` green. (sbt-scalafix 0.14.x may need the scalafix rules' pins checked.)
- **R-3 (spec-required, later).** sbt 2.0.x after R-1, and only once sbt-kanela-runner and sbt-scapegoat have sbt 2
  builds or are replaced; sbt-git's sbt 2 build is an RC. Review `ThisBuild`/bare-settings semantics and the `it`,
  `evm`, `rpcTest`, `benchmark` configurations.
- **R-4 (low-risk batch).** The patch/minor table in §5, one PR, `testEssential` once; netty 4.1.139 in the same batch.
- **R-5 (evaluate).** pekko 1.7.1, pekko-http 1.4.2, cats-effect 3.7.1, fs2 3.14.0 together, with `testEssential`
  and the SNAP `SyncTest` job; `loom` reads the Pekko notes.
- **R-6 (docs).** Replace "JDK 21" with 25 in the ADR index, INF-001/002/003 and the migration guide, or add a note
  that INF-001 records the historical target.
- **R-7 (CI).** Add `sbt dependencyUpdates` to `dependency-check.yml` beside `dependencyTree`, so the weekly artifact
  carries the list this audit had to assemble by hand.

## 7. Verification record

- `VERIFY: ran` grep for `import akka.` in `src/` and for Akka coordinates in `Dependencies.scala` — result: PASS (zero).
- `VERIFY: ran` `curl endoflife.date/api/{scala,eclipse-temurin}.json` — result: PASS (3.9 LTS 2026-09-03; 3.3 LTS
  latest 3.3.8; Temurin 25 LTS 25.0.4.1+1).
- `VERIFY: ran` Maven Central `maven-metadata.xml` / directory listings for every row in §1, §3 and §5 — result: PASS
  (versions and dates as listed).
- `VERIFY: ran` `sbt dependencyUpdates` from a scratch launcher (sbt 1.10.7, JDK 21 sandbox) — result: PASS on the
  second attempt (the first hit HTTP 429 from Maven Central; rerun with `-Dsbt.coursier.parallel-downloads=2`): "Found
  47 dependency updates for fukuii", root project only (the `bytes`, `crypto`, `rlp` and `scalanet` modules were not
  aggregated by the task). One false positive: `commons-io 2.22.0 -> 20030203.000550` is a legacy timestamped artifact,
  ignore it. Netty is a `scalanet` dependency and did not appear; its figures above come from Maven Central directly.
