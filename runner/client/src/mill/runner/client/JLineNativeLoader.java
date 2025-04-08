package mill.runner.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import org.jline.nativ.OSInfo;

/**
 * Helper to load the jline-native native library before jline-native itself attempts to do so.
 *
 * We load it from a location that doesn't change. That way, if the library is already
 * there, we can load it straightaway. That's the "fast path".
 *
 * If the library isn't there already, we write it there first. That's the "slow path".
 * This should need to run only once on the user's machine. Once the library is there,
 * we can go through the fast path above every time.
 *
 * If we don't do that, jline-native loads its library on its own, and always does things slowly,
 * by writing its library in a new temporary location upon each run.
 */
final class JLineNativeLoader {

  private final String jlineNativeVersion;
  private final Path millJLineNativeDir;
  private final Path millJLineNativeLibLocation;

  JLineNativeLoader(String jlineNativeVersion) {
    this.jlineNativeVersion = jlineNativeVersion;
    final boolean isWindows =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    final Path baseDir;
    if (isWindows) baseDir = Paths.get(System.getenv("UserProfile")).resolve(".mill/cache/");
    else baseDir = Paths.get(System.getProperty("user.home")).resolve(".cache/mill/");
    this.millJLineNativeDir = baseDir.resolve("jline/" + jlineNativeVersion);
    this.millJLineNativeLibLocation =
        millJLineNativeDir.resolve(OSInfo.getNativeLibFolderPathForCurrentOS() + "/"
            + System.mapLibraryName("jlinenative").replace(".dylib", ".jnilib"));
  }

  private static String jlineNativeLibResourcePath() {
    return "org/jline/nativ/" + OSInfo.getNativeLibFolderPathForCurrentOS()
        + "/"
        // Replacing '.dylib' by '.jnilib' is necessary, as jlinenative uses the latter extension on
        // macOS, rather than '.dylib', which is the default. The call to replace has no effect on
        // other platforms.
        + System.mapLibraryName("jlinenative").replace(".dylib", ".jnilib");
  }

  boolean tryLoadFast() {
    return Files.exists(millJLineNativeLibLocation);
  }

  // If the jlinenative native library isn't in cache (tryLoadFast returns null), loadSlow
  // reads it from the resources and writes it on disk, which is more heavyweight.
  // That's the slow path of our jlinenative-loading logic, that we try to avoid when we can.
  void loadSlow() {
    Path tmpLocation = millJLineNativeLibLocation
        .getParent()
        .resolve(millJLineNativeLibLocation.getFileName().toString() + "-" + UUID.randomUUID());
    try {
      Files.createDirectories(millJLineNativeLibLocation.getParent());
      try (InputStream is = Thread.currentThread()
              .getContextClassLoader()
              .getResourceAsStream(jlineNativeLibResourcePath());
          OutputStream os = Files.newOutputStream(tmpLocation)) {
        is.transferTo(os);
      }
      // Concurrent Mill processes might try to create millJLineNativeLibLocation too, so we ignore
      // errors if the file has been written by another process in the mean time.
      // Also, we move it atomically to its final location, so that if another Mill process finds
      // it, it can use it fine straightaway.
      if (!Files.exists(millJLineNativeLibLocation))
        try {
          Files.move(tmpLocation, millJLineNativeLibLocation, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException ex) {
          // Ignored, file should have been created by another Mill process
        } catch (AtomicMoveNotSupportedException ex) {
          try {
            Files.move(tmpLocation, millJLineNativeLibLocation);
          } catch (FileAlreadyExistsException ex0) {
            // Ignored, file should have been created by another Mill process
          }
        }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    } finally {
      try {
        Files.deleteIfExists(tmpLocation);
      } catch (IOException ex) {
        // ignored
      }
    }
  }

  private static boolean initialized = Runtime.version().feature() >= 22;

  static void initJLineNative() {
    if (!initialized) doInitJLineNative();
  }

  private static synchronized void doInitJLineNative() {
    if (initialized) return;

    JLineNativeLoader loader =
        new JLineNativeLoader(mill.runner.client.Versions.jlineNativeVersion());
    if (!loader.tryLoadFast()) loader.loadSlow();

    // In theory, this should be enough for org.jline.nativ.JLineNativeLoader
    // to use the JAR we cached ourselves.
    // But org.jline.nativ.JLineNativeLoader.initialize() also starts a "clean-up" thread,
    // which slows things down too apparently. So we keep using reflection, so that
    // org.jline.nativ.JLineNativeLoader.initialize() doesn't try to do anything.
    System.setProperty("library.jline.path", loader.millJLineNativeDir.toString());
    System.setProperty(
        "library.jline.name", loader.millJLineNativeLibLocation.getFileName().toString());

    System.load(loader.millJLineNativeLibLocation.toString());

    Class cls = org.jline.nativ.JLineNativeLoader.class;
    java.lang.reflect.Field fld;
    try {
      fld = cls.getDeclaredField("loaded");
    } catch (NoSuchFieldException ex) {
      throw new RuntimeException(ex);
    }
    fld.setAccessible(true);
    try {
      fld.set(null, Boolean.TRUE);
    } catch (IllegalAccessException ex) {
      throw new RuntimeException(ex);
    }

    initialized = true;
  }
}
