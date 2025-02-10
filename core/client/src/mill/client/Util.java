package mill.client;

import java.io.Console;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Util {

  public static boolean isWindows =
      System.getProperty("os.name").toLowerCase().startsWith("windows");
  public static boolean isJava9OrAbove =
      !System.getProperty("java.specification.version").startsWith("1.");

  /**
   * @return Hex encoded MD5 hash of input string.
   */
  public static String md5hex(String str) throws NoSuchAlgorithmException {
    return hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)));
  }

  static String hexArray(byte[] arr) {
    return String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr));
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
    Console console = System.console();

    if (console != null) {
      try {
        Method method = console.getClass().getMethod("isTerminal");
        return (Boolean) method.invoke(console);
      } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException ignored) {
        return true;
      }
    } else return false;
  }
}
