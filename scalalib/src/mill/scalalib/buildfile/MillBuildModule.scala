package mill.scalalib.buildfile

import mill.api.{Loose, PathRef}
import mill.buildfile.{ParsedMillSetup, ReadDirectives}
import mill.define._
import mill.scalalib.bsp.BspBuildTarget
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule}
import mill.{Agg, BuildInfo, T}
import upickle.default.{ReadWriter, macroRW}

trait MillBuildModule extends ScalaModule {

  override def scalaVersion: T[String] = T(BuildInfo.scalaVersion)

  override def platformSuffix: T[String] = T("_mill" + BuildInfo.millBinPlatform)
  override def artifactSuffix: T[String] = T(s"${platformSuffix()}_${artifactScalaVersion()}")

  override def compileIvyDeps: T[Agg[Dep]] = T {
    val deps = BuildInfo.millEmbeddedDeps
    Agg.from(deps.map(d => ivy"${d}"))
  }

  override def ivyDeps: T[Agg[Dep]] = T {
    val deps = parsedMillSetup().ivyDeps
    Agg.from(deps.map(d => ivy"${d}"))
  }

  def buildScFile: Source = T.source(millSourcePath / "build.sc")

  def parsedMillSetup: T[ParsedMillSetup] = T {
    ReadDirectives.readUsingDirectives(buildScFile().path)
  }

  def includedSourceFiles: T[Seq[PathRef]] = T {
    parsedMillSetup().includedSourceFiles.map(PathRef(_))
  }

  override def sources: Sources = T.sources {
    val optsFileName =
      T.env.get("MILL_JVM_OPTS_PATH").filter(!_.isEmpty).getOrElse(".mill-jvm-opts")

    Seq(
      PathRef(millSourcePath / ".mill-version"),
      PathRef(millSourcePath / optsFileName),
      buildScFile()
    ) ++ includedSourceFiles()
  }

  def wrapScToScala(file: os.Path, destDir: os.Path) = {
    val prefix =
      s"""object `${file.baseName}` {
         |""".stripMargin
    val suffix = "\n}"
    val content = os.read.lines(file)

    val destFile = destDir / (file.last + ".scala")
    os.write(destFile, prefix + content.mkString("\n") + suffix)

    destFile
  }

  def wrapMainBuildScToScala(file: os.Path, destDir: os.Path, projectDir: os.Path) = {
    val wrapName = s"`${file.baseName}`"
    val literalPath = pprint.Util.literalize(projectDir.toString)

    val foreign = "None"

    val prefix =
      s"""
         |object ${wrapName}
         |extends _root_.mill.define.BaseModule(os.Path($literalPath), foreign0 = ${foreign})(
         |  implicitly, implicitly, implicitly, implicitly, _root_.mill.define.Caller(())
         |)
         |with ${wrapName} {
         |  // Stub to make sure Ammonite has something to call after it evaluates a script,
         |  // even if it does nothing...
         |  def $$main() = Iterator[String]()
         |
         |  // Need to wrap the returned Module in Some(...) to make sure it
         |  // doesn't get picked up during reflective child-module discovery
         |  def millSelf = Some(this)
         |
         |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
         |}
         |
         |sealed trait ${wrapName} extends _root_.mill.main.MainModule {
         |""".stripMargin

    val suffix = "\n}"

    val content = os.read.lines(file)

    val destFile = destDir / (file.last + ".scala")
    os.write(destFile, prefix + content.mkString("\n") + suffix)

    destFile

  }

  def wrapForeignBuildScToScala(file: os.Path, destDir: os.Path) = {
    val prefix =
      s"""object `${file.baseName}` {
         |""".stripMargin
    val suffix = "\n}"
    val content = os.read.lines(file)

    val destFile = destDir / (file.last + ".scala")
    os.write(destFile, prefix + content.mkString("\n") + suffix)

    destFile
  }

  def wrappedSourceFiles: T[Seq[WrappedSource]] = T {
    val dest = T.dest
    val buildSc = buildScFile().path
    val baseDir = buildSc / os.up
    Lib.findSourceFiles(allSources(), Seq("scala", "sc"))
      .map { file =>
        val mappedFile =
          if (file.ext != "sc") None
          else Some(
            if (file == buildSc) {
              wrapMainBuildScToScala(file, dest, baseDir)
            } else {
              val relFile = file.relativeTo(baseDir)
              // TODO: encode dirs up
              if (relFile.last == "build.sc") {
                wrapForeignBuildScToScala(file, dest)
              } else {
                wrapScToScala(file, dest)
              }
            }
          )
        WrappedSource(PathRef(file), mappedFile.map(p => PathRef(p)))
      }
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    wrappedSourceFiles().map { w => w.wrapped.getOrElse(w.orig) }
  }

  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    displayName = Some("mill-build")
  )
}

object MillBuildModule extends ExternalModule with MillBuildModule {

  lazy val millDiscover: Discover[MillBuildModule.this.type] = Discover[this.type]

}

case class WrappedSource(orig: PathRef, wrapped: Option[PathRef])

object WrappedSource {
  implicit val upickleRW: ReadWriter[WrappedSource] = macroRW
}
