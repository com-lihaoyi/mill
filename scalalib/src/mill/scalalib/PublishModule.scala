package mill
package scalalib

import ammonite.ops._
import mill.define.{ExternalModule, Task}
import mill.eval.{PathRef, Result}
import mill.scalalib.publish.SonatypePublisher
import mill.util.Loose.Agg
/**
  * Configuration necessary for publishing a Scala module to Maven Central or similar
  */
trait PublishModule extends ScalaModule { outer =>
  import mill.scalalib.publish._

  override def moduleDeps = Seq.empty[PublishModule]

  def pomSettings: T[PomSettings]
  def publishVersion: T[String]
  def artifactId: T[String] = T { s"${artifactName()}${artifactSuffix()}" }
  def publishSelfDependency = T{
    Artifact(pomSettings().organization, artifactId(), publishVersion()),
  }

  def publishXmlDeps = T.task{
    val ivyPomDeps = ivyDeps().map(
      Artifact.fromDep(_, scalaVersion(), Lib.scalaBinaryVersion(scalaVersion()))
    )
    val modulePomDeps = Task.sequence(moduleDeps.map(_.publishSelfDependency))()
    ivyPomDeps ++ modulePomDeps.map(Dependency(_, Scope.Compile))
  }
  def pom = T {
    val pom = Pom(artifact(), publishXmlDeps(), artifactId(), pomSettings())
    val pomPath = T.ctx().dest / s"${artifactId()}-${publishVersion()}.pom"
    write.over(pomPath, pom)
    PathRef(pomPath)
  }

  def ivy = T {
    val ivy = Ivy(artifact(), publishXmlDeps())
    val ivyPath = T.ctx().dest / "ivy.xml"
    write.over(ivyPath, ivy)
    PathRef(ivyPath)
  }

  def artifact: T[Artifact] = T {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def publishLocal(): define.Command[Unit] = T.command {
    LocalPublisher.publish(
      jar = jar().path,
      sourcesJar = sourcesJar().path,
      docsJar = docsJar().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifact()
    )
  }

  def sonatypeUri: String = "https://oss.sonatype.org/service/local"

  def sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots"

  def publishArtifacts = T{
    val baseName = s"${artifactId()}-${publishVersion()}"
    Seq(
      jar().path -> s"$baseName.jar",
      sourcesJar().path -> s"$baseName-sources.jar",
      docsJar().path -> s"$baseName-javadoc.jar",
      pom().path -> s"$baseName.pom"
    )
  }

  def publish(sonatypeCreds: String, gpgPassphrase: String): define.Command[Unit] = T.command {
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      sonatypeCreds,
      gpgPassphrase,
      T.ctx().log
    ).publish(publishArtifacts(), artifact())
  }
}
object PublishModule extends ExternalModule{
  def publishAll(sonatypeCreds: String,
                 gpgPassphrase: String,
                 modules: Seq[PublishModule],
                 sonatypeUri: String = "https://oss.sonatype.org/service/local",
                 sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots") = T.task{
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      sonatypeCreds,
      gpgPassphrase,
      T.ctx().log
    ).publishAll(
      Task.traverse(modules)(
        m => T.task{(m.publishArtifacts(), m.artifact())}
      )():_*
    )
  }
  def millDiscover = mill.define.Discover[this.type]
}