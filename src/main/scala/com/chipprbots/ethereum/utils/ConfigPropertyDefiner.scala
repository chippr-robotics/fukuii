package com.chipprbots.ethereum.utils

import ch.qos.logback.core.PropertyDefinerBase
import com.typesafe.config.{Config, ConfigException, ConfigFactory}

/** PropertyDefiner for logback that loads values from TypeSafe Config.
  *
  * This class is compatible with logback 1.5.x and provides configuration properties from application.conf/base.conf to
  * logback.
  *
  * Usage in logback.xml:
  * {{{
  * <define name="LOGSLEVEL" class="com.chipprbots.ethereum.utils.ConfigPropertyDefiner">
  *   <key>logging.logs-level</key>
  *   <defaultValue>INFO</defaultValue>
  * </define>
  * }}}
  *
  * Properties loaded:
  *   - logging.logs-level (default: "INFO") → LOGSLEVEL
  *   - logging.json-output (default: "false") → ASJSON
  *   - logging.logs-dir (default: "./logs") → LOGSDIR
  *   - logging.logs-file (default: "fukuii") → LOGSFILENAME
  */
class ConfigPropertyDefiner extends PropertyDefinerBase {

  private var key: String = _
  private var defaultValue: String = _

  /** Set the configuration key to load (called by logback via reflection) */
  def setKey(key: String): Unit =
    this.key = key

  /** Set the default value to use if key is not found (called by logback via reflection) */
  def setDefaultValue(defaultValue: String): Unit =
    this.defaultValue = defaultValue

  /** Return the property value from config, or default if not found */
  override def getPropertyValue(): String =
    Option(key).filter(_.nonEmpty) match {
      case None =>
        addError("ConfigPropertyDefiner: 'key' property must be set")
        Option(defaultValue).getOrElse("")
      case Some(configKey) =>
        try {
          val value = ConfigPropertyDefiner.config.getString(configKey)
          addInfo(s"ConfigPropertyDefiner: Loaded '$configKey' = '$value'")
          value
        } catch {
          case _: ConfigException.Missing =>
            Option(defaultValue).filter(_.nonEmpty) match {
              case Some(default) =>
                addWarn(s"ConfigPropertyDefiner: Key '$configKey' not found, using default: $default")
                default
              case None =>
                addError(s"ConfigPropertyDefiner: Key '$configKey' not found and no default value provided")
                ""
            }
          case e: Exception =>
            addError(s"ConfigPropertyDefiner: Error loading key '$configKey': ${e.getMessage}")
            Option(defaultValue).getOrElse("")
        }
    }
}

object ConfigPropertyDefiner {

  /** Shared configuration instance to avoid reloading on each PropertyDefiner instantiation */
  private lazy val config: Config = ConfigFactory.load()
}
