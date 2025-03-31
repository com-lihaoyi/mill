package mill.runner.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import org.fusesource.jansi.internal.OSInfo;

/**
 * Helper to load the jansi native library before jansi itself attempts to do so.
 *
 * We load it from a location that doesn't change. That way, if the library is already
 * there, we can load it straightaway. That's the "fast path".
 *
 * If the library isn't there already, we write it there first. That's the "slow path".
 * This should need to run only once on the user's machine. Once the library is there,
 * we can go through the fast path above every time.
 *
 * If we don't do that, jansi loads its library on its own, and always does things slowly,
 * by writing its library in a new temporary location upon each run.
 */
final class JansiLoader {

  private String jansiVersion;
  private Path millJansiLibLocation;

  JansiLoader(String jansiVersion) {
    this.jansiVersion = jansiVersion;
    boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    Path baseDir;
    if (isWindows)
      baseDir = Paths.get(System.getenv("UserProfile")).resolve(".mill/cache/");
    else
      baseDir = Paths.get(System.getProperty("user.home")).resolve(".cache/mill/");
    this.millJansiLibLocation = baseDir.resolve("jansi-" + jansiVersion + "/" + System.mapLibraryName("jansi"));
  }

  private String jansiLibResourcePath() {
    return "org/fusesource/jansi/internal/native/" + OSInfo.getNativeLibFolderPathForCurrentOS()
        + "/"
        // Replacing '.dylib' by '.jnilib' is necessary, as jansi uses the latter extension on
        // macOS,
        // rather than '.dylib', which is the default. The call to replace has no effect on other
        // platforms.
        + System.mapLibraryName("jansi").replace(".dylib", ".jnilib");
  }

  Path tryLoadFast() {
    return Files.exists(millJansiLibLocation) ? millJansiLibLocation : null;
  }

  // If the jansi native library isn't in cache (tryLoadFast returns null), loadSlow
  // reads it from the resources and writes it on disk, which is more heavyweight.
  // That's the slow path of our jansi-loading logic, that we try to avoid when we can.
  Path loadSlow() {
    try {
      Files.createDirectories(millJansiLibLocation.getParent());
      try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(jansiLibResourcePath());
        OutputStream os = Files.newOutputStream(millJansiLibLocation)) {
        is.transferTo(os);
      }
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    return millJansiLibLocation;
  }

  private static boolean initialized = false;

  static void initJansi() {
    if (!initialized) doInitJansi();
  }

  private static synchronized void doInitJansi() {
    if (initialized) return;

    JansiLoader jansiLoader = new JansiLoader(mill.runner.client.Versions.jansiVersion());
    Path jansiLib = jansiLoader.tryLoadFast();
    if (jansiLib == null) jansiLib = jansiLoader.loadSlow();

    // We have the jansi native library, we proceed to load it.
    System.load(jansiLib.toString());

    initialized = true;

    // Tell jansi not to attempt to load a native library on its own
    Class cls = org.fusesource.jansi.internal.JansiLoader.class;
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
  }
}
