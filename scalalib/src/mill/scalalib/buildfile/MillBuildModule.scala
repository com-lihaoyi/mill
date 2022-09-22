package mill.scalalib.buildfile

import mill.api.JsonFormatters._
import mill.api.{Loose, PathRef}
import mill.buildfile.{AmmoniteParser, ParsedMillSetup, ReadDirectives}
import mill.define._
import mill.scalalib.bsp.BspBuildTarget
import mill.scalalib.buildfile.MillBuildModule.millDiscover
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule}
import mill.{Agg, BuildInfo, T}
import upickle.default.{ReadWriter, macroRW}

trait MillSetupScannerModule extends Module {

  object Deps {
    // incorrectly released without patch-level-scala-version
    val millModuledefs = ivy"com.lihaoyi::mill-main-moduledefs:${BuildInfo.millVersion}"
  }

  /** The root path of the Mill project to scan. */
  def millProjectPath: T[os.Path] = T { millSourcePath }

  /** The Mill buildfile. */
  def buildScFile: Source = T.source(millProjectPath() / "build.sc")

  def supportUsingDirectives: T[Boolean] = false

  /** The Mill project setup. */
  def parsedMillSetup: T[ParsedMillSetup] = T {
    val pass1 =
      if (supportUsingDirectives()) {
        ReadDirectives.readUsingDirectives(buildScFile().path)
      } else {
        ParsedMillSetup(
          projectDir = millProjectPath(),
          directives = Seq(),
          buildScript = Option(buildScFile().path).filter(os.exists)
        )
      }
    val pass2 = AmmoniteParser.parseAmmoniteImports(pass1)
    pass2
  }

  def includedSourceFiles: T[Seq[PathRef]] = T {
    parsedMillSetup().includedSourceFiles.map(PathRef(_))
  }

  protected def parseDeps(signature: String): Dep = {
    // a missing version means, we want the Mill version
    val millSig = signature.split("[:]") match {
      case Array(org, "", pname, "", version)
          if org.length > 0 && pname.length > 0 && version.length > 0 =>
        s"${org}::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
      case Array(org, "", "", pname, "", version)
          if org.length > 0 && pname.length > 0 && version.length > 0 =>
        s"${org}:::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
      case Array(org, "", name) if org.length > 0 && name.length > 0 && signature.endsWith(":") =>
        s"${org}::${name}:$$MILL_VERSION"
      case _ => signature
    }

    // replace variables
    val replaced = millSig
      .replace("$MILL_VERSION", mill.BuildInfo.millVersion)
      .replace("${MILL_VERSION}", mill.BuildInfo.millVersion)
      .replace("$MILL_BIN_PLATFORM", mill.BuildInfo.millBinPlatform)
      .replace("${MILL_BIN_PLATFORM}", mill.BuildInfo.millBinPlatform)

    ivy"${replaced}"
  }

}

trait MillBuildModule extends ScalaModule with MillSetupScannerModule {

  override def scalaVersion: T[String] = T(BuildInfo.scalaVersion)

  override def platformSuffix: T[String] = T("_mill" + BuildInfo.millBinPlatform)
  override def artifactSuffix: T[String] = T(s"${platformSuffix()}_${artifactScalaVersion()}")

  protected def millEmbeddedDeps: Input[Seq[String]] = T.input {
    BuildInfo.millEmbeddedDeps
  }

  override def compileIvyDeps: T[Agg[Dep]] = T {
    Agg.from(millEmbeddedDeps().map(d => parseDeps(d)))
  }

  override def ivyDeps: T[Agg[Dep]] = T {
    val deps = parsedMillSetup().ivyDeps
    Agg.from(deps.map(d => parseDeps(d)))
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

  def wrapScToScala(
      file: os.Path,
      destDir: os.Path,
      contentReader: os.Path => Seq[String] = os.read.lines
  ) = {
    val prefix =
      s"""// Wrapped from ${file}
         |object `${file.baseName}` {
         |""".stripMargin
    val suffix = "\n}"

    val destFile = destDir / (file.last + ".scala")
    os.write(destFile, prefix + contentReader(file).mkString("\n") + suffix)

    destFile
  }

  def wrapMainBuildScToScala(
      file: os.Path,
      destDir: os.Path,
      projectDir: os.Path,
      contentReader: os.Path => Seq[String] = os.read.lines
  ) = {
    val wrapName = s"`${file.baseName}`"
    val literalPath = pprint.Util.literalize(projectDir.toString)

    val foreign = "None"

    val prefix =
      s"""// Wrapped from ${file}
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

    val destFile = destDir / (file.last + ".scala")
    os.write(destFile, prefix + contentReader(file).mkString("\n") + suffix)

    destFile

  }

  def wrapForeignBuildScToScala(
      file: os.Path,
      destDir: os.Path,
      contentReader: os.Path => Seq[String] = os.read.lines
  ) = {
    val prefix =
      s"""// Wrapped from ${file}
         |object `${file.baseName}` {
         |""".stripMargin
    val suffix = "\n}"

    val destFile = destDir / (file.last + ".scala")
    os.write(destFile, prefix + contentReader(file).mkString("\n") + suffix)

    destFile
  }

  def wrappedSourceFiles: T[Seq[WrappedSource]] = T {
    val millSetup = parsedMillSetup()
    val replacements = AmmoniteParser.replaceAmmoniteImports(millSetup)
    val contentReader = (file: os.Path) => replacements.get(file).getOrElse(os.read.lines(file))
    val dest = T.dest
    val baseDir = millSetup.projectDir
    val buildSc = buildScFile().path
    Lib.findSourceFiles(allSources(), Seq("scala", "sc"))
      .map { file =>
        val mappedFile =
          if (file == buildSc) {
            Some(wrapMainBuildScToScala(file, dest, baseDir, contentReader))
          } else {
            val relFile = file.relativeTo(baseDir)
            // TODO: encode dirs up
            if (relFile.last == "build.sc") {
              Some(wrapForeignBuildScToScala(file, dest, contentReader))
            } else if (file.ext == "sc") {
              Some(wrapScToScala(file, dest, contentReader))
            } else None
          }
        WrappedSource(PathRef(file), mappedFile.map(p => PathRef(p)))
      }
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    wrappedSourceFiles().map { w => w.wrapped.getOrElse(w.orig) }
  }

  override def scalacPluginIvyDeps: Target[Loose.Agg[Dep]] = T{
    super.scalacPluginIvyDeps() ++ Agg(Deps.millModuledefs)
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
