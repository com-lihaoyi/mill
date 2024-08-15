package mill.main.client;

import java.util.Arrays;
import java.util.function.BiConsumer;
/**
 * This is a Java implementation to speed up repetitive starts.
 * A Scala implementation would result in the JVM loading much more classes almost doubling the start-up times.
 */
public class MillClientMain {
    public static void main(String[] args) throws Exception {
        boolean runNoServer = false;
        if (args.length > 0) {
            String firstArg = args[0];
            runNoServer =
                Arrays.asList("--interactive", "--no-server", "--repl", "--bsp", "--help")
                    .contains(firstArg) || firstArg.startsWith("-i");
        }
        if (!runNoServer) {
            // WSL2 has the directory /run/WSL/ and WSL1 not.
            String osVersion = System.getProperty("os.version");
            if(osVersion != null && (osVersion.contains("icrosoft") || osVersion.contains("WSL"))) {
                // Server-Mode not supported under WSL1
                runNoServer = true;
            }
        }

        if (runNoServer) {
            // start in no-server mode
            MillNoServerLauncher.runMain(args);
        } else try {
            // start in client-server mode
            int exitCode = ServerLauncher.runMain(args, initServer);
            if (exitCode == Util.ExitServerCodeWhenVersionMismatch()) {
                exitCode = ServerLauncher.runMain(args, initServer);
            }
            System.exit(exitCode);
        } catch (MillServerCouldNotBeStarted e) {
            // TODO: try to run in-process
            System.err.println("Could not start a Mill server process.\n" +
                "This could be caused by too many already running Mill instances " +
                "or by an unsupported platform.\n" + e.getMessage() + "\n");
            if (MillNoServerLauncher.load().canLoad) {
                System.err.println("Trying to run Mill in-process ...");
                MillNoServerLauncher.runMain(args);
            } else {
                System.err.println("Loading Mill in-process isn't possible.\n" +
                    "Please check your Mill installation!");
                throw e;
            }
        }
    }

    private static BiConsumer<String, Boolean> initServer = (serverDir, setJnaNoSys) -> {
        try {
            MillProcessLauncher.launchMillServer(serverDir, setJnaNoSys);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    };
}
