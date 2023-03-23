package mill.entrypoint
import coursier.{Dependency, Module, Organization}

import collection.mutable
import mill._
import mill.api.{Loose, PathRef, Result}
import mill.define.Task
import mill.scalalib.{BoundDep, DepSyntax, Lib, Versions}
class MillBootstrapModule(enclosingClasspath: Seq[os.Path], base: os.Path)
  extends mill.define.BaseModule(base)(implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())) {

  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]

  object millbuild extends mill.scalalib.ScalaModule {
    def resolveDeps(deps: Task[Agg[BoundDep]], sources: Boolean = false): Task[Agg[PathRef]] = T.task{
      // We need to resolve the sources to make GenIdeaExtendedTests pass for
      // some reason, but we don't need to actually return them (???)
      val unused = super.resolveDeps(deps, true)()

      super.resolveDeps(deps, false)()
    }
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
      if (errors.nonEmpty) Result.Failure(errors.mkString("\n"))
      else Result.Success(
        for ((p, (_, s)) <- scriptSources().zip(parseBuildFiles()._1)) yield {
          val relative = p.path.relativeTo(base)
          val segments = Seq.fill(relative.ups)("^") ++ relative.segments
          val dest = T.dest / segments

          os.write(
            dest,
            MillBootstrapModule.top(relative, p.path / os.up, segments.dropRight(1), p.path.baseName) +
            s +
            MillBootstrapModule.bottom,
            createFolders = true
          )
          PathRef(dest)
        }
      )
    }

    def scriptImportGraph = T {
      val (scripts, ivy, scriptImportGraph, errors) = parseBuildFiles()
      scriptImportGraph
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
  def top(relative: os.RelPath, base: os.Path, pkg: Seq[String], name: String) = {
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
       |extends _root_.mill.define.BaseModule(os.Path("${pprint.Util.literalize(base.toString)}"), foreign0 = $foreign)(
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
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"
  def parseBuildFiles(base: os.Path) = {


    val seenScripts = mutable.Map.empty[os.Path, String]
    val seenIvy = mutable.Set.empty[String]
    val importGraphEdges = mutable.Map.empty[String, (os.Path, Seq[String])]
    val errors = mutable.Buffer.empty[String]
    def traverseScripts(s: os.Path): Unit = {
      val importGraphId = prepareImportGraphEdges(s)
      importGraphEdges(importGraphId) = (s, Nil)
      val fileImports = mutable.Set.empty[os.Path]

      if (!seenScripts.contains(s)) {
        val txt =
          try os.read(s)
          catch{case e => throw new Exception(os.list(s / os.up).map("[[[" + _ + "]]]")mkString("\n"))}
        Parsers.splitScript(txt, s.last) match {
          case Left(err) =>
            // Make sure we mark even scripts that failed to parse as seen, so
            // they can be watched and the build can be re-triggered if the user
            // fixes the parse error
            seenScripts(s) = ""
            errors.append(err)
          case Right(stmts) =>
            val parsedStmts = Parsers.parseImportHooksWithIndices(stmts)

            val transformedStmts = mutable.Buffer.empty[String]

            for((stmt0, importTrees) <- parsedStmts) {
              var stmt = stmt0
              for(importTree <- importTrees) {
                val (start, patchString, end) = importTree match {
                  case ImportTree(Seq(("$ivy", _), rest@_*), mapping, start, end) =>
                    seenIvy.addAll(mapping.map(_._1))
                    (start, "_root_._", end)
                  case ImportTree(Seq(("$file", _), rest@_*), mapping, start, end) =>
                    val nextPaths = mapping.map { case (lhs, rhs) => nextPathFor(s, rest.map(_._1) :+ lhs) }

                    val patchPrefix = prepareImportGraphEdges(nextPaths(0) / os.up)
                    fileImports.addAll(nextPaths)

                    importGraphEdges(importGraphId) = (
                      importGraphEdges(importGraphId)._1,
                      importGraphEdges(importGraphId)._2 ++ nextPaths.map(prepareImportGraphEdges)
                    )

                    if (rest.isEmpty) (start, "_root_._", end)
                    else {
                      val end = rest.last._2
                      (start, patchPrefix, end)
                    }
                }
                val numNewLines = stmt.substring(start, end).count(_ == '\n')
                stmt = stmt.patch(start, patchString + "\n" * numNewLines, end - start)
              }

              transformedStmts.append(stmt)
            }
            val finalCode = transformedStmts.mkString
            seenScripts(s) = finalCode
        }
      }
      fileImports.foreach(traverseScripts)
    }

    def nextPathFor(s: os.Path, rest: Seq[String]): os.Path = {
      val restSegments = rest
        .map { case "^" => os.up case s => os.rel / s }
        .foldLeft(os.rel)(_ / _)

      s / os.up / restSegments / os.up / s"${rest.last}.sc"
    }

    def prepareImportGraphEdges(s: os.Path) = {
      val rel = s.relativeTo(base)
      (Seq("millbuild") ++ Seq.fill(rel.ups)("^") ++ rel.segments).mkString(".").stripSuffix(".sc")
    }

    traverseScripts(base / "build.sc")
    (seenScripts.toSeq, seenIvy.toSeq, importGraphEdges.toMap, errors)
  }
}
