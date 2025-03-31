package mill.runner.client;

import io.github.alexarchambault.nativeterm.NativeTerminal;
import org.fusesource.jansi.internal.OSInfo;

import java.io.File;
import java.io.IOException;

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

  JansiLoader(String jansiVersion) {
    this.jansiVersion = jansiVersion;
  }

  private String jansiLibPathInArchive() {
    return "org/fusesource/jansi/internal/native/" + OSInfo.getNativeLibFolderPathForCurrentOS()
        + "/"
        // Replacing '.dylib' by '.jnilib' is necessary, as jansi uses the latter extension on macOS,
        // rather than '.dylib', which is the default. The call to replace has no effect on other
        // platforms.
        + System.mapLibraryName("jansi").replace(".dylib", ".jnilib");
  }

  File tryLoadFast() {
    File archiveCacheLocation;
    try {
      archiveCacheLocation = coursier.paths.CachePath.defaultArchiveCacheDirectory();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    File jansiLib = new File(
        archiveCacheLocation,
        "https/repo1.maven.org/maven2/org/fusesource/jansi/jansi/" + jansiVersion + "/jansi-"
            + jansiVersion + ".jar/" + jansiLibPathInArchive());
    return jansiLib.exists() ? jansiLib : null;
  }

  // If the jansi native library isn't in cache (tryLoadFast returns null), loadSlow
  // downloads it using coursier, which is more heavyweight.
  // That's the slow path of our jansi-loading logic, that we try to avoid when we can.
  File loadSlow() {
    // coursierapi.Logger.progressBars actually falls back to non-ANSI logging when running
    // without a terminal
    coursierapi.Cache cache =
        coursierapi.Cache.create().withLogger(coursierapi.Logger.progressBars());
    coursierapi.ArchiveCache archiveCache = coursierapi.ArchiveCache.create().withCache(cache);
    File jansiDir = archiveCache.get(
        coursierapi.Artifact.of("https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/"
            + jansiVersion + "/jansi-" + jansiVersion + ".jar"));
    // Should have the exact same value as jansiLib computed in tryLoadFast.
    // That way, tryLoadFast finds this file first upon the next Mill startup.
    return new File(jansiDir, jansiLibPathInArchive());
  }

  private static boolean initialized = false;

  static void initJansi() {
    if (!initialized)
      doInitJansi();
  }

  private static synchronized void doInitJansi() {
    if (initialized)
      return;

    JansiLoader jansiLoader = new JansiLoader(mill.runner.client.Versions.jansiVersion());
    File jansiLib = jansiLoader.tryLoadFast();
    if (jansiLib == null) jansiLib = jansiLoader.loadSlow();

    // We have the jansi native library, we proceed to load it.
    System.load(jansiLib.getAbsolutePath());

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
