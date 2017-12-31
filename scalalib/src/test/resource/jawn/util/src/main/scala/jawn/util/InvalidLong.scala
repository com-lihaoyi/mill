package jawn.util

class InvalidLong(s: String) extends NumberFormatException(s"For input string '$s'")

object InvalidLong {
  def apply(s: String): InvalidLong = new InvalidLong(s)
}
