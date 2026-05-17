package com.chipprbots.ethereum.consensus.pow.difficulty

/** Real ETC mainnet block data, verified against core-geth RPC (port 8545).
  *
  * Miner model (Oct 2024 – present oscillation era):
  *   - Stable base: ~125 TH/s ETChash ASICs — dedicated hardware, cannot mine other coins, mine whenever energy is
  *     available. Without ETC this equipment is a brick.
  *   - Flex load: ~0–60 TH/s ETChash ASICs cycling on/off based on energy cost or grid-operator curtailment contracts
  *     (demand-response). The sudden large gaps (137 s) and rapid return (137→5 s within 5 blocks) are consistent with
  *     coordinated batch on/off, not gradual individual profitability decisions.
  *
  * Observed oscillation cycle (Oct 2024 – Jan 2026):
  *   - cycleStart (~21 M, Nov 2024):  ~2286 TH difficulty — flex-off equilibrium
  *   - cycleMid   (~21.85 M, Mar 2025): ~4327 TH difficulty — flex-on peak (all ASICs running)
  *   - cycleEnd   (~23.71 M, Jan 2026): ~2086 TH difficulty — flex-off trough (maximum curtailment)
  *
  * Flex-load exit threshold for c = −1: exitFrac ≥ 0.278 of equilibrium hashrate. Observed flex fraction: ~0.324 →
  * above threshold → c < 0 throughout flex-off phases.
  *
  * Note: the Merge spike (Sept 2022) and post-Merge decline (2022–2024) DO involve GPU miners migrating from ETH and
  * then leaving. Only the current oscillation era (Oct 2024+) is the ASIC flex-load phenomenon.
  */
object OscillationFixtures {

  /** Single-block snapshot sufficient for difficulty and TD verification. */
  case class BlockSnapshot(
      number: BigInt,
      hash: String,
      parentHash: String,
      difficulty: BigInt,
      totalDifficulty: BigInt,
      timestamp: Long
  )

  // ---------------------------------------------------------------------------
  // Pre-Merge ASIC baseline (Apr 2022, block 15,000,000)
  // ~50 TH/s native ASIC community; ~13s blocks; no GPU overlay
  // ---------------------------------------------------------------------------

  val premergeBaseline: BlockSnapshot = BlockSnapshot(
    number = BigInt(15_000_000),
    hash = "0x84874b0adc571dd049d02788a013d74c30362de6fdbc0aa178fec370c8dd1114",
    parentHash = "0xebf1b5d82c42d8fa8dcce8cb2a3a24cc9247a2781bed92e692db93a1061cc8e2",
    difficulty = BigInt("356469657693355"),
    totalDifficulty = BigInt("1905451831131517342514"),
    timestamp = 1650885412L
  )

  // ---------------------------------------------------------------------------
  // Ethereum Merge spike (Sept 15, 2022) — blocks 15,948,899–15,948,903
  // GPU influx: ETC hashrate ~50→~300 TH/s in days; fast blocks (2–6 s each)
  // mergeSpikeParent is block 15,948,898 — seed for verifyDifficultyChain
  // ---------------------------------------------------------------------------

  val mergeSpikeParent: BlockSnapshot = BlockSnapshot(
    number = BigInt(15_948_898),
    hash = "0x71ba68e765355831815d05159edfff13122badc2bc3433aac8aff0d892bd8109",
    parentHash = "",
    difficulty = BigInt("3017524330257252"),
    totalDifficulty = BigInt("2266739390417665856599"),
    timestamp = 1663260648L
  )

  // Inter-block gaps: 2, 3, 5, 6, 3 seconds — all c = +1
  val mergeSpike: Seq[BlockSnapshot] = Seq(
    BlockSnapshot(
      BigInt(15_948_899),
      "0xeb96a361642107454c631faa44caf2042a6ecebe9314c57cbeeb7480096a5d7e",
      "0x71ba68e765355831815d05159edfff13122badc2bc3433aac8aff0d892bd8109",
      BigInt("3018997730809135"),
      BigInt("2266742409415396665734"),
      1663260650L
    ),
    BlockSnapshot(
      BigInt(15_948_900),
      "0x5740b8ff398b9ab03d6c0615132597e8e3b16a7188932ebfe3c955e45fb4ae68",
      "0xeb96a361642107454c631faa44caf2042a6ecebe9314c57cbeeb7480096a5d7e",
      BigInt("3020471850794881"),
      BigInt("2266745429887247460615"),
      1663260653L
    ),
    BlockSnapshot(
      BigInt(15_948_901),
      "0xf5cef05b027dd24175d42b313f74f2485da33f4aa6278600dffad511be54d1b4",
      "0x5740b8ff398b9ab03d6c0615132597e8e3b16a7188932ebfe3c955e45fb4ae68",
      BigInt("3021946690565776"),
      BigInt("2266748451833938026391"),
      1663260658L
    ),
    BlockSnapshot(
      BigInt(15_948_902),
      "0xc7e5a0b3905c4d632d1f171597a263f2cbe15ccfc22f70be50c6c6cef369584c",
      "0xf5cef05b027dd24175d42b313f74f2485da33f4aa6278600dffad511be54d1b4",
      BigInt("3023422250473278"),
      BigInt("2266751475256188499669"),
      1663260664L
    ),
    BlockSnapshot(
      BigInt(15_948_903),
      "0x27d6d6329095e64d434e40132a067e8d6662f89d97df47f75d741aca7160c6de",
      "0xc7e5a0b3905c4d632d1f171597a263f2cbe15ccfc22f70be50c6c6cef369584c",
      BigInt("3024898530869016"),
      BigInt("2266754500154719368685"),
      1663260667L
    )
  )

  /** Peak Merge-era difficulty (Sept 16, 2022) — ~3410 TH, ~300 TH/s equivalent. */
  val mergePeak: BlockSnapshot = BlockSnapshot(
    number = BigInt(15_951_400),
    hash = "0x501eb6291996edaad05f11317dded1a741bf813cdc1e951c90748030ced91b3e",
    parentHash = "0xc20b97258630d02e92a4569a5efe544701fc47d68790640d12f7a1b9822885f0",
    difficulty = BigInt("3410908635201688"),
    totalDifficulty = BigInt("2274898303168627081531"),
    timestamp = 1663291074L
  )

  // ---------------------------------------------------------------------------
  // Post-Merge miner exodus (July 2023) — blocks 18,000,000–18,000,004
  // GPU refugees (ETH Merge migrants) leaving; highly variable inter-block times (5–268 s)
  // declinePhaseParent is block 17,999,999 — seed for verifyDifficultyChain
  // ---------------------------------------------------------------------------

  val declinePhaseParent: BlockSnapshot = BlockSnapshot(
    number = BigInt(17_999_999),
    hash = "0xad7aba3851d2c1302a2bfd63a566e8e511c0cef559b95ba13a54f3f45c3df27a",
    parentHash = "",
    difficulty = BigInt("1926698365544102"),
    totalDifficulty = BigInt("5816585972940532730658"),
    timestamp = 1690407145L
  )

  // Inter-block gaps: 22, 182, 268, 5, 129 seconds
  // c values:         -1, -19,  -28, +1,  -13
  val declinePhase: Seq[BlockSnapshot] = Seq(
    BlockSnapshot(
      BigInt(18_000_000),
      "0x75ee56f65fd654484465c63cd78af5dfdfc583067085f9637650c3ca2c93a683",
      "0xad7aba3851d2c1302a2bfd63a566e8e511c0cef559b95ba13a54f3f45c3df27a",
      BigInt("1925757594857802"),
      BigInt("5816587898698127588460"),
      1690407167L
    ),
    BlockSnapshot(
      BigInt(18_000_001),
      "0x2dbc8c3ff39693e6d587e6862d49416887691d0a84404d9cd2518582c84c5fba",
      "0x75ee56f65fd654484465c63cd78af5dfdfc583067085f9637650c3ca2c93a683",
      BigInt("1907891679671136"),
      BigInt("5816589806589807259596"),
      1690407349L
    ),
    BlockSnapshot(
      BigInt(18_000_002),
      "0x145191f77c9de7d189834c7354baba1ec654dafa5f8fc878c1318f67195743e9",
      "0x2dbc8c3ff39693e6d587e6862d49416887691d0a84404d9cd2518582c84c5fba",
      BigInt("1881807223113144"),
      BigInt("5816591688397030372740"),
      1690407617L
    ),
    BlockSnapshot(
      BigInt(18_000_003),
      "0x976a7f204532eac62acfb8f249e49c1c0bd9d9fc77893557bdc4f4248d93c82f",
      "0x145191f77c9de7d189834c7354baba1ec654dafa5f8fc878c1318f67195743e9",
      BigInt("1882726074296304"),
      BigInt("5816593571123104669044"),
      1690407622L
    ),
    BlockSnapshot(
      BigInt(18_000_004),
      "0xda51b6ad912316acf049de0658851ec09ab55a63df7aa4e5f6ef329dd222074c",
      "0x976a7f204532eac62acfb8f249e49c1c0bd9d9fc77893557bdc4f4248d93c82f",
      BigInt("1870775176363772"),
      BigInt("5816595441898281032816"),
      1690407751L
    )
  )

  // ---------------------------------------------------------------------------
  // ASIC flex-load oscillation — full cycle boundaries (Oct 2024 – Jan 2026)
  // ---------------------------------------------------------------------------

  /** Oscillation onset (Nov 2024) — flex-off equilibrium, ~2286 TH difficulty. */
  val cycleStart: BlockSnapshot = BlockSnapshot(
    number = BigInt(21_000_000),
    hash = "0x3551672560c1fbc33e20c31c4e91eaa9643c173a7e073872144ea4fdbaac0209",
    parentHash = "0x1e263ee5eb2fffb3052a59e3cf0d1b0873064b84f6a22b10a6dc6c3570b2a06b",
    difficulty = BigInt("2285976208884323"),
    totalDifficulty = BigInt("12223071069702416209526"),
    timestamp = 1730518580L
  )

  /** Flex-on peak (Mar 2025) — all ASICs running, difficulty maximum, ~4327 TH. */
  val cycleMid: BlockSnapshot = BlockSnapshot(
    number = BigInt(21_852_000),
    hash = "0x16b21a09f830b63481eff46319d4c9c84ade0cca2b707b2b46d9d72c2e2e6248",
    parentHash = "0x62148fa093cd098ddef506ef0de54105b291e264bab555f7060b823d6722f808",
    difficulty = BigInt("4326933474757836"),
    totalDifficulty = BigInt("15123768209429521428962"),
    timestamp = 1742028945L
  )

  /** Flex-off trough (Jan 2026) — maximum curtailment for the cycle, ~2086 TH. */
  val cycleEnd: BlockSnapshot = BlockSnapshot(
    number = BigInt(23_706_000),
    hash = "0x73453ed3739f175416af0768b3835f48d961cad8bb573767d389ec3e1b53ecb5",
    parentHash = "0xc1a2157823f4e7cb65e0d7ef11d190cbfc672a6c3c6f19b945fb5c32b7229919",
    difficulty = BigInt("2086436210099032"),
    totalDifficulty = BigInt("21951580636532252739556"),
    timestamp = 1767258889L
  )

  // ---------------------------------------------------------------------------
  // Flex-on phase: 10 consecutive fast blocks near difficulty peak (Mar 2025)
  // All ETChash ASICs running; flexOnPhaseParent is block 21,852,179 — seed for verifyDifficultyChain
  // ---------------------------------------------------------------------------

  val flexOnPhaseParent: BlockSnapshot = BlockSnapshot(
    number = BigInt(21_852_179),
    hash = "0x873dbe1c2d83d7b7bc494e5d4a7f7ebaf42941930c56e597fd4a948b64718bea",
    parentHash = "0x90f5f6677271ce922e6f69e90f45b30be1f4c599957bcfdb24da2e8c4ff9cda3",
    difficulty = BigInt("4295110242530329"),
    totalDifficulty = BigInt("15124542577870379169468"),
    timestamp = 1742031567L
  )

  // Inter-block gaps: 4, 2, 1, 8, 8, 6, 2, 2, 5, 6 seconds — all c = +1
  val flexOnPhase: Seq[BlockSnapshot] = Seq(
    BlockSnapshot(
      BigInt(21_852_180),
      "0x9ce8543f386d8d3df818ba72def514f4120e951860fc67015a39a2ba12c96796",
      "0x873dbe1c2d83d7b7bc494e5d4a7f7ebaf42941930c56e597fd4a948b64718bea",
      BigInt("4297207464328439"),
      BigInt("15124546875077843497907"),
      1742031571L
    ),
    BlockSnapshot(
      BigInt(21_852_181),
      "0x89dd5def24b55082a83523019478c8045aa4555e11aada3350afdf3b600a3d77",
      "0x9ce8543f386d8d3df818ba72def514f4120e951860fc67015a39a2ba12c96796",
      BigInt("4299305710160630"),
      BigInt("15124551174383553658537"),
      1742031573L
    ),
    BlockSnapshot(
      BigInt(21_852_182),
      "0x7cf4b8f5d31583343e3b9fdb02f1d19ab54cf00eca40cce093ca62fc9bb3ae39",
      "0x89dd5def24b55082a83523019478c8045aa4555e11aada3350afdf3b600a3d77",
      BigInt("4301404980526919"),
      BigInt("15124555475788534185456"),
      1742031574L
    ),
    BlockSnapshot(
      BigInt(21_852_183),
      "0x9d28e86e1394dabbfa1283ffb58e163a6a1c9a01f384a570c343217118c9490d",
      "0x7cf4b8f5d31583343e3b9fdb02f1d19ab54cf00eca40cce093ca62fc9bb3ae39",
      BigInt("4303505275927566"),
      BigInt("15124559779293810113022"),
      1742031582L
    ),
    BlockSnapshot(
      BigInt(21_852_184),
      "0xdf46097d731630cc8282e99f35bc1705578a642c66a47925b7cd9afc4c565d68",
      "0x9d28e86e1394dabbfa1283ffb58e163a6a1c9a01f384a570c343217118c9490d",
      BigInt("4305606596863077"),
      BigInt("15124564084900406976099"),
      1742031590L
    ),
    BlockSnapshot(
      BigInt(21_852_185),
      "0x2dbec2bb56f67d5eff6a107dc5e9737d79cde22a99fc13ee6b2c5296b042b126",
      "0xdf46097d731630cc8282e99f35bc1705578a642c66a47925b7cd9afc4c565d68",
      BigInt("4307708943834201"),
      BigInt("15124568392609350810300"),
      1742031596L
    ),
    BlockSnapshot(
      BigInt(21_852_186),
      "0x45f2a93a4406128d56f22ad9fc0b01ba7486e94eb1acb63246eadf8e0cb52cc1",
      "0x2dbec2bb56f67d5eff6a107dc5e9737d79cde22a99fc13ee6b2c5296b042b126",
      BigInt("4309812317341932"),
      BigInt("15124572702421668152232"),
      1742031598L
    ),
    BlockSnapshot(
      BigInt(21_852_187),
      "0x977129d99acd34656b77b8423d14c51ba9b2822efbbcb1205c676a036152f844",
      "0x45f2a93a4406128d56f22ad9fc0b01ba7486e94eb1acb63246eadf8e0cb52cc1",
      BigInt("4311916717887509"),
      BigInt("15124577014338386039741"),
      1742031600L
    ),
    BlockSnapshot(
      BigInt(21_852_188),
      "0xa6a4f0b34892a2ed2c3c50a423e7a611b5cbb5b8e88ab9fe73083d0287c733ee",
      "0x977129d99acd34656b77b8423d14c51ba9b2822efbbcb1205c676a036152f844",
      BigInt("4314022145972415"),
      BigInt("15124581328360532012156"),
      1742031605L
    ),
    BlockSnapshot(
      BigInt(21_852_189),
      "0xea4d9a477c8e92f35754d4d1785e0242941f1137c75193b531a52326d0e8ec02",
      "0xa6a4f0b34892a2ed2c3c50a423e7a611b5cbb5b8e88ab9fe73083d0287c733ee",
      BigInt("4316128602098378"),
      BigInt("15124585644489134110534"),
      1742031611L
    )
  )

  // ---------------------------------------------------------------------------
  // Flex-off phase: 10 consecutive blocks during ASIC curtailment (May 2025)
  // 9/10 blocks have gap > 17 s → c < 0 → difficulty falling
  // Block 22,200,018 (index 7) has gap = 12 s → c = 0 (one neutral in ten)
  // flexOffPhaseParent is block 22,200,010 — seed for verifyDifficultyChain
  // ---------------------------------------------------------------------------

  val flexOffPhaseParent: BlockSnapshot = BlockSnapshot(
    number = BigInt(22_200_010),
    hash = "0xf89a9a2d748a59289c00bca3e36edab92fcb08c41664f8eeac7c9f6f2ac2d8ac",
    parentHash = "0x29eee1202c2e1eea151ea5d0bef2ed49b3a89b5b51c8c33f4c062c99b5c47309",
    difficulty = BigInt("3927774101450170"),
    totalDifficulty = BigInt("16491497699140020264714"),
    timestamp = 1746771794L
  )

  // Inter-block gaps: 137, 96, 109, 29, 36, 80, 26, 12, 34, 53 seconds
  // c values:         -14,  -9,  -11, -2,  -3,  -7, -1,  0,  -2,  -4
  val flexOffPhase: Seq[BlockSnapshot] = Seq(
    BlockSnapshot(
      BigInt(22_200_011),
      "0x5c2c443873e5630f30680730f82163e09cb2a16a4150f908235654c25ae05e80",
      "0xf89a9a2d748a59289c00bca3e36edab92fcb08c41664f8eeac7c9f6f2ac2d8ac",
      BigInt("3900924083178548"),
      BigInt("16491501600064103443262"),
      1746771931L
    ),
    BlockSnapshot(
      BigInt(22_200_012),
      "0x8f5984894284e7912cd2a2083983f5559bbdb216b1ee56457d9a7c3aa1a4c841",
      "0x5c2c443873e5630f30680730f82163e09cb2a16a4150f908235654c25ae05e80",
      BigInt("3883781350391147"),
      BigInt("16491505483845453834409"),
      1746772027L
    ),
    BlockSnapshot(
      BigInt(22_200_013),
      "0xd5c4ac052f55540471206850b1326f0e2f7dc182569f49bcee4402c9da100a69",
      "0x8f5984894284e7912cd2a2083983f5559bbdb216b1ee56457d9a7c3aa1a4c841",
      BigInt("3862921196653702"),
      BigInt("16491509346766650488111"),
      1746772136L
    ),
    BlockSnapshot(
      BigInt(22_200_014),
      "0x3dd906c2d3adce266b46ec0838dab2ad27e26ad7831559f6650a7b7cd0ce973f",
      "0xd5c4ac052f55540471206850b1326f0e2f7dc182569f49bcee4402c9da100a69",
      BigInt("3859148812672596"),
      BigInt("16491513205915463160707"),
      1746772165L
    ),
    BlockSnapshot(
      BigInt(22_200_015),
      "0xd7cc1ba3f2e2a1354e48cfd91cb79b4c5e173ce9bcaee0432181171a75f92ec3",
      "0x3dd906c2d3adce266b46ec0838dab2ad27e26ad7831559f6650a7b7cd0ce973f",
      BigInt("3853495762654035"),
      BigInt("16491517059411225814742"),
      1746772201L
    ),
    BlockSnapshot(
      BigInt(22_200_016),
      "0xbb11e2099d4417319fca481d2251bd0523242ac51c872011d281f4e5e389c192",
      "0xd7cc1ba3f2e2a1354e48cfd91cb79b4c5e173ce9bcaee0432181171a75f92ec3",
      BigInt("3840324634559029"),
      BigInt("16491520899735860373771"),
      1746772281L
    ),
    BlockSnapshot(
      BigInt(22_200_017),
      "0xbdc8b5280cf68c1d06f53842e682b3e87dfd703a4aea2281ca1b6dc191830272",
      "0xbb11e2099d4417319fca481d2251bd0523242ac51c872011d281f4e5e389c192",
      BigInt("3838449476046061"),
      BigInt("16491524738185336419832"),
      1746772307L
    ),
    BlockSnapshot(
      BigInt(22_200_018), // gap=12s → c=0, difficulty unchanged from prev block
      "0xa7319557d0794007b80d476ae39aaff03d28b32f7b31e8db342a06df0533665d",
      "0xbdc8b5280cf68c1d06f53842e682b3e87dfd703a4aea2281ca1b6dc191830272",
      BigInt("3838449476046061"),
      BigInt("16491528576634812465893"),
      1746772319L
    ),
    BlockSnapshot(
      BigInt(22_200_019),
      "0x450d1d789830c582ab019309e3be57252b3dc3e6d502175fe8a382caba53efee",
      "0xa7319557d0794007b80d476ae39aaff03d28b32f7b31e8db342a06df0533665d",
      BigInt("3834700990229611"),
      BigInt("16491532411335802695504"),
      1746772353L
    ),
    BlockSnapshot(
      BigInt(22_200_020),
      "0x077d7a7c65cc78249e64c179a58753ccc34609fd3656a581057ddb07f48e4d05",
      "0x450d1d789830c582ab019309e3be57252b3dc3e6d502175fe8a382caba53efee",
      BigInt("3827211339858071"),
      BigInt("16491536238547142553575"),
      1746772406L
    )
  )

  // ---------------------------------------------------------------------------
  // Derived constants from miner composition model
  //
  // Equilibrium (all flex ASICs on): ~185 TH/s → 13 s block time
  // Stable base:  ~125 TH/s ETChash ASICs (always on when energy available)
  // Flex load:    ~ 60 TH/s ETChash ASICs (curtailed by energy grid demand-response)
  //
  // Flex-off expected block time = 13 × (185/125) ≈ 19.2 s → c = −1
  // ---------------------------------------------------------------------------

  val asicBaseHrFrac: Double = 125.0 / 185.0 // ≈ 0.676
  val flexMaxHrFrac: Double  = 60.0 / 185.0  // ≈ 0.324

  /** Minimum flex-load exit fraction that pushes block time ≥ 18 s → c = −1. */
  val flexExitThresholdFrac: Double = 1.0 - 13.0 / 18.0 // ≈ 0.278

  /** Expected block time (seconds) when `exitFrac` of equilibrium hashrate exits. */
  def expectedBlockTimeAfterExit(exitFrac: Double): Long =
    math.round(13.0 / (1.0 - exitFrac))

  /** DAA c coefficient for a given inter-block gap: max(1 − floor(gap/9), −99). */
  def cCoeff(gapSecs: Long): Long = math.max(1L - gapSecs / 9L, -99L)
}
