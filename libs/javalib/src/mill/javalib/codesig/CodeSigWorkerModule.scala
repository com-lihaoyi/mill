package mill.javalib.codesig

import mainargs.Flag
import mill.{Command, T, Task}
import mill.api.{PathRef, Discover, ExternalModule}
import mill.javalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

/**
 * Trait for modules that provide a CodeSig worker for computing bytecode-level
 * method signatures. Used by testQuick for fine-grained change detection.
 */
trait CodeSigWorkerModule extends CoursierModule with OfflineSupportModule {

  def codesigWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-codesig-worker")
    ))
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++
        codesigWorkerClasspath()
    ).distinct
  }

  private def codesigWorkerClassloader: Task.Worker[ClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = codesigWorkerClasspath().map(_.path),
      parent = getClass().getClassLoader()
    )
  }

  def codesigWorker: Task.Worker[CodeSigWorkerApi] = Task.Worker {
    codesigWorkerClassloader()
      .loadClass("mill.javalib.codesig.CodeSigWorker")
      .getConstructor().newInstance().asInstanceOf[CodeSigWorkerApi]
  }
}

object CodeSigWorkerModule extends ExternalModule with CodeSigWorkerModule {
  override def millDiscover: Discover = Discover[this.type]
}
