package mill.client;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public class ClientUtil {
  // use methods instead of constants to avoid inlining by compiler
  public static int ExitClientCodeCannotReadFromExitCodeFile() {
    return 1;
  }

  public static int ExitServerCodeWhenIdle() {
    return 0;
  }

  public static int ExitServerCodeWhenVersionMismatch() {
    return 101;
  }

  private static final Charset utf8 = StandardCharsets.UTF_8;

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
    if (length == -1) return null;
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
    if (string == null) {
      writeInt(outputStream, -1);
    } else {
      final byte[] bytes = string.getBytes(utf8);
      writeInt(outputStream, bytes.length);
      outputStream.write(bytes);
    }
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
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  public static List<String> readOptsFileLines(final Path file, Map<String, String> env)
      throws Exception {
    final List<String> vmOptions = new LinkedList<>();
    try (final Scanner sc = new Scanner(file.toFile(), StandardCharsets.UTF_8)) {
      while (sc.hasNextLine()) {
        String arg = sc.nextLine();
        String trimmed = arg.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          vmOptions.add(mill.constants.Util.interpolateEnvVars(arg, env));
        }
      }
    } catch (FileNotFoundException e) {
      // ignored
    }
    return vmOptions;
  }
}
