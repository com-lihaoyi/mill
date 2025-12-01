package mill.constants;

import java.io.Console;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {

  private static String lowerCaseOsName() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT);
  }

  public static boolean isWindows = lowerCaseOsName().startsWith("windows");
  public static boolean isLinux = lowerCaseOsName().equals("linux");
  public static boolean isJava9OrAbove =
      !System.getProperty("java.specification.version").startsWith("1.");

  /**
   * @return Hex encoded MD5 hash of input string.
   */
  public static String md5hex(String str) throws NoSuchAlgorithmException {
    return hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)));
  }

  private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

  public static String hexArray(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      hexChars[i * 2] = HEX_ARRAY[v >>> 4];
      hexChars[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
    }
    return new String(hexChars);
  }

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
   * Formats an error message in dotty style with file location, code snippet, and pointer.
   */
  public static String formatError(
      String fileName,
      int lineNum,
      int colNum,
      String lineContent,
      String message,
      Function<String, String> highlight) {
    return "[" + highlight.apply("error") + "] "
        + formatError(fileName, lineNum, colNum, lineContent, message, 1, highlight);
  }

  /**
   * Formats an error message in dotty style with file location, code snippet, and pointer.
   *
   * @param pointerLength The number of ^ characters to show in the pointer
   * @param highlight Function to apply highlighting/coloring to header and pointer
   */
  public static String formatError(
      String fileName,
      int lineNum,
      int colNum,
      String lineContent,
      String message,
      int pointerLength,
      Function<String, String> highlight) {
    String pointer =
        colNum > 0 ? " ".repeat(colNum - 1) + highlight.apply("^".repeat(pointerLength)) : "";
    String header = (lineNum >= 0 && colNum >= 0)
        ? highlight.apply(fileName) + ":" + highlight.apply(String.valueOf(lineNum)) + ":"
            + highlight.apply(String.valueOf(colNum))
        : highlight.apply(fileName);
    return header + "\n" + lineContent + "\n" + pointer + "\n" + highlight.apply(message);
  }

  private static String throwBuildHeaderError(
      String errorFileName, int lineNumber, String line, String msg) {
    // lineNumber is 0-indexed, convert to 1-indexed for display
    // Column is 1 since the error applies to the start of the line
    throw new RuntimeException(formatError(errorFileName, lineNumber + 1, 1, line, msg, s -> s));
  }

  public static String readBuildHeader(Path buildFile, String errorFileName) {
    return readBuildHeader(buildFile, errorFileName, false);
  }

  public static String readBuildHeader(
      Path buildFile, String errorFileName, boolean allowNonBuild) {
    try {
      String fileName = buildFile.getFileName().toString();

      // For .yaml files, return the entire file content as YAML
      if (fileName.endsWith(".yaml")) {
        return Files.readString(buildFile);
      }

      // For other files, extract YAML from //| comments
      java.util.List<String> lines = Files.readAllLines(buildFile);
      boolean readingBuildHeader = true;
      java.util.List<String> output = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (!line.startsWith("//|")) readingBuildHeader = false;
        else if (!allowNonBuild && !buildFile.getFileName().toString().startsWith("build.")) {
          throwBuildHeaderError(
              errorFileName,
              i,
              line,
              "YAML header can only be defined in the `build.mill` file, not `" + errorFileName
                  + "`");
        } else if (!readingBuildHeader) {
          throwBuildHeaderError(
              errorFileName,
              i,
              line,
              "YAML header comments can only occur at the start of the file");
        } else if (line.length() >= 4 && !line.startsWith("//| ")) {
          throwBuildHeaderError(
              errorFileName,
              i,
              line,
              "YAML header comments must start with `//| ` with a newline separating the `|` and"
                  + " the data on the right");
        } else if (line.equals("//|")) output.add("");
        else output.add(line.substring(4));
      }
      return String.join("\n", output);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String envInterpolatorPattern0 = "(\\$|[A-Z_][A-Z0-9_]*)";
  private static Pattern envInterpolatorPattern =
      Pattern.compile("\\$\\{" + envInterpolatorPattern0 + "\\}|\\$" + envInterpolatorPattern0);

  /**
   * Interpolate variables in the form of <code>${VARIABLE}</code> based on the given Map <code>env</code>.
   * @throws IllegalArgumentException if a variable is missing.
   */
  public static String interpolateEnvVars(String input, Map<String, String> env0) {
    return interpolateEnvVars(input, env0, var -> {
      throw new IllegalArgumentException("Cannot interpolate missing env var '" + var + "'");
    });
  }

  /**
   * Interpolate variables in the form of <code>${VARIABLE}</code> based on the given Map <code>env</code>.
   */
  public static String interpolateEnvVars(
      String input, Map<String, String> env0, Function<String, String> onMissing) {
    Matcher matcher = envInterpolatorPattern.matcher(input);
    // StringBuilder to store the result after replacing
    StringBuffer result = new StringBuffer();

    Map<String, String> env = new java.util.HashMap<>();
    env.putAll(env0);

    while (matcher.find()) {
      String match = matcher.group(1);
      if (match == null) match = matcher.group(2);
      if (match.equals("$")) {
        matcher.appendReplacement(result, "\\$");
      } else {
        String envVarValue;
        envVarValue = env.containsKey(match) ? env.get(match) : onMissing.apply(match);
        matcher.appendReplacement(result, envVarValue);
      }
    }

    matcher.appendTail(result); // Append the remaining part of the string
    return result.toString();
  }
}
