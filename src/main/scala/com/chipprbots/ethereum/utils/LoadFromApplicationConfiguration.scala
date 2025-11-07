package com.chipprbots.ethereum.utils

import ch.qos.logback.core.joran.action.Action
import ch.qos.logback.core.joran.spi.SaxEventInterpretationContext
import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import org.xml.sax.Attributes

/** Make properties defined in application.conf available to logback.
  * 
  * This class is instantiated by logback during initialization to load configuration
  * properties from the application's TypeSafe Config (application.conf/base.conf) and
  * make them available as logback substitution variables (used in logback.xml).
  * 
  * Properties loaded (config key → logback variable name):
  * - logging.logs-level (default: "INFO") → ${LOGSLEVEL} in logback.xml
  * - logging.json-output (default: "false") → ${ASJSON} in logback.xml
  * - logging.logs-dir (default: "./logs") → ${LOGSDIR} in logback.xml
  * - logging.logs-file (default: "fukuii") → ${LOGSFILENAME} in logback.xml
  * 
  * If a property is missing from the configuration, a sensible default is used
  * and a warning is logged. Unknown configuration keys will cause an error.
  */
class LoadFromApplicationConfiguration extends Action {

  val config: Config = ConfigFactory.load
  
  override def begin(ic: SaxEventInterpretationContext, body: String, attributes: Attributes): Unit = {
    val key = Option(attributes.getValue("key")).getOrElse {
      addError("Missing 'key' attribute in load element")
      return
    }
    val asName = Option(attributes.getValue("as")).getOrElse {
      addError("Missing 'as' attribute in load element")
      return
    }
    
    try {
      val value = config.getString(key)
      ic.addSubstitutionProperty(asName, value)
    } catch {
      case _: ConfigException.Missing =>
        // Provide sensible defaults for known properties
        key match {
          case "logging.logs-level" =>
            ic.addSubstitutionProperty(asName, "INFO")
            addWarn(s"Configuration key '$key' not found, using default value: INFO")
          case "logging.json-output" =>
            ic.addSubstitutionProperty(asName, "false")
            addWarn(s"Configuration key '$key' not found, using default value: false")
          case "logging.logs-dir" =>
            ic.addSubstitutionProperty(asName, "./logs")
            addWarn(s"Configuration key '$key' not found, using default value: ./logs")
          case "logging.logs-file" =>
            ic.addSubstitutionProperty(asName, "fukuii")
            addWarn(s"Configuration key '$key' not found, using default value: fukuii")
          case _ =>
            addError(s"Configuration key '$key' not found and no default value available")
        }
    }
  }
  
  override def end(ic: SaxEventInterpretationContext, body: String): Unit = ()
}
