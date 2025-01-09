package mill.runner.client
import coursier.cache.{ArchiveCache, FileCache}
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.util.Task
import coursier.Resolve

object CoursierClient {
  def resolveJavaHome(id: String,
                      jvmIndexVersion: String = "latest.release"): java.io.File = {
    val coursierCache0 = FileCache[Task]()
    val jvmCache = JvmCache()
      .withArchiveCache(
        ArchiveCache().withCache(coursierCache0)
      )
      .withIndex(jvmIndex0(jvmIndexVersion))
    val javaHome = JavaHome()
      .withCache(jvmCache)
    javaHome.get(id).unsafeRun()(coursierCache0.ec)
  }


  def jvmIndex0(
                 jvmIndexVersion: String = "latest.release"
               ): Task[JvmIndex] = {
    val coursierCache0 = FileCache[Task]()
    JvmIndex.load(
      cache = coursierCache0, // the coursier.cache.Cache instance to use
      repositories = Resolve().repositories, // repositories to use
      indexChannel = JvmChannel.module(
        JvmChannel.centralModule(),
        version = jvmIndexVersion
      ) // use new indices published to Maven Central
    )
  }
}
