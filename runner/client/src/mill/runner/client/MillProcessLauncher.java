package mill.runner.client;

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
import mill.constants.BuildInfo;
import mill.constants.CodeGenConstants;
import mill.constants.EnvVars;
import mill.constants.ServerFiles;

public class MillProcessLauncher {

  static int launchMillNoServer(String[] args) throws Exception {
    final boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
    final String sig = String.format("%08x", UUID.randomUUID().hashCode());
    final Path processDir = Paths.get(".").resolve(out).resolve(millNoServer).resolve(sig);

    final List<String> l = new ArrayList<>();
    l.addAll(millLaunchJvmCommand(setJnaNoSys));
    l.add("mill.runner.MillMain");
    l.add(processDir.toAbsolutePath().toString());
    l.addAll(millOpts());
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
      if (!interrupted && Files.exists(processDir)) {
        // cleanup if process terminated for sure
        try (Stream<Path> stream = Files.walk(processDir)) {
          // depth-first
          stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
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

  static List<String> loadMillConfig(String key) throws Exception {

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
              "yaml-config-" + key, mill.constants.Util.readYamlHeader(buildFile), () -> {
                Object conf = mill.runner.client.ConfigReader.readYaml(buildFile);
                if (!(conf instanceof Map)) return new String[] {};
                Map<String, List<String>> conf2 = (Map<String, List<String>>) conf;

                if (!conf2.containsKey(key)) return new String[] {};
                if (conf2.get(key) instanceof List) {
                  return (String[]) ((List) conf2.get(key))
                      .stream()
                          .map(x -> mill.constants.Util.interpolateEnvVars(x.toString(), env))
                          .toArray(String[]::new);
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

  static List<String> millJvmOpts() throws Exception {
    return loadMillConfig("mill-jvm-opts");
  }

  static List<String> millOpts() throws Exception {
    return loadMillConfig("mill-opts");
  }

  static String millJvmVersion() throws Exception {
    List<String> res = loadMillConfig("mill-jvm-version");
    if (res.isEmpty()) return null;
    else return res.get(0);
  }

  static String millServerTimeout() {
    return System.getenv(EnvVars.MILL_SERVER_TIMEOUT_MILLIS);
  }

  static boolean isWin() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  static String javaHome() throws Exception {
    String jvmId = null;
    jvmId = millJvmVersion();

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

  static String javaExe() throws Exception {
    String javaHome = javaHome();
    if (javaHome == null) return "java";
    else {
      final Path exePath = Paths.get(
          javaHome + File.separator + "bin" + File.separator + "java" + (isWin() ? ".exe" : ""));

      return exePath.toAbsolutePath().toString();
    }
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
      if (k.startsWith("MILL_")) {
        vmOptions.add("-D" + k + "=" + sysProps.getProperty(k));
      }
    }

    String serverTimeout = millServerTimeout();
    if (serverTimeout != null) vmOptions.add("-D" + "mill.server_timeout" + "=" + serverTimeout);

    // extra opts
    vmOptions.addAll(millJvmOpts());

    vmOptions.add("-XX:+HeapDumpOnOutOfMemoryError");
    vmOptions.add("-cp");
    String[] runnerClasspath = cachedComputedValue0(
        "resolve-runner",
        BuildInfo.millVersion,
        () -> CoursierClient.resolveMillRunner(),
        arr -> Arrays.stream(arr).allMatch(s -> Files.exists(Paths.get(s))));
    vmOptions.add(String.join(File.pathSeparator, runnerClasspath));

    return vmOptions;
  }

  static String[] cachedComputedValue(String name, String key, Supplier<String[]> block) {
    return cachedComputedValue0(name, key, block, arr -> true);
  }

  static String[] cachedComputedValue0(
      String name, String key, Supplier<String[]> block, Function<String[], Boolean> validate) {
    try {
      Path cacheFile = Paths.get(".").resolve(out).resolve("mill-" + name);
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

  private static AtomicReference<String> memoizedTerminalDims = new AtomicReference();

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

  static void writeTerminalDims(boolean tputExists, Path serverDir) throws Exception {
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
    termInfoPropagatorThread.setDaemon(true);
    termInfoPropagatorThread.start();
  }
}
