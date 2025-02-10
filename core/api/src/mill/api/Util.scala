package mill.api

import mill.api.Loose.Agg
import mill.api.{BuildInfo, PathRef, Result}

import java.nio.file.{Files, Paths}

object Util {

  def isInteractive(): Boolean = mill.client.Util.hasConsole()

  val newLine: String = System.lineSeparator()

  val windowsPlatform: Boolean = System.getProperty("os.name").startsWith("Windows")

  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }
}
