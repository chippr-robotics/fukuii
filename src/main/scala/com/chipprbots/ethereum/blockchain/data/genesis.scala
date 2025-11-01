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
    alloc: Map[String, GenesisAccount]
)
