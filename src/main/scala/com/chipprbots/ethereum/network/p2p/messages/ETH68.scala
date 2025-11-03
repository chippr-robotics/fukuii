package com.chipprbots.ethereum.network.p2p.messages

/** ETH68 protocol - removes GetNodeData and NodeData messages See
  * https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth68
  *
  * The main change in ETH68 is the removal of GetNodeData (0x0d) and NodeData (0x0e) messages. These were used for
  * state synchronization but have been deprecated in favor of snap sync.
  *
  * ETH68 includes all messages from ETH67:
  *   - All messages from ETH66 (with request-id)
  *   - ETH67's enhanced NewPooledTransactionHashes (with types and sizes)
  *   - But excludes GetNodeData and NodeData
  *
  * All message implementations are inherited from ETH66 and ETH67, except for the removed messages.
  */
object ETH68 {
  // ETH68 uses all messages from ETH66 (with request-id) and ETH67 (enhanced NewPooledTransactionHashes)
  // The only difference is that GetNodeData and NodeData messages are not supported
  // This is enforced in the MessageDecoder for ETH68 by explicitly rejecting these message codes
}
