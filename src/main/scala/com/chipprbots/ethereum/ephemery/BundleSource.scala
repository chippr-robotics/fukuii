package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** The Ephemery iteration built into the jar (spec 028 R4): `ephemery/bundled/ephemery-N/` holding the three files of
  * an entry, named by `ephemery/bundled/index.txt`. `scripts/ephemery/update-bundled.sh` regenerates both; nothing here
  * names an iteration, so a bump is a resource change only.
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

  /** The index resource: one bundled iteration name per line (`#` comments and blank lines ignored). A classpath
    * resource cannot list its children, in a jar or out of one, so the bundle names itself here;
    * `scripts/ephemery/update-bundled.sh` rewrites it, and `BundledEphemerySpec` checks it against the directory.
    */
  val IndexFile: String = s"$ResourceRoot/index.txt"

  /** The bundle on the classpath: the iterations `index.txt` lists, each read through `getResourceAsStream`. */
  val classpath: BundleSource = new BundleSource:
    override def entries(): List[EntryText] =
      val loader = classOf[BundleSource].getClassLoader
      def text(path: String): Option[String] =
        Option(loader.getResourceAsStream(path)).map { in =>
          try new String(in.readAllBytes(), StandardCharsets.UTF_8)
          finally in.close()
        }
      text(IndexFile).toList
        .flatMap(_.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")))
        .filter(n => EntryName.matcher(n).matches())
        .distinct
        .sorted
        .flatMap { name =>
          for
            metadata <- text(s"$ResourceRoot/$name/${ReleaseEntry.MetadataFile}")
            conf <- text(s"$ResourceRoot/$name/${EphemeryRelease.ChainConfFileName}")
            genesis <- text(s"$ResourceRoot/$name/${EphemeryRelease.GenesisFileName}")
          yield EntryText(name, metadata, conf, genesis)
        }
