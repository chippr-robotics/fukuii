# EIP-2537 test vectors (B-153)

Read by `Eip2537Corpus`, which feeds `Eip2537DifferentialSpec`
(`src/test/scala/com/chipprbots/ethereum/vm/`). Test resources only; nothing here ships.

| Directory | Upstream | Revision | Licence |
|---|---|---|---|
| `eip/` | ethereum/EIPs `assets/eip-2537/*.json` | master `542e62eb129517130b7facc52bbb9a1f333c711e`; the directory last changed in `faaa35fff856a109c40d291e612447fadc2095fe` (2025-04-10) | CC0-1.0 |
| `geth/` | ethereum/go-ethereum `core/vm/testdata/precompiles/{,fail-}bls*.json` | master `a01129526c55e4f4f42092160776390958c2a662`; the directory last changed in `b381804eb145684a39653aca42936bb17388a7fb` (2025-09-08) | LGPL-3.0-or-later; see `geth/COPYING.LESSER` |

Every file is byte-identical to upstream except the four large MSM files below. Those keep every
vector with k <= 8 pairs, and at most two vectors for each k in 9..32, 64 and 128. Vectors with
k > 128, and the other sizes, are dropped: upstream is 16.7 MB in total, and these four files are
most of it. The kept vectors are unchanged field for field; the JSON is re-serialised with
two-space indentation. The differential's seeded large-k MSMs (k up to 256) cover the dropped sizes.

| File | Vectors kept | Upstream sha256 |
|---|---|---|
| `eip/msm_G1_bls.json` | 48 of 163 | `9473ca855509a10238388355093e092fab46d80753e72a64b8c1accba8364f65` |
| `eip/msm_G2_bls.json` | 49 of 164 | `198411e8e72830245866ad94e2f743fa2499ffb928ca7c2bd10a18ed842368ef` |
| `geth/blsG1MultiExp.json` | 50 of 154 | `b2603f681b9695e6ceb3cc7562c3c922b6db709882c26e84050774c0db7ce33a` |
| `geth/blsG2MultiExp.json` | 51 of 153 | `f9b3fabe719b89883be935d7482805ac1fe734419e5ec77707dc262b0fdad926` |

The `mul_*` and `blsG*Mul` files hold the pre-final G1MUL/G2MUL vectors. They are run as MSMs at
k = 1, the same way go-ethereum's `contracts_test.go` runs them.
