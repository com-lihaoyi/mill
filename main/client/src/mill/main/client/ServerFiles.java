package mill.main.client;

/**
 * Central place containing all the files that live inside the `out/mill-worker-*` folder
 * and documentation about what they do
 */
public class ServerFiles {
    final public static String sandbox = "sandbox";

    /**
     * Lock file used to ensure a single server is running in a particular
     * mill-worker folder.
     */
    final public static String processLock = "processLock";


    final public static String clientLock = "clientLock";


    final public static String serverLock = "serverLock";



    /**
     * The pipe by which the client snd server exchange IO
     *
     * Use uniquely-named pipes based on the fully qualified path of the project folder
     * because on Windows the un-qualified name of the pipe must be globally unique
     * across the whole filesystem
     */
    public static String pipe(String base) {
        try {
            return base + "/mill-" + Util.md5hex(new java.io.File(base).getCanonicalPath()) + "-io";
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
