package com.chipprbots.ethereum.console.ops

import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueType

/** The effective configuration, a row a key, with every secret hidden (spec 029 FR-054, FR-058): `key = value`, then
  * where the value came from (a file and its line, the command line, the environment). What is shown is the resolved
  * configuration the node runs on, not a file: a key set twice shows the one that won.
  *
  * Every value passes through [[Redaction]] before it is a row: this is one of the two places a secret could reach a
  * screen, and the only way out of here is through it.
  */
object ConfigView:

  /** The longest value shown: a list of bootstrap nodes is thousands of characters, and the file has it whole. */
  val MaxValueChars: Int = 160

  /** Where a value came from, as an operator can check it: the file's name and line, or what the library says. */
  private[ops] def origin(value: ConfigValue): String =
    val o = value.origin()
    val where = Option(o.filename())
      .orElse(Option(o.resource()))
      .map(path => path.drop(path.lastIndexOf('/') + 1))
      .getOrElse(Option(o.description()).getOrElse("unknown"))
    // The library's descriptions name every merged source: the first is the one that set the value.
    val short = where.split(",").headOption.getOrElse(where).replace("merge of ", "").trim
    if o.lineNumber() > 0 then s"$short:${o.lineNumber()}" else short

  private def rendered(value: ConfigValue): String =
    val text = value.valueType() match
      case ConfigValueType.STRING => String.valueOf(value.unwrapped())
      case ConfigValueType.NULL   => "null"
      case _                      => value.render(com.typesafe.config.ConfigRenderOptions.concise())
    if text.length > MaxValueChars then text.take(MaxValueChars) + "…" else text

  /** The rows, by key: `prefix.key = value   # origin`. A list or an object under a secret key is hidden whole. */
  def rows(config: Config, prefix: String = ""): Vector[String] =
    config
      .entrySet()
      .asScala
      .toVector
      .map { entry =>
        // The library quotes a key that is not a plain word: the quotes are not part of its name.
        val key = (if prefix.isEmpty then "" else s"$prefix.") + entry.getKey.replace("\"", "")
        s"$key = ${Redaction.show(key, rendered(entry.getValue))}   # ${origin(entry.getValue)}"
      }
      .sorted

  /** The handler of `config`: the node's configuration, read again each time it is asked for. */
  def handler(config: () => Config, prefix: String): ActionHandler =
    ActionHandler(
      _ => Preview(Vector("Shows the effective configuration; secrets are hidden.")),
      (_, _) => Right(rows(config(), prefix).mkString("\n"))
    )
