package mill.runner.client;

import org.fusesource.jansi.internal.OSInfo;

import java.io.File;
import java.io.IOException;

final class JansiLoader {

  private String jansiVersion;

  JansiLoader(String jansiVersion) {
    this.jansiVersion = jansiVersion;
  }

  private String jansiLibPathInArchive() {
    return "org/fusesource/jansi/internal/native/" + OSInfo.getNativeLibFolderPathForCurrentOS() + "/"
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

}
