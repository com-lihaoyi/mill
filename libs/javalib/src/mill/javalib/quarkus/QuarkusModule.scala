package mill.javalib.quarkus

import coursier.core.VariantSelector.ConfigurationBased
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

  def quarkusDependencies: Task[Seq[ApplicationModelWorker.Dependency]] = Task.Anon {
    val dep = coursierDependencyTask().withVariantSelector(
      ConfigurationBased(coursier.core.Configuration.runtime)
    )

    val resolution = millResolver().artifacts(Seq(mill.javalib.BoundDep(dep, force = false)))

    resolution.detailedArtifacts0.map {
      case (dependency, _, _, file) =>
        ApplicationModelWorker.Dependency(
          dependency.module.organization.value,
          dependency.module.name.value,
          dependency.versionConstraint.asString,
          os.Path(file)
        )
    }
  }

  def quarkusSerializedAppModel: T[PathRef] = this match {
    case m: PublishModule => Task {
        quarkusApplicationModelWorker().bootstrapQuarkus(
          ApplicationModelWorker.AppModel(
            moduleDir,
            m.pomSettings().organization,
            m.artifactId(),
            m.publishVersion(),
            m.sources().head.path, // TODO support multiple
            m.resources().head.path,
            m.compile().classes.path,
            m.compileResources().head.path, // TODO this is wrong, adjust later,
            bomMvnDeps().map(_.formatted),
            quarkusDependencies()
          ),
          Task.dest
        )
        PathRef(Task.dest)
      }
    case _ => Task {
        quarkusApplicationModelWorker().bootstrapQuarkus(
          ApplicationModelWorker.AppModel(
            moduleDir,
            "unspecified", // todo add organisation in quarkus module
            artifactId(),
            "unspecified",
            sources().head.path, // TODO support multiple
            resources().head.path,
            compile().classes.path,
            compileResources().head.path, // TODO this is wrong, adjust later,
            bomMvnDeps().map(_.formatted),
            quarkusDependencies()
          ),
          Task.dest
        )
        PathRef(Task.dest)
      }
  }

}
