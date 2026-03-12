package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.vm.ProgramError

case class TxResult(
    worldState: InMemoryWorldStateProxy,
    gasUsed: BigInt,
    logs: Seq[TxLogEntry],
    vmReturnData: ByteString,
    vmError: Option[ProgramError]
)
