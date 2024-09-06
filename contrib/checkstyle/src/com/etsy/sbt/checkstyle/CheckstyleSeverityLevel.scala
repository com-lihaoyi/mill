package com.etsy.sbt.checkstyle

/**
 * Enumeration of the different Checkstyle severity levels
 *
 * @author Andrew Johnson
 */
object CheckstyleSeverityLevel extends Enumeration {
  type CheckstyleSeverityLevel = Value
  val Ignore: Value = Value("ignore")
  val Info: Value = Value("info")
  val Warning: Value = Value("warning")
  val Error: Value = Value("error")
}
