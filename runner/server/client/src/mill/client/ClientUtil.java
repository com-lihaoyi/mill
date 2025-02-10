package mill.client;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientUtil {
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

  private static Charset utf8 = Charset.forName("UTF-8");

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
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  public static List<String> readOptsFileLines(final Path file) throws Exception {
    final List<String> vmOptions = new LinkedList<>();
    try (final Scanner sc = new Scanner(file.toFile(), StandardCharsets.UTF_8)) {
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
