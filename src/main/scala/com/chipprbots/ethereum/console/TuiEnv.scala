package com.chipprbots.ethereum.console

import java.util.Locale

/** The TUI's environment overrides (spec 027 FR-008, FR-009, FR-024; research §7.5).
  *
  * Parsed from an injected environment map and system properties, so tests never touch the process environment. An
  * invalid value is not an error: it becomes a warning naming the variable, and the configured value applies.
  *
  * @param noColor
  *   `NO_COLOR` is set to a non-empty value (no-color.org; an empty value counts as unset): draw with the monochrome
  *   theme
  * @param glyphs
  *   a valid `FUKUII_TUI_GLYPHS`, overriding `fukuii.tui.glyphs`
  * @param utf8Locale
  *   the locale's character encoding is UTF-8; `auto` draws ASCII when it is not (Q3)
  * @param reducedMotion
  *   a valid `FUKUII_TUI_MOTION`: `reduced` (true) or `full` (false), overriding `fukuii.tui.reduced-motion`
  * @param noSplash
  *   `FUKUII_TUI_NO_SPLASH` is set to a non-empty value: no startup splash
  * @param warnings
  *   one per invalid variable, for the caller to log
  */
final case class TuiEnv(
    noColor: Boolean,
    glyphs: Option[GlyphChoice],
    utf8Locale: Boolean,
    warnings: List[String],
    reducedMotion: Option[Boolean] = None,
    noSplash: Boolean = false,
    readOnly: Boolean = false
):
  /** The configuration the TUI runs with: `settings` (`fukuii.tui`) with these overrides applied, and `auto` resolved
    * against the locale into a [[GlyphSet]] (B-124). Every other field keeps [[TuiConfig.default]]'s value.
    */
  def applyTo(settings: TuiSettings): TuiConfig =
    val glyphSet = glyphs.getOrElse(settings.glyphs) match
      case GlyphChoice.Emoji => GlyphSet.Emoji
      case GlyphChoice.Ascii => GlyphSet.Ascii
      case GlyphChoice.Auto  => if utf8Locale then GlyphSet.Emoji else GlyphSet.Ascii
    TuiConfig.default.copy(
      glyphs = glyphSet,
      noColor = noColor,
      reducedMotion = reducedMotion.getOrElse(settings.reducedMotion),
      animationIntervalMs = settings.animationIntervalMs,
      statusPollIntervalMs = settings.statusPollIntervalMs,
      splashMs = if noSplash then 0L else settings.splashMs,
      logRingSize = settings.logRingSize,
      logLevel = settings.logLevel,
      readOnly = readOnly || settings.readOnly
    )

object TuiEnv:
  val NoColorVar: String = "NO_COLOR"
  val GlyphsVar: String = "FUKUII_TUI_GLYPHS"
  val MotionVar: String = "FUKUII_TUI_MOTION"
  val NoSplashVar: String = "FUKUII_TUI_NO_SPLASH"

  /** Set to a non-empty value: no operator action can run (spec 029 FR-053). It can only add the restriction:
    * `fukuii.tui.read-only = true` is not undone by leaving the variable unset.
    */
  val ReadOnlyVar: String = "FUKUII_TUI_READ_ONLY"

  /** The accepted `FUKUII_TUI_MOTION` values. */
  val MotionAccepted: String = "full, reduced"

  /** Where the locale's encoding is read, first set wins (POSIX precedence). */
  val LocaleVars: Seq[String] = Seq("LC_ALL", "LC_CTYPE", "LANG")

  /** The JVM's view of the locale encoding, when no locale variable is set. */
  val EncodingProperty: String = "sun.jnu.encoding"

  def from(env: Map[String, String], props: Map[String, String]): TuiEnv =
    val glyphsValue = env.get(GlyphsVar).map(_.trim).filter(_.nonEmpty)
    val glyphs = glyphsValue.flatMap(GlyphChoice.parse)
    val glyphWarnings = glyphsValue.filter(_ => glyphs.isEmpty).toList.map { v =>
      s"Ignoring $GlyphsVar='$v': expected one of ${GlyphChoice.Accepted}; fukuii.tui.glyphs applies (default auto)"
    }
    val motionValue = env.get(MotionVar).map(_.trim).filter(_.nonEmpty)
    val reducedMotion = motionValue.flatMap(v =>
      v.toLowerCase(Locale.ROOT) match
        case "reduced" => Some(true)
        case "full"    => Some(false)
        case _         => None
    )
    val motionWarnings = motionValue.filter(_ => reducedMotion.isEmpty).toList.map { v =>
      s"Ignoring $MotionVar='$v': expected one of $MotionAccepted; fukuii.tui.reduced-motion applies (default false)"
    }
    TuiEnv(
      noColor = env.get(NoColorVar).exists(_.nonEmpty),
      glyphs = glyphs,
      utf8Locale = localeIsUtf8(env, props),
      warnings = glyphWarnings ++ motionWarnings,
      reducedMotion = reducedMotion,
      noSplash = env.get(NoSplashVar).exists(_.nonEmpty),
      readOnly = env.get(ReadOnlyVar).exists(_.nonEmpty)
    )

  /** The running process's environment and system properties. */
  def system(): TuiEnv = from(sys.env, sys.props.toMap)

  /** Whether the first set locale variable, or else the JVM's `sun.jnu.encoding`, names UTF-8. With neither, the
    * encoding is unknown and taken as not UTF-8: ASCII is the safe choice.
    */
  def localeIsUtf8(env: Map[String, String], props: Map[String, String]): Boolean =
    LocaleVars.iterator
      .flatMap(env.get)
      .find(_.nonEmpty)
      .orElse(props.get(EncodingProperty).filter(_.nonEmpty))
      .exists(namesUtf8)

  private def namesUtf8(value: String): Boolean = value.toUpperCase(Locale.ROOT).replace("-", "").contains("UTF8")
