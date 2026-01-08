package mill.javalib

import coursier.core.Dependency
import coursier.core.VariantSelector.ConfigurationBased
import mainargs.Flag
import mill.*
import mill.api.BuildCtx
import mill.constants.{DaemonFiles, Util}
import mill.javalib.graalvm.{GraalVMMetadataWorker, MetadataQuery, MetadataResult}

import java.io.File
import scala.util.Properties

/**
 * Provides a [[NativeImageModule.nativeImage task]] to build a native executable using [[https://www.graalvm.org/ Graal VM]].
 *
 * It is recommended to specify a custom JDK that includes the `native-image` Tool.
 * {{{
 * trait AppModule extends NativeImageModule {
 *   def jvmWorker = ModuleRef(JvmWorkerGraalvm)
 *
 *   def jvmVersion = "graalvm-community:23.0.1"
 * }
 * }}}
 */
@mill.api.experimental
trait NativeImageModule extends WithJvmWorkerModule, OfflineSupportModule {
  def runClasspath: T[Seq[PathRef]]

  def finalMainClass: T[String]

  /**
   * [[https://www.graalvm.org/latest/reference-manual/native-image/#from-a-class Builds a native executable]] for this
   * module with [[finalMainClass]] as the application entry point.
   */
  def nativeImage: T[PathRef] = Task {
    val dest = Task.dest

    val executeableName = "native-executable"
    val command = Seq.newBuilder[String]
      .+=(nativeImageTool().path.toString)
      .++=(nativeImageOptions())
      .+=("-cp")
      .+=(nativeImageClasspath().iterator.map(_.path).mkString(java.io.File.pathSeparator))
      .+=(finalMainClass())
      .+=((dest / executeableName).toString())
      .result()

    os.proc(command).call(cwd = dest, stdout = os.Inherit)

    val ext = if (Util.isWindows) ".exe" else ""
    val executable = dest / s"$executeableName$ext"
    assert(os.exists(executable))
    PathRef(executable)
  }

  /**
   * Runs the Native Image from [[nativeImage]]
   *
   * @param args
   */
  def nativeRun(args: Task[Args] = Task.Anon(Args())): Task.Command[Unit] = Task.Command {
    val runScript = nativeImage().path
    os.call(Seq(runScript.toString) ++ args().value, stdout = os.Inherit)
  }

  /**
   * Runs the Native Image from [[nativeImage]] in the background
   *
   * @param args
   */
  def nativeRunBackground(args: mill.api.Args) = Task.Command(persistent = true) {
    val backgroundPaths = mill.javalib.RunModule.BackgroundPaths(Task.dest)
    val pwd0 = os.Path(java.nio.file.Paths.get(".").toAbsolutePath)

    BuildCtx.withFilesystemCheckerDisabled {
      mill.util.Jvm.spawnProcess(
        mainClass = "mill.javalib.backgroundwrapper.MillBackgroundWrapper",
        classPath = mill.javalib.JvmWorkerModule.backgroundWrapperClasspath().map(_.path).toSeq,
        jvmArgs = Nil,
        mainArgs = backgroundPaths.toArgs ++ Seq(
          "<subprocess>",
          nativeImage().path.toString
        ) ++ args.value,
        cwd = BuildCtx.workspaceRoot,
        stdin = "",
        // Hack to forward the background subprocess output to the Mill server process
        // stdout/stderr files, so the output will get properly slurped up by the Mill server
        // and shown to any connected Mill client even if the current command has completed
        stdout = os.PathAppendRedirect(pwd0 / ".." / DaemonFiles.stdout),
        stderr = os.PathAppendRedirect(pwd0 / ".." / DaemonFiles.stderr),
        javaHome = javaHome().map(_.path)
      )
    }
    ()
  }

  /**
   * The classpath to use to generate the native image. Defaults to [[runClasspath]].
   */
  def nativeImageClasspath: T[Seq[PathRef]] = Task {
    runClasspath()
  }

  /**
   * Additional options for the `native-image` Tool.
   */
  def nativeImageOptions: T[Seq[String]] = Task {
    val configurations =
      nativeMetadataConfigurations()
    if (configurations.isEmpty) {
      Seq.empty[String]
    } else {
      val configurationFileDirectoriesValue =
        configurations.map(_.metadataLocation.toString).mkString(",")
      Seq(s"-H:ConfigurationFileDirectories=$configurationFileDirectoriesValue")
    }
    nativeExcludedConfig() ++ Seq(s"-H:ConfigurationFileDirectories=$configurations")
  }

  /**
   * Path to the [[https://www.graalvm.org/latest/reference-manual/native-image/ `native-image` Tool]].
   * Defaults to a path relative to
   *  - [[JvmWorkerModule.javaHome]], if defined
   *  - environment variable `GRAALVM_HOME`, if defined
   *
   * @note The task fails if the `native-image` Tool is not found.
   */
  def nativeImageTool: T[PathRef] = Task {
    javaHome().map(_.path)
      .orElse(sys.env.get("GRAALVM_HOME").map(os.Path(_))) match {
      case Some(home) =>
        val tool = if (Properties.isWin) "native-image.cmd" else "native-image"
        val path = home / "bin" / tool
        if (os.exists(path))
          // native-image is externally managed, better revalidate it at least once
          PathRef(path).withRevalidateOnce
        else throw new RuntimeException(s"$path not found")
      case None =>
        throw new RuntimeException("JvmWorkerModule.javaHome/GRAALVM_HOME not defined")
    }
  }

  /**
   * The version of the reachability metadata as found in
   * [[https://github.com/oracle/graalvm-reachability-metadata]]
   *
   * Default value is retrieved from the [[nativeGraalVMReachabilityMetadataWorker]]
   */
  def nativeGraalVMReachabilityMetadataVersion: T[String] = Task {
    nativeGraalVMReachabilityMetadataWorker().reachabilityMetadataVersion
  }

  /**
   * Downloads the version [[nativeGraalVMReachabilityMetadataVersion]] graalvm-reachability-metadata
   * from [[https://github.com/oracle/graalvm-reachability-metadata]]
   */
  def nativeGraalVMReachabilityMetadata: T[PathRef] = Task {
    val rootDir =
      nativeGraalVMReachabilityMetadataWorker()
        .downloadRepo(Task.dest, nativeGraalVMReachabilityMetadataVersion())

    PathRef(rootDir)
  }

  /**
   * Resolved classpath of mill-libs-javalib-graalvm-reachability-worker
   */
  def nativeGraalVMReachabilityMetadataClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-libs-javalib-graalvm-reachability-worker")
      )
    )
  }

  /**
   * Classloader with [[nativeGraalVMReachabilityMetadataClasspath]]
   */
  def nativeGraalVMReachabilityMetadataClassloader: Worker[ClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(
      classPath = nativeGraalVMReachabilityMetadataClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  /**
   * Worker that fetches the graalvm-reachability-metadata and collects any relevant
   * metadata
   */
  def nativeGraalVMReachabilityMetadataWorker: Worker[GraalVMMetadataWorker] = Task.Worker {
    nativeGraalVMReachabilityMetadataClassloader()
      .loadClass("mill.javalib.graalvm.GraalVMMetadataWorkerImpl").getConstructor().newInstance()
      .asInstanceOf[GraalVMMetadataWorker]
  }

  /**
   * Computes the MetadataResults of the module's dependencies using
   * [[nativeGraalVMReachabilityMetadataWorker]] .
   */
  def nativeMetadataConfigurations: T[Set[MetadataResult]] = Task {
    nativeGraalVMReachabilityMetadataWorker().findConfigurations(
      nativeGraalVmMetadataQuery()
    )
  }

  /**
   * Whether to use the latest graalvm reachability metadata config
   * if the dependency version is not listed in the tested versions.
   *
   * Defaults to `true`
   */
  def nativeUseLatestConfigWhenVersionIsUntested: T[Boolean] = Task {
    true
  }

  /**
   * Defines the query to run for finding reachability metadata via [[mill.javalib.graalvm.GraalVMMetadataWorker]].
   *
   * For more information and implementation details go to [[https://github.com/graalvm/native-build-tools/blob/master/common/graalvm-reachability-metadata/src/main/java/org/graalvm/reachability/internal/FileSystemRepository.java]]
   */
  def nativeGraalVmMetadataQuery: Task[MetadataQuery] = this match {
    case _: JavaModule => Task {
        val metadataPath = nativeGraalVMReachabilityMetadata().path
        MetadataQuery(
          rootPath = metadataPath,
          deps = nativeGraalVmQueryDeps(),
          useLatestConfigWhenVersionIsUntested = nativeUseLatestConfigWhenVersionIsUntested()
        )
      }
    case _ => Task {
        MetadataQuery(
          rootPath = Task.dest,
          deps = Set.empty,
          useLatestConfigWhenVersionIsUntested = false
        )
      }
  }

  /**
   * A list of native configs to exclude from the native image.
   * Uses the [[nativeDependencyMetadata]] and honors the override flag specified in the
   * reachability metadata fetched from [[nativeGraalVMReachabilityMetadata]]
   */
  def nativeExcludedConfigJars: T[Seq[PathRef]] = Task {
    nativeDependencyMetadata().filter(_.overridesNativeConfig)
      .map(
        _.file
      )
  }

  /**
   * Gets the runtime module dependencies for using against the reachability metadata.
   */
  def nativeResolvedRunDeps: Task[Seq[(Dependency, File)]] = this match {
    case m: JavaModule => Task {
        val dep = m.coursierDependencyTask().withVariantSelector(
          ConfigurationBased(coursier.core.Configuration.runtime)
        )

        val resolution =
          m.millResolver().artifacts(Seq(mill.javalib.BoundDep(dep, force = false)))

        resolution.detailedArtifacts0.map {
          case (dependency, _, _, file) =>
            (dependency, file)
        }
      }
    case _ =>
      Task(Seq.empty[(Dependency, File)])
  }

  /**
   * Combines the [[nativeMetadataConfigurations]] with the [[nativeResolvedRunDeps]]
   * into a list of [[NativeImageModule.DependencyMetadata]] to make the strategy of
   * picking or excluding native configuration files easier.
   */
  def nativeDependencyMetadata: T[Seq[NativeImageModule.DependencyMetadata]] = this match {
    case _: JavaModule => Task {

        val overrideDeps = nativeMetadataConfigurations().filter(_.isOverride)

        def isReachabilityOverride(dependency: coursier.core.Dependency): Boolean = {
          overrideDeps.exists(mr =>
            mr.dependencyGroupId == dependency.module.organization.value &&
              mr.dependencyArtifactId == dependency.module.name.value
          )
        }

        nativeResolvedRunDeps().map {
          case (dependency, file) =>
            NativeImageModule.DependencyMetadata(
              dependency.module.organization.value,
              dependency.module.name.value,
              dependency.versionConstraint.asString,
              isReachabilityOverride(dependency),
              PathRef(os.Path(file))
            )
        }
      }
    case _ =>
      Task(Seq.empty[NativeImageModule.DependencyMetadata])
  }

  /**
   * Constructs the native image excluded config given a list of
   * artifacts that these configs should be excluded from.
   * To find more about the syntax see [[https://github.com/paketo-buildpacks/native-image/issues/196]]
   */
  def nativeExcludedConfig: T[Seq[String]] = Task {
    nativeExcludedConfigJars()
      .distinct
      .flatMap(file =>
        Seq("--exclude-config", s"\\Q${file.path.toString}\\E", s"^/META-INF/native-image/.*")
      )
  }

  /**
   * The GAV coordinates of the dependencies to search for reachability metadata
   * in [[nativeGraalVMReachabilityMetadata]].
   *
   * This task must be compatible with [[https://github.com/graalvm/native-build-tools/blob/54db68cfcc20ab6a43ccbf4e04130325210f5b3a/common/graalvm-reachability-metadata/src/main/java/org/graalvm/reachability/internal/DefaultArtifactQuery.java#L57]]
   */
  def nativeGraalVmQueryDeps: T[Set[String]] = this match {
    case _: JavaModule => Task {

        def isValidGAVSection(value: String): Boolean = value.nonEmpty && !value.contains(':')

        def isValidGAV(d: coursier.core.Dependency): Boolean =
          Seq(
            d.module.organization.value,
            d.module.name.value,
            d.versionConstraint.asString
          ).forall(isValidGAVSection)

        def artifactQueryGav(d: coursier.core.Dependency): String =
          s"${d.module.organization.value}:${d.module.name.value}:${d.versionConstraint.asString}"

        val depsMetadata = nativeResolvedRunDeps().map(_._1).toSet.groupBy(isValidGAV)

        val skippedDeps = depsMetadata.getOrElse(false, Set.empty)
        val validDeps = depsMetadata.getOrElse(true, Set.empty)

        skippedDeps.foreach {
          dep =>
            Task.log.info(s"Skipping dependency: ${dep.module.repr} due to invalid GAV coordinates")
        }

        validDeps.map(artifactQueryGav)
      }
    case _ =>
      Task {
        Set.empty[String]
      }
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++
        nativeImageClasspath() ++
        nativeGraalVMReachabilityMetadataClasspath() ++
        // should be in WithJvmWorkerModule, but isn't due to bin-compat
        jvmWorker().prepareOffline(all)()
    ).distinct
  }
}

object NativeImageModule {
  case class DependencyMetadata(
      groupId: String,
      artifactId: String,
      version: String,
      overridesNativeConfig: Boolean,
      file: PathRef
  ) derives upickle.ReadWriter
}
