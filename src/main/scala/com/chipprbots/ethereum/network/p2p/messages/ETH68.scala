package com.chipprbots.ethereum.network.p2p.messages

/** ETH/68 wire protocol packet definitions.
  *
  * See https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth68
  *
  * MESSAGE CODE TABLE (Fukuii offsets: wire code = std + 0x10 due to capability framing)
  *
  * Wire Std Message Source object Change vs ETH67 0x10 (0x00) Status ETHPackets.Status68 (6-field, TD present)
  * unchanged (ETH64.Status deleted) 0x11 (0x01) NewBlockHashes ETHPackets.NewBlockHashes unchanged
  * (ETH62.NewBlockHashes deleted) 0x12 (0x02) Transactions ETHPackets.SignedTransactions unchanged 0x13 (0x03)
  * GetBlockHeaders ETHPackets.GetBlockHeaders unchanged (ETH66 deleted) 0x14 (0x04) BlockHeaders
  * ETHPackets.BlockHeaders unchanged (ETH66 deleted) 0x15 (0x05) GetBlockBodies ETHPackets.GetBlockBodies unchanged
  * (ETH66 deleted) 0x16 (0x06) BlockBodies ETHPackets.BlockBodies unchanged (ETH66 deleted) 0x17 (0x07) NewBlock
  * ETHPackets.NewBlock unchanged 0x18 (0x08) NewPooledTransactionHashes ETHPackets.NewPooledTransactionHashes unchanged
  * (ETH67 deleted) 0x19 (0x09) GetPooledTransactions ETHPackets.GetPooledTransactions unchanged (ETH66 deleted) 0x1a
  * (0x0a) PooledTransactions ETHPackets.PooledTransactions unchanged (ETH66 deleted) 0x1d (0x0d) GetNodeData REJECTED —
  * EIP-4938 REMOVED in ETH68 0x1e (0x0e) NodeData REJECTED — EIP-4938 REMOVED in ETH68 0x1f (0x0f) GetReceipts
  * ETHPackets.GetReceipts unchanged (ETH66 deleted) 0x20 (0x10) Receipts ETHPackets.Receipts68 (bloom-inclusive)
  * unchanged (ETH66 deleted)
  *
  * NOTE: Status uses TD (totalDifficulty) in ETH68. ETC is PoW — TD is a permanent field, not a legacy artefact. ETH69
  * drops TD; see ETH69.Status.
  *
  * REJECTED messages are enforced in ETH68MessageDecoder (MessageDecoders.scala) by returning MalformedMessageError,
  * consistent with go-ethereum handler.go and Erigon ProtoIds.
  */
object ETH68 {

  // Type aliases — documents what ETH68 IS. All pre-ETH68 protocol files deleted.
  // ETHPackets is the canonical source for all ETH68+ wire types.
  type Status = ETHPackets.Status68.Status68
  type NewBlockHashes = ETHPackets.NewBlockHashes.NewBlockHashes
  type SignedTransactions = ETHPackets.SignedTransactions
  type NewBlock = ETHPackets.NewBlock
  type GetBlockHeaders = ETHPackets.GetBlockHeaders
  type BlockHeaders = ETHPackets.BlockHeaders
  type GetBlockBodies = ETHPackets.GetBlockBodies
  type BlockBodies = ETHPackets.BlockBodies
  type NewPooledTransactionHashes = ETHPackets.NewPooledTransactionHashes
  type GetPooledTransactions = ETHPackets.GetPooledTransactions
  type PooledTransactions = ETHPackets.PooledTransactions
  type GetReceipts = ETHPackets.GetReceipts
  type Receipts = ETHPackets.Receipts68
}
