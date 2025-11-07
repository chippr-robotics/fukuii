package com.chipprbots.ethereum.utils

import ch.qos.logback.core.joran.action.Action
import ch.qos.logback.core.joran.spi.SaxEventInterpretationContext
import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import org.xml.sax.Attributes

/** Make properties defined in application.conf available to logback
  */
class LoadFromApplicationConfiguration extends Action {

  val config: Config = ConfigFactory.load
  
  override def begin(ic: SaxEventInterpretationContext, body: String, attributes: Attributes): Unit = {
    val key = attributes.getValue("key")
    val asName = attributes.getValue("as")
    
    try {
      val value = config.getString(key)
      ic.addSubstitutionProperty(asName, value)
    } catch {
      case _: ConfigException.Missing =>
        // Provide sensible defaults for known properties
        val defaultValue = key match {
          case "logging.logs-level" => "INFO"
          case "logging.json-output" => "false"
          case "logging.logs-dir" => "./logs"
          case "logging.logs-file" => "fukuii"
          case _ => ""
        }
        if (defaultValue.nonEmpty) {
          ic.addSubstitutionProperty(asName, defaultValue)
          addWarn(s"Configuration key '$key' not found, using default value: $defaultValue")
        } else {
          addError(s"Configuration key '$key' not found and no default value available")
        }
    }
  }
  
  override def end(ic: SaxEventInterpretationContext, body: String): Unit = ()
}
