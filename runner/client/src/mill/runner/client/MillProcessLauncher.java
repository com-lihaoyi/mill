package mill.runner.client;

import static mill.main.client.OutFiles.*;

import io.github.alexarchambault.nativeterm.NativeTerminal;
import io.github.alexarchambault.nativeterm.TerminalSize;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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
    Map<String, String> propsMap = Util.getUserSetProperties();
    for (String key : propsMap.keySet()) {
      l.add("-D" + key + "=" + propsMap.get(key));
    }
    l.add("mill.runner.MillMain");
    l.add(processDir.toAbsolutePath().toString());
    l.addAll(Util.readOptsFileLines(millOptsFile()));
    l.addAll(Arrays.asList(args));

    final ProcessBuilder builder = new ProcessBuilder().command(l).inheritIO();

    boolean interrupted = false;

    try {
      MillProcessLauncher.prepareMillRunFolder(processDir);
      Process p = configureRunMillProcess(builder, processDir);
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

  static Path millJvmVersionFile() {
    String millJvmOptsPath = System.getenv(EnvVars.MILL_JVM_VERSION_PATH);
    if (millJvmOptsPath == null || millJvmOptsPath.trim().equals("")) {
      millJvmOptsPath = ".mill-jvm-version";
    }
    return Paths.get(millJvmOptsPath).toAbsolutePath();
  }

  static Path millJvmOptsFile() {
    String millJvmOptsPath = System.getenv(EnvVars.MILL_JVM_OPTS_PATH);
    if (millJvmOptsPath == null || millJvmOptsPath.trim().equals("")) {
      millJvmOptsPath = ".mill-jvm-opts";
    }
    return Paths.get(millJvmOptsPath).toAbsolutePath();
  }

  static Path millOptsFile() {
    String millJvmOptsPath = System.getenv(EnvVars.MILL_OPTS_PATH);
    if (millJvmOptsPath == null || millJvmOptsPath.trim().equals("")) {
      millJvmOptsPath = ".mill-opts";
    }
    return Paths.get(millJvmOptsPath).toAbsolutePath();
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

  static String javaHome() throws IOException {
    String jvmId;
    Path millJvmVersionFile = millJvmVersionFile();

    String javaHome = null;
    if (Files.exists(millJvmVersionFile)) {
      jvmId = Files.readString(millJvmVersionFile).trim();
      ;
      javaHome = CoursierClient.resolveJavaHome(jvmId).getAbsolutePath();
    }

    if (javaHome == null || javaHome.isEmpty()) javaHome = System.getProperty("java.home");
    if (javaHome == null || javaHome.isEmpty()) javaHome = System.getenv("JAVA_HOME");
    return javaHome;
  }

  static String javaExe() throws IOException {
    String javaHome = javaHome();
    if (javaHome == null) return "java";
    else {
      final Path exePath = Paths.get(
          javaHome + File.separator + "bin" + File.separator + "java" + (isWin() ? ".exe" : ""));

      return exePath.toAbsolutePath().toString();
    }
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
      // Assuming native assembly run
      selfJars = MillProcessLauncher.class
          .getProtectionDomain()
          .getCodeSource()
          .getLocation()
          .getPath();
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
    Path millJvmOptsFile = millJvmOptsFile();
    if (Files.exists(millJvmOptsFile)) {
      vmOptions.addAll(Util.readOptsFileLines(millJvmOptsFile));
    }

    vmOptions.add("-XX:+HeapDumpOnOutOfMemoryError");
    vmOptions.add("-cp");
    vmOptions.add(String.join(File.pathSeparator, millClasspath()));

    return vmOptions;
  }

  static List<String> readMillJvmOpts() throws Exception {
    return Util.readOptsFileLines(millJvmOptsFile());
  }

  static int getTerminalDim(String s, boolean inheritError) throws Exception {
    Process proc = new ProcessBuilder()
        .command("tput", s)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        // We cannot redirect error to PIPE, because `tput` needs at least one of the
        // outputstreams inherited so it can inspect the stream to get the console
        // dimensions. Instead, we check up-front that `tput cols` and `tput lines` do
        // not raise errors, and hope that means it continues to work going forward
        .redirectError(
            inheritError ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE)
        .start();

    int exitCode = proc.waitFor();
    if (exitCode != 0) throw new Exception("tput failed");
    return Integer.parseInt(new String(proc.getInputStream().readAllBytes()).trim());
  }

  private static AtomicReference<String> memoizedTerminalDims = new AtomicReference();

  private static final boolean canUseNativeTerminal;

  static {
    JLineNativeLoader.initJLineNative();

    boolean canUse;
    if (mill.main.client.Util.hasConsole()) {
      try {
        NativeTerminal.getSize();
        canUse = true;
      } catch (Throwable ex) {
        canUse = false;
      }
    } else canUse = false;

    canUseNativeTerminal = canUse;
  }

  static void writeTerminalDims(boolean tputExists, Path serverDir) throws Exception {
    String str;

    try {
      if (!mill.main.client.Util.hasConsole()) str = "0 0";
      else {
        if (canUseNativeTerminal) {

          TerminalSize size = NativeTerminal.getSize();
          int width = size.getWidth();
          int height = size.getHeight();
          str = width + " " + height;
        } else if (!tputExists) {
          // Hardcoded size of a quarter screen terminal on 13" windows laptop
          str = "78 24";
        } else {
          str = getTerminalDim("cols", true) + " " + getTerminalDim("lines", true);
        }
      }
    } catch (Exception e) {
      str = "0 0";
    }

    // We memoize previously seen values to avoid causing lots
    // of upstream work if the value hasn't actually changed.
    // The upstream work could cause significant load, see
    //
    //    https://github.com/com-lihaoyi/mill/discussions/4092
    //
    // The cause is currently unknown, but this fixes the symptoms at least.
    //
    String oldValue = memoizedTerminalDims.getAndSet(str);
    if ((oldValue == null) || !oldValue.equals(str)) {
      Files.write(serverDir.resolve(ServerFiles.terminfo), str.getBytes());
    }
  }

  public static boolean checkTputExists() {
    try {
      getTerminalDim("cols", false);
      getTerminalDim("lines", false);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static void prepareMillRunFolder(Path serverDir) throws Exception {
    // Clear out run-related files from the server folder to make sure we
    // never hit issues where we are reading the files from a previous run
    Files.deleteIfExists(serverDir.resolve(ServerFiles.exitCode));
    Files.deleteIfExists(serverDir.resolve(ServerFiles.terminfo));
    Files.deleteIfExists(serverDir.resolve(ServerFiles.runArgs));

    Path sandbox = serverDir.resolve(ServerFiles.sandbox);
    Files.createDirectories(sandbox);
    boolean tputExists = checkTputExists();

    writeTerminalDims(tputExists, serverDir);
    Thread termInfoPropagatorThread = new Thread(
        () -> {
          try {
            while (true) {
              writeTerminalDims(tputExists, serverDir);
              Thread.sleep(100);
            }
          } catch (Exception e) {
          }
        },
        "TermInfoPropagatorThread");
    termInfoPropagatorThread.start();
  }
}
