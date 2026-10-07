package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

/** The Prague request-queue predeploys — EIP-7002 withdrawal requests at `BlockExecution.WithdrawalQueueAddress`,
  * EIP-7251 consolidation requests at `BlockExecution.ConsolidationQueueAddress` — as runtime code verbatim from
  * go-ethereum's devp2p test chain `genesis.json` (504 and 414 bytes).
  *
  * A Prague block needs both deployed (`BlockExecution.requireRequestPredeploysPresent`), and they are what turns a
  * transaction into an EIP-7685 execution request, so every spec that builds or executes a Prague block with requests
  * uses these.
  */
object QueuePredeploys:

  val WithdrawalQueueCode: ByteString = ByteString(
    Hex.decode(
      "3373fffffffffffffffffffffffffffffffffffffffe1460cb5760115f54807fffffffffffffffffffffffffffffffff" +
        "ffffffffffffffffffffffffffffffff146101f457600182026001905f5b5f8211156068578101908302848302900491" +
        "6001019190604d565b909390049250505036603814608857366101f457346101f4575f5260205ff35b34106101f45760" +
        "0154600101600155600354806003026004013381556001015f35815560010160203590553360601b5f5260385f601437" +
        "604c5fa0600101600355005b6003546002548082038060101160df575060105b5f5b8181146101835782810160030260" +
        "040181604c02815460601b8152601401816001015481526020019060020154807fffffffffffffffffffffffffffffff" +
        "ff00000000000000000000000000000000168252906010019060401c908160381c81600701538160301c816006015381" +
        "60281c81600501538160201c81600401538160181c81600301538160101c81600201538160081c816001015353600101" +
        "60e1565b910180921461019557906002556101a0565b90505f6002555f6003555b5f54807fffffffffffffffffffffff" +
        "ffffffffffffffffffffffffffffffffffffffffff14156101cd57505f5b6001546002828201116101e25750505f6101" +
        "e8565b01600290035b5f555f600155604c025ff35b5f5ffd"
    )
  )

  val ConsolidationQueueCode: ByteString = ByteString(
    Hex.decode(
      "3373fffffffffffffffffffffffffffffffffffffffe1460d35760115f54807fffffffffffffffffffffffffffffffff" +
        "ffffffffffffffffffffffffffffffff1461019a57600182026001905f5b5f8211156068578101908302848302900491" +
        "6001019190604d565b9093900492505050366060146088573661019a573461019a575f5260205ff35b341061019a5760" +
        "0154600101600155600354806004026004013381556001015f358155600101602035815560010160403590553360601b" +
        "5f5260605f60143760745fa0600101600355005b6003546002548082038060021160e7575060025b5f5b818114610129" +
        "5782810160040260040181607402815460601b8152601401816001015481526020018160020154815260200190600301" +
        "54905260010160e9565b910180921461013b5790600255610146565b90505f6002555f6003555b5f54807fffffffffff" +
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffff141561017357505f5b600154600182820111610188" +
        "5750505f61018e565b01600190035b5f555f6001556074025ff35b5f5ffd"
    )
  )

  /** An EIP-7002 add-request call's calldata: pubkey48 || amount8 (big-endian gwei), 56 bytes. */
  val WithdrawalRequestCalldata: ByteString = ByteString(Hex.decode("42" * 48 + "000000003b9aca00"))
