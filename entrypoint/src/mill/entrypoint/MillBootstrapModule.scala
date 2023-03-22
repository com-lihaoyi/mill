package mill.entrypoint
import coursier.{Dependency, Module, Organization}
import mill._
import mill.api.{Loose, PathRef, Result}
import mill.scalalib.{DepSyntax, Lib, Versions}
class MillBootstrapModule(enclosingClasspath: Seq[os.Path], base: os.Path)
  extends mill.define.BaseModule(base)(implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())) {

  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]

  object millbuild extends mill.scalalib.ScalaModule {
    def scalaVersion = "2.13.10"

    def parseBuildFiles = T.input {
      MillBootstrapModule.parseBuildFiles(base)
    }

    def ivyDeps = T {
      Agg.from(parseBuildFiles()._2.map(mill.scalalib.Dep.parse(_))) ++
      Seq(ivy"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
    }

    def scriptSources = T.sources {
      for ((p, s) <- parseBuildFiles()._1) yield PathRef(p)
    }

    def generatedSources = T {
      val (scripts, ivy, importTrees, errors) = parseBuildFiles()
//      pprint.log(importTrees)
      if (errors.nonEmpty) Result.Failure(errors.mkString("\n"))
      else Result.Success(
        for ((p, (_, s)) <- scriptSources().zip(parseBuildFiles()._1)) yield {
//          pprint.log(p.path)
          val relative = p.path.relativeTo(base)
          val segments = Seq.fill(relative.ups)("^") ++ relative.segments
          val dest = T.dest / segments

          val fileImportProxy: String = {
            importTrees(segments.mkString(".").stripSuffix(".sc"))
              .filter(_.contains("."))
              .map{s => s"import millbuild.$s"}
              .mkString("\n")
          }
          os.write(
            dest,
            MillBootstrapModule.top(relative, p.path / os.up, segments.dropRight(1), p.path.baseName, fileImportProxy) +
            s +
            MillBootstrapModule.bottom,
            createFolders = true
          )
          PathRef(dest)
        }
      )
    }

    def importTree = T {
      val (scripts, ivy, importTree, errors) = parseBuildFiles()
      importTree
    }

    override def allSourceFiles: T[Seq[PathRef]] = T {
      Lib.findSourceFiles(allSources(), Seq("scala", "java", "sc")).map(PathRef(_))
    }

    def unmanagedClasspath = mill.define.Target.input {
      mill.api.Loose.Agg.from(enclosingClasspath.map(p => mill.api.PathRef(p))) ++
      lineNumberPluginClasspath()
    }

    def scalacPluginIvyDeps = Agg(
      ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
    )

    def scalacPluginClasspath = super.scalacPluginClasspath() ++ lineNumberPluginClasspath()

    def lineNumberPluginClasspath: T[Agg[PathRef]] = T {
      mill.modules.Util.millProjectModule(
        "MILL_LINENUMBERS",
        "mill-entrypoint-linenumbers",
        repositoriesTask()
      )
    }
  }
}

object MillBootstrapModule{
  def top(relative: os.RelPath, base: os.Path, pkg: Seq[String], name: String, fileImportProxy: String) = {
    val foreign =
      if (pkg.nonEmpty || name != "build") {
        // Computing a path in "out" that uniquely reflects the location
        // of the foreign module relatively to the current build.

        // Encoding the number of `/..`
        val ups = if (relative.ups > 0) Seq(s"up-${relative.ups}") else Seq()
        val segs =
          Seq("foreign-modules") ++
          ups ++
          relative.segments.init ++
          Seq(relative.segments.last.stripSuffix(".sc"))

        val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
        s"Some(_root_.mill.define.Segments.labels($segsList))"
      } else "None"
    s"""
       |package millbuild${pkg.map("." + _).mkString}
       |import _root_.mill._
       |object $name
       |extends _root_.mill.define.BaseModule(os.Path("${base}"), foreign0 = $foreign)(
       |  implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())
       |)
       |with $name{
       |  // Stub to make sure Ammonite has something to call after it evaluates a script,
       |  // even if it does nothing...
       |  def $$main() = Iterator[String]()
       |
       |  // Need to wrap the returned Module in Some(...) to make sure it
       |  // doesn't get picked up during reflective child-module discovery
       |  def millSelf = Some(this)
       |
       |  @_root_.scala.annotation.nowarn("cat=deprecation")
       |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
       |}
       |
       |sealed trait $name extends _root_.mill.main.MainModule{
       |
       |$fileImportProxy
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"
  def parseBuildFiles(base: os.Path) = {


    val seenScripts = collection.mutable.Map.empty[os.Path, String]
    val seenIvy = collection.mutable.Set.empty[String]
    val importTree = collection.mutable.Map.empty[String, Seq[String]]

    def traverseScripts(s: os.Path): Seq[String] = {
      if (seenScripts.contains(s)) Nil
      else Parsers.splitScript(os.read(s), s.last) match {
        case Left(err) =>
          // Make sure we mark even scripts that failed to parse as seen, so
          // they can be watched and the build can be re-triggered if the user
          // fixes the parse error
          seenScripts(s) = ""
          Seq(err)
        case Right(stmts) =>
          val (cleanedStmts, importTrees) = Parsers.parseImportHooksWithIndices(stmts)

          val finalCode = cleanedStmts.mkString
          seenScripts(s) = finalCode
          val ivyDeps = importTrees.collect {
            case ImportTree(Seq("$ivy"), Some(mapping), _, _) => mapping.map(_._1)
            case ImportTree(Seq("$ivy", mapping), None, _, _) => Seq(mapping)
          }

          seenIvy.addAll(ivyDeps.flatten)
          val fileImports = importTrees.collect {
            case ImportTree(Seq("$file", rest@_*), mapping, _, _) =>
//              pprint.log(rest)
              def nextPathFor(rest: Seq[String]): os.Path = {
                s / os.up / rest.map{case "^" => os.up case s => os.rel/s}.foldLeft(os.rel)(_ / _) / os.up / s"${rest.last}.sc"
              }
              val nextPaths = mapping match {
                case None => Seq(nextPathFor(rest))
                case Some(items) => items.map { case (lhs, rhs) => nextPathFor(rest :+ lhs) }
              }

//              pprint.log(nextPaths)
              nextPaths
          }.flatten

          def prepareImportTree(s: os.Path) = {
            val rel = s.relativeTo(base)
            (Seq.fill(rel.ups)("^") ++ rel.segments).mkString(".").stripSuffix(".sc")
          }

          importTree(prepareImportTree(s)) = fileImports.map(prepareImportTree)
          fileImports.flatMap(traverseScripts)
      }
    }

    val errors = traverseScripts(base / "build.sc")
    (seenScripts.toSeq, seenIvy.toSeq, importTree.toMap, errors)
  }
}
