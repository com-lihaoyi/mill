package mill.scalalib.buildfile

import mill.api.JsonFormatters._
import mill.api.{Loose, PathRef, internal}
import mill.buildfile.{AmmoniteParser, ParsedMillSetup, ReadDirectives}
import mill.define._
import mill.scalalib.api.CompilationResult
import mill.scalalib.bsp.BspBuildTarget
import mill.scalalib.buildfile.MillBuildModule.millDiscover
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule, UnresolvedPath}
import mill.{Agg, BuildInfo, T}
import os.Path
import upickle.default.{ReadWriter, macroRW}

/** A module that scans a mill project by inspecting the `build.sc`. */
trait MillSetupScannerModule extends Module {

//  object Deps {
//    // incorrectly released without patch-level-scala-version
//    val millModuledefs = ivy"com.lihaoyi::mill-main-moduledefs:${BuildInfo.millVersion}"
//  }

  /** The root path of the Mill project to scan. */
  def millProjectPath: T[os.Path] = T { millSourcePath }

  /** The Mill buildfile. */
  def buildScFile: Source = T.source(millProjectPath() / "build.sc")

  def supportUsingDirectives: T[Boolean] = false

  /** The Mill project setup. */
  def parsedMillSetup: T[ParsedMillSetup] = T {
    val buildSc = buildScFile().path

    val pass1 =
      if (supportUsingDirectives()) {
        ReadDirectives.readUsingDirectives(buildSc, parseVersionFiles = true)
      } else {
        ParsedMillSetup(
          projectDir = millProjectPath(),
          directives = ReadDirectives.readVersionFiles(buildSc / os.up),
          buildScript = Option(buildScFile().path).filter(os.exists)
        )
      }
    val pass2 = AmmoniteParser.parseAmmoniteImports(pass1)
    pass2
  }

  def millVersion: T[String] = T {
    parsedMillSetup().millVersion.getOrElse(BuildInfo.millVersion)
  }

  def millBinPlatform: T[String] = T {
    millVersion().split("[.]") match {
      case Array("0", "0" | "1" | "2" | "3" | "4" | "5", _*) => ""
      case Array("0", a, _*) => s"0.${a}"
      // TODO: support Milestone releases
    }
  }

  def includedSourceFiles: T[Seq[PathRef]] = T {
    parsedMillSetup().includedSourceFiles.map(PathRef(_))
  }

  protected def parseDeps: Task[String => Dep] = T.task { signature: String =>
    val mv = millVersion()
    val mbp = millBinPlatform()

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
      .replace("$MILL_VERSION", mv)
      .replace("${MILL_VERSION}", mv)
      .replace("$MILL_BIN_PLATFORM", mbp)
      .replace("${MILL_BIN_PLATFORM}", mbp)

    ivy"${replaced}"
  }

}

trait MillBuildModule extends ScalaModule with MillSetupScannerModule {

  override def scalaVersion: T[String] = T {
    millBinPlatform() match {
      case "" | "0.6" => "2.12.17"
      case _ => "2.13.9"
    }
  }

  override def platformSuffix: T[String] = T {
    Option(millBinPlatform()).filter(_.nonEmpty).map("_mill" + _).getOrElse("")
  }

  override def artifactSuffix: T[String] = T(s"${platformSuffix()}_${artifactScalaVersion()}")

//  protected def millEmbeddedDeps: Input[Seq[String]] = T.input {
//    millBinPlatform() match {
//      case "" =>
//    }
////    Seq("com.lihaoyi::mill-scalalib_2.13:0.10.7-24-b7869c-DIRTY158cb392",
  // "com.lihaoyi:mill-scalajslib_2.13:0.10.7-24-b7869c-DIRTY158cb392",
  // "com.lihaoyi:mill-scalanativelib_2.13:0.10.7-24-b7869c-DIRTY158cb392",
  // "com.lihaoyi:mill-bsp_2.13:0.10.7-24-b7869c-DIRTY158cb392")
//    BuildInfo.millEmbeddedDeps
//  }

  override def compileIvyDeps: T[Agg[Dep]] = T {
//    Agg.from(millEmbeddedDeps().map(d => parseDeps(d)))
    val mv = millVersion()
    // TODO: check at which version we introduced which library
    super.compileIvyDeps() ++ Agg(
      ivy"com.lihaoyi::mill-scalalib:${mv}",
      ivy"com.lihaoyi::mill-scalajslib:${mv}",
      ivy"com.lihaoyi::mill-scalanativelib:${mv}",
      ivy"com.lihaoyi::mill-bsp:${mv}",
    )
  }

  override def ivyDeps: T[Agg[Dep]] = T {
    val parseDepsTask = parseDeps()
    val deps = parsedMillSetup().rawIvyDeps
    super.ivyDeps() ++ Agg.from(deps.map(d => parseDepsTask(d)))
  }

  override def scalacPluginIvyDeps: T[Loose.Agg[Dep]] = T {
    super.scalacPluginIvyDeps() ++ Agg(
      // incorrectly released without patch-level-scala-version
      ivy"com.lihaoyi::mill-main-moduledefs:${millVersion()}"
    )
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

  override def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), extensions = Seq("java", "sc", "scala")).map(PathRef(_))
  }

  def wrappedSourceFiles: T[Seq[WrappedSource]] = T {
    // trigger changes in all source files
    sources()
    val millSetup = parsedMillSetup()
    val replacements = AmmoniteParser.replaceAmmoniteImportsAndShebang(millSetup)
    val contentReader = (file: os.Path) => replacements.get(file).getOrElse(os.read.lines(file))
    val dest = T.dest
    val baseDir = millSetup.projectDir
    val buildSc = buildScFile().path
    allSourceFiles()
      .map { pr =>
        val file = pr.path
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

  override def compile: T[CompilationResult] = T {
    // we want to compile modified classes
    // TOOD: we need to map source locations in error messages
    val sourceFiles = T.task {
      wrappedSourceFiles().map { w => w.wrapped.getOrElse(w.orig).path }
    }
    scalaMixedCompileTask(sourceFiles, allScalacOptions)
  }

  /** the path to the compiled classes without forcing the compilation. */
  override def bspCompileClassesPath: Target[UnresolvedPath] =
    if (compile.ctx.enclosing == s"${classOf[MillBuildModule].getName}#compile") {
      T {
        T.log.debug(
          s"compile target was not overridden, assuming hard-coded classes directory for target ${compile}"
        )
        UnresolvedPath.DestPath(os.sub / "classes", compile.ctx.segments, compile.ctx.foreign)
      }
    } else {
      T {
        T.log.debug(
          s"compile target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compile}"
        )
        UnresolvedPath.ResolvedPath(compile().classes.path)
      }
    }

  override def scalacOptions: Target[Seq[String]] = T {
    super.scalacOptions() ++ Seq("-deprecation")
  }

//  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
//    displayName = Some("mill-build")
//  )
}

object MillBuildModule extends ExternalModule with MillBuildModule {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}

object MillNextBuildModule extends ExternalModule with MillBuildModule {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  override def supportUsingDirectives: T[Boolean] = T(true)
}

case class WrappedSource(orig: PathRef, wrapped: Option[PathRef])

object WrappedSource {
  implicit val upickleRW: ReadWriter[WrappedSource] = macroRW
}
