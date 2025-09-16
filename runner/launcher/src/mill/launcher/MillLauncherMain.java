package mill.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;
import mill.client.*;
import mill.client.lock.Locks;
import mill.constants.BuildInfo;
import mill.constants.EnvVars;
import mill.constants.OutFiles;
import mill.constants.OutFolderMode;
import mill.internal.MillCliConfig;

/**
 * This is a Java implementation to speed up repetitive starts.
 * A Scala implementation would result in the JVM loading much more classes almost doubling the start-up times.
 */
public class MillLauncherMain {

  public static void main(String[] args) throws Exception {
    var needParsedConfig = Arrays.stream(args)
        .anyMatch(f -> f.startsWith("-") && !f.startsWith("--") && f.contains("i"));
    for (var token :
        Arrays.asList("--interactive", "--no-server", "--no-daemon", "--repl", "--bsp", "--help")) {
      if (Arrays.stream(args).anyMatch(f -> f.equals(token))) needParsedConfig = true;
    }

    var runNoDaemon = false;
    var bspMode = false;

    // Only use MillCliConfig and other Scala classes if we detect that a relevant flag
    // might have been passed, to avoid loading those classes on the common path for performance
    if (needParsedConfig) {
      var config = MillCliConfig.parse(args).toOption();
      if (config.exists(c -> c.bsp().value())) bspMode = true;
      if (config.exists(
          c -> c.interactive().value() || c.noServer().value() || c.noDaemon().value()))
        runNoDaemon = true;
    }

    // Ensure that if we're running in BSP mode we don't start a daemon.
    //
    // This is needed because when Metals/Idea closes, they only kill the BSP client and the BSP
    // server lurks around
    // waiting for the next client to connect.
    //
    // This is unintuitive from the user's perspective and wastes resources, as most people expect
    // everything related
    // to the BSP server to be killed when closing the editor.
    if (bspMode) runNoDaemon = true;

    var outMode = bspMode ? OutFolderMode.BSP : OutFolderMode.REGULAR;
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

    String[] runnerClasspath = MillProcessLauncher.cachedComputedValue0(
        outMode,
        "resolve-runner",
        BuildInfo.millVersion,
        () -> CoursierClient.resolveMillDaemon(),
        arr -> {
          for (String s : arr) {
            if (!Files.exists(Paths.get(s))) return false;
          }
          return true;
        });

    if (runNoDaemon) {
      // start in no-server mode
      System.exit(MillProcessLauncher.launchMillNoDaemon(args, outMode, runnerClasspath));
    } else {
      var logs = new java.util.ArrayList<String>();
      try {
        // start in client-server mode
        var optsArgs = new java.util.ArrayList<>(MillProcessLauncher.millOpts(outMode));
        Collections.addAll(optsArgs, args);
        var formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));
        Consumer<String> log = (s) -> logs.add(formatter.format(Instant.now()) + " " + s);
        MillServerLauncher launcher =
            new MillServerLauncher(
                new MillServerLauncher.Streams(System.in, System.out, System.err),
                System.getenv(),
                optsArgs.toArray(new String[0]),
                Optional.empty(),
                -1) {
              public LaunchedServer initServer(Path daemonDir, Locks locks) throws Exception {
                return new LaunchedServer.OsProcess(
                    MillProcessLauncher.launchMillDaemon(daemonDir, outMode, runnerClasspath)
                        .toHandle());
              }
            };

        var daemonDir0 = Paths.get(outDir, OutFiles.millDaemon);
        String javaHome = MillProcessLauncher.javaHome(outMode);

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

  private static void exitInTestsAfterBspCheck() {
    if (System.getenv("MILL_TEST_EXIT_AFTER_BSP_CHECK") != null) {
      System.exit(0);
    }
  }
}
