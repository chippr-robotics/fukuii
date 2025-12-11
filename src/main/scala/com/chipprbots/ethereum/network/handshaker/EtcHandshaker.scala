package com.chipprbots.ethereum.network.handshaker

@deprecated("Use NetworkHandshaker", "v1.0")
type EtcHandshaker = NetworkHandshaker

@deprecated("Use NetworkHandshakerConfiguration", "v1.0")
type EtcHandshakerConfiguration = NetworkHandshakerConfiguration

@deprecated("Use NetworkHandshaker", "v1.0")
object EtcHandshaker {
  def apply(handshakerConfiguration: NetworkHandshakerConfiguration): NetworkHandshaker =
    NetworkHandshaker(handshakerConfiguration)
}
