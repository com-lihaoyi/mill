package mill
package scalalib

import mill.define.{Command, ExternalModule, Target, Task}
import mill.api.PathRef
import mill.main.Tasks
import mill.scalalib.publish.{Artifact, SonatypePublisher}

/**
  * Configuration necessary for publishing a Scala module to Maven Central or similar
  */
trait PublishModule extends JavaModule { outer =>
  import mill.scalalib.publish._

  override def moduleDeps: Seq[PublishModule] = Seq.empty[PublishModule]

  def pomSettings: T[PomSettings]
  def publishVersion: T[String]

  def publishSelfDependency: Target[Artifact] = T {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def publishXmlDeps: Task[Agg[Dependency]] = T.task {
    val ivyPomDeps = ivyDeps().map(resolvePublishDependency().apply(_))

    val compileIvyPomDeps = compileIvyDeps()
      .map(resolvePublishDependency().apply(_))
      .filter(!ivyPomDeps.contains(_))
      .map(_.copy(scope = Scope.Provided))

    val modulePomDeps = T.sequence(moduleDeps.map(_.publishSelfDependency))()
    val compileModulePomDeps = T.sequence(compileModuleDeps.collect {
      case m: PublishModule => m.publishSelfDependency
    })()

    ivyPomDeps ++ compileIvyPomDeps ++
      modulePomDeps.map(Dependency(_, Scope.Compile)) ++
      compileModulePomDeps.map(Dependency(_, Scope.Provided))
  }

  def pom: Target[PathRef] = T {
    val pom = Pom(artifactMetadata(), publishXmlDeps(), artifactId(), pomSettings())
    val pomPath = T.dest / s"${artifactId()}-${publishVersion()}.pom"
    os.write.over(pomPath, pom)
    PathRef(pomPath)
  }

  def ivy: Target[PathRef] = T {
    val ivy = Ivy(artifactMetadata(), publishXmlDeps(), extraPublish())
    val ivyPath = T.dest / "ivy.xml"
    os.write.over(ivyPath, ivy)
    PathRef(ivyPath)
  }

  def artifactMetadata: Target[Artifact] = T {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  /**
    * Extra artifacts to publish.
    */
  def extraPublish: Target[Seq[PublishInfo]] = T{ Seq.empty[PublishInfo] }

  /**
    * Publish artifacts to a local ivy repository.
    * @param localIvyRepo The local ivy repository.
    *                     If not defines, defaults to `$HOME/.ivy2/local`
    */
  def publishLocal(localIvyRepo: String = null): define.Command[Unit] = T.command {
    val publisher = localIvyRepo match {
      case null => LocalIvyPublisher
      case repo => new LocalIvyPublisher(os.Path(repo))
    }

    publisher.publish(
      jar = jar().path,
      sourcesJar = sourceJar().path,
      docJar = docJar().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifactMetadata(),
      extras = extraPublish()
    )
  }

  /**
    * Publish artifacts to a local Maven repository.
    * @param m2RepoPath The path to the local repository  as string (default: `$HOME/.m2repository`).
    * @return [[PathRef]]s to published files.
    */
  def publishM2Local(m2RepoPath: String = (os.home / ".m2" / "repository").toString()): Command[Seq[PathRef]] = T.command {
    val path = os.Path(m2RepoPath, os.pwd)
    new LocalM2Publisher(path)
      .publish(
        jar = jar().path,
        sourcesJar = sourceJar().path,
        docJar = docJar().path,
        pom = pom().path,
        artifact = artifactMetadata(),
        extras = extraPublish()
      ).map(PathRef(_))
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
      ) ++ extraPublish().map(p => (p.file, s"$baseName${p.classifierPart}.${p.ext}"))
    )
  }

  /**
    * Publish all given artifacts to Sonatype.
    * @param gpgArgs GPG arguments. Defaults to `--batch --yes -a -b`.
    *                 Specifying this will override/remove the defaults. Add the default args to your args to keep them.
    */
  def publish(sonatypeCreds: String,
              signed: Boolean = true,
              gpgArgs: Seq[String] = PublishModule.defaultGpgArgs,
              release: Boolean = false,
              readTimeout: Int = 60000,
              connectTimeout: Int = 5000,
              awaitTimeout: Int = 120 * 1000,
              stagingRelease: Boolean = true): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      sonatypeCreds,
      signed,
      gpgArgs,
      readTimeout,
      connectTimeout,
      T.log,
      awaitTimeout,
      stagingRelease
    ).publish(artifacts.map{case (a, b) => (a.path, b)}, artifactInfo, release)
  }
}

object PublishModule extends ExternalModule {
  val defaultGpgArgs = Seq("--batch", "--yes", "-a", "-b")

  case class PublishData(meta: Artifact, payload: Seq[(PathRef, String)])
  object PublishData{
    implicit def jsonify: upickle.default.ReadWriter[PublishData] = upickle.default.macroRW
  }

  /**
    * Publish all given artifacts to Sonatype.
    * @param gpgArgs GPG arguments. Defaults to `--batch --yes -a -b`.
    *                Specifying this will override/remove the defaults. Add the default args to your args to keep them.
    */
  def publishAll(publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
                 sonatypeCreds: String,
                 signed: Boolean = true,
                 gpgArgs: Seq[String] = defaultGpgArgs,
                 release: Boolean = false,
                 sonatypeUri: String = "https://oss.sonatype.org/service/local",
                 sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots",
                 readTimeout: Int = 60000,
                 connectTimeout: Int = 5000,
                 awaitTimeout: Int = 120 * 1000,
                 stagingRelease: Boolean = true): Command[Unit] = T.command {
    val x: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map{
      case PublishModule.PublishData(a, s) => (s.map{case (p, f) => (p.path, f)}, a)
    }
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      sonatypeCreds,
      signed,
      gpgArgs,
      readTimeout,
      connectTimeout,
      T.log,
      awaitTimeout,
      stagingRelease
    ).publishAll(
      release,
      x:_*
    )
  }

  implicit def millScoptTargetReads[T]: scopt.Read[Tasks[T]] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
