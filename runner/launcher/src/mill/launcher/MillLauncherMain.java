package mill.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;
import mill.client.*;
import mill.client.lock.Locks;
import mill.constants.EnvVars;
import mill.constants.OutFiles;
import mill.constants.OutFolderMode;
import mill.internal.MillCliConfig;

/**
 * This is a Java implementation to speed up repetitive starts.
 * A Scala implementation would result in the JVM loading much more classes almost doubling the start-up times.
 */
public class MillLauncherMain {
  static final String bspFlag = "--bsp";

  public static void main(String[] args) throws Exception {
    boolean runNoServer = false;
    if (args.length > 0) {
      String firstArg = args[0];
      runNoServer =
          Arrays.asList("--interactive", "--no-server", "--no-daemon", "--repl", bspFlag, "--help")
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

    var outMode = containsBspFlag(args) ? OutFolderMode.BSP : OutFolderMode.REGULAR;
    exitInTestsAfterBspCheck();
    var outDir = OutFiles.outFor(outMode);

    if (outMode == OutFolderMode.BSP) {
      System.err.println(
          OutFiles.mergeBspOut
              ? "Mill is running in BSP mode and '" + EnvVars.MILL_NO_SEPARATE_BSP_OUTPUT_DIR
                  + "' environment variable " + "is set, Mill will use the regular '"
                  + outDir + "' as the output directory. Unset this environment variable if you"
                  + " want to use a separate output directory for BSP. This will increase"
                  + " the CPU usage of the BSP server but make it more responsive."
              : "Mill is running in BSP mode, using a separate output directory '" + outDir
                  + "'. If you would like to reuse the regular `out/` directory, set the '"
                  + EnvVars.MILL_NO_SEPARATE_BSP_OUTPUT_DIR
                  + "' environment variable. This will reduce the CPU usage of the BSP server but"
                  + " make it less responsive.");
    }

    if (runNoServer) {
      // start in no-server mode
      System.exit(MillProcessLauncher.launchMillNoDaemon(args, outMode));
    } else {
      var logs = new java.util.ArrayList<String>();
      try {
        // start in client-server mode
        var optsArgs = new java.util.ArrayList<>(MillProcessLauncher.millOpts(outMode));
        Collections.addAll(optsArgs, args);

        MillServerLauncher launcher =
            new MillServerLauncher(
                new MillServerLauncher.Streams(System.in, System.out, System.err),
                System.getenv(),
                optsArgs.toArray(new String[0]),
                Optional.empty(),
                -1) {
              public LaunchedServer initServer(Path daemonDir, Locks locks) throws Exception {
                return new LaunchedServer.OsProcess(
                    MillProcessLauncher.launchMillDaemon(daemonDir, outMode).toHandle());
              }

              public void prepareDaemonDir(Path daemonDir) throws Exception {
                MillProcessLauncher.prepareMillRunFolder(daemonDir);
              }
            };

        var daemonDir0 = Paths.get(outDir, OutFiles.millDaemon);
        String javaHome = MillProcessLauncher.javaHome(outMode);
        Consumer<String> log = logs::add;
        var exitCode = launcher.run(daemonDir0, javaHome, log).exitCode;
        if (exitCode == ClientUtil.ExitServerCodeWhenVersionMismatch()) {
          exitCode = launcher.run(daemonDir0, javaHome, log).exitCode;
        }
        System.exit(exitCode);
      } catch (Exception e) {
        System.err.println("Mill client failed with unknown exception.");
        System.err.println();

        System.err.println("Exception:");
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        System.err.println();
        System.err.println("Logs:");
        logs.forEach(System.err::println);
        System.exit(1);
      }
    }
  }

  private static boolean containsBspFlag(String[] args) {
    // First do a simple check because it is faster, as it only uses java stdlib.
    var simpleCheck = Arrays.asList(args).contains(bspFlag);
    if (!simpleCheck) return false;

    // If the simple check passed, do a more expensive check which loads more classes, thus
    // introduces startup
    // overhead.
    //
    // This is done to prevent false positives in the simple check, for example when the user passes
    // "--bsp" as a flag to `run` task.
    return MillCliConfig.parse(args).toOption().exists(config -> config.bsp().value());
  }

  private static void exitInTestsAfterBspCheck() {
    if (System.getenv("MILL_TEST_EXIT_AFTER_BSP_CHECK") != null) {
      System.exit(0);
    }
  }
}
