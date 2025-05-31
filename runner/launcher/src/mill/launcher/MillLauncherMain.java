package mill.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import mill.client.*;
import mill.client.lock.Locks;
import mill.constants.OutFiles;

/**
 * This is a Java implementation to speed up repetitive starts.
 * A Scala implementation would result in the JVM loading much more classes almost doubling the start-up times.
 */
public class MillLauncherMain {
  public static void main(String[] args) throws Exception {
    boolean runNoServer = false;
    if (args.length > 0) {
      String firstArg = args[0];
      runNoServer =
          Arrays.asList("--interactive", "--no-server", "--no-daemon", "--repl", "--bsp", "--help")
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
        java.util.List<String> optsArgs = new java.util.ArrayList<>();
        optsArgs.addAll(MillProcessLauncher.millOpts());
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
              public void initServer(Path daemonDir, Locks locks) throws Exception {
                MillProcessLauncher.launchMillServer(daemonDir);
              }

              public void preparedaemonDir(Path daemonDir) throws Exception {
                MillProcessLauncher.prepareMillRunFolder(daemonDir);
              }
            };

        Path daemonDir0 = Paths.get(OutFiles.out, OutFiles.millDaemon);
        String javaHome = MillProcessLauncher.javaHome();
        int exitCode = launcher.run(daemonDir0, javaHome).exitCode;
        if (exitCode == ClientUtil.ExitServerCodeWhenVersionMismatch()) {
          exitCode = launcher.run(daemonDir0, javaHome).exitCode;
        }
        System.exit(exitCode);
      } catch (Exception e) {
        System.err.println("Mill client failed with unknown exception");
        e.printStackTrace();
        System.exit(1);
      }
  }
}
