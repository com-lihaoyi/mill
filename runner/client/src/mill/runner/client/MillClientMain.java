package mill.runner.client;

import static mill.runner.client.MillProcessLauncher.millOptsFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import mill.main.client.*;
import mill.main.client.lock.Locks;

/**
 * This is a Java implementation to speed up repetitive starts.
 * A Scala implementation would result in the JVM loading much more classes almost doubling the start-up times.
 */
public class MillClientMain {
  public static void main(String[] args) throws Exception {
    boolean runNoServer = false;
    if (args.length > 0) {
      String firstArg = args[0];
      runNoServer = Arrays.asList("--interactive", "--no-server", "--repl", "--bsp", "--help")
              .contains(firstArg)
          || firstArg.startsWith("-i");
    }
    if (!runNoServer) {
      // WSL2 has the directory /run/WSL/ and WSL1 not.
      String osVersion = System.getProperty("os.version");
      if (osVersion != null && (osVersion.contains("icrosoft") || osVersion.contains("WSL"))) {
        // Server-Mode not supported under WSL1
        runNoServer = true;
      }
    }

    if (runNoServer) {
      // start in no-server mode
      System.exit(MillProcessLauncher.launchMillNoServer(args));
    } else
      try {
        // start in client-server mode
        java.util.List<String> optsArgs = Util.readOptsFileLines(millOptsFile());
        Collections.addAll(optsArgs, args);

        ServerLauncher launcher =
            new ServerLauncher(
                System.in,
                System.out,
                System.err,
                System.getenv(),
                optsArgs.toArray(new String[0]),
                null,
                -1) {
              public void initServer(Path serverDir, boolean setJnaNoSys, Locks locks)
                  throws Exception {
                MillProcessLauncher.launchMillServer(serverDir, setJnaNoSys);
              }

              public void preRun(Path serverDir) throws Exception {
                MillProcessLauncher.prepareMillRunFolder(serverDir);
              }
            };

        final String versionAndJvmHomeEncoding =
            Util.sha1Hash(BuildInfo.millVersion + MillProcessLauncher.javaHome());
        Path serverDir0 = Paths.get(OutFiles.out, OutFiles.millServer, versionAndJvmHomeEncoding);
        int exitCode = launcher.acquireLocksAndRun(serverDir0).exitCode;
        if (exitCode == Util.ExitServerCodeWhenVersionMismatch()) {
          exitCode = launcher.acquireLocksAndRun(serverDir0).exitCode;
        }
        System.exit(exitCode);
      } catch (ServerCouldNotBeStarted e) {
        // TODO: try to run in-process
        System.err.println("Could not start a Mill server process.\n"
            + "This could be caused by too many already running Mill instances "
            + "or by an unsupported platform.\n"
            + e.getMessage() + "\n");

        System.err.println(
            "Loading Mill in-process isn't possible.\n" + "Please check your Mill installation!");
        throw e;

      } catch (Exception e) {
        System.err.println("Mill client failed with unknown exception");
        e.printStackTrace();
        System.exit(1);
      }
  }
}
