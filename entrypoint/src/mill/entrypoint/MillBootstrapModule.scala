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
      def top(pkg: Seq[String], name: String) =
        s"""
           |package millbuild${pkg.map("." + _).mkString}
           |import _root_.{millbuild => $$file}
           |import _root_.mill._
           |object $name
           |extends _root_.mill.define.BaseModule(os.Path("${base}"))(
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
           |//MILL_USER_CODE_START_MARKER
           |""".stripMargin

      val bottom = "\n}"

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
            seenScripts(s) = top((s / os.up).relativeTo(base).segments, s.baseName) + finalCode + bottom
            val ivyDeps = importTrees.collect {
              case ImportTree(Seq("$ivy"), Some(mapping), _, _) => mapping.map(_._1)
              case ImportTree(Seq("$ivy", mapping), None, _, _) => Seq(mapping)
            }

            seenIvy.addAll(ivyDeps.flatten)
            val fileImports = importTrees.collect {
              case ImportTree(Seq("$file", rest@_*), mapping, _, _) =>
                mapping match {
                  case None => Seq(s / os.up / rest / os.up / s"${rest.last}.sc")
                  case Some(items) => items.map { case (lhs, rhs) => s / os.up / rest / s"${lhs}.sc" }
                }
            }.flatten

            def prepareImportTree(s: os.Path) =
              s.relativeTo(base).segments.mkString(".").stripSuffix(".sc")
            importTree(prepareImportTree(s)) = fileImports.map(prepareImportTree)
            fileImports.flatMap(traverseScripts)
        }



      }

      val errors = traverseScripts(base / "build.sc")
      (seenScripts.toSeq, seenIvy.toSeq, importTree.toMap, errors)
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
          val dest = T.dest / p.path.relativeTo(base)
          os.write(dest, s)
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
