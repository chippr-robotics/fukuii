package com.chipprbots.ethereum.network.p2p.messages

/** ETH/68 wire protocol packet definitions.
  *
  * See https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth68
  *
  * MESSAGE CODE TABLE (Fukuii offsets: wire code = std + 0x10 due to capability framing)
  *
  * Wire   Std    Message                      Source object                              Change vs ETH67
  * 0x10  (0x00)  Status                       ETH64.Status (6-field, TD present)         unchanged
  * 0x11  (0x01)  NewBlockHashes               ETH62.NewBlockHashes                       unchanged
  * 0x12  (0x02)  Transactions                 BaseETH6XMessages.SignedTransactions        unchanged
  * 0x13  (0x03)  GetBlockHeaders              ETH66.GetBlockHeaders                       unchanged
  * 0x14  (0x04)  BlockHeaders                 ETH66.BlockHeaders                          unchanged
  * 0x15  (0x05)  GetBlockBodies               ETH66.GetBlockBodies                        unchanged
  * 0x16  (0x06)  BlockBodies                  ETH66.BlockBodies                           unchanged
  * 0x17  (0x07)  NewBlock                     BaseETH6XMessages.NewBlock                  unchanged
  * 0x18  (0x08)  NewPooledTransactionHashes   ETH67.NewPooledTransactionHashes            unchanged (ETH68 format)
  * 0x19  (0x09)  GetPooledTransactions        ETH66.GetPooledTransactions                 unchanged
  * 0x1a  (0x0a)  PooledTransactions           ETH66.PooledTransactions                    unchanged
  * 0x1d  (0x0d)  GetNodeData                  REJECTED — EIP-4938                        REMOVED in ETH68
  * 0x1e  (0x0e)  NodeData                     REJECTED — EIP-4938                        REMOVED in ETH68
  * 0x1f  (0x0f)  GetReceipts                  ETH66.GetReceipts                           unchanged
  * 0x20  (0x10)  Receipts                     ETH66.Receipts (bloom-inclusive)            unchanged
  *
  * NOTE: Status uses TD (totalDifficulty) in ETH68. ETC is PoW — TD is a permanent field,
  * not a legacy artefact. ETH69 drops TD; see ETH69.Status.
  *
  * All packet implementations are inherited from ETH62–ETH67 until ETHPackets.scala is
  * established as the canonical source. Deletion of ETH62–ETH67 is blocked on retiring
  * those capability versions from EtcHelloExchangeState.
  *
  * REJECTED messages are enforced in ETH68MessageDecoder (MessageDecoders.scala) by
  * returning MalformedMessageError, consistent with go-ethereum handler.go and Erigon ProtoIds.
  */
object ETH68 {

  // Type aliases — documents what ETH68 IS while ETH62-67 still exist as source files.
  // Will be removed when packet definitions move to ETHPackets.scala.
  type Status                     = ETH64.Status
  type NewBlockHashes             = ETH62.NewBlockHashes
  type SignedTransactions         = BaseETH6XMessages.SignedTransactions
  type NewBlock                   = BaseETH6XMessages.NewBlock
  type GetBlockHeaders            = ETH66.GetBlockHeaders
  type BlockHeaders               = ETH66.BlockHeaders
  type GetBlockBodies             = ETH66.GetBlockBodies
  type BlockBodies                = ETH66.BlockBodies
  type NewPooledTransactionHashes = ETH67.NewPooledTransactionHashes
  type GetPooledTransactions      = ETH66.GetPooledTransactions
  type PooledTransactions         = ETH66.PooledTransactions
  type GetReceipts                = ETH66.GetReceipts
  type Receipts                   = ETH66.Receipts
}
