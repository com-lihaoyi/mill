package mill.main.client

import java.io._
import java.math.BigInteger
import java.nio.charset.{Charset, StandardCharsets}
import java.security.{MessageDigest, NoSuchAlgorithmException}
import java.util.{Base64, HashMap, LinkedList, Map, Scanner}
import scala.util.matching.Regex
import pt.kcry.sha._

object Util {
  val sha1 = new Sha1()
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
   * This allows the mill client to pass the environment as it sees it to the
   * server (as the server remains alive over the course of several runs and
   * does not see the environment changes the client would)
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
    scala.collection.immutable.Map(theMap.toSeq: _*)
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

  /**
   * @return Hex encoded MD5 hash of input string.
   */
  def md5hex(str: String): String = {
    Sha1.hash(str.getBytes).mkString
    // hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)))
  }

  private def hexArray(arr: Array[Byte]): String = {
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))
  }

  def sha1Hash(path: String): String = {
    val sha1 = new Sha1()
    val hashed = Array.ofDim[Byte](20)
    sha1.update(path.getBytes(StandardCharsets.UTF_8), 0, path.length)
    sha1.finish(hashed, 0)
    Base64.getEncoder.encodeToString(hashed)
  }

  /**
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  def readOptsFileLines(file: File): List[String] = {

    val vmOptions = scala.collection.mutable.ListBuffer[String]()
    println("VM OPTIONS ARE BEING IGNOREDå")
    // try {
    //   val sc = new Scanner(file)
    //   val env = sys.env
    //   while (sc.hasNextLine) {
    //     val arg = sc.nextLine()
    //     val trimmed = arg.trim
    //     if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
    //       vmOptions += interpolateEnvVars(arg, env)
    //     }
    //   }
    // } catch {
    //   case _: FileNotFoundException => // ignored
    // }
    vmOptions.toList
  }

  /**
   * Interpolate variables in the form of <code>${VARIABLE}</code> based on the given Map <code>env</code>.
   * Missing vars will be replaced by the empty string.
   */

  def interpolateEnvVars(
      input: String,
      env: scala.collection.immutable.Map[String, String]
  ): String = {
    val envInterpolatorPattern: Regex = """\$\{(\$|[A-Z_][A-Z0-9_]*)\}""".r

    // Replace the matched variables with values from the env map
    envInterpolatorPattern.replaceAllIn(
      input,
      m => {
        val matchStr = m.group(1)
        if (matchStr == "$") {
          "$" // If it's the "$" symbol itself, keep it
        } else {
          env.getOrElse(matchStr, "") // Replace with env value or empty string
        }
      }
    )
  }

  private val envInterpolatorPattern: Regex = "\\$\\{(\\$|[A-Z_][A-Z0-9_]*)\\}".r
}
