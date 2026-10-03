package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.UInt256

case class PrecompiledAccountConfig(name: String)

case class GenesisAccount(
    precompiled: Option[PrecompiledAccountConfig],
    balance: UInt256,
    code: Option[ByteString],
    nonce: Option[UInt256],
    storage: Option[Map[UInt256, UInt256]]
)

case class GenesisData(
    nonce: ByteString,
    mixHash: Option[ByteString],
    difficulty: String,
    extraData: ByteString,
    gasLimit: String,
    coinbase: ByteString,
    timestamp: String,
    alloc: Map[String, GenesisAccount],
    baseFeePerGas: Option[String] = None,
    excessBlobGas: Option[String] = None,
    blobGasUsed: Option[String] = None,
    // EIP-7843 genesis `slotNumber` (hex quantity), read only when Amsterdam is active at the genesis timestamp;
    // absent means 0 (go-ethereum core/genesis.go `IsAmsterdam`).
    slotNumber: Option[String] = None
)
