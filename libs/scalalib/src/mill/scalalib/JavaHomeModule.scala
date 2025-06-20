package mill.scalalib

import mill.T
import mill.define.{Module, ModuleRef, PathRef, Task}

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
trait JavaHomeModule extends CoursierModule {
  def jvmId: T[String] = ""

  def jvmIndexVersion: T[String] = mill.scalalib.api.Versions.coursierJvmIndexVersion

  def useShortJvmPath(jvmId: String): Boolean =
    scala.util.Properties.isWin && (jvmId.startsWith("graalvm") || jvmId.startsWith("liberica-nik"))

  /**
   * Optional custom Java Home for the JvmWorker to use
   *
   * If this value is None, then the JvmWorker uses the same Java used to run
   * the current mill instance.
   */
  def javaHome: T[Option[PathRef]] = Task {
    Option(jvmId()).filter(_ != "").map { id =>
      val path = mill.util.Jvm.resolveJavaHome(
        id = id,
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(Task.ctx()),
        jvmIndexVersion = jvmIndexVersion(),
        useShortPaths = useShortJvmPath(id),
        config = CoursierConfigModule.coursierConfig()
      ).get
      // Java home is externally managed, better revalidate it at least once
      PathRef(path, quick = true).withRevalidateOnce
    }
  }
}
