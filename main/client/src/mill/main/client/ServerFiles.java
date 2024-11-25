package mill.main.client;

/**
 * Central place containing all the files that live inside the `out/mill-server-*` folder
 * and documentation about what they do
 */
public class ServerFiles {
  public static final String serverId = "serverId";
  public static final String sandbox = "sandbox";

  /**
   * Ensures only a single client is manipulating each mill-server folder at
   * a time, either spawning the server or submitting a command. Also used by
   * the server to detect when a client disconnects, so it can terminate execution
   */
  public static final String clientLock = "clientLock";

  /**
   * Lock file ensuring a single server is running in a particular mill-server
   * folder. If multiple servers are spawned in the same folder, only one takes
   * the lock and the others fail to do so and terminate immediately.
   */
  public static final String processLock = "processLock";

  /**
   * The port used to connect between server and client
   */
  public static final String socketPort = "socketPort";

  /**
   * The pipe by which the client snd server exchange IO
   *
   * Use uniquely-named pipes based on the fully qualified path of the project folder
   * because on Windows the un-qualified name of the pipe must be globally unique
   * across the whole filesystem
   */
  public static String pipe(String base) {
    try {
      return base + "/mill-"
          + Util.md5hex(new java.io.File(base).getCanonicalPath()).substring(0, 8) + "-io";
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Log file containing server housekeeping information
   */
  public static final String serverLog = "server.log";

  /**
   * File that the client writes to pass the arguments, environment variables,
   * and other necessary metadata to the Mill server to kick off a run
   */
  public static final String runArgs = "runArgs";

  /**
   * File the server writes to pass the exit code of a completed run back to the
   * client
   */
  public static final String exitCode = "exitCode";

  /**
   * Where the server's stdout is piped to
   */
  public static final String stdout = "stdout";

  /**
   * Where the server's stderr is piped to
   */
  public static final String stderr = "stderr";

  /**
   * Terminal information that we need to propagate from client to server
   */
  public static final String terminfo = "terminfo";
}
