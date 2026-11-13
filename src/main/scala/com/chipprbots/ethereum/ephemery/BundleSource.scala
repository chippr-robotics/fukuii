package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import java.util.regex.Pattern

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The Ephemery iteration built into the jar (spec 028 R4): `ephemery/bundled/ephemery-N/` holding the three files of
  * an entry. `scripts/ephemery/update-bundled.sh` regenerates it; nothing here names an iteration, so a bump is a
  * resource change only.
  */
trait BundleSource:

  /** The raw text of each bundled iteration, unverified: [[ReleaseEntry.verify]] decides whether it is trusted. */
  def entries(): List[BundleSource.EntryText]

object BundleSource:

  /** @param name
    *   the directory's name, `ephemery-N`
    */
  final case class EntryText(name: String, metadata: String, chainConf: String, genesisJson: String)

  /** The directory the bundle sits in, on the classpath. */
  val ResourceRoot: String = "ephemery/bundled"

  private val EntryName = Pattern.compile("ephemery-[0-9]+")

  /** A bundle of the given texts (for tests). */
  def of(texts: EntryText*): BundleSource = new BundleSource:
    override def entries(): List[EntryText] = texts.toList

  /** No bundle. */
  val none: BundleSource = of()

  /** The bundle in the jar (or the classes directory) this class was loaded from. */
  val classpath: BundleSource = new BundleSource:
    override def entries(): List[EntryText] =
      val loader = classOf[BundleSource].getClassLoader
      names().sorted.flatMap { name =>
        def resource(file: String): Option[String] =
          Option(loader.getResourceAsStream(s"$ResourceRoot/$name/$file")).map { in =>
            try new String(in.readAllBytes(), StandardCharsets.UTF_8)
            finally in.close()
          }
        for
          metadata <- resource(ReleaseEntry.MetadataFile)
          conf <- resource(EphemeryRelease.ChainConfFileName)
          genesis <- resource(EphemeryRelease.GenesisFileName)
        yield EntryText(name, metadata, conf, genesis)
      }

    /** The `ephemery-N` directories under the bundle root, read from where this class came from: a jar's entry list, or
      * a directory. A classpath resource cannot list its children.
      */
    private def names(): List[String] =
      Try {
        val location = Paths.get(classOf[BundleSource].getProtectionDomain.getCodeSource.getLocation.toURI)
        if Files.isDirectory(location) then
          val root = location.resolve(ResourceRoot)
          if Files.isDirectory(root) then
            val s = Files.list(root)
            try s.iterator().asScala.map(_.getFileName.toString).filter(EntryName.matcher(_).matches()).toList
            finally s.close()
          else Nil
        else
          val jar = new JarFile(location.toFile)
          try
            val prefix = ResourceRoot + "/"
            jar
              .entries()
              .asScala
              .map(_.getName)
              .filter(_.startsWith(prefix))
              .map(_.stripPrefix(prefix).takeWhile(_ != '/'))
              .filter(n => EntryName.matcher(n).matches())
              .toList
              .distinct
          finally jar.close()
      }.getOrElse(Nil)
