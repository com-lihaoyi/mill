package mill.javalib.revapi

import mill.*
import mill.javalib.*
import mill.javalib.revapi.RevapiModule.optional
import mill.javalib.api.Versions
import mill.javalib.publish.Artifact
import mill.util.Jvm

/**
 * Adds support for [[https://revapi.org/revapi-site/main/index.html Revapi checker]] to enable API analysis and change tracking.
 */
@mill.api.experimental // until Revapi has a stable release
trait RevapiModule extends PublishModule {

  /**
   * Runs [[revapiCliVersion Revapi CLI]] on this module's archives.
   *
   * @param args additional CLI options
   * @return CLI working directory
   */
  def revapi(args: String*): Command[PathRef] = Task.Command {
    val workingDir = Task.dest

    val oldFiles = revapiOldFiles()
    val oldFile = oldFiles.head
    val oldSupFiles = oldFiles.tail

    val newFiles = revapiNewFiles()
    val newFile = newFiles.head
    val newSupFiles = newFiles.tail

    val mainClass = "org.revapi.standalone.Main"
    val mainArgs =
      Seq.newBuilder[String]
        // https://github.com/revapi/revapi/blob/69445626881347fbf7811a4a78ff230fe152a2dc/revapi-standalone/src/main/java/org/revapi/standalone/Main.java#L149
        .++=(Seq(mainClass, workingDir.toString()))
        // https://github.com/revapi/revapi/blob/69445626881347fbf7811a4a78ff230fe152a2dc/revapi-standalone/src/main/java/org/revapi/standalone/Main.java#L97
        .++=(Seq("-e", revapiExtensions().mkString(",")))
        .++=(Seq("-o", oldFile.path.toString()))
        .++=(optional("-s", oldSupFiles.iterator.map(_.path)))
        .++=(Seq("-n", newFile.path.toString()))
        .++=(optional("-t", newSupFiles.iterator.map(_.path)))
        .++=(optional("-c", revapiConfigFiles().iterator.map(_.path)))
        .++=(Seq("-d", revapiCacheDir().path.toString()))
        .++=(optional("-r", revapiRemoteRepositories()))
        .++=(args)
        .result()

    Task.log.info("running revapi cli")
    Jvm.callProcess(
      mainClass = mainClass,
      classPath = revapiClasspath().map(_.path).toVector,
      jvmArgs = revapiJvmArgs(),
      mainArgs = mainArgs,
      cwd = workingDir,
      stdin = os.Inherit,
      stdout = os.Inherit
    )
    PathRef(workingDir)
  }

  /**
   * List of Maven GAVs of Revapi extensions
   *
   * @note Must be non-empty.
   */
  def revapiExtensions: T[Seq[String]] = Seq(
    "org.revapi:revapi-java:0.28.1",
    "org.revapi:revapi-reporter-text:0.15.0"
  )

  /** API archive and supplement files (dependencies) to compare against */
  def revapiOldFiles: T[Seq[PathRef]] = Task {
    val Artifact(group, id, version) = artifactMetadata()
    defaultResolver().classpath(
      Seq(mvn"$group:$id:$version"),
      artifactTypes = Some(revapiArtifactTypes())
    )
  }

  /** API archive and supplement files (dependencies) to compare */
  def revapiNewFiles: T[Seq[PathRef]] = Task {
    Seq(jar()) ++
      Task.traverse(recursiveModuleDeps)(_.jar)() ++
      millResolver().classpath(
        Seq(coursierDependency),
        artifactTypes = Some(revapiArtifactTypes())
      )
  }

  /** List of configuration files */
  def revapiConfigFiles: T[Seq[PathRef]] = Seq.empty[PathRef]

  /** Location of local cache of extensions to use to locate artifacts */
  def revapiCacheDir: T[PathRef] = Task { PathRef(Task.dest) }

  /** URLs of remote Maven repositories to use for artifact resolution */
  def revapiRemoteRepositories: T[Seq[String]] = Task {
    repositoriesTask()
      .collect { case repo: coursier.MavenRepository => repo.root }
  }

  /** Classpath containing the Revapi [[revapiCliVersion CLI]] */
  def revapiClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.revapi:revapi-standalone:${revapiCliVersion()}")
    )
  }

  /** [[https://revapi.org/revapi-standalone/0.12.0/index.html Revapi CLI]] version */
  def revapiCliVersion: T[String] = Task { Versions.revApiVersion }

  /** JVM arguments for the Revapi [[revapiCliVersion CLI]] */
  def revapiJvmArgs: T[Seq[String]] = Seq.empty[String]

  /** Artifact types to resolve archives and supplement files (dependencies) */
  def revapiArtifactTypes: T[Set[coursier.Type]] = Set(coursier.Type.jar)
}
@mill.api.experimental
object RevapiModule {

  private def optional[T](name: String, values: IterableOnce[T]): Seq[String] = {
    val it = values.iterator
    if (it.isEmpty) Seq.empty
    else Seq(name, it.mkString(","))
  }
}
