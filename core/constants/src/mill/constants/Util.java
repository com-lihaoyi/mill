package mill.constants;

import java.io.Console;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class Util {

  private static String lowerCaseOsName() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT);
  }

  public static boolean isWindows = lowerCaseOsName().startsWith("windows");
  public static boolean isLinux = lowerCaseOsName().equals("linux");
}
