# Quickstart: Chain Registry (ChainSpec) — spec 024

Runnable scenarios for the three user stories. Commands assume a built distribution (`sbt dist`, or `./bin/fukuii`
from `target/universal/stage`). Replace `<dir>` with an empty directory. Expected exit codes follow
[contracts/errors.md](./contracts/errors.md).

## US1 — add a chain without a release (Platåberget as an operator entry)

Platåberget is built in, so the scenario uses it under a second key to show the path an unknown chain takes. In the
023 harness the same steps run against the Engine API devnet fixture.

```bash
mkdir -p <dir>/chains
# 1. take the shipped entry and genesis as a starting point
unzip -p fukuii.jar conf/base/chains/plataberget-chain.conf  > <dir>/chains/plataberget2-chain.conf
unzip -p fukuii.jar conf/base/chains/plataberget-genesis.json > <dir>/chains/plataberget-genesis.json
# 2. edit plataberget2-chain.conf: it already carries consensus = "engine-api" and genesis-hash after slice 4;
#    the file name supplies the key. Nothing else changes.
# 3. validate without starting a node
fukuii chains validate <dir>/chains          # exit 0; one OK line per entry, plataberget2 listed from <dir>
fukuii -Dfukuii.blockchains.custom-chains-dir=<dir>/chains chains list | grep plataberget2
# 4. start it (an ingest node needs a consensus client on the Engine API; the JWT file is deployment config)
FUKUII_CONF_DIR=<dir>/conf fukuii plataberget2 \
  -Dfukuii.blockchains.custom-chains-dir=<dir>/chains \
  -Dfukuii.network.engine-api.enabled=true \
  -Dfukuii.network.engine-api.jwt-secret-path=<dir>/jwt.hex
```

Expected: the node logs `Using network plataberget2`, `chain 'plataberget2' (engine-api, family eth, finality
consensus-layer) loaded from <dir>/chains/plataberget2-chain.conf`, and imports once the CL pushes a head. No code
change, no release (SC-001). Acceptance variants:

```bash
# AS-2: a consensus the build does not implement
sed -i 's/consensus = "engine-api"/consensus = "clique"/' <dir>/chains/plataberget2-chain.conf
fukuii plataberget2 -Dfukuii.blockchains.custom-chains-dir=<dir>/chains
#   -> [UnknownConsensusType] ...: consensus = "clique" is not provided by this build; supported: ethash-pow, engine-api
#   exit 1; <datadir>/rocksdb does not exist afterwards
# AS-3: the same key twice (operator copy of a built-in)
cp <dir>/chains/plataberget2-chain.conf <dir>/chains/sepolia-chain.conf   # key 'sepolia' now in both sets
fukuii chains validate <dir>/chains
#   -> [ChainKeyCollision] chain 'sepolia' is defined twice: built-in classpath:... and operator <dir>/chains/sepolia-chain.conf
#   exit 1
fukuii sepolia --chain-registry-allow-shadow -Dfukuii.blockchains.custom-chains-dir=<dir>/chains
#   -> WARN chain 'sepolia': operator entry ... shadows built-in ... ; node starts on the operator entry
```

## US2 — the built-in chains are unchanged (GT-1)

```bash
# the golden capture, taken on today's files before any loader code exists (slice 1)
sbt "testOnly *GoldenBlockchainConfigSpec*"
# after the loader lands (slice 2): same test, both code paths, both must equal the committed fixtures
sbt -Dfukuii.blockchains.registry.enabled=true  "testOnly *GoldenBlockchainConfigSpec*"
sbt -Dfukuii.blockchains.registry.enabled=false "testOnly *GoldenBlockchainConfigSpec*"
# regenerate fixtures only when a reviewed delta is intended (slice 4, R-3); CI never sets this
sbt -Dfukuii.chainspec.golden.regen=true "testOnly *GoldenBlockchainConfigSpec*" && git diff src/test/resources/chainspec/golden
# the wider baseline
./scripts/fukuii-test essential        # end of thread, once
```

Expected: eight fixtures (`etc`, `mordor`, `gorgoroth`, `test`, `eth`, `sepolia`, `plataberget`, `hive`) under
`src/test/resources/chainspec/golden/`; zero diff except, in slice 4, the enumerated `messConfig.reactivationBlock`
lines on `eth`, `sepolia`, `plataberget`, `hive`, `test` (research R-3; user decision D-1 in plan.md).

## US3 — a misconfigured profile cannot start

```bash
# AS-1: a Sepolia profile that forces the PoW validator
cat > <dir>/conf/sepolia.conf <<'HOCON'
include classpath("application.conf")
fukuii { blockchains { network = "sepolia" }, mining { protocol = "pow" } }
HOCON
FUKUII_CONF_DIR=<dir>/conf fukuii sepolia
#   -> [ProfileConsensusMismatch] chain 'sepolia' (consensus engine-api) derives mining.protocol = engine-api, but the
#      profile sets pow at <dir>/conf/sepolia.conf: 2; remove the key from the profile
#   exit 1, before the datadir is created; no HeaderPoWError can ever be reached

# AS-2: an ETC entry without a MESS block
unzip -p fukuii.jar conf/base/chains/mordor-chain.conf | sed '/^  mess {/,/^  }/d' > <dir>/chains/mordor2-chain.conf
fukuii chains validate <dir>/chains
#   -> [MessBlockMissing] <dir>/chains/mordor2-chain.conf: ETC-family entry must declare mess { enabled = <bool> }
#      (the default is false; ECIP-1100 stays off unless enabled = true)
#   exit 1

# AS-3: moving a fork the datadir has synced past
fukuii mordor -Dfukuii.datadir=<dir>/mordor          # sync past Spiral (block 9,957,000 on Mordor), stop
unzip -p fukuii.jar conf/base/chains/mordor-chain.conf \
  | sed 's/spiral-block-number = "9957000"/spiral-block-number = "99957000"/' > <dir>/chains/mordor-chain.conf
fukuii mordor --chain-registry-allow-shadow -Dfukuii.blockchains.custom-chains-dir=<dir>/chains -Dfukuii.datadir=<dir>/mordor
#   -> [ForkScheduleIncompatible] datadir <dir>/mordor has synced past spiral-block-number (head 10012345); recorded
#      activation 9957000, ChainSpec says 99957000. Refusing: a fork already applied cannot move. ...
#   exit 3; nothing bound; the record is not rewritten
fukuii chains validate --datadir <dir>/mordor        # same error without starting the node, exit 3
```

Adding a *future* fork or a bootnode to the entry and restarting is accepted; the datadir record is rewritten and the
node logs `datadir chain record updated: <fork> <old> -> <new>`.

## Test tiers touched

| Command | What it proves |
| --- | --- |
| `sbt "testOnly *GoldenBlockchainConfigSpec*"` | GT-1 / SC-002 |
| `sbt "testOnly *ChainSpec* *ChainRegistry* *DeploymentDerivation* *DatadirCompatibility*"` | FR-001..FR-005, SC-003 |
| `sbt "testOnly *ChainSpecSchemaSyncSpec*"` | the published JSON Schema equals the loader's tables |
| `sbt "testOnly *LauncherIntegrationSpec* *BlockchainsConfigSpec* *ChainConfig*Spec*"` | FR-002 and the existing config specs at baseline |
| `./scripts/fukuii-test essential` | tier 1 at baseline (end of thread) |
| 023 harness: `plataberget2` / Engine API devnet brought up from an entry | SC-001 |
