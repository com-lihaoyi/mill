package mill.launcher

import org.jline.nativ.OSInfo

import java.util.UUID

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
private[launcher] class JLineNativeLoader(jlineNativeVersion: String) {
  private val isWindows =
    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")

  private val baseDir: os.Path = {
    if (isWindows) os.Path(System.getenv("UserProfile")) / ".cache" / "mill"
    else {
      val xdgCacheHome = System.getenv("XDG_CACHE_HOME")
      val cacheBase =
        if (xdgCacheHome == null) os.home / ".cache"
        else os.Path(xdgCacheHome)
      cacheBase / "mill"
    }
  }

  val millJLineNativeDir: os.Path = baseDir / "jline" / jlineNativeVersion

  val millJLineNativeLibLocation: os.Path = {
    val libName = System.mapLibraryName("jlinenative").replace(".dylib", ".jnilib")
    millJLineNativeDir / os.SubPath(OSInfo.getNativeLibFolderPathForCurrentOS) / libName
  }

  def tryLoadFast: Boolean = os.exists(millJLineNativeLibLocation)

  // If the jlinenative native library isn't in cache (tryLoadFast returns false), loadSlow
  // reads it from the resources and writes it on disk, which is more heavyweight.
  // That's the slow path of our jlinenative-loading logic, that we try to avoid when we can.
  def loadSlow(): Unit = {
    val libName = System.mapLibraryName("jlinenative").replace(".dylib", ".jnilib")
    val resourcePath = s"org/jline/nativ/${OSInfo.getNativeLibFolderPathForCurrentOS}/$libName"

    val tmpLocation =
      millJLineNativeLibLocation / os.up / s"${millJLineNativeLibLocation.last}-${UUID.randomUUID()}"

    try {
      os.makeDir.all(millJLineNativeLibLocation / os.up)

      val is = Thread.currentThread().getContextClassLoader.getResourceAsStream(resourcePath)

      try os.write(tmpLocation, is)
      finally is.close()

      // Concurrent Mill processes might try to create millJLineNativeLibLocation too, so we ignore
      // errors if the file has been written by another process in the mean time.
      // Also, we move it atomically to its final location, so that if another Mill process finds
      // it, it can use it fine straightaway.
      if (!os.exists(millJLineNativeLibLocation)) {
        try {
          os.move(tmpLocation, millJLineNativeLibLocation, atomicMove = true)
        } catch {
          case _: java.nio.file.FileAlreadyExistsException =>
          // Ignored, file should have been created by another Mill process
          case e: java.nio.file.AccessDeniedException =>
            if (!os.exists(millJLineNativeLibLocation)) throw new RuntimeException(e)
          // else ignored, file should have been created by another Mill process
          case _: java.nio.file.AtomicMoveNotSupportedException =>
            try {
              os.move(tmpLocation, millJLineNativeLibLocation)
            } catch {
              case _: java.nio.file.FileAlreadyExistsException =>
              // Ignored, file should have been created by another Mill process
            }
        }
      }
    } finally {
      try os.remove(tmpLocation)
      catch { case _: Exception => }
    }
  }
}

object JLineNativeLoader {
  @volatile private var initialized = false

  def initJLineNative(): Unit = {
    if (!initialized) doInitJLineNative()
  }

  private def doInitJLineNative(): Unit = synchronized {
    if (initialized) return

    val loader = new JLineNativeLoader(mill.client.Versions.jlineNativeVersion)
    if (!loader.tryLoadFast) loader.loadSlow()

    // In theory, this should be enough for org.jline.nativ.JLineNativeLoader
    // to use the JAR we cached ourselves.
    // But org.jline.nativ.JLineNativeLoader.initialize() also starts a "clean-up" thread,
    // which slows things down too apparently. So we keep using reflection, so that
    // org.jline.nativ.JLineNativeLoader.initialize() doesn't try to do anything.
    System.setProperty("library.jline.path", loader.millJLineNativeDir.toString)
    System.setProperty("library.jline.name", loader.millJLineNativeLibLocation.last)

    System.load(loader.millJLineNativeLibLocation.toString)

    val cls = classOf[org.jline.nativ.JLineNativeLoader]
    val fld = cls.getDeclaredField("loaded")
    fld.setAccessible(true)
    fld.set(null, java.lang.Boolean.TRUE)

    initialized = true
  }
}
