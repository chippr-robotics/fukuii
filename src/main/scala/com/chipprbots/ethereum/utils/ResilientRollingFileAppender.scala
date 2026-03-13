package com.chipprbots.ethereum.utils

import ch.qos.logback.core.rolling.RollingFileAppender
import java.io.File

/** RollingFileAppender that recreates the log file if deleted while running.
  *
  * Standard RollingFileAppender holds an open file descriptor and writes to a dangling inode if the file is deleted —
  * logs are silently lost. This subclass checks file existence periodically and reopens if needed.
  */
class ResilientRollingFileAppender[E] extends RollingFileAppender[E] {
  private var checkCounter: Int = 0
  private val CheckInterval: Int = 100 // Check every 100 log events

  override def subAppend(event: E): Unit = {
    checkCounter += 1
    if (checkCounter >= CheckInterval) {
      checkCounter = 0
      val f = new File(getFile)
      if (!f.exists()) {
        // File was deleted — reopen to recreate it
        openFile(getFile)
      }
    }
    super.subAppend(event)
  }
}
