package com.chipprbots.ethereum.jsonrpc.graphql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import sangria.renderer.SchemaRenderer

class GraphQLSchemaSpec extends AnyFlatSpec with Matchers {

  "GraphQLSchema" should "build a valid Sangria Schema" in {
    // Construction alone exercises schema validation — Sangria throws on bad shapes.
    GraphQLSchema.schema shouldNot be(null)
  }

  it should "expose the canonical EIP-1767 top-level query fields" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)

    // Query root fields
    sdl should include("chainID: BigInt!")
    sdl should include("gasPrice: BigInt!")
    sdl should include("maxPriorityFeePerGas: BigInt!")
    sdl should include("syncing: SyncState")
    sdl should include("pending: Pending!")
    sdl should include("transaction(hash: Bytes32!): Transaction")
    sdl should include("block(number: Long, hash: Bytes32): Block")
    sdl should include("logs(filter: FilterCriteria!): [Log!]!")
  }

  it should "expose the canonical mutation" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)
    sdl should include("sendRawTransaction(data: Bytes!): Bytes32!")
  }

  it should "define all required object types" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)
    sdl should include("type Block")
    sdl should include("type Transaction")
    sdl should include("type Account")
    sdl should include("type Log")
    sdl should include("type CallResult")
    sdl should include("type SyncState")
    sdl should include("type Pending")
    sdl should include("type AccessTuple")
    sdl should include("type Withdrawal")
  }

  it should "define all required input types" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)
    sdl should include("input CallData")
    sdl should include("input FilterCriteria")
    sdl should include("input BlockFilterCriteria")
  }

  it should "reference the custom scalars from field types" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)
    sdl should include("Bytes32")
    sdl should include("Address")
    sdl should include("Bytes")
    sdl should include("BigInt")
    sdl should include("Long")
  }

  it should "expose block.withdrawals, blobGasUsed, excessBlobGas for post-Cancun queries" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)
    sdl should include("withdrawalsRoot: Bytes32")
    sdl should include("withdrawals: [Withdrawal!]")
    sdl should include("blobGasUsed: Long")
    sdl should include("excessBlobGas: Long")
  }

  it should "expose transaction.blobVersionedHashes for post-Cancun queries" in {
    val sdl = SchemaRenderer.renderSchema(GraphQLSchema.schema)
    sdl should include("blobVersionedHashes: [Bytes32!]")
  }
}
