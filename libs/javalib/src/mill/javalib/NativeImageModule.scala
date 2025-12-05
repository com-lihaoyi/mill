package mill.javalib

import mill.*
import mill.constants.{DaemonFiles, Util}

import scala.util.Properties
import mill.api.BuildCtx
import mill.javalib.graalvm.GraalVMMetadataWorker

/**
 * Provides a [[NativeImageModule.nativeImage task]] to build a native executable using [[https://www.graalvm.org/ Graal VM]].
 *
 * It is recommended to specify a custom JDK that includes the `native-image` Tool.
 * {{{
 * trait AppModule extends NativeImageModule {
 *   def jvmWorker = ModuleRef(JvmWorkerGraalvm)
 *
 *   def jvmId = "graalvm-community:23.0.1"
 * }
 * }}}
 */
@mill.api.experimental
trait NativeImageModule extends WithJvmWorkerModule {
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
  def nativeImageOptions: T[Seq[String]] = Seq()

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
   *
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
   * Deps for the [[nativeGraalVMReachabilityMetadataWorker]]
   */
  def nativeGraalVMReachabilityMetadataToolsDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"org.graalvm.buildtools:graalvm-reachability-metadata:0.11.3",
      mvn"com.github.openjson:openjson:1.0.13"
    )
  }

  /**
   * Resolved classpath of [[nativeGraalVMReachabilityMetadataToolsDeps]]
   */
  def nativeGraalVMReachabilityMetadataClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-libs-javalib-graalvm-reachability-worker")
      ) ++ nativeGraalVMReachabilityMetadataToolsDeps()
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

}
