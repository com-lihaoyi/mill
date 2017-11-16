
package acyclic.plugin
import acyclic.file
import scala.collection.{SortedSet, mutable}
import scala.tools.nsc.{Global, Phase}
import tools.nsc.plugins.PluginComponent

/**
 * - Break dependency graph into strongly connected components
 * - Turn acyclic packages into virtual "files" in the dependency graph, as
 *   aggregates of all the files within them
 * - Any strongly connected component which includes an acyclic.file or
 *   acyclic.pkg is a failure
 *   - Pick an arbitrary cycle and report it
 * - Don't report more than one cycle per file/pkg, to avoid excessive spam
 */
class PluginPhase(val global: Global,
                  cycleReporter: Seq[(Value, SortedSet[Int])] => Unit,
                  force: => Boolean)
                  extends PluginComponent
                  with GraphAnalysis { t =>

  import global._

  val runsAfter = List("typer")

  override val runsBefore = List("patmat")

  val phaseName = "acyclic"
  def pkgName(unit: CompilationUnit) = {
    unit.body
        .collect{case x: PackageDef => x.pid.toString}
        .flatMap(_.split('.'))
  }

  def units = global.currentRun
                    .units
                    .toSeq
                    .sortBy(_.source.content.mkString.hashCode())

  def findAcyclics() = {
    val acyclicNodePaths = for {
      unit <- units
      if unit.body.children.collect{
        case Import(expr, List(sel)) =>
          expr.symbol.toString == "package acyclic" && sel.name.toString == "file"
      }.exists(x => x)
    } yield {
      Value.File(unit.source.path, pkgName(unit))
    }
    val skipNodePaths = for {
      unit <- units
      if unit.body.children.collect{
        case Import(expr, List(sel)) =>
          expr.symbol.toString == "package acyclic" && sel.name.toString == "skipped"
      }.exists(x => x)
    } yield {
      Value.File(unit.source.path, pkgName(unit))
    }

    val acyclicPkgNames = for {
      unit <- units
      pkgObject <- unit.body.collect{case x: ModuleDef if x.name.toString == "package" => x }
      if pkgObject.impl.children.collect{case Import(expr, List(sel)) =>
        expr.symbol.toString == "package acyclic" && sel.name.toString == "pkg"
      }.exists(x => x)
    } yield {
      Value.Pkg(
        pkgObject.symbol
          .enclosingPackageClass
          .fullName
          .split('.')
          .toList
      )
    }
    (skipNodePaths, acyclicNodePaths, acyclicPkgNames)
  }

  override def newPhase(prev: Phase): Phase = new Phase(prev) {
    override def run() {
      val unitMap = units.map(u => u.source.path -> u).toMap
      val nodes = for (unit <- units) yield {

        val deps = DependencyExtraction(t.global)(unit)

        val connections = for{
          (sym, tree) <- deps
          if sym != NoSymbol
          if sym.sourceFile != null
          if sym.sourceFile.path != unit.source.path
        } yield (sym.sourceFile.path, tree)

        Node[Value.File](
          Value.File(unit.source.path, pkgName(unit)),
          connections.groupBy(c => Value.File(c._1, pkgName(unitMap(c._1))): Value)
                     .mapValues(_.map(_._2))
        )
      }

      val nodeMap = nodes.map(n => n.value -> n).toMap

      val (skipNodePaths, acyclicFiles, acyclicPkgs) = findAcyclics()

      val allAcyclics = acyclicFiles ++ acyclicPkgs

      // synthetic nodes for packages, which aggregate the dependencies of
      // their contents
      val pkgNodes = acyclicPkgs.map{ value =>
        Node(
          value,
          nodes.filter(_.value.pkg.startsWith(value.pkg))
               .flatMap(_.dependencies.toSeq)
               .groupBy(_._1)
               .mapValues(_.flatMap(_._2))
        )
      }

      val linkedNodes: Seq[DepNode] = (nodes ++ pkgNodes).map{ d =>
        val extraLinks = for{
          (value: Value.File, pos) <- d.dependencies
          acyclicPkg <- acyclicPkgs
          if nodeMap(value).value.pkg.startsWith(acyclicPkg.pkg)
          if !d.value.pkg.startsWith(acyclicPkg.pkg)
        } yield (acyclicPkg, pos)
        d.copy(dependencies = d.dependencies ++ extraLinks)
      }

      // only care about cycles with size > 1 here
      val components = DepNode.stronglyConnectedComponents(linkedNodes)
                              .filter(_.size > 1)

      val usedNodes = mutable.Set.empty[DepNode]
      for{
        c <- components
        n <- c
        if !usedNodes.contains(n)
        if (!force && allAcyclics.contains(n.value)) || (force && !skipNodePaths.contains(n.value))
      }{
        val cycle = DepNode.smallestCycle(n, c)
        val cycleInfo =
          (cycle :+ cycle.head).sliding(2)
                               .map{ case Seq(a, b) => (a.value, a.dependencies(b.value))}
                               .toSeq
        cycleReporter(
          cycleInfo.map{ case (a, b) => a -> b.map(_.pos.line).to[SortedSet]}
        )

        global.error("Unwanted cyclic dependency")
        for (Seq((value, locs), (nextValue, _)) <- (cycleInfo :+ cycleInfo.head).sliding(2)){
          global.inform("")
          value match{
            case Value.Pkg(pkg) => global.inform(s"package ${pkg.mkString(".")}")
            case Value.File(_, _) =>
          }

          units.find(_.source.path == locs.head.pos.source.path)
               .get
               .echo(locs.head.pos, "")

          val otherLines = locs.tail
                               .map(_.pos.line)
                               .filter(_ != locs.head.pos.line)

          global.inform("symbol: " + locs.head.symbol.toString)

          if (!otherLines.isEmpty){
            global.inform("More dependencies at lines " + otherLines.mkString(" "))
          }

        }
        global.inform("")
        usedNodes ++= cycle
      }
    }

    def name: String = "acyclic"
  }


}
