package com.chipprbots.ethereum.network

/** Classification of Ethereum client implementations from the P2P Hello `clientId` string.
  *
  * The `clientId` field in the Hello message carries a free-form version string (e.g.
  * "Geth/v1.13.0-stable/linux-amd64/go1.21.0", "besu/v24.5.1/linux-x86_64/openjdk-21"). This object classifies the
  * string into known client families for peer diversity logging.
  */
object NodeClientType {

  sealed trait ClientType {
    def name: String
  }

  case object CoreGeth extends ClientType { val name = "core-geth" }
  case object Geth extends ClientType { val name = "geth" }
  case object Besu extends ClientType { val name = "besu" }
  case object Nethermind extends ClientType { val name = "nethermind" }
  case object Erigon extends ClientType { val name = "erigon" }
  case object Reth extends ClientType { val name = "reth" }
  case object Fukuii extends ClientType { val name = "fukuii" }
  final case class Other(raw: String) extends ClientType { val name = s"other($raw)" }

  /** Classify a raw `clientId` string from the P2P Hello message into a known client type.
    *
    * Matching is case-insensitive prefix/substring. core-geth must be checked before geth since core-geth client
    * strings contain "Geth" as a substring.
    */
  def recognize(clientId: String): ClientType = {
    val lower = clientId.toLowerCase
    if (lower.contains("core-geth") || lower.contains("coregeth")) CoreGeth
    else if (lower.startsWith("geth") || lower.contains("/geth/")) Geth
    else if (lower.startsWith("besu") || lower.contains("/besu/")) Besu
    else if (lower.startsWith("nethermind") || lower.contains("nethermind")) Nethermind
    else if (lower.startsWith("erigon") || lower.contains("erigon")) Erigon
    else if (lower.startsWith("reth") || lower.contains("/reth/")) Reth
    else if (lower.startsWith("fukuii") || lower.contains("fukuii") || lower.contains("chippr")) Fukuii
    else Other(clientId.take(30))
  }
}
