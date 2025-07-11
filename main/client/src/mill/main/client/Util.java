package mill.main.client;

import java.io.Console;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {
  // use methods instead of constants to avoid inlining by compiler
  public static final int ExitClientCodeCannotReadFromExitCodeFile() {
    return 1;
  }

  public static final int ExitServerCodeWhenIdle() {
    return 0;
  }

  public static final int ExitServerCodeWhenVersionMismatch() {
    return 101;
  }

  private static String lowerCaseOsName() {
    return System.getProperty("os.name").toLowerCase();
  }

  public static boolean isWindows = lowerCaseOsName().startsWith("windows");
  public static boolean isLinux = lowerCaseOsName().equals("linux");
  public static boolean isJava9OrAbove =
      !System.getProperty("java.specification.version").startsWith("1.");
  private static Charset utf8 = Charset.forName("UTF-8");

  private static final boolean hasConsole0;

  static {
    Console console = System.console();

    boolean foundConsole;
    if (console != null) {
      try {
        Method method = console.getClass().getMethod("isTerminal");
        foundConsole = (Boolean) method.invoke(console);
      } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException ignored) {
        foundConsole = true;
      }
    } else foundConsole = false;

    hasConsole0 = foundConsole;
  }

  /**
   * Determines if we have an interactive console attached to the application.
   * <p>
   * Before JDK 22 we could use <code>System.console() != null</code> to do that check.
   * However, with JDK &gt;= 22 it no longer works because <code>System.console()</code>
   * always returns a console instance even for redirected streams. Instead,
   * JDK &gt;= 22 introduced the method <a href="https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/io/Console.html#isTerminal()">`Console.isTerminal`</a>.
   * See: JLine As The Default Console Provider (JDK-8308591)
   * <p>
   * This method takes into account these differences and is compatible with
   * both JDK versions before 22 and later.
   */
  public static boolean hasConsole() {
    return hasConsole0;
  }

  /**
   * When using Graal Native Image, the launcher receives any `-D` properties
   * as system properties rather than command-line flags. Thus we try to identify
   * any system properties that are set by the user so we can propagate them to
   * the Mill daemon process appropriately
   */
  public static Map<String, String> getUserSetProperties() {
    java.util.Set<String> bannedPrefixes =
        java.util.Set.of("path", "line", "native", "sun", "os", "java", "file", "jdk", "user");
    java.util.Properties props = System.getProperties();
    Map<String, String> propsMap = new java.util.HashMap<>();
    for (String key : props.stringPropertyNames()) {
      if (!bannedPrefixes.contains(key.split("\\.")[0])) {
        propsMap.put(key, props.getProperty(key));
      }
    }
    return propsMap;
  }

  public static String[] parseArgs(InputStream argStream) throws IOException {
    int argsLength = readInt(argStream);
    String[] args = new String[argsLength];
    for (int i = 0; i < args.length; i++) {
      args[i] = readString(argStream);
    }
    return args;
  }

  public static void writeArgs(String[] args, OutputStream argStream) throws IOException {
    writeInt(argStream, args.length);
    for (String arg : args) {
      writeString(argStream, arg);
    }
  }

  /**
   * This allows the mill client to pass the environment as it sees it to the
   * server (as the server remains alive over the course of several runs and
   * does not see the environment changes the client would)
   */
  public static void writeMap(Map<String, String> map, OutputStream argStream) throws IOException {
    writeInt(argStream, map.size());
    for (Map.Entry<String, String> kv : map.entrySet()) {
      writeString(argStream, kv.getKey());
      writeString(argStream, kv.getValue());
    }
  }

  public static Map<String, String> parseMap(InputStream argStream) throws IOException {
    Map<String, String> env = new HashMap<>();
    int mapLength = readInt(argStream);
    for (int i = 0; i < mapLength; i++) {
      String key = readString(argStream);
      String value = readString(argStream);
      env.put(key, value);
    }
    return env;
  }

  public static String readString(InputStream inputStream) throws IOException {
    // Result is between 0 and 255, hence the loop.
    final int length = readInt(inputStream);
    final byte[] arr = new byte[length];
    int total = 0;
    while (total < length) {
      int res = inputStream.read(arr, total, length - total);
      if (res == -1) throw new IOException("Incomplete String");
      else {
        total += res;
      }
    }
    return new String(arr, utf8);
  }

  public static void writeString(OutputStream outputStream, String string) throws IOException {
    final byte[] bytes = string.getBytes(utf8);
    writeInt(outputStream, bytes.length);
    outputStream.write(bytes);
  }

  public static void writeInt(OutputStream out, int i) throws IOException {
    out.write((byte) (i >>> 24));
    out.write((byte) (i >>> 16));
    out.write((byte) (i >>> 8));
    out.write((byte) i);
  }

  public static int readInt(InputStream in) throws IOException {
    return ((in.read() & 0xFF) << 24)
        + ((in.read() & 0xFF) << 16)
        + ((in.read() & 0xFF) << 8)
        + (in.read() & 0xFF);
  }

  /**
   * @return Hex encoded MD5 hash of input string.
   */
  public static String md5hex(String str) throws NoSuchAlgorithmException {
    return hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)));
  }

  private static String hexArray(byte[] arr) {
    return String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr));
  }

  public static String sha1Hash(String path) throws NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("SHA1");
    md.reset();
    byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
    md.update(pathBytes);
    byte[] digest = md.digest();
    return hexArray(digest);
  }

  /**
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  public static List<String> readOptsFileLines(final Path file) throws Exception {
    final List<String> vmOptions = new LinkedList<>();
    try (final Scanner sc = new Scanner(file.toFile())) {
      final Map<String, String> env = System.getenv();
      while (sc.hasNextLine()) {
        String arg = sc.nextLine();
        String trimmed = arg.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          vmOptions.add(interpolateEnvVars(arg, env));
        }
      }
    } catch (FileNotFoundException e) {
      // ignored
    }
    return vmOptions;
  }

  /**
   * Interpolate variables in the form of <code>${VARIABLE}</code> based on the given Map <code>env</code>.
   * Missing vars will be replaced by the empty string.
   */
  public static String interpolateEnvVars(String input, Map<String, String> env) throws Exception {
    Matcher matcher = envInterpolatorPattern.matcher(input);
    // StringBuilder to store the result after replacing
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String match = matcher.group(1);
      if (match.equals("$")) {
        matcher.appendReplacement(result, "\\$");
      } else {
        String envVarValue =
            // Hardcode support for PWD because the graal native launcher has it set to the
            // working dir of the enclosing process, when we want it to be set to the working
            // dir of the current process
            match.equals("PWD")
                ? new java.io.File(".").getAbsoluteFile().getCanonicalPath()
                : env.containsKey(match) ? env.get(match) : "";
        matcher.appendReplacement(result, envVarValue);
      }
    }

    matcher.appendTail(result); // Append the remaining part of the string
    return result.toString();
  }

  private static Pattern envInterpolatorPattern =
      Pattern.compile("\\$\\{(\\$|[A-Z_][A-Z0-9_]*)\\}");
}
