package mill.main.client

import java.io._
import java.math.BigInteger
import java.nio.charset.{Charset, StandardCharsets}
import java.security.{MessageDigest, NoSuchAlgorithmException}
import java.util.{Base64, HashMap, LinkedList, Map, Scanner}
import scala.util.matching.Regex
import java.lang.reflect.InvocationTargetException
import scala.util.Try
import scala.io.Source
import scala.compat.Platform

trait UtilCommon {

  def hasConsole(): Boolean = {
    val console = System.console()
    if (console != null) {
      try {
        val method = console.getClass.getMethod("isTerminal")
        method.invoke(console).asInstanceOf[Boolean]
      } catch {
        case _: InvocationTargetException | _: NoSuchMethodException | _: IllegalAccessException =>
          true
      }
    } else {
      false
    }
  }

  // use methods instead of constants to avoid inlining by compiler
  def ExitClientCodeCannotReadFromExitCodeFile(): Int = 1

  def ExitServerCodeWhenIdle(): Int = 0

  def ExitServerCodeWhenVersionMismatch(): Int = 101

  val isWindows: Boolean = System.getProperty("os.name").toLowerCase.startsWith("windows")
  val isJava9OrAbove: Boolean = !System.getProperty("java.specification.version").startsWith("1.")
  private val utf8: Charset = Charset.forName("UTF-8")

  def parseArgs(argStream: InputStream): Array[String] = {
    val argsLength = readInt(argStream)
    Array.fill(argsLength)(readString(argStream))
  }

  def writeArgs(args: Array[String], argStream: OutputStream): Unit = {
    writeInt(argStream, args.length)
    args.foreach(writeString(argStream, _))
  }

  /**
   * This allows the mill client to pass the environment as it sees it to the server (as the server remains alive over the course of several runs and does not see the environment changes the client would)
   */
  def writeMap(
      map: scala.collection.immutable.Map[String, String],
      argStream: OutputStream
  ): Unit = {
    writeInt(argStream, map.size)
    for ((key: String, value: String) <- map) {
      writeString(argStream, key)
      writeString(argStream, value)
    }

  }

  def parseMap(argStream: InputStream): scala.collection.immutable.Map[String, String] = {
    val mapLength = readInt(argStream)
    val theMap = scala.collection.mutable.Map[String, String]()
    (for (_ <- 0 until mapLength) yield {
      val key = readString(argStream)
      val value = readString(argStream)
      theMap.addOne(key -> value)
    })
    scala.collection.immutable.Map(theMap.toSeq*)
  }

  def readString(inputStream: InputStream): String = {
    val length = readInt(inputStream)
    val arr = new Array[Byte](length)
    var total = 0
    while (total < length) {
      val res = inputStream.read(arr, total, length - total)
      if (res == -1) throw new IOException("Incomplete String")
      total += res
    }
    new String(arr, utf8)
  }

  def writeString(outputStream: OutputStream, string: String): Unit = {
    val bytes = string.getBytes(utf8)
    writeInt(outputStream, bytes.length)
    outputStream.write(bytes)
  }

  def writeInt(out: OutputStream, i: Int): Unit = {
    out.write((i >>> 24).toByte)
    out.write((i >>> 16).toByte)
    out.write((i >>> 8).toByte)
    out.write(i.toByte)
  }

  def readInt(in: InputStream): Int = {
    (in.read() & 0xff) << 24 |
      (in.read() & 0xff) << 16 |
      (in.read() & 0xff) << 8 |
      (in.read() & 0xff)
  }

  protected def hexArray(arr: Array[Byte]): String = {
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))
  }

  /**
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return
   *   The non-empty lines of the files or an empty list, if the file does not exist
   */
  def readOptsFileLines(filePath: File): List[String] = {
    val vmOptions = scala.collection.mutable.ListBuffer[String]()
    // Attempt to read the file
    val env: scala.collection.immutable.Map[String, String] = sys.env
    Try {
      val source = Source.fromFile(filePath)
      try {
        for (line <- source.getLines()) {
          val trimmed = line.trim
          if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
            vmOptions += interpolateEnvVars(trimmed, env)
          }
        }
      } finally {
        source.close()
      }
    }.recover { case _: java.io.FileNotFoundException => // File not found, ignore
    }
    vmOptions.toList
  }

  def interpolateEnvVars(
      line: String,
      env: scala.collection.immutable.Map[String, String]
  ): String = {
    env.foldLeft(line) { case (acc, (key, value)) =>
      acc.replace(s"$$$key", value)
    }

  }

  private val envInterpolatorPattern: Regex = "\\$\\{(\\$|[A-Z_][A-Z0-9_]*)\\}".r
}
