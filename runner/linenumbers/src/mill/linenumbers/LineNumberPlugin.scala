package mill.linenumbers

import scala.tools.nsc._
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

// TRANSFORMING
//
// class MillPackageClass
// extends _root_.mill.main.RootModule.$superClass($segsList) {
//   import a.b
//   import a.b.c
//   object module extends Foo with Bar with Baz {
//     import x.y
//     import x.y.z
//     ...
//   }
// }
//
// BECOMES
//
// import a.b
// import a.b.c
// class MillPackageClass
// extends _root_.mill.main.RootModule.$superClass($segsList)
// with Foo with Bar with Baz{
//   import x.y
//   import x.y.z
//   ...
// }
/**
 * Used to capture the names in scope after every execution, reporting them
 * to the `output` function. Needs to be a compiler plugin so we can hook in
 * immediately after the `typer`
 */
class LineNumberPlugin(val global: Global) extends Plugin {
  override def init(options: List[String], error: String => Unit): Boolean = true
  val name: String = "mill-linenumber-plugin"
  val description = "Adjusts line numbers in the user-provided script to compensate for wrapping"
  val components: List[PluginComponent] = List(
    new PluginComponent {
      val global = LineNumberPlugin.this.global
      val runsAfter = List("parser")
      val phaseName = "FixLineNumbers"
      def newPhase(prev: Phase): Phase = new global.GlobalPhase(prev) {
        def name = phaseName
        def apply(unit: global.CompilationUnit): Unit = {
          LineNumberPlugin.apply(global)(unit)
        }
      }
    }
  )
}

object LineNumberPlugin {
  def apply(g: Global)(unit: g.CompilationUnit): Unit = {

    object LineNumberCorrector extends g.Transformer {
      import scala.reflect.internal.util._

      val str = new String(g.currentSource.content)
      val userCodeStartMarker = "//MILL_USER_CODE_START_MARKER"
      val lines = str.linesWithSeparators.toVector

      val adjustedFile = lines
        .collectFirst { case s"//MILL_ORIGINAL_FILE_PATH=$rest" => rest.trim }
        .get

      val markerLine = lines.indexWhere(_.startsWith(userCodeStartMarker))

      val topWrapperLen = lines.take(markerLine + 1).map(_.length).sum

      val trimmedSource = new BatchSourceFile(
        new scala.reflect.io.PlainFile(adjustedFile),
        g.currentSource.content.drop(topWrapperLen)
      )

      override def transform(tree: g.Tree) = {
        val transformedTree = super.transform(tree)
        // The `start` and `end` values in transparent/range positions are left
        // untouched, because of some aggressive validation in scalac that checks
        // that trees are not overlapping, and shifting these values here
        // violates the invariant (which breaks Ammonite, potentially because
        // of multi-stage).
        // Moreover, we rely only on the "point" value (for error reporting).
        // The ticket https://github.com/scala/scala-dev/issues/390 tracks down
        // relaxing the aggressive validation.
        val newPos = tree.pos match {
          case s: TransparentPosition if s.start > topWrapperLen =>
            new TransparentPosition(
              trimmedSource,
              s.start - topWrapperLen,
              s.point - topWrapperLen,
              s.end - topWrapperLen
            )
          case s: RangePosition if s.start > topWrapperLen =>
            new RangePosition(
              trimmedSource,
              s.start - topWrapperLen,
              s.point - topWrapperLen,
              s.end - topWrapperLen
            )
          case s: OffsetPosition if s.start > topWrapperLen =>
            new OffsetPosition(trimmedSource, s.point - topWrapperLen)
          case s => s

        }
        transformedTree.pos = newPos

        transformedTree
      }

      def apply(unit: g.CompilationUnit) = transform(unit.body)
    }

    object PackageObjectUnpacker extends g.Transformer {

      def isRootModuleIdent(t: g.Tree) = t match {
        case i: g.Ident =>
          i.name.toString() == "RootModule" || i.name.toString() == "MillBuildRootModule"
        case t => false
      }
      override def transform(tree: g.Tree) = tree match {
        case pkgDef: g.PackageDef =>
          val resOpt = pkgDef.stats.zipWithIndex.collect {
            case (pkgCls: g.ClassDef, pkgClsIndex)
                if pkgCls.name.toString().startsWith("MillPackageClass") =>
              pkgCls.impl.body.collect {
                case pkgObj: g.ModuleDef
                    if pkgObj.name.toString() == g.currentSource.file.name.stripSuffix(".sc")
                      || pkgObj.impl.parents.exists(isRootModuleIdent) =>
                  val (importStmts, nonImportStmts) =
                    pkgCls.impl.body.partition(_.isInstanceOf[g.Import])
                  val newPkgCls = g.treeCopy.ClassDef(
                    pkgCls,
                    pkgCls.mods,
                    pkgCls.name,
                    pkgCls.tparams,
                    g.treeCopy.Template(
                      pkgCls.impl,
                      pkgCls.impl.parents ++ pkgObj.impl.parents.filter(!isRootModuleIdent(_)),
                      g.ValDef(g.Modifiers(), pkgObj.name, g.EmptyTree, g.EmptyTree),
                      nonImportStmts
                        .filter {
                          case d: g.DefDef => d.name.toString() != "<init>"
                          case t => t ne pkgObj
                        } ++
                        pkgObj.impl.body
                    )
                  )
                  (pkgClsIndex, importStmts, newPkgCls)
              }
          }.flatten
          resOpt match {
            case Nil => super.transform(tree)
            case List((pkgClsIndex, newOuterStmts, newPkgCls)) =>
              val (before, after) = pkgDef.stats.splitAt(pkgClsIndex)
              g.treeCopy.PackageDef(
                unit.body,
                pkgDef.pid,
                before ++ newOuterStmts ++ Seq(newPkgCls) ++ after.drop(1)
              )
          }
        case tree => super.transform(tree)
      }

      def apply(unit: g.CompilationUnit) = transform(unit.body)
    }

    if (g.currentSource.file.hasExtension("sc")) {
      unit.body = LineNumberCorrector(unit)

      if (g.currentSource.file.name == "module.sc" || g.currentSource.file.name == "build.sc") {
        unit.body = PackageObjectUnpacker(unit)
      }
    }
  }
}
