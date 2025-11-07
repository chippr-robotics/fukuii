package com.chipprbots.ethereum.utils

import ch.qos.logback.core.PropertyDefinerBase
import com.typesafe.config.{Config, ConfigException, ConfigFactory}

/** PropertyDefiner for logback that loads values from TypeSafe Config.
  * 
  * This class is compatible with logback 1.5.x and provides configuration
  * properties from application.conf/base.conf to logback.
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
  * - logging.logs-level (default: "INFO") → LOGSLEVEL
  * - logging.json-output (default: "false") → ASJSON  
  * - logging.logs-dir (default: "./logs") → LOGSDIR
  * - logging.logs-file (default: "fukuii") → LOGSFILENAME
  */
class ConfigPropertyDefiner extends PropertyDefinerBase {

  private var key: String = _
  private var defaultValue: String = _
  
  private lazy val config: Config = ConfigFactory.load()
  
  /** Set the configuration key to load (called by logback via reflection) */
  def setKey(key: String): Unit = {
    this.key = key
  }
  
  /** Set the default value to use if key is not found (called by logback via reflection) */
  def setDefaultValue(defaultValue: String): Unit = {
    this.defaultValue = defaultValue
  }
  
  /** Return the property value from config, or default if not found */
  override def getPropertyValue(): String = {
    if (key == null || key.isEmpty) {
      addError("ConfigPropertyDefiner: 'key' property must be set")
      return if (defaultValue != null) defaultValue else ""
    }
    
    try {
      val value = config.getString(key)
      addInfo(s"ConfigPropertyDefiner: Loaded '$key' = '$value'")
      value
    } catch {
      case _: ConfigException.Missing =>
        val fallback = if (defaultValue != null && defaultValue.nonEmpty) {
          addWarn(s"ConfigPropertyDefiner: Key '$key' not found, using default: $defaultValue")
          defaultValue
        } else {
          addError(s"ConfigPropertyDefiner: Key '$key' not found and no default value provided")
          ""
        }
        fallback
      case e: Exception =>
        addError(s"ConfigPropertyDefiner: Error loading key '$key': ${e.getMessage}")
        if (defaultValue != null) defaultValue else ""
    }
  }
}
