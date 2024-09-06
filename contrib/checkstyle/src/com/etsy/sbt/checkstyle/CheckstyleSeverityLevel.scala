package com.etsy.sbt.checkstyle

/**
 * Enumeration of the different Checkstyle severity levels
 *
 * @author Andrew Johnson
 */
object CheckstyleSeverityLevel extends Enumeration {
  type CheckstyleSeverityLevel = Value
  val Ignore = Value("ignore")
  val Info = Value("info")
  val Warning = Value("warning")
  val Error = Value("error")
}
