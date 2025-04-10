package mill.runner.client
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.util.Task
import coursier.Resolve

object CoursierClient {
  def resolveJavaHome(id: String): java.io.File = {
    val coursierCache0 = FileCache[Task]()
      .withLogger(coursier.cache.loggers.RefreshLogger.create())
    val archiveCache = ArchiveCache().withCache(coursierCache0)
    val jvmCache = JvmCache()
      .withArchiveCache(archiveCache)
      .withIndex(
        JvmIndex.load(
          cache = coursierCache0,
          repositories = Resolve().repositories,
          indexChannel = JvmChannel.module(
            JvmChannel.centralModule(),
            version = mill.runner.client.Versions.coursierJvmIndexVersion
          )
        )
      )

    val javaHome = JavaHome().withCache(jvmCache)
      // when given a version like "17", always pick highest version in the index
      // rather than the highest already on disk
      .withUpdate(true)

    pprint.err.log(archiveCache.location)
    pprint.err.log(id)
    javaHome.get(id).unsafeRun()(coursierCache0.ec)
  }
}
