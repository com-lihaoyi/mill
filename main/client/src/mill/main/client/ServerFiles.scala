package mill.main.client

import java.io.File

/**
 * Central place containing all the files that live inside the `out/mill-server-*` folder
 * and documentation about what they do
 */
object ServerFiles {
  val serverId: String = "serverId"
  val sandbox: String = "sandbox"

  /**
   * Ensures only a single client is manipulating each mill-server folder at
   * a time, either spawning the server or submitting a command. Also used by
   * the server to detect when a client disconnects, so it can terminate execution.
   */
  val clientLock: String = "clientLock"

  /**
   * Lock file ensuring a single server is running in a particular mill-server
   * folder. If multiple servers are spawned in the same folder, only one takes
   * the lock and the others fail to do so and terminate immediately.
   */
  val processLock: String = "processLock"

  /**
   * The port used to connect between server and client.
   */
  val socketPort: String = "socketPort"

  /**
   * The pipe by which the client and server exchange IO.
   *
   * Use uniquely named pipes based on the fully qualified path of the project folder
   * because on Windows the unqualified name of the pipe must be globally unique
   * across the whole filesystem.
   */
  def pipe(base: String): String = {
    try {
      val canonicalPath = new File(base).getCanonicalPath
      val uniquePart = Util.md5hex(canonicalPath).take(8)
      s"$base/mill-$uniquePart-io"
    } catch {
      case e: Exception => throw new RuntimeException(e)
    }
  }

  /**
   * Log file containing server housekeeping information.
   */
  val serverLog: String = "server.log"

  /**
   * File that the client writes to pass the arguments, environment variables,
   * and other necessary metadata to the Mill server to kick off a run.
   */
  val runArgs: String = "runArgs"

  /**
   * File the server writes to pass the exit code of a completed run back to the client.
   */
  val exitCode: String = "exitCode"

  /**
   * Where the server's stdout is piped to.
   */
  val stdout: String = "stdout"

  /**
   * Where the server's stderr is piped to.
   */
  val stderr: String = "stderr"

  /**
   * Terminal information that we need to propagate from client to server.
   */
  val terminfo: String = "terminfo"
}
