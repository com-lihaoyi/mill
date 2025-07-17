package mill.javalib.internal

import mill.api.PathRef
import mill.api.daemon.internal.internal
import mill.javalib.api.{JvmWorkerApi, JvmWorkerUtil, Versions}

import java.io.File
import scala.util.Properties.isWin

@internal
enum ZincCompilerBridge {
  /** The compiler bridge is already compiled, we just need to run it.
   *
   * @param forScalaVersion returns the path to the compiler bridge jar for the given Scala version
   * */
  case Compiled(forScalaVersion: String => PathRef)

  /** The compiler bridge needs to be compiled. */
  case Provider(context: JvmWorkerApi.Ctx, compile: ZincCompilerBridge.Compile)
}
@internal
object ZincCompilerBridge {
  trait Compile {
    def apply(scalaVersion: String, scalaOrganization: String): CompileResult
  }
  case class CompileResult(classpath: Option[Seq[PathRef]], bridgeJar: PathRef) {
    def fullClasspath: Vector[PathRef] = (Iterator(bridgeJar) ++ classpath.iterator.flatten).toVector
  }

  /** Compile the `sbt`/Zinc compiler bridge in the `compileDest` directory */
  def compile(
    ctx0: JvmWorkerApi.Ctx,
    workingDir: os.Path,
    compileDest: os.Path,
    scalaVersion: String,
    compilerClasspath: Seq[PathRef],
    compilerBridgeClasspath: Seq[PathRef],
    compilerBridgeSourcesJar: os.Path
  ): Unit = {
    if (scalaVersion == "2.12.0") {
      // The Scala 2.10.0 compiler fails on compiling the compiler bridge
      throw new IllegalArgumentException(
        "The current version of Zinc is incompatible with Scala 2.12.0.\n" +
          "Use Scala 2.12.1 or greater (2.12.12 is recommended)."
      )
    }

    ctx0.log.info("Compiling compiler interface...")

    os.makeDir.all(workingDir)
    os.makeDir.all(compileDest)

    val sourceFolder = os.unzip(compilerBridgeSourcesJar, workingDir / "unpacked")
    val classloader = mill.util.Jvm.createClassLoader(
      compilerClasspath.map(_.path),
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
        (compilerClasspath.iterator ++ compilerBridgeClasspath).map(_.path).mkString(
          File.pathSeparator
        )
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
