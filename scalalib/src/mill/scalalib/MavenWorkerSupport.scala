package mill.scalalib

import mill._
import mill.javalib.publish.{Artifact, PublishInfo}
import mill.util.Jvm

private[mill] trait MavenWorkerSupport extends CoursierModule {
  private def mavenWorkerClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().classpath(Agg(
      Dep.millProjectModule("mill-scalalib-maven-worker")
    ))
  }

  private def mavenWorkerClassloader: Worker[ClassLoader] = Task.Worker {
    val classPath = mavenWorkerClasspath().map(_.path)
    Jvm.createClassLoader(classPath = classPath.indexed, parent = getClass.getClassLoader)
  }

  protected def mavenWorker: Worker[MavenWorkerSupport.Api] = Task.Worker {
    mavenWorkerClassloader().loadClass("mill.scalalib.maven.worker.impl.WorkerImpl")
      .getConstructor().newInstance().asInstanceOf[MavenWorkerSupport.Api]
  }
}
object MavenWorkerSupport {
  trait Api {

    /** Publishes artifacts to a remote Maven repository. */
    def publishToRemote(
        uri: String,
        workspace: os.Path,
        username: String,
        password: String,
        artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
    ): RemoteM2Publisher.DeployResult
  }

  object RemoteM2Publisher {
    def asM2Artifacts(
        pom: os.Path,
        artifact: Artifact,
        publishInfos: IterableOnce[PublishInfo]
    ): List[M2Artifact] =
      M2Artifact.POM(
        pom,
        artifact
      ) +: publishInfos.iterator.map(M2Artifact.Default(_, artifact)).toList

    sealed trait M2Artifact
    object M2Artifact {
      case class Default(info: PublishInfo, artifact: Artifact) extends M2Artifact
      case class POM(pom: os.Path, artifact: Artifact) extends M2Artifact
    }

    case class DeployResult(
        // The classes which Maven returns aren't loaded here, thus we'd need to perform a translation, but we do not use
        // the result for anything else but logging, thus we just convert them to strings on the worker side.
        artifacts: Vector[String],
        // Same
        metadatas: Vector[String]
    ) {
      override def toString: String =
        s"""DeployResult(
           |  artifacts = ${artifacts.mkString("[\n    ", "\n    ", "\n  ]")}
           |  metadatas = ${metadatas.mkString("[\n    ", "\n    ", "\n  ]")}
           |)
           |""".stripMargin
    }
  }
}
