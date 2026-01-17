package mill.client

object ClientUtil {

  /**
   * Exit code indicating the server shut down and the client should retry.
   * This can happen due to version mismatch or when the server is terminated
   * while the client is waiting.
   */
  val ServerExitPleaseRetry: Int = 101

  /**
   * When using Graal Native Image, the launcher receives any `-D` properties
   * as system properties rather than command-line flags. Thus we try to identify
   * any system properties that are set by the user so we can propagate them to
   * the Mill daemon process appropriately
   */
  def getUserSetProperties(): Map[String, String] = {
    import scala.jdk.CollectionConverters.*
    val bannedPrefixes = Set("path", "line", "native", "sun", "os", "java", "file", "jdk", "user")
    val props = System.getProperties
    props.stringPropertyNames().asScala.iterator
      .filterNot(key => bannedPrefixes.contains(key.split("\\.")(0)))
      .map(key => key -> props.getProperty(key))
      .toMap
  }

  /**
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  def readOptsFileLines(file: os.Path, env: Map[String, String]): Seq[String] = {
    import scala.jdk.CollectionConverters.*
    if (!os.exists(file)) Seq.empty
    else {
      os.read.lines(file)
        .filter(line => line.trim.nonEmpty && !line.trim.startsWith("#"))
        .map(line => mill.constants.Util.interpolateEnvVars(line, env.asJava))
        .toSeq
    }
  }
}
