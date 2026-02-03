package mill.javalib.quarkus

import mill.api.PathRef
import mill.{T, Task}
import mill.javalib.{Dep, DepSyntax, JavaModule, PublishModule}
import mill.util.Jvm

import java.net.URLClassLoader

trait QuarkusModule extends JavaModule {

  def quarkusBootstrapDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"io.quarkus:quarkus-bootstrap-core",
      mvn"io.quarkus:quarkus-bootstrap-app-model",
      mvn"io.quarkus:quarkus-bootstrap-maven-resolver"
    )
  }

  def quarkusApplicationModelWorkerResolvedDeps: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      quarkusBootstrapDeps() ++ Seq(Dep.millProjectModule("mill-libs-javalib-quarkus")),
      boms = allBomDeps()
    )
  }

  def quarkusApplicationModelWorkerClassloader: Task.Worker[URLClassLoader] = Task.Worker {

    val classpath = defaultResolver().classpath(
      quarkusBootstrapDeps() ++ Seq(Dep.millProjectModule("mill-libs-javalib-quarkus")),
      boms = allBomDeps()
    )

    Jvm.createClassLoader(classpath.map(_.path), parent = getClass.getClassLoader)
  }

  def quarkusApplicationModelWorker: Task.Worker[ApplicationModelWorker] = Task.Worker {
    quarkusApplicationModelWorkerClassloader().loadClass(
      "mill.javalib.quarkus.ApplicationModelWorkerImpl"
    )
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[ApplicationModelWorker]
  }

  def quarkusSerializedAppModel: T[PathRef] = this match {
    case m: PublishModule => Task {
        quarkusApplicationModelWorker().bootstrapQuarkus(
          moduleDir,
          m.pomSettings().organization,
          m.artifactName(),
          m.publishVersion(),
          m.jar().path,
          Task.dest
        )
        PathRef(Task.dest)
      }
    case _ => Task {
        quarkusApplicationModelWorker().bootstrapQuarkus(
          moduleDir,
          "unspecified",
          artifactName(),
          "unspecified",
          jar().path,
          Task.dest
        )
        PathRef(Task.dest)
      }
  }

}
