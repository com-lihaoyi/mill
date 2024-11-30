package mill.scalalib

import mill.*

import java.nio.file.Paths
import scala.util.Properties

/**
 * Provides a [[nativeImage task]] to generate a native binary for this JVM application.
 *
 * For reproducible builds, specify a custom Java home.
 * {{{
 * trait AppModule extends NativeImageModule {
 *   def zincWorker = ModuleRef(ZincWorkerGraalvm)
 *
 *   object ZincWorkerGraalvm extends ZincWorkerModule {
 *     def jvmId = "graalvm-community:23.0.1"
 *   }
 * }
 * }}}
 */
trait NativeImageModule extends RunModule {

  /**
   * Generates a native binary for [[finalMainClass]] using [[nativeImageCli]].
   */
  def nativeImage: T[PathRef] = Task {
    val dest = T.dest

    val classPath = runClasspath().iterator
      .map(_.path)
      .mkString(java.io.File.pathSeparator)
    val executableName = nativeImageExecutableName()

    val command =
      Seq.newBuilder[String]
        .+=(nativeImageCli().path.toString)
        .++=(Seq("--class-path", classPath))
        .++=(nativeImageOptions())
        .++=(Seq(finalMainClass(), executableName))
        .result()

    T.log.info(s"building native image $executableName")
    os.proc(command).call(cwd = dest)

    PathRef(dest / executableName)
  }

  /**
   * Path to [[https://www.graalvm.org/latest/reference-manual/native-image/ `native-image`]] CLI.
   * Defaults to `bin/native-image` relative to [[ZincWorkerModule.javaHome]] or `GRAALVM_HOME` environment variable.
   */
  def nativeImageCli: T[PathRef] = Task {
    val ext = if (Properties.isWin) ".cmd" else ""
    val path = zincWorker().javaHome()
      .map(_.path)
      .orElse(sys.env.get("GRAALVM_HOME").map(os.Path(_))) match {
      case None =>
        // assume native-image is installed
        os.Path(Paths.get(s"native-image$ext").toAbsolutePath)
      case Some(home) =>
        home / "bin" / s"native-image$ext"
    }
    if (os.exists(path)) PathRef(path)
    else throw new RuntimeException(s"native-image not found at $path")
  }

  /**
   * The name of the generated native binary.
   */
  def nativeImageExecutableName: T[String] = Task {
    val name = finalMainClass().split('.').last
    if (Properties.isWin) s"$name.exe" else name
  }

  /**
   * Additional options for [[nativeImageCli]].
   */
  def nativeImageOptions: T[Seq[String]] = Seq.empty[String]
}
