package mill.launcher;

import static mill.constants.OutFiles.*;

import io.github.alexarchambault.nativeterm.NativeTerminal;
import io.github.alexarchambault.nativeterm.TerminalSize;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mill.client.ClientUtil;
import mill.constants.*;

public class MillProcessLauncher {

  static int launchMillNoDaemon(
      String[] args, OutFolderMode outMode, String[] runnerClasspath, String mainClass)
      throws Exception {
    final String sig = String.format("%08x", UUID.randomUUID().hashCode());
    final Path processDir =
        Paths.get(".").resolve(outFor(outMode)).resolve(millNoDaemon).resolve(sig);

    MillProcessLauncher.prepareMillRunFolder(processDir);

    final List<String> l = new ArrayList<>();
    l.addAll(millLaunchJvmCommand(outMode, runnerClasspath));
    Map<String, String> propsMap = ClientUtil.getUserSetProperties();
    for (String key : propsMap.keySet()) l.add("-D" + key + "=" + propsMap.get(key));
    l.add(mainClass);
    l.add(processDir.toAbsolutePath().toString());
    l.add(outMode.asString());
    l.addAll(millOpts(outMode));
    l.addAll(Arrays.asList(args));

    final ProcessBuilder builder = new ProcessBuilder().command(l).inheritIO();

    boolean interrupted = false;

    try {
      Process p = configureRunMillProcess(builder, processDir);
      return p.waitFor();

    } catch (InterruptedException e) {
      interrupted = true;
      throw e;
    } finally {
      if (!interrupted && Files.exists(processDir)) {
        // cleanup if process terminated for sure
        try (Stream<Path> stream = Files.walk(processDir)) {
          // depth-first
          stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
      }
    }
  }

  static Process launchMillDaemon(Path daemonDir, OutFolderMode outMode, String[] runnerClasspath)
      throws Exception {
    List<String> l = new ArrayList<>(millLaunchJvmCommand(outMode, runnerClasspath));
    l.add("mill.daemon.MillDaemonMain");
    l.add(daemonDir.toFile().getCanonicalPath());
    l.add(outMode.asString());

    ProcessBuilder builder = new ProcessBuilder()
        .command(l)
        .redirectOutput(daemonDir.resolve(DaemonFiles.stdout).toFile())
        .redirectError(daemonDir.resolve(DaemonFiles.stderr).toFile());

    return configureRunMillProcess(builder, daemonDir);
  }

  static Process configureRunMillProcess(ProcessBuilder builder, Path daemonDir) throws Exception {

    Path sandbox = daemonDir.resolve(DaemonFiles.sandbox);
    Files.createDirectories(sandbox);

    builder.environment().put(EnvVars.MILL_WORKSPACE_ROOT, new File("").getCanonicalPath());
    if (System.getenv(EnvVars.MILL_EXECUTABLE_PATH) == null)
      builder.environment().put(EnvVars.MILL_EXECUTABLE_PATH, getExecutablePath());

    String jdkJavaOptions = System.getenv("JDK_JAVA_OPTIONS");
    if (jdkJavaOptions == null) jdkJavaOptions = "";
    String javaOpts = System.getenv("JAVA_OPTS");
    if (javaOpts == null) javaOpts = "";

    String opts = (jdkJavaOptions + " " + javaOpts).trim();
    if (!opts.isEmpty()) {
      builder.environment().put("JDK_JAVA_OPTIONS", opts);
    }

    builder.directory(sandbox.toFile());
    return builder.start();
  }

  static List<String> loadMillConfig(OutFolderMode outMode, String key) throws Exception {

    Path configFile = Paths.get("." + key);
    final Map<String, String> env = new HashMap<>();
    env.putAll(System.getenv());
    // Hardcode support for PWD because the graal native launcher has it set to the
    // working dir of the enclosing process, when we want it to be set to the working
    // dir of the current process
    final String workspaceDir = new java.io.File(".").getAbsoluteFile().getCanonicalPath();
    env.put("PWD", workspaceDir);
    env.put("WORKSPACE", workspaceDir);
    env.put("MILL_VERSION", mill.constants.BuildInfo.millVersion);
    env.put("MILL_BIN_PLATFORM", mill.constants.BuildInfo.millBinPlatform);

    if (Files.exists(configFile)) {
      return ClientUtil.readOptsFileLines(configFile.toAbsolutePath(), env);
    } else {
      for (String rootBuildFileName : CodeGenConstants.rootBuildFileNames) {
        Path buildFile = Paths.get(rootBuildFileName);
        if (Files.exists(buildFile)) {
          String[] config = cachedComputedValue(
              outMode,
              key,
              mill.constants.Util.readBuildHeader(
                  buildFile, buildFile.getFileName().toString()),
              () -> {
                Object conf = mill.launcher.ConfigReader.readYaml(
                    buildFile, buildFile.getFileName().toString());
                if (!(conf instanceof Map)) return new String[] {};
                @SuppressWarnings("unchecked")
                var conf2 = (Map<String, Object>) conf;

                if (!conf2.containsKey(key)) return new String[] {};
                if (conf2.get(key) instanceof List) {
                  @SuppressWarnings("unchecked")
                  var list = (List<String>) conf2.get(key);
                  String[] arr = new String[list.size()];
                  for (int i = 0; i < arr.length; i++) {
                    arr[i] = mill.constants.Util.interpolateEnvVars(list.get(i), env);
                  }
                  return arr;
                } else {
                  return new String[] {
                    mill.constants.Util.interpolateEnvVars(conf2.get(key).toString(), env)
                  };
                }
              });
          return Arrays.asList(config);
        }
      }
    }
    return List.of();
  }

  static List<String> millJvmOpts(OutFolderMode outMode) throws Exception {
    return loadMillConfig(outMode, "mill-jvm-opts");
  }

  static List<String> millOpts(OutFolderMode outMode) throws Exception {
    return loadMillConfig(outMode, "mill-opts");
  }

  static String millJvmVersion(OutFolderMode outMode) throws Exception {
    List<String> res = loadMillConfig(outMode, "mill-jvm-version");
    if (res.isEmpty()) return null;
    else return res.get(0);
  }

  static String millServerTimeout() {
    return System.getenv(EnvVars.MILL_SERVER_TIMEOUT_MILLIS);
  }

  static boolean isWin() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  static String javaHome(OutFolderMode outMode) throws Exception {
    var jvmId = millJvmVersion(outMode);

    String javaHome = null;
    if (jvmId == null) {
      boolean systemJavaExists =
          new ProcessBuilder(isWin() ? "where" : "which", "java").start().waitFor() == 0;
      if (systemJavaExists && System.getenv("MILL_TEST_SUITE_IGNORE_SYSTEM_JAVA") == null) {
        jvmId = null;
      } else {
        jvmId = mill.client.BuildInfo.defaultJvmId;
      }
    }

    if (jvmId != null) {
      final String jvmIdFinal = jvmId;
      javaHome = cachedComputedValue0(
          outMode,
          "java-home",
          jvmId,
          () -> new String[] {CoursierClient.resolveJavaHome(jvmIdFinal).getAbsolutePath()},
          // Make sure we check to see if the saved java home exists before using
          // it, since it may have been since uninstalled, or the `out/` folder
          // may have been transferred to a different machine
          arr -> Files.exists(Paths.get(arr[0])))[0];
    }

    if (javaHome == null || javaHome.isEmpty()) javaHome = System.getProperty("java.home");
    if (javaHome == null || javaHome.isEmpty()) javaHome = System.getenv("JAVA_HOME");
    return javaHome;
  }

  static String javaExe(OutFolderMode outMode) throws Exception {
    String javaHome = javaHome(outMode);
    if (javaHome == null) return "java";
    else {
      final Path exePath = Paths.get(
          javaHome + File.separator + "bin" + File.separator + "java" + (isWin() ? ".exe" : ""));

      return exePath.toAbsolutePath().toString();
    }
  }

  static List<String> millLaunchJvmCommand(OutFolderMode outMode, String[] runnerClasspath)
      throws Exception {
    final List<String> vmOptions = new ArrayList<>();

    // Java executable
    vmOptions.add(javaExe(outMode));

    // sys props
    final Properties sysProps = System.getProperties();
    for (final String k : sysProps.stringPropertyNames()) {
      if (k.startsWith("MILL_")) {
        vmOptions.add("-D" + k + "=" + sysProps.getProperty(k));
      }
    }

    String serverTimeout = millServerTimeout();
    if (serverTimeout != null) vmOptions.add("-Dmill.server_timeout=" + serverTimeout);

    // extra opts
    vmOptions.addAll(millJvmOpts(outMode));

    vmOptions.add("-XX:+HeapDumpOnOutOfMemoryError");
    vmOptions.add("-cp");

    vmOptions.add(String.join(File.pathSeparator, runnerClasspath));

    return vmOptions;
  }

  static String[] cachedComputedValue(
      OutFolderMode outMode, String name, String key, Supplier<String[]> block) {
    return cachedComputedValue0(outMode, name, key, block, arr -> true);
  }

  static String[] cachedComputedValue0(
      OutFolderMode outMode,
      String name,
      String key,
      Supplier<String[]> block,
      Function<String[], Boolean> validate) {
    try {
      Path cacheFile = Paths.get(".").resolve(outFor(outMode)).resolve("mill-launcher/" + name);
      String[] value = null;
      if (Files.exists(cacheFile)) {
        String[] savedInfo = Files.readString(cacheFile).split("\n");
        if (savedInfo[0].equals(Escaping.literalize(key))) {
          value = Arrays.copyOfRange(savedInfo, 1, savedInfo.length);
          for (int i = 0; i < value.length; i++) value[i] = Escaping.unliteralize(value[i]);
        }
      }

      if (value != null && !validate.apply(value)) value = null;

      if (value == null) {
        value = block.get();
        String[] literalized = new String[value.length];
        for (int i = 0; i < literalized.length; i++) {
          literalized[i] = Escaping.literalize(value[i]);
        }

        Files.createDirectories(cacheFile.getParent());
        Files.write(
            cacheFile,
            (Escaping.literalize(key) + "\n" + String.join("\n", literalized)).getBytes());
      }
      return value;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
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

  private static final AtomicReference<String> memoizedTerminalDims = new AtomicReference<>();

  private static final boolean canUseNativeTerminal;

  static {
    JLineNativeLoader.initJLineNative();

    boolean canUse;
    if (mill.constants.Util.hasConsole()) {
      try {
        NativeTerminal.getSize();
        canUse = true;
      } catch (Throwable ex) {
        canUse = false;
      }
    } else canUse = false;

    canUseNativeTerminal = canUse;
  }

  static void writeTerminalDims(boolean tputExists, Path daemonDir) throws Exception {
    String str;

    try {
      if (!mill.constants.Util.hasConsole()) str = "0 0";
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
      Files.write(daemonDir.resolve(DaemonFiles.terminfo), str.getBytes());
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

  public static void prepareMillRunFolder(Path daemonDir) throws Exception {
    // Clear out run-related files from the server folder to make sure we
    // never hit issues where we are reading the files from a previous run
    Files.deleteIfExists(daemonDir.resolve(DaemonFiles.terminfo));

    Path sandbox = daemonDir.resolve(DaemonFiles.sandbox);
    Files.createDirectories(sandbox);
    boolean tputExists = checkTputExists();

    writeTerminalDims(tputExists, daemonDir);
    Thread termInfoPropagatorThread = new Thread(
        () -> {
          try {
            while (true) {
              writeTerminalDims(tputExists, daemonDir);
              Thread.sleep(100);
            }
          } catch (Exception e) {
          }
        },
        "TermInfoPropagatorThread");
    termInfoPropagatorThread.setDaemon(true);
    termInfoPropagatorThread.start();
  }

  public static String getExecutablePath() {
    try {
      // Gets the location of the JAR or native image
      Path path = Paths.get(MillProcessLauncher.class
          .getProtectionDomain()
          .getCodeSource()
          .getLocation()
          .toURI());

      File file = path.toFile();
      return file.getAbsolutePath();
    } catch (java.net.URISyntaxException e) {
      throw new RuntimeException("Failed to determine Mill client executable path", e);
    }
  }
}
