package com.chipprbots.ethereum.db.storage.pathstate

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C (C2a, beacon's review): the state anchor is written in one place.
  *
  * The clock that views are checked against takes the anchor from the datadir once, when the storage is built, and from
  * each move after that. A writer that set the anchor without making a move would leave the clock on a block the disk
  * no longer holds: views of that block would still look like views of what is on disk, and a row they read would be
  * called corrupt, which is the one verdict that can end in a delete. The compiler keeps the anchor's update inside
  * `db.storage`; this ratchet keeps it to the path state storage.
  *
  * A source ratchet, because there is no behaviour to assert: only a call that must not appear anywhere else.
  */
class AppStateAnchorRatchetSpec extends AnyFlatSpec with Matchers:

  private val mainSources: Path = Path.of("src/main/scala")
  private val definition = "AppStateStorage.scala"
  private val allowed = Set("PathStateStorage.scala")

  private def callers: Seq[String] =
    Using.resource(Files.walk(mainSources)) { walk =>
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .filter(p => Files.readString(p).contains("putStateAnchor("))
        .map(_.getFileName.toString)
        .toSeq
    }

  "The state anchor" should "be defined where this ratchet looks, so that it cannot pass on nothing" taggedAs
    UnitTest in {
      Files.isDirectory(mainSources) shouldBe true
      callers should contain(definition)
    }

  it should "be written by the path state storage and by nothing else" taggedAs UnitTest in {
    withClue(
      "The state anchor is written inside a move of the state's clock (StateMoveLog.inFlight), by PathStateStorage. " +
        "Files that write it elsewhere: "
    ) {
      callers.filterNot(file => file == definition || allowed.contains(file)) shouldBe empty
    }
  }
