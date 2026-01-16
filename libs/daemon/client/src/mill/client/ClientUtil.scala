package mill.client

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Scanner

object ClientUtil {
  /**
   * Exit code indicating the server shut down and the client should retry.
   * This can happen due to version mismatch or when the server is terminated
   * while the client is waiting.
   */
  def ServerExitPleaseRetry: Int = 101

  /**
   * When using Graal Native Image, the launcher receives any `-D` properties
   * as system properties rather than command-line flags. Thus we try to identify
   * any system properties that are set by the user so we can propagate them to
   * the Mill daemon process appropriately
   */
  def getUserSetProperties(): Map[String, String] = {
    val bannedPrefixes = Set("path", "line", "native", "sun", "os", "java", "file", "jdk", "user")
    val props = System.getProperties
    val names = props.stringPropertyNames().iterator()
    val builder = Map.newBuilder[String, String]
    while (names.hasNext) {
      val key = names.next()
      val prefix = key.split("\\.")(0)
      if (!bannedPrefixes.contains(prefix)) {
        builder += key -> props.getProperty(key)
      }
    }
    builder.result()
  }

  /**
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  def readOptsFileLines(file: Path, env: java.util.Map[String, String]): java.util.List[String] = {
    val vmOptions = new java.util.LinkedList[String]()
    try {
      val sc = new Scanner(file.toFile, StandardCharsets.UTF_8)
      try {
        while (sc.hasNextLine) {
          val arg = sc.nextLine()
          val trimmed = arg.trim
          if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
            vmOptions.add(mill.constants.Util.interpolateEnvVars(arg, env))
          }
        }
      } finally {
        sc.close()
      }
    } catch {
      case _: FileNotFoundException => // ignored
    }
    vmOptions
  }
}
