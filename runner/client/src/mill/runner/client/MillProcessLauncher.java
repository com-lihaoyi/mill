package mill.runner.client;

import static mill.main.client.OutFiles.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import mill.main.client.EnvVars;
import mill.main.client.ServerFiles;
import mill.main.client.Util;

public class MillProcessLauncher {

  static int launchMillNoServer(String[] args) throws Exception {
    final boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
    final String sig = String.format("%08x", UUID.randomUUID().hashCode());
    final Path processDir = Paths.get(".").resolve(out).resolve(millNoServer).resolve(sig);

    final List<String> l = new ArrayList<>();
    l.addAll(millLaunchJvmCommand(setJnaNoSys));
    l.add("mill.runner.MillMain");
    l.add(processDir.toAbsolutePath().toString());
    l.addAll(Util.readOptsFileLines(millOptsFile()));
    l.addAll(Arrays.asList(args));

    final ProcessBuilder builder = new ProcessBuilder().command(l).inheritIO();

    boolean interrupted = false;

    try {
      Process p = configureRunMillProcess(builder, processDir);
      MillProcessLauncher.runTermInfoThread(processDir);
      return p.waitFor();

    } catch (InterruptedException e) {
      interrupted = true;
      throw e;
    } finally {
      if (!interrupted) {
        // cleanup if process terminated for sure
        Files.walk(processDir)
            // depth-first
            .sorted(Comparator.reverseOrder())
            .forEach(p -> p.toFile().delete());
      }
    }
  }

  static void launchMillServer(Path serverDir, boolean setJnaNoSys) throws Exception {
    List<String> l = new ArrayList<>();
    l.addAll(millLaunchJvmCommand(setJnaNoSys));
    l.add("mill.runner.MillServerMain");
    l.add(serverDir.toFile().getCanonicalPath());

    ProcessBuilder builder = new ProcessBuilder()
        .command(l)
        .redirectOutput(serverDir.resolve(ServerFiles.stdout).toFile())
        .redirectError(serverDir.resolve(ServerFiles.stderr).toFile());

    configureRunMillProcess(builder, serverDir);
  }

  static Process configureRunMillProcess(ProcessBuilder builder, Path serverDir) throws Exception {

    Path sandbox = serverDir.resolve(ServerFiles.sandbox);
    Files.createDirectories(sandbox);
    builder.environment().put(EnvVars.MILL_WORKSPACE_ROOT, new File("").getCanonicalPath());

    builder.directory(sandbox.toFile());
    return builder.start();
  }

  static File millJvmOptsFile() {
    String millJvmOptsPath = System.getenv(EnvVars.MILL_JVM_OPTS_PATH);
    if (millJvmOptsPath == null || millJvmOptsPath.trim().equals("")) {
      millJvmOptsPath = ".mill-jvm-opts";
    }
    return new File(millJvmOptsPath).getAbsoluteFile();
  }

  static File millOptsFile() {
    String millJvmOptsPath = System.getenv(EnvVars.MILL_OPTS_PATH);
    if (millJvmOptsPath == null || millJvmOptsPath.trim().equals("")) {
      millJvmOptsPath = ".mill-opts";
    }
    return new File(millJvmOptsPath).getAbsoluteFile();
  }

  static boolean millJvmOptsAlreadyApplied() {
    final String propAppliedProp = System.getProperty("mill.jvm_opts_applied");
    return propAppliedProp != null && propAppliedProp.equals("true");
  }

  static String millServerTimeout() {
    return System.getenv(EnvVars.MILL_SERVER_TIMEOUT_MILLIS);
  }

  static boolean isWin() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  static String javaExe() {
    final String javaHome = System.getProperty("java.home");
    if (javaHome != null && !javaHome.isEmpty()) {
      final File exePath = new File(
          javaHome + File.separator + "bin" + File.separator + "java" + (isWin() ? ".exe" : ""));
      if (exePath.exists()) {
        return exePath.getAbsolutePath();
      }
    }
    return "java";
  }

  static String[] millClasspath() throws Exception {
    String selfJars = "";
    List<String> vmOptions = new LinkedList<>();
    String millOptionsPath = System.getProperty("MILL_OPTIONS_PATH");
    if (millOptionsPath != null) {

      // read MILL_CLASSPATH from file MILL_OPTIONS_PATH
      Properties millProps = new Properties();
      try (InputStream is = Files.newInputStream(Paths.get(millOptionsPath))) {
        millProps.load(is);
      } catch (IOException e) {
        throw new RuntimeException("Could not load '" + millOptionsPath + "'", e);
      }

      for (final String k : millProps.stringPropertyNames()) {
        String propValue = millProps.getProperty(k);
        if ("MILL_CLASSPATH".equals(k)) {
          selfJars = propValue;
        }
      }
    } else {
      // read MILL_CLASSPATH from file sys props
      selfJars = System.getProperty("MILL_CLASSPATH");
    }

    if (selfJars == null || selfJars.trim().isEmpty()) {
      // We try to use the currently local classpath as MILL_CLASSPATH
      selfJars = System.getProperty("java.class.path").replace(File.pathSeparator, ",");
    }

    if (selfJars == null || selfJars.trim().isEmpty()) {
      throw new RuntimeException("MILL_CLASSPATH is empty!");
    }
    String[] selfJarsArray = selfJars.split("[,]");
    for (int i = 0; i < selfJarsArray.length; i++) {
      selfJarsArray[i] = new java.io.File(selfJarsArray[i]).getCanonicalPath();
    }
    return selfJarsArray;
  }

  static List<String> millLaunchJvmCommand(boolean setJnaNoSys) throws Exception {
    final List<String> vmOptions = new ArrayList<>();

    // Java executable
    vmOptions.add(javaExe());

    // jna
    if (setJnaNoSys) {
      vmOptions.add("-Djna.nosys=true");
    }

    // sys props
    final Properties sysProps = System.getProperties();
    for (final String k : sysProps.stringPropertyNames()) {
      if (k.startsWith("MILL_") && !"MILL_CLASSPATH".equals(k)) {
        vmOptions.add("-D" + k + "=" + sysProps.getProperty(k));
      }
    }

    String serverTimeout = millServerTimeout();
    if (serverTimeout != null) vmOptions.add("-D" + "mill.server_timeout" + "=" + serverTimeout);

    // extra opts
    File millJvmOptsFile = millJvmOptsFile();
    if (millJvmOptsFile.exists()) {
      vmOptions.addAll(Util.readOptsFileLines(millJvmOptsFile));
    }

    vmOptions.add("-cp");
    vmOptions.add(String.join(File.pathSeparator, millClasspath()));

    return vmOptions;
  }

  static List<String> readMillJvmOpts() {
    return Util.readOptsFileLines(millJvmOptsFile());
  }

  static int getTerminalDim(String s) throws Exception {
    Process proc = new ProcessBuilder()
        .command("tput", s)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start();
    proc.waitFor();
    return Integer.parseInt(new String(proc.getInputStream().readAllBytes()).trim());
  }

  static void writeTerminalDims(Path serverDir) throws Exception {
    try {
      Files.write(
          serverDir.resolve(ServerFiles.terminfo),
          (getTerminalDim("cols") + " " + getTerminalDim("lines")).getBytes());
    } catch (Exception e) {
      Files.write(serverDir.resolve(ServerFiles.terminfo), "".getBytes());
    }
  }

  public static void runTermInfoThread(Path serverDir) throws Exception {

    Thread termInfoPropagatorThread = new Thread(
        () -> {
          try {
            while (true) {
              writeTerminalDims(serverDir);
              Thread.sleep(100);
            }
          } catch (Exception e) {
          }
        },
        "TermInfoPropagatorThread");
    termInfoPropagatorThread.start();
  }
}
