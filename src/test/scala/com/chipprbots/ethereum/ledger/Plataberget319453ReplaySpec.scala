package com.chipprbots.ethereum.ledger

import java.util.zip.GZIPInputStream

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*

/** Replays the transactions of Platåberget (glamsterdam-devnet-8, chain 7091047534) block 319453 over its canonical
  * pre-state and requires every receipt gas figure and every account to equal the canonical block's: gas from the
  * receipts, post-state from `eth_getBlockAccessList` (parent trie + that BAL was verified to give the header's
  * stateRoot 1d1c22e5…). fukuii computed f6538e04… for this block: tx 3's failed CREATE left memory unexpanded, so a
  * later MSTORE charged the expansion twice (+142 gas), shifting the sender's and the coinbase's balances.
  *
  * Fixture: `amsterdam/plataberget-319453.txt.gz` — sections #TXS (type|from|to|nonce|value|gas|gasPrice|maxFee|maxTip|
  * input|receiptGas|status|hash), #PRE (addr|nonce|balance|codeLen, non-contract accounts only), #POST (addr|nonce|
  * balance|code, "-" = unchanged), #WD (addr|gwei). System contracts are not read by any transaction, so they are left
  * out.
  */
// scalastyle:off magic.number
class Plataberget319453ReplaySpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private def sections: Map[String, List[String]] =
    val in = new GZIPInputStream(getClass.getResourceAsStream("/amsterdam/plataberget-319453.txt.gz"))
    val ls =
      try scala.io.Source.fromInputStream(in, "UTF-8").getLines().filter(_.nonEmpty).toList
      finally in.close()
    var cur = ""
    ls.groupBy { l =>
      if l.startsWith("#") then
        cur = l; "_"
      else cur
    }.view
      .filterKeys(_ != "_")
      .toMap

  private def addr(s: String) = Address(Hex.decode(s.stripPrefix("0x")))

  "block 319453 (Amsterdam, devnet-8)" should "replay to the canonical receipt gas and post-state" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val sec = sections
    val cfg = amsterdamConfig.copy(chainId = ChainId(BigInt("7091047534")))
    val base = amsterdamHeader(319453, 0x6ac152f0L).copy(
      gasLimit = GasAmount(BigInt("be8c711", 16)),
      beneficiary = addr("0x8f614cdb61e37b6f18d9705942c93fa86b04d711").bytes,
      mixHash = BlockHash(ByteString(Hex.decode("a9fa01dc46e098d3433294e5b26dd4a54d1ec4dcb6647e47b6ec2a86d0a229e5")))
    )
    val hdr = base.copy(extraFields = base.extraFields match
      case h: HefPostAmsterdam => h.copy(baseFee = BigInt("1846e82ed", 16), slotNumber = BigInt(0x5a2c4L))
      case o                   => o
    )
    var world = InMemoryWorldStateProxy(
      setup.storagesInstance.storages.evmCodeStorage,
      setup.blockchain.getBackingMptStorage(-1),
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = true,
      ethCompatibleStorage = true
    )
    val pre = sec("#PRE").map { l =>
      val p = l.split("\\|"); (p(0), (BigInt(p(1)), BigInt(p(2))))
    }.toMap
    pre.foreach { case (a, (n, b)) =>
      if n != 0 || b != 0 then world = world.saveAccount(addr(a), Account(nonce = UInt256(n), balance = UInt256(b)))
    }

    val gasMismatches = scala.collection.mutable.ListBuffer[String]()
    sec("#TXS").zipWithIndex.foreach { case (l, i) =>
      val p = l.split("\\|", -1)
      val to = if p(2).isEmpty then None else Some(addr(p(2)))
      val payload = ByteString(Hex.decode(p(9)))
      val tx: Transaction = p(0).toInt match
        case 0 =>
          LegacyTransaction(BigInt(p(3)), GasPrice(BigInt(p(6))), GasAmount(BigInt(p(5))), to, BigInt(p(4)), payload)
        case 1 =>
          TransactionWithAccessList(
            cfg.chainId.value,
            BigInt(p(3)),
            GasPrice(BigInt(p(6))),
            GasAmount(BigInt(p(5))),
            to,
            BigInt(p(4)),
            payload,
            Nil
          )
        case _ =>
          TransactionWithDynamicFee(
            cfg.chainId.value,
            BigInt(p(3)),
            BigInt(p(8)),
            BigInt(p(7)),
            GasAmount(BigInt(p(5))),
            to,
            BigInt(p(4)),
            payload,
            Nil
          )
      val stx = SignedTransaction(tx, com.chipprbots.ethereum.crypto.ECDSASignature(BigInt(1), BigInt(1), BigInt(27)))
      val res = setup.prep.executeTransaction(stx, addr(p(1)), hdr, world)(cfg)
      if res.gasUsed != BigInt(p(10)) then gasMismatches += s"tx$i: got ${res.gasUsed} canonical ${p(10)}"
      (res.vmError.isEmpty, p(11)) match
        case (true, "0x1") | (false, "0x0") => ()
        case other                          => gasMismatches += s"tx$i: status $other"
      world = res.worldState
    }
    gasMismatches.toList shouldBe Nil

    sec("#WD").foreach { l =>
      val p = l.split("\\|"); val a = addr(p(0))
      val acc = world.getAccount(a).getOrElse(Account.empty(UInt256.Zero))
      world =
        world.saveAccount(a, acc.copy(balance = UInt256(acc.balance.toBigInt + BigInt(p(1)) * BigInt(1000000000L))))
    }
    val accountMismatches = sec("#POST").flatMap { l =>
      val p = l.split("\\|")
      val (pn, pb) = pre.getOrElse(p(0), (BigInt(0), BigInt(0)))
      val expN = if p(1) == "-" then pn else BigInt(p(1))
      val expB = if p(2) == "-" then pb else BigInt(p(2))
      val a = world.getAccount(addr(p(0)))
      val gotN = a.map(_.nonce.toBigInt).getOrElse(BigInt(0))
      val gotB = a.map(_.balance.toBigInt).getOrElse(BigInt(0))
      val codeLen = world.getCode(addr(p(0))).size
      val expCode = if p(3).startsWith("CODE") then p(3).drop(4).toInt else 0
      Option.when(gotN != expN || gotB != expB || codeLen != expCode)(
        s"${p(0)} nonce $gotN/$expN balance $gotB/$expB (delta ${gotB - expB}) codeLen $codeLen/$expCode"
      )
    }
    accountMismatches shouldBe Nil
  }
