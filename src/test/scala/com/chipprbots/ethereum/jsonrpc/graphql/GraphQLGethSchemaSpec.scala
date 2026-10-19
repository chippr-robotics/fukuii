package com.chipprbots.ethereum.jsonrpc.graphql

import scala.io.Source

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sangria.ast
import sangria.parser.QueryParser
import sangria.renderer.SchemaRenderer

import com.chipprbots.ethereum.testing.Tags.*

/** fukuii's GraphQL schema against go-ethereum's (`graphql/schema.go`, vendored verbatim as
  * `src/test/resources/graphql/geth-schema.graphql`): for every object and input type, the field names, their argument
  * lists and their types including nullability. EIP-1767 is the shared baseline; geth's schema is the one hive's
  * graphql simulator and client tooling are written against, typed-transaction fields included (#1629 S5).
  */
class GraphQLGethSchemaSpec extends AnyFlatSpec with Matchers:

  /** type name -> field name -> rendered signature, e.g. `from(block: Long): Account!`. */
  private type Shape = Map[String, Map[String, String]]

  private def render(t: ast.Type): String = t match
    case ast.NamedType(name, _)    => name
    case ast.NotNullType(inner, _) => render(inner) + "!"
    case ast.ListType(inner, _)    => "[" + render(inner) + "]"

  private def shape(sdl: String): Shape =
    val document = QueryParser.parse(sdl).get
    def args(as: Vector[ast.InputValueDefinition]): String =
      if as.isEmpty then "" else as.map(a => s"${a.name}: ${render(a.valueType)}").mkString("(", ", ", ")")
    document.definitions.collect {
      case o: ast.ObjectTypeDefinition =>
        o.name -> o.fields.map(f => f.name -> s"${args(f.arguments)}: ${render(f.fieldType)}").toMap
      case i: ast.InputObjectTypeDefinition =>
        i.name -> i.fields.map(f => f.name -> s": ${render(f.valueType)}").toMap
    }.toMap

  private lazy val geth: Shape =
    val source = Source.fromResource("graphql/geth-schema.graphql")
    try shape(source.mkString)
    finally source.close()

  private lazy val fukuii: Shape = shape(SchemaRenderer.renderSchema(GraphQLSchema.schema))

  /** Every field whose signature differs, as `Type.field: geth <sig> / fukuii <sig>` (`-` = absent). */
  private def differences(typeName: String): Seq[String] =
    val g = geth.getOrElse(typeName, Map.empty)
    val f = fukuii.getOrElse(typeName, Map.empty)
    (g.keySet ++ f.keySet).toSeq.sorted.flatMap { field =>
      val (gs, fs) = (g.get(field), f.get(field))
      Option.when(gs != fs)(s"$typeName.$field: geth ${gs.getOrElse("-")} / fukuii ${fs.getOrElse("-")}")
    }

  "the Transaction type" should "have exactly go-ethereum's fields, arguments and types" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    differences("Transaction") shouldBe empty
  }

  "the rest of the schema" should "differ from go-ethereum's only where recorded here" taggedAs (UnitTest, RPCTest) in {
    // Known, deliberate or not yet closed. A change in either direction fails this test, so the list stays current.
    val known = Seq(
      // geth dropped totalDifficulty after the merge; hive's graphql tests 14/16/51 still query it, and ETC is PoW.
      "Block.totalDifficulty: geth - / fukuii : BigInt!",
      // Amsterdam (EIP-7928 / EIP-7843) Block fields geth added in #35469; not yet served by fukuii's GraphQL.
      "Block.blockAccessListHash: geth : Bytes32 / fukuii -",
      "Block.slotNumber: geth : Long / fukuii -",
      // EIP-4895 amount in Gwei: geth types it Long, fukuii BigInt. Both render 0x-hex, so the JSON is the same.
      "Withdrawal.amount: geth : Long! / fukuii : BigInt!"
    )
    val all = (geth.keySet ++ fukuii.keySet).toSeq.sorted.filterNot(_ == "Transaction").flatMap(differences)
    all should contain theSameElementsAs known
  }
