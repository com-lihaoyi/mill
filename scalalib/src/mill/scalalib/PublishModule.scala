package mill
package scalalib

import mill.define.{ExternalModule, Task}
import mill.eval.PathRef
import mill.scalalib.publish.{Artifact, SonatypePublisher}

/**
  * Configuration necessary for publishing a Scala module to Maven Central or similar
  */
trait PublishModule extends JavaModule { outer =>
  import mill.scalalib.publish._

  override def moduleDeps = Seq.empty[PublishModule]

  def pomSettings: T[PomSettings]
  def publishVersion: T[String]

  def publishSelfDependency = T {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def publishXmlDeps = T.task {
    val ivyPomDeps = ivyDeps().map(resolvePublishDependency().apply(_))
    val modulePomDeps = Task.sequence(moduleDeps.map(_.publishSelfDependency))()
    ivyPomDeps ++ modulePomDeps.map(Dependency(_, Scope.Compile))
  }
  def pom = T {
    val pom = Pom(artifactMetadata(), publishXmlDeps(), artifactId(), pomSettings())
    val pomPath = T.ctx().dest / s"${artifactId()}-${publishVersion()}.pom"
    os.write.over(pomPath, pom)
    PathRef(pomPath)
  }

  def ivy = T {
    val ivy = Ivy(artifactMetadata(), publishXmlDeps())
    val ivyPath = T.ctx().dest / "ivy.xml"
    os.write.over(ivyPath, ivy)
    PathRef(ivyPath)
  }

  def artifactMetadata: T[Artifact] = T {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def publishLocal(): define.Command[Unit] = T.command {
    LocalPublisher.publish(
      jar = jar().path,
      sourcesJar = sourceJar().path,
      docJar = docJar().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifactMetadata()
    )
  }

  def sonatypeUri: String = "https://oss.sonatype.org/service/local"

  def sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots"

  def publishArtifacts = T {
    val baseName = s"${artifactId()}-${publishVersion()}"
    PublishModule.PublishData(
      artifactMetadata(),
      Seq(
        jar() -> s"$baseName.jar",
        sourceJar() -> s"$baseName-sources.jar",
        docJar() -> s"$baseName-javadoc.jar",
        pom() -> s"$baseName.pom"
      )
    )
  }

  def publish(sonatypeCreds: String,
              gpgPassphrase: String = null,
              signed: Boolean = true,
              release: Boolean): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      sonatypeCreds,
      Option(gpgPassphrase),
      signed,
      T.ctx().log
    ).publish(artifacts.map{case (a, b) => (a.path, b)}, artifactInfo, release)
  }
}

object PublishModule extends ExternalModule {
  case class PublishData(meta: Artifact, payload: Seq[(PathRef, String)])

  object PublishData{
    implicit def jsonify: upickle.default.ReadWriter[PublishData] = upickle.default.macroRW
  }

  def publishAll(sonatypeCreds: String,
                 gpgPassphrase: String = null,
                 publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
                 release: Boolean = false,
                 sonatypeUri: String = "https://oss.sonatype.org/service/local",
                 sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots",
                 signed: Boolean = true) = T.command {

    val x: Seq[(Seq[(os.Path, String)], Artifact)] = Task.sequence(publishArtifacts.value)().map{
      case PublishModule.PublishData(a, s) => (s.map{case (p, f) => (p.path, f)}, a)
    }
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      sonatypeCreds,
      Option(gpgPassphrase),
      signed,
      T.ctx().log
    ).publishAll(
      release,
      x:_*
    )
  }

  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
