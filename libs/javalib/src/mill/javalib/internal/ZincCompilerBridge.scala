package mill.javalib.internal

import mill.api.daemon.internal.internal
import mill.javalib.api.JvmWorkerUtil
import upickle.default.ReadWriter

import java.io.File
import scala.util.Properties.isWin

/**
 * Allows you to retrieve or compile the Scala compiler bridge.
 *
 * @param taskDest The task's destination folder.
 * @param logInfo  logs a message at INFO level.
 */
@internal
case class ZincCompilerBridge[AcquireData](
    taskDest: os.Path,
    logInfo: String => Unit,
    acquire: ZincCompilerBridge.Acquire[AcquireData]
)
@internal
object ZincCompilerBridge {

  /**
   * Provides the compiler bridge.
   *
   * @tparam Data extra data to pass to the acquire function.
   */
  trait Acquire[Data] {
    def apply(scalaVersion: String, scalaOrganization: String, data: Data): AcquireResult[os.Path]
  }

  sealed trait AcquireResult[Path] {
    def map[B](f: Path => B): AcquireResult[B]

    def fullClasspath: Vector[Path]
  }
  object AcquireResult {

    /**
     * The compiler bridge is already compiled and can be ran.
     *
     * @param bridgeJar The path to the compiled compiler bridge jar.
     */
    case class Compiled[Path](bridgeJar: Path) extends AcquireResult[Path] {
      override def map[B](f: Path => B): AcquireResult[B] = Compiled(f(bridgeJar))

      override def fullClasspath: Vector[Path] = Vector(bridgeJar)
    }

    /**
     * The compiler bridge is not compiled yet and needs to be compiled.
     *
     * @param classpath The classpath to use to compile the compiler bridge.
     * @param bridgeSourcesJar The path to the compiler bridge sources jar.
     */
    case class NotCompiled[Path](classpath: Seq[Path], bridgeSourcesJar: Path)
        extends AcquireResult[Path] {
      override def map[B](f: Path => B): AcquireResult[B] =
        NotCompiled(classpath = classpath.map(f), bridgeSourcesJar = f(bridgeSourcesJar))

      def fullClasspath: Vector[Path] =
        (Iterator(bridgeSourcesJar) ++ classpath.iterator).toVector
    }

    given rw[Path: ReadWriter]: ReadWriter[AcquireResult[Path]] = ReadWriter.derived
  }

  /** Compile the `sbt`/Zinc compiler bridge in the `compileDest` directory */
  def compile(
      logInfo: String => Unit,
      workingDir: os.Path,
      compileDest: os.Path,
      scalaVersion: String,
      compilerClasspath: Seq[os.Path],
      compilerBridgeClasspath: Seq[os.Path],
      compilerBridgeSourcesJar: os.Path
  ): Unit = {
    if (scalaVersion == "2.12.0") {
      // The Scala 2.10.0 compiler fails on compiling the compiler bridge
      throw new IllegalArgumentException(
        "The current version of Zinc is incompatible with Scala 2.12.0.\n" +
          "Use Scala 2.12.1 or greater (2.12.12 is recommended)."
      )
    }

    logInfo("Compiling compiler interface...")

    os.makeDir.all(workingDir)
    os.makeDir.all(compileDest)

    val sourceFolder = os.unzip(compilerBridgeSourcesJar, workingDir / "unpacked")
    val classloader = mill.util.Jvm.createClassLoader(
      compilerClasspath,
      parent = null
    )

    try {
      val (sources, resources) =
        os.walk(sourceFolder).filter(os.isFile)
          .partition(a => a.ext == "scala" || a.ext == "java")

      resources.foreach { res =>
        val dest = compileDest / res.relativeTo(sourceFolder)
        os.move(res, dest, replaceExisting = true, createFolders = true)
      }

      val argsArray = Array[String](
        "-d",
        compileDest.toString,
        "-classpath",
        (compilerClasspath.iterator ++ compilerBridgeClasspath).mkString(File.pathSeparator)
      ) ++ sources.map(_.toString)

      val allScala = sources.forall(_.ext == "scala")
      val allJava = sources.forall(_.ext == "java")
      if (allJava) {
        val javacExe: String =
          sys.props
            .get("java.home")
            .map(h =>
              if (isWin) new File(h, "bin\\javac.exe")
              else new File(h, "bin/javac")
            )
            .filter(f => f.exists())
            .fold("javac")(_.getAbsolutePath())
        import scala.sys.process.*
        (Seq(javacExe) ++ argsArray).!
      } else if (allScala) {
        val compilerMain = classloader.loadClass(
          if (JvmWorkerUtil.isDottyOrScala3(scalaVersion)) "dotty.tools.dotc.Main"
          else "scala.tools.nsc.Main"
        )
        compilerMain
          .getMethod("process", classOf[Array[String]])
          .invoke(null, argsArray ++ Array("-nowarn"))
      } else {
        throw new IllegalArgumentException("Currently not implemented case.")
      }
    } finally classloader.close()
  }
}
