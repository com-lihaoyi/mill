package mill
package scalalib

import com.lumidion.sonatype.central.client.core.SonatypeCredentials
import mill.define.{Command, ExternalModule, Target, Task}
import mill.api.{JarManifest, PathRef, Result}
import mill.scalalib.PublishModule.{checkSonatypeCentralCreds, checkSonatypeCreds}
import mill.scalalib.publish.{Artifact, SonatypePublisher}
import os.Path

/**
 * Configuration necessary for publishing a Scala module to Maven Central or similar
 */
trait PublishModule extends JavaModule { outer =>
  import mill.scalalib.publish._

  override def moduleDeps: Seq[PublishModule] = super.moduleDeps.map {
    case m: PublishModule => m
    case other =>
      throw new Exception(
        s"PublishModule moduleDeps need to be also PublishModules. $other is not a PublishModule"
      )
  }

  /**
   * Configuration for the `pom.xml` metadata file published with this module
   */
  def pomSettings: T[PomSettings]

  /**
   * The artifact version that this module would be published as
   */
  def publishVersion: T[String]

  /**
   * Optional information about the used version scheme.
   * This may enable dependency resolvers to properly resolve version ranges and version mismatches (conflicts).
   * This information will be written as `info.versionScheme` property in the `pom.xml`.
   * See [[VersionScheme]] for possible values.
   *
   * You can find more info under these links:
   * - https://docs.scala-lang.org/overviews/core/binary-compatibility-for-library-authors.html#recommended-versioning-scheme
   * - https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html
   * - https://www.scala-sbt.org/1.x/docs/Publishing.html#Version+scheme
   * - https://semver.org
   *
   * @since Mill after 0.10.0-M5
   */
  def versionScheme: Target[Option[VersionScheme]] = T { None }

  def publishSelfDependency: Target[Artifact] = T {
    Artifact(pomSettings().organization, artifactId(), publishVersion())
  }

  def publishXmlDeps: Task[Agg[Dependency]] = T.task {
    val ivyPomDeps = (ivyDeps() ++ mandatoryIvyDeps()).map(resolvePublishDependency().apply(_))

    val compileIvyPomDeps = compileIvyDeps()
      .map(resolvePublishDependency().apply(_))
      .filter(!ivyPomDeps.contains(_))
      .map(_.copy(scope = Scope.Provided))

    val modulePomDeps = T.sequence(moduleDepsChecked.collect {
      case m: PublishModule => m.publishSelfDependency
    })()
    val compileModulePomDeps = T.sequence(compileModuleDepsChecked.collect {
      case m: PublishModule => m.publishSelfDependency
    })()

    ivyPomDeps ++ compileIvyPomDeps ++
      modulePomDeps.map(Dependency(_, Scope.Compile)) ++
      compileModulePomDeps.map(Dependency(_, Scope.Provided))
  }

  def pom: Target[PathRef] = T {
    val pom =
      Pom(artifactMetadata(), publishXmlDeps(), artifactId(), pomSettings(), publishProperties())
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
  def extraPublish: Target[Seq[PublishInfo]] = T { Seq.empty[PublishInfo] }

  /**
   * Properties to be published with the published pom/ivy XML.
   * Use `super.publishProperties() ++` when overriding to avoid losing default properties.
   * @since Mill after 0.10.0-M5
   */
  def publishProperties: Target[Map[String, String]] = T {
    versionScheme().map(_.toProperty).toMap
  }

  /**
   * Publish artifacts to a local ivy repository.
   * @param localIvyRepo The local ivy repository.
   *                     If not defined, the default resolution is used (probably `$HOME/.ivy2/local`).
   */
  def publishLocal(localIvyRepo: String = null): define.Command[Unit] = T.command {
    publishLocalTask(T.task {
      Option(localIvyRepo).map(os.Path(_, T.workspace))
    })()
    Result.Success(())
  }

  /**
   * Publish artifacts the local ivy repository.
   */
  def publishLocalCached: T[Seq[PathRef]] = T {
    publishLocalTask(T.task(None))().map(p => PathRef(p).withRevalidateOnce)
  }

  private def publishLocalTask(localIvyRepo: Task[Option[os.Path]]): Task[Seq[Path]] = T.task {
    val publisher = localIvyRepo() match {
      case None => LocalIvyPublisher
      case Some(path) => new LocalIvyPublisher(path)
    }
    publisher.publishLocal(
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
  def publishM2Local(m2RepoPath: String = (os.home / ".m2" / "repository").toString())
      : Command[Seq[PathRef]] = T.command {
    publishM2LocalTask(T.task {
      os.Path(m2RepoPath, T.workspace)
    })()
  }

  /**
   * Publish artifacts to the local Maven repository.
   * @return [[PathRef]]s to published files.
   */
  def publishM2LocalCached: T[Seq[PathRef]] = T {
    publishM2LocalTask(T.task {
      os.Path(os.home / ".m2" / "repository", T.workspace)
    })()
  }

  private def publishM2LocalTask(m2RepoPath: Task[os.Path]): Task[Seq[PathRef]] = T.task {
    val path = m2RepoPath()
    new LocalM2Publisher(path)
      .publish(
        jar = jar().path,
        sourcesJar = sourceJar().path,
        docJar = docJar().path,
        pom = pom().path,
        artifact = artifactMetadata(),
        extras = extraPublish()
      ).map(PathRef(_).withRevalidateOnce)
  }

  def sonatypeUri: String = "https://oss.sonatype.org/service/local"

  def sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots"

  def useSonatypeCentral: Boolean = false

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
   * Uses environment variables SONATYPE_USERNAME and SONATYPE_PASSWORD as
   * credentials.
   *
   * @param sonatypeCreds Sonatype credentials in format username:password.
   *                      If specified, environment variables will be ignored.
   *                      <i>Note: consider using environment variables over this argument due
   *                      to security reasons.</i>
   * @param gpgArgs       GPG arguments. Defaults to `--batch --yes -a -b`.
   *                      Specifying this will override/remove the defaults.
   *                      Add the default args to your args to keep them.
   */
  def publish(
      sonatypeCreds: String = "",
      signed: Boolean = true,
      // mainargs wasn't handling a default value properly,
      // so we instead use the empty Seq as default.
      // see https://github.com/com-lihaoyi/mill/pull/1678
      // TODO: In mill 0.11, we may want to change to a String argument
      // which we can split at `,` symbols, as we do in `PublishModule.publishAll`.
      gpgArgs: Seq[String] = Seq.empty,
      release: Boolean = false,
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000,
      awaitTimeout: Int = 120 * 1000,
      stagingRelease: Boolean = true
  ): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    val finalGpgArgs = if (gpgArgs.isEmpty) PublishModule.defaultGpgArgs else gpgArgs

    if (useSonatypeCentral) {
      val publisher = new SonatypeCentralPublisher(
        credentials = checkSonatypeCentralCreds(sonatypeCreds)(),
        signed = signed,
        gpgArgs = finalGpgArgs,
        connectTimeout = connectTimeout,
        readTimeout = readTimeout,
        log = T.log,
        workspace = T.workspace,
        env = T.env,
        awaitTimeout = awaitTimeout
      )
      publisher.publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo, release)
    } else {
      new SonatypePublisher(
        sonatypeUri,
        sonatypeSnapshotUri,
        checkSonatypeCreds(sonatypeCreds)(),
        signed,
        finalGpgArgs,
        readTimeout,
        connectTimeout,
        T.log,
        T.workspace,
        T.env,
        awaitTimeout,
        stagingRelease
      ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo, release)
    }
  }

  override def manifest: T[JarManifest] = T {
    import java.util.jar.Attributes.Name
    val pom = pomSettings()
    super.manifest().add(
      Name.IMPLEMENTATION_TITLE.toString() -> artifactName(),
      Name.IMPLEMENTATION_VERSION.toString() -> publishVersion(),
      Name.IMPLEMENTATION_VENDOR.toString() -> pom.organization,
      "Description" -> pom.description,
      "URL" -> pom.url,
      "Licenses" -> pom.licenses.map(l => s"${l.name} (${l.id})").mkString(",")
    )
  }
}

object PublishModule extends ExternalModule {
  val defaultGpgArgs: Seq[String] = Seq("--batch", "--yes", "-a", "-b")

  case class PublishData(meta: Artifact, payload: Seq[(PathRef, String)])
  object PublishData {
    implicit def jsonify: upickle.default.ReadWriter[PublishData] = upickle.default.macroRW
  }

  /**
   * Publish all given artifacts to Sonatype.
   * Uses environment variables SONATYPE_USERNAME and SONATYPE_PASSWORD as
   * credentials.
   *
   * @param sonatypeCreds Sonatype credentials in format username:password.
   *                      If specified, environment variables will be ignored.
   *                      <i>Note: consider using environment variables over this argument due
   *                      to security reasons.</i>
   * @param gpgArgs       GPG arguments. Defaults to `--batch --yes -a -b`.
   *                      Specifying this will override/remove the defaults.
   *                      Add the default args to your args to keep them.
   */
  def publishAll(
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      sonatypeCreds: String = "",
      signed: Boolean = true,
      gpgArgs: String = defaultGpgArgs.mkString(","),
      release: Boolean = false,
      sonatypeUri: String = "https://oss.sonatype.org/service/local",
      sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots",
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000,
      awaitTimeout: Int = 120 * 1000,
      stagingRelease: Boolean = true
  ): Command[Unit] = T.command {
    val x: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map {
      case PublishModule.PublishData(a, s) => (s.map { case (p, f) => (p.path, f) }, a)
    }
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      checkSonatypeCreds(sonatypeCreds)(),
      signed,
      gpgArgs.split(",").toIndexedSeq,
      readTimeout,
      connectTimeout,
      T.log,
      T.workspace,
      T.env,
      awaitTimeout,
      stagingRelease
    ).publishAll(
      release,
      x: _*
    )
  }

  private[scalalib] def getSonatypeCredsFromEnv: Task[(String, String)] = T.task {
    (for {
      username <- T.env.get("SONATYPE_USERNAME")
      password <- T.env.get("SONATYPE_PASSWORD")
    } yield {
      Result.Success((username, password))
    }).getOrElse(
      Result.Failure(
        "Consider using SONATYPE_USERNAME/SONATYPE_PASSWORD environment variables or passing `sonatypeCreds` argument"
      )
    )
  }

  private[scalalib] def checkSonatypeCreds(sonatypeCreds: String): Task[String] =
    if (sonatypeCreds.isEmpty) {
      for {
        (username, password) <- getSonatypeCredsFromEnv
      } yield s"$username:$password"
    } else {
      T.task {
        Result.Success(sonatypeCreds)
      }
    }

  private[scalalib] def checkSonatypeCentralCreds(rawCredentials: String)
      : Task[SonatypeCredentials] =
    if (rawCredentials.isEmpty) {
      for {
        (username, password) <- getSonatypeCredsFromEnv
      } yield SonatypeCredentials(username, password)
    } else {
      T.task {
        val splitCreds = rawCredentials.split(":").toVector
        if (splitCreds.length >= 2) {
          val username = splitCreds.head
          val password = splitCreds.tail.mkString(":")
          Result.Success(SonatypeCredentials(username, password))
        } else {
          Result.Failure(
            "Invalid credentials set. Expected username and password to be separated by a colon."
          )
        }
      }
    }

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
