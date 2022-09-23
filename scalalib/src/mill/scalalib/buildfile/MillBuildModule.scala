package mill.scalalib.buildfile

import mill.api.{Loose, PathRef, experimental}
import mill.buildfile.AmmoniteParser
import mill.define._
import mill.scalalib.api.CompilationResult
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule, UnresolvedPath}
import mill.{Agg, T}
import os.Path

@experimental
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

  override def compileIvyDeps: T[Agg[Dep]] = T {
//    Agg.from(millEmbeddedDeps().map(d => parseDeps(d)))
    val mv = millVersion()
    // TODO: check at which version we introduced which library
    super.compileIvyDeps() ++ Agg(
      ivy"com.lihaoyi::mill-scalalib:${mv}",
      ivy"com.lihaoyi::mill-scalajslib:${mv}",
      ivy"com.lihaoyi::mill-scalanativelib:${mv}",
      ivy"com.lihaoyi::mill-bsp:${mv}"
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

  def millBuildSourceFiles: T[Seq[PathRef]] = T{
    val exts = Seq("sc", "scala", "java")
    allSourceFiles().filter(f => exts.contains(f.path.ext))
  }

  override def sources: Sources = T.sources {
    val optsFileName =
      T.env.get("MILL_JVM_OPTS_PATH").filter(!_.isEmpty).getOrElse(".mill-jvm-opts")

    Seq(
      PathRef(millSourcePath / ".mill-version"),
      PathRef(millSourcePath / ".config" / "mill-version"),
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
    // trigger changes in all source files
    sources()
    val millSetup = parsedMillSetup()
    val replacements = AmmoniteParser.replaceAmmoniteImportsAndShebang(millSetup)
    val contentReader = (file: os.Path) => replacements.get(file).getOrElse(os.read.lines(file))
    val dest = T.dest
    val baseDir = millSetup.projectDir
    val buildSc = buildScFile().path
    millBuildSourceFiles()
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

  override def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), extensions = Seq("java", "sc", "scala")).map(PathRef(_))
  }

  /** Uses [[allSourceFiles]] but replaces those, that needs wrapping. */
  private def compileInputFiles: Task[Seq[Path]] = T.task {
    val wrapped: Map[os.Path, Option[os.Path]] = wrappedSourceFiles().map(w => (w.orig.path, w.wrapped.map(_.path))).toMap
    allSourceFiles().map(pr => wrapped.get(pr.path).flatten.getOrElse(pr.path))
  }

  override def compile: T[CompilationResult] = T {
    // we want to compile modified classes
    // TOOD: we need to map source locations in error messages
    scalaMixedCompileTask(compileInputFiles, allScalacOptions)
  }

  override def semanticDbData: T[PathRef] = T{
    semanticDbDataTask(compileInputFiles)()
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

@experimental
object MillBuildModule extends ExternalModule with MillBuildModule {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}
