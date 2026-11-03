package mill.javalib

import mill.T
import mill.api.{PathRef, Task}

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
// TODO: extend OfflineSupportModule once we can break bin-compat
trait JavaHomeModule extends CoursierModule {

  @deprecated("Please use `jvmVersion` instead", "Mill after 1.1.0-RC3")
  def jvmId: T[String] = ""
  def jvmVersion: T[String] = jvmId()

  /**
   * Version of the Coursier JVM index used to resolve [[jvmVersion]].
   *
   * Either a version of the JVM index published on Maven Central (such as `0.0.5`
   * or `latest.release`), or `github` to use the latest JVM index from
   * https://github.com/coursier/jvm-index directly
   */
  def jvmIndexVersion: T[String] = mill.javalib.api.Versions.coursierJvmIndexVersion

  def useShortJvmPath(jvmVersion: String): Boolean =
    scala.util.Properties.isWin && (jvmVersion.startsWith("graalvm") || jvmVersion.startsWith(
      "liberica-nik"
    ))

  /**
   * Optional custom Java Home for the JvmWorker to use
   *
   * If this value is None, then the JvmWorker uses the same Java used to run
   * the current mill instance.
   */
  def javaHome: T[Option[PathRef]] = Task {
    Option(jvmVersion()).filter(_ != "").map { id =>
      val path = mill.util.Jvm.resolveJavaHome(
        id = id,
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(Task.ctx()),
        jvmIndexVersion = jvmIndexVersion(),
        useShortPaths = useShortJvmPath(id),
        config = coursierConfigModule().coursierConfig()
      ).get
      // Java home is externally managed, better revalidate it at least once
      PathRef(path, quick = true).withRevalidateOnce
    }
  }

  // TODO: add once we can break bin-compat
  //  override def prepareOffline(all: Flag): Task.Command[Seq[PathRef]] = Task.Command {
  //    (super.prepareOffline(all)() ++ javaHome().toSeq).distinct
  //  }
}
