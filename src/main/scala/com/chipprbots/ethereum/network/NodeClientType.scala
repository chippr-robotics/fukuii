package com.chipprbots.ethereum.network

sealed trait NodeClientType
object NodeClientType {
  case object CoreGeth   extends NodeClientType
  case object Fukuii     extends NodeClientType
  case object Nethermind extends NodeClientType
  case object Besu       extends NodeClientType
  case object Erigon     extends NodeClientType
  case object Reth       extends NodeClientType
  case object Geth       extends NodeClientType // must come after CoreGeth in pattern list
  case object Unknown    extends NodeClientType

  // Ordered so longer/more-specific substrings match before shorter ones.
  // "coregeth" and "core-geth" must precede "geth".
  private val patterns: Seq[(String, NodeClientType)] = Seq(
    "coregeth"   -> CoreGeth,
    "core-geth"  -> CoreGeth,
    "fukuii"     -> Fukuii,
    "nethermind" -> Nethermind,
    "besu"       -> Besu,
    "erigon"     -> Erigon,
    "reth"       -> Reth,
    "geth"       -> Geth,
  )

  def recognize(clientId: String): NodeClientType = {
    val lower = clientId.toLowerCase
    patterns.collectFirst { case (pat, ct) if lower.contains(pat) => ct }.getOrElse(Unknown)
  }
}
