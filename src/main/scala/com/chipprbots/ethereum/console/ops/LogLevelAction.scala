package com.chipprbots.ethereum.console.ops

/** `loglevel <level> [logger]` and `loglevel reset` (spec 029 FR-054, contracts/actions.md): a logger's level, until
  * the node restarts. The level is set by the function `admin_changeLogLevel` runs (FR-052); what it was before is
  * read beside it, and a reset returns every logger changed since the node started to that.
  */
object LogLevelAction:

  /** A logger as the preview states it: the level set on it, if any, and the one it logs at. */
  final case class Level(name: String, configured: Option[String], effective: String)

  /** A logger changed since the node started. */
  final case class Changed(name: String, before: Option[String], now: String, created: Boolean)

  /** What the action needs of the node: each is the admin service's own function.
    *
    * @param level
    *   a logger's level; None for a name no logger has
    * @param near
    *   the loggers whose name starts with a prefix, to name the nearest to a mistyped one
    * @param change
    *   sets a logger's level, as `admin_changeLogLevel` does; a Left is why it could not
    * @param changed
    *   the loggers changed since the node started
    * @param reset
    *   returns each of them to its level before its first change, and says which
    */
  final case class Node(
      level: String => Option[Level],
      near: String => Seq[String],
      change: (String, String) => Either[String, Unit],
      changed: () => Seq[Changed],
      reset: () => Seq[Changed]
  )

  val Root: String = "ROOT"

  private def said(level: Option[String]): String = level.fold("none of its own (it inherits)")(identity)

  def handler(node: Node): ActionHandler =
    ActionHandler(
      request =>
        request.param("reset") match
          case Some(_) =>
            val changed = node.changed()
            if changed.isEmpty then
              Preview(Vector.empty, Some("no logger's level has been changed since the node started"))
            else
              Preview(
                changed.toVector.map(c => s"${c.name}: ${c.now} now, ${said(c.before)} after.") ++
                  Option.when(changed.exists(_.created))(
                    "A logger that a change made stays, and inherits its level again."
                  )
              )
          case None =>
            val name = request.param("logger").getOrElse(Root)
            val to = request.param("level").getOrElse("")
            node.level(name) match
              case None =>
                // A name no logger has is most likely mistyped: a logger made of it would log nothing.
                val prefix = name.split('.').dropRight(1).mkString(".")
                val nearest = node.near(prefix).filter(_ != Root).take(3)
                Preview(
                  Vector.empty,
                  Some(
                    s"there is no logger '$name'" +
                      (if nearest.isEmpty then "" else s"; the nearest are ${nearest.mkString(", ")}")
                  )
                )
              case Some(now) if now.configured.contains(to) =>
                Preview(Vector.empty, Some(s"${now.name} is already at $to"))
              case Some(now) =>
                Preview(
                  Vector(
                    s"${now.name}: logs at ${now.effective} now (set on it: ${said(now.configured)}), $to after.",
                    "Every logger under it that has no level of its own follows.",
                    "Until the node restarts, or loglevel reset."
                  ) ++ Option.when(now.name == Root && Set("OFF", "ERROR").contains(to))(
                    "The root logger feeds the dashboard's own Logs view: at this level it shows little or nothing."
                  )
                )
      ,
      (request, _) =>
        request.param("reset") match
          case Some(_) =>
            val restored = node.reset()
            Right(s"${restored.size} logger${if restored.size == 1 then "" else "s"} returned to the configured level")
          case None =>
            val name = request.param("logger").getOrElse(Root)
            val to = request.param("level").getOrElse("")
            node.level(name) match
              case None    => Left(s"there is no logger '$name'")
              case Some(_) => node.change(to, name).map(_ => s"$name logs at $to")
    )
