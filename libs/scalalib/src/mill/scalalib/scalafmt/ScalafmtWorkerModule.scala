package mill.scalalib.scalafmt

import mill.*
import mill.api.{PathRef, Task, *}
import mill.scalalib.*
import mill.util.Jvm

object ScalafmtWorkerModule extends ExternalModule with JavaModule {

  /**
   * Classpath for running Scalafmt
   */
  @deprecated("Unused", "Mill after 1.1.6")
  def scalafmtClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.scalameta:scalafmt-dynamic_2.13:${mill.javalib.api.Versions.scalafmtVersion}")
    )
  }

  @deprecated("Unused", "Mill after 1.1.6")
  def scalafmtClassLoader: Worker[java.net.URLClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(scalafmtClasspath().map(_.path))
  }

  /**
   * CLasspath used to load the scalafmt worker, which itself depends on scalafmt-dynamic
   */
  def scalafmtWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-scalalib-scalafmt-worker")))
  }

  private def scalafmtWorkerClassLoader: Worker[ClassLoader & AutoCloseable] = Task.Worker {
    Jvm.createClassLoader(
      classPath = scalafmtWorkerClasspath().map(_.path),
      parent = getClass().getClassLoader()
    )
  }

  def worker: Worker[ScalafmtWorker] = Task.Worker {
    val cls = scalafmtWorkerClassLoader().loadClass(
      getClass().getPackageName + ".worker.ScalafmtWorkerImpl"
    )
    cls.getConstructor().newInstance().asInstanceOf[ScalafmtWorker]
  }

  lazy val millDiscover = Discover[this.type]
}
