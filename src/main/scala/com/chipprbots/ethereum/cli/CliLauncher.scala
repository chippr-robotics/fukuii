package com.chipprbots.ethereum.cli

import scala.collection.immutable.ArraySeq

import com.monovore.decline._

//scalastyle:off
object CliLauncher {

  def main(args: Array[String]): Unit = {
    val arguments: Seq[String] = if (args == null) Seq.empty else ArraySeq.unsafeWrapArray(args)
    CliCommands.api.map(println).parse(arguments, sys.env) match {
      case Left(help) => System.err.println(help)
      case Right(_)   => ()
    }
  }

}
