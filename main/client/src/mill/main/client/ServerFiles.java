package mill.main.client;

/**
 * Central place containing all the files that live inside the `out/mill-worker-*` folder
 * and documentation about what they do
 */
public class ServerFiles {
    public static String sandbox(String base){
        return base + "/sandbox";
    }

    /**
     * Lock file used to ensure a single server is running in a particular
     * mill-worker folder.
     */
    public static String processLock(String base){
        return base + "/processLock";
    }

    public static String clientLock(String base){
        return base + "/clientLock";
    }

    public static String serverLock(String base){
        return base + "/serverLock";
    }


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
    public static String serverLog(String base){
        return base + "/server.log";
    }

    /**
     * File that the client writes to pass the arguments, environment variables,
     * and other necessary metadata to the Mill server to kick off a run
     */
    public static String runArgs(String base){
        return base + "/runArgs";
    }

    /**
     * File the server writes to pass the exit code of a completed run back to the
     * client
     */
    public static String exitCode(String base){
        return base + "/exitCode";
    }

    /**
     * Where the server's stdout is piped to
     */
    public static String stdout(String base){
        return base + "/stdout";
    }

    /**
     * Where the server's stderr is piped to
     */
    public static String stderr(String base){
        return base + "/stderr";
    }
}
