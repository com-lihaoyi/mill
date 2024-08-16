package mill.main.client;

/**
 * Central place containing all the files that live inside the `out/mill-worker-*` folder
 * and documentation about what they do
 */
public class ServerFiles {
    final public static String serverId = "serverId";
    final public static String sandbox = "sandbox";

    /**
     * Ensures only a single client is manipulating each mill-worker folder at
     * a time, either spawning the server or submitting a command. Also used by
     * the server to detect when a client disconnects, so it can terminate execution
     */
    final public static String clientLock = "clientLock";

    /**
     * Lock file ensuring a single server is running in a particular mill-worker
     * folder. If multiple servers are spawned in the same folder, only one takes
     * the lock and the others fail to do so and terminate immediately.
     */
    final public static String processLock = "processLock";

    /**
     * The pipe by which the client snd server exchange IO
     *
     * Use uniquely-named pipes based on the fully qualified path of the project folder
     * because on Windows the un-qualified name of the pipe must be globally unique
     * across the whole filesystem
     */
    public static String pipe(String base) {
        try {
            return base + "/mill-" + Util.md5hex(new java.io.File(base).getCanonicalPath()).substring(0, 8) + "-io";
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Log file containing server housekeeping information
     */
    final public static String serverLog = "server.log";


    /**
     * File that the client writes to pass the arguments, environment variables,
     * and other necessary metadata to the Mill server to kick off a run
     */
    final public static String runArgs = "runArgs";


    /**
     * File the server writes to pass the exit code of a completed run back to the
     * client
     */
    final public static String exitCode = "exitCode";


    /**
     * Where the server's stdout is piped to
     */
    final public static String stdout = "stdout";


    /**
     * Where the server's stderr is piped to
     */
    final public static String stderr = "stderr";
}
