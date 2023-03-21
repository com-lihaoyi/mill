package mill.entrypoint
import coursier.{Dependency, Module, Organization}
import mill._
import mill.api.Result
class MillBootstrapModule(enclosingClasspath: Seq[os.Path], millSourcePath0: os.Path)
  extends mill.define.BaseModule(os.pwd)(implicitly, implicitly, implicitly, implicitly, mill.define.Caller(()))
    with mill.scalalib.ScalaModule {
  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]

  def scalaVersion = "2.13.10"

//  def buildFiles = T.sources{ discoveredScripts()._1.map(t => PathRef(t._1)) }

  def discoveredScripts = T.input{
    def top(pkg: Seq[String], name: String) =
      s"""
         |package millbuild${pkg.map("." + _).mkString}
         |import _root_.{millbuild => $$file}
         |import _root_.mill._
         |object $name
         |extends _root_.mill.define.BaseModule(os.Path("${os.pwd}"))(
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
         |""".stripMargin

    val bottom = "\n}"

    val seenScripts = collection.mutable.Map.empty[os.Path, String]
    val seenIvy = collection.mutable.Set.empty[String]
    def traverseScripts(s: os.Path): Unit = {
      if (seenScripts.contains(s)) return

      val buildFileCode = os.read(s)
      val (cleanedStmts, importTrees) = Parsers.splitScript(buildFileCode, s.last) match {
        case Left(err) => throw new Exception(err)
        case Right(stmts) => Parsers.parseImportHooksWithIndices(stmts)
      }

      val finalCode = cleanedStmts.mkString
      seenScripts(s) = top((s / os.up).relativeTo(os.pwd).segments, s.baseName) + finalCode + bottom
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

      fileImports.foreach(traverseScripts)
    }




    traverseScripts(os.pwd / "build.sc")
    (seenScripts.toSet, seenIvy.toSet)
  }

  def ivyDeps = T{
    Result.Success(Agg.from(discoveredScripts()._2.map(mill.scalalib.Dep.parse(_))))
  }

  def generatedSources = T.sources {
    for((p, s) <- discoveredScripts()._1.toSeq) yield {
      val dest = T.dest / p.relativeTo(os.pwd) / os.up / s"${p.baseName}.scala"
      os.write(dest, s)
      PathRef(dest)
    }
  }

  def unmanagedClasspath = mill.define.Target.input {
    mill.api.Loose.Agg.from(enclosingClasspath.map(p => mill.api.PathRef(p)))
  }

  def millSourcePath = millSourcePath0
}
