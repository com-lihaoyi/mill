package mill.javalib.api.internal

import mill.api.daemon.internal.internal
import mill.javalib.api.JvmWorkerUtil
import upickle.ReadWriter

import java.io.File
import scala.util.Properties.isWin

/**
 * Allows you to retrieve or compile the Scala compiler bridge.
 *
 * @param workspace Workspace for the compiler bridge.
 * @param logInfo  logs a message at INFO level.
 */
@internal
case class ZincCompilerBridgeProvider(
    workspace: os.Path,
    logInfo: String => Unit,
    acquire: ZincCompilerBridgeProvider.Acquire
)
@internal
object ZincCompilerBridgeProvider {
  private def resolvePossiblyAliasedPath(path: os.Path): os.Path = {
    val workspaceRoot = sys.env.get("MILL_WORKSPACE_ROOT")
      .map(p => os.Path(p, os.pwd))
      .getOrElse(mill.api.BuildCtx.workspaceRoot)
    val workspaceAbs = os.Path(workspaceRoot.wrapped.toAbsolutePath.normalize())
    val homeAbs = os.Path(os.home.wrapped.toAbsolutePath.normalize())
    val nio = path.wrapped
    if (nio.isAbsolute) path
    else {
      val raw = nio.toString.replace('\\', '/')
      val workspaceAlias = "out/mill-workspace"
      val homeAlias = "out/mill-home"

      def resolveFromAlias(base: os.Path, aliasIdx: Int, alias: String): os.Path = {
        val suffix = raw.substring(aliasIdx + alias.length).stripPrefix("/")
        if (suffix.isEmpty) base else base / os.RelPath(suffix)
      }

      if (raw == workspaceAlias) workspaceAbs
      else if (raw.startsWith(workspaceAlias + "/"))
        workspaceAbs / os.RelPath(raw.stripPrefix(workspaceAlias + "/"))
      else if (raw == homeAlias) homeAbs
      else if (raw.startsWith(homeAlias + "/"))
        homeAbs / os.RelPath(raw.stripPrefix(homeAlias + "/"))
      else {
        val workspaceIdx = raw.indexOf(workspaceAlias)
        if (workspaceIdx >= 0)
          resolveFromAlias(workspaceAbs, workspaceIdx, workspaceAlias)
        else {
          val homeIdx = raw.indexOf(homeAlias)
          if (homeIdx >= 0) resolveFromAlias(homeAbs, homeIdx, homeAlias)
          else os.Path(raw, os.pwd)
        }
      }
    }
  }

  private def unzipWithJdk(zip: os.Path, dest: os.Path): os.Path = {
    os.makeDir.all(dest)
    val zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(os.read.inputStream(zip)))
    val buffer = new Array[Byte](8192)
    try {
      Iterator
        .continually(zis.getNextEntry)
        .takeWhile(_ != null)
        .foreach { entry =>
          val out = dest / os.RelPath(entry.getName)
          if (entry.isDirectory) os.makeDir.all(out)
          else {
            os.makeDir.all(out / os.up)
            val osOut = os.write.outputStream(out)
            try {
              Iterator
                .continually(zis.read(buffer))
                .takeWhile(_ != -1)
                .foreach(read => osOut.write(buffer, 0, read))
            } finally osOut.close()
          }
          zis.closeEntry()
        }
    } finally zis.close()
    dest
  }

  /** Provides the compiler bridge. */
  trait Acquire {
    def apply(scalaVersion: String, scalaOrganization: String): AcquireResult[os.Path]
  }

  enum AcquireResult[+Path] derives ReadWriter {

    /**
     * The compiler bridge is already compiled and can be run.
     *
     * @param bridgeJar The path to the compiled compiler bridge jar.
     */
    case Compiled(bridgeJar: Path)

    /**
     * The compiler bridge is not compiled yet and needs to be compiled.
     *
     * @param classpath The classpath to use to compile the compiler bridge.
     * @param bridgeSourcesJar The path to the compiler bridge sources jar.
     */
    case NotCompiled(classpath: Seq[Path], bridgeSourcesJar: Path)

    def map[B](f: Path => B): AcquireResult[B] = this match {
      case Compiled(bridgeJar) => Compiled(f(bridgeJar))
      case NotCompiled(classpath, bridgeSourcesJar) =>
        NotCompiled(classpath.map(f), f(bridgeSourcesJar))
    }

    def fullClasspath: Vector[Path] = this match {
      case Compiled(bridgeJar) => Vector(bridgeJar)
      case NotCompiled(classpath, bridgeSourcesJar) =>
        (Iterator(bridgeSourcesJar) ++ classpath.iterator).toVector
    }
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

    val workingDir0 = resolvePossiblyAliasedPath(workingDir)
    val compileDest0 = resolvePossiblyAliasedPath(compileDest)
    val compilerClasspath0 = compilerClasspath.map(resolvePossiblyAliasedPath)
    val compilerBridgeClasspath0 = compilerBridgeClasspath.map(resolvePossiblyAliasedPath)
    val compilerBridgeSourcesJar0 = resolvePossiblyAliasedPath(compilerBridgeSourcesJar)

    os.makeDir.all(workingDir0)
    os.makeDir.all(compileDest0)

    val sourceFolder =
      try os.unzip(compilerBridgeSourcesJar0, workingDir0 / "unpacked")
      catch {
        case _: UnsupportedClassVersionError =>
          unzipWithJdk(compilerBridgeSourcesJar0, workingDir0 / "unpacked")
        case _: NoClassDefFoundError =>
          unzipWithJdk(compilerBridgeSourcesJar0, workingDir0 / "unpacked")
      }
    val classloader = mill.util.Jvm.createClassLoader(compilerClasspath0, parent = null)

    try {
      val (sources, resources) =
        os.walk(sourceFolder).filter(os.isFile)
          .partition(a => a.ext == "scala" || a.ext == "java")

      resources.foreach { res =>
        val dest = compileDest0 / res.relativeTo(sourceFolder)
        os.move(res, dest, replaceExisting = true, createFolders = true)
      }

      val argsArray = Array[String](
        "-d",
        compileDest0.wrapped.toString,
        "-classpath",
        (compilerClasspath0.iterator ++ compilerBridgeClasspath0).map(_.wrapped.toString)
          .mkString(File.pathSeparator)
      ) ++ sources.map(_.wrapped.toString)

      val allScala = sources.forall(_.ext == "scala")
      val allJava = sources.forall(_.ext == "java")
      if (allJava) {
        val javacExe: String =
          sys.props
            .get("java.home")
            .map(h => new File(h, if (isWin) "bin\\javac.exe" else "bin/javac"))
            .filter(f => f.exists())
            .fold("javac")(_.getAbsolutePath())

        os.call(Seq(javacExe) ++ argsArray)
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
