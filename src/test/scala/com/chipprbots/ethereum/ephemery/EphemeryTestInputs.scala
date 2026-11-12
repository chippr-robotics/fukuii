package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import com.chipprbots.ethereum.ephemery.StrictJson.*

/** The committed raw inputs of the Ephemery releases the tests start from, and ways to change one field of them. */
object EphemeryTestInputs:

  /** `src/test/resources/ephemery/releases/ephemery-N/`: the five execution-layer members of the release. */
  def releaseDir(iteration: Int): Path =
    Paths.get(getClass.getResource(s"/ephemery/releases/ephemery-$iteration").toURI)

  /** The committed generated output `src/test/resources/ephemery/ephemery-N/<name>`. */
  def fixture(iteration: Int, name: String): String =
    new String(
      Files.readAllBytes(Paths.get(getClass.getResource(s"/ephemery/ephemery-$iteration/$name").toURI)),
      StandardCharsets.UTF_8
    )

  def raw(iteration: Int): ReleaseInputs =
    ReleaseInputs.fromDir(releaseDir(iteration)).fold(r => throw new AssertionError(r.message), identity)

  /** The published genesis hashes (`deposit_contract_block_hash.txt`), independent of this implementation. */
  val Hash164 = "0x39c91f8ef961470061e5f6eb1e2d8a05c79f630a41644cefb17397960c4868f0"
  val Hash165 = "0x9485bbe8e32e278194666d249590c9167057edeabaecc62e7a68355f68305ecc"

  def parsed(text: String): JObj =
    StrictJson.parse(text) match
      case Right(o: JObj) => o
      case other          => throw new AssertionError(s"not an object: $other")

  extension (o: JObj)
    def set(key: String, value: Js): JObj =
      o.fields.indexWhere(_._1 == key) match
        case -1 => JObj(o.fields :+ (key -> value))
        case i  => JObj(o.fields.updated(i, key -> value))
    def without(key: String): JObj = JObj(o.fields.filterNot(_._1 == key))
    def obj(key: String): JObj = o.get(key) match
      case Some(c: JObj) => c
      case other         => throw new AssertionError(s"$key is not an object: $other")
    def edit(key: String)(f: JObj => JObj): JObj = o.set(key, f(o.obj(key)))

  extension (in: ReleaseInputs)
    /** The inputs with the genesis.json object changed by `f`. */
    def editGenesis(f: JObj => JObj): ReleaseInputs =
      in.copy(genesisJson = render(f(parsed(in.genesisJson))))

    /** The inputs with `genesis.config` changed by `f`. */
    def editConfig(f: JObj => JObj): ReleaseInputs = in.editGenesis(_.edit("config")(f))
    def retention(key: String, value: String): ReleaseInputs =
      val line = s"""export $key="$value""""
      val kept = in.retentionVars.linesIterator.filterNot(_.startsWith(s"export $key=")).toList
      in.copy(retentionVars = (kept :+ line).mkString("", "\n", "\n"))
    def withoutRetention(key: String): ReleaseInputs =
      in.copy(retentionVars =
        in.retentionVars.linesIterator.filterNot(_.startsWith(s"export $key=")).mkString("", "\n", "\n")
      )

  def num(n: Long): JNum = JNum(n.toString)
