package mill.scalalib

import mill.*
import mill.constants.Util

import java.math.BigInteger
import java.security.MessageDigest

import scala.util.Properties

/**
 * Provides a [[NativeImageModule.nativeImage task]] to build a native executable using [[https://www.graalvm.org/ Graal VM]].
 *
 * It is recommended to specify a custom JDK that includes the `native-image` Tool.
 * {{{
 * trait AppModule extends NativeImageModule {
 *   def jvmWorker = ModuleRef(JvmWorkerGraalvm)
 *
 *   object JvmWorkerGraalvm extends JvmWorkerModule {
 *     def jvmId = "graalvm-community:23.0.1"
 *   }
 * }
 * }}}
 */
@mill.api.experimental
trait NativeImageModule extends WithJvmWorker {
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
   * The classpath to use to generate the native image. Defaults to [[runClasspath]].
   */
  def nativeImageClasspath: T[Seq[PathRef]] = Task {
    runClasspath()
  }

  /**
   * Additional options for the `native-image` Tool.
   */
  def nativeImageOptions: T[Seq[String]] = Seq.empty[String]

  /**
   * Path to the [[https://www.graalvm.org/latest/reference-manual/native-image/ `native-image` Tool]].
   * Defaults to a path relative to
   *  - [[JvmWorkerModule.javaHome]], if defined
   *  - environment variable `GRAALVM_HOME`, if defined
   *
   * @note The task fails if the `native-image` Tool is not found.
   */
  def nativeImageTool: T[PathRef] = Task {
    jvmWorker().javaHome().map(_.path)
      .orElse(sys.env.get("GRAALVM_HOME").map(os.Path(_))) match {
      case Some(home) =>
        val tool = if (Properties.isWin) "native-image.cmd" else "native-image"
        val actualHome = maybeCopiedGraalVmHome(home, checkExists = true) match {
          case Some(home0) =>
            System.err.println(s"Using $home0 as GraalVM")
            home0
          case None =>
            home
        }
        val path = actualHome / "bin" / tool
        if (os.exists(path))
          // native-image is externally managed, better revalidate it at least once
          PathRef(path).withRevalidateOnce
        else throw new RuntimeException(s"$path not found")
      case None =>
        throw new RuntimeException("JvmWorkerModule.javaHome/GRAALVM_HOME not defined")
    }
  }

  def nativeImageGraalVmCopyBase: Option[os.Path] =
    if (Properties.isWin && System.getenv("CI") != null)
      Some(os.Path("C:/jvms"))
    else
      None

  private def maybeCopiedGraalVmHome(home: os.Path, checkExists: Boolean = false): Option[os.Path] =
    nativeImageGraalVmCopyBase
      .map { base =>
        val sha1 = {
          val bytes = MessageDigest.getInstance("SHA-1").digest(home.toString.getBytes)
          val baseSha1 = new BigInteger(1, bytes).toString(16)
          "0" * (40 - baseSha1.length) + baseSha1
        }
        base / sha1.take(8)
      }
      .filter(dir => !checkExists || os.exists(dir))

  def nativeImageMaybeCopyGraal(): Command[Unit] =
    if (nativeImageGraalVmCopyBase.isEmpty)
      Task.Command {}
    else
      Task.Command {
        val originalHome = jvmWorker().javaHome().map(_.path).getOrElse {
          sys.error("No JVM defined via jvmWorker")
        }
        for (dest <- maybeCopiedGraalVmHome(originalHome))
          if (os.exists(dest))
            System.err.println(s"$dest already exists")
          else {
            System.err.println(s"Copying $originalHome to $dest")
            os.copy(originalHome, dest, createFolders = true)
          }
      }
}
