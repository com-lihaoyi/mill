package mill.linenumbers

import scala.tools.nsc.Global

object PackageObjectUnpacker {
  def apply(g: Global, adjustedFile: String)(unit: g.CompilationUnit): g.Tree = {
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
    def isRootModuleIdent(t: g.Tree) = t match {
      case i: g.Ident =>
        i.name.toString() == "RootModule" || i.name.toString() == "MillBuildRootModule"
      case t => false
    }

    val expectedName = "package"
    object Transformer extends g.Transformer {
      override def transform(tree: g.Tree) = tree match {
        case pkgDef: g.PackageDef =>
          val resOpt = pkgDef.stats.zipWithIndex.collect {
            case (pkgCls: g.ClassDef, pkgClsIndex)
                if pkgCls.name.toString().startsWith("MillPackageClass") =>
              pkgCls.impl.body.collect {
                case pkgObj: g.ModuleDef
                    if pkgObj.name.toString() == expectedName || pkgObj.name.toString() == "build"
                      || pkgObj.impl.parents.exists(isRootModuleIdent) =>
                  val (outerStmts, innerStmts) =
                    pkgCls.impl.body
                      .filter {
                        case d: g.DefDef => d.name.toString() != "<init>"
                        case t => t ne pkgObj
                      }
                      .partition {
                        case t: g.Import => true
                        case t: g.ClassDef => true
                        case t: g.ModuleDef => true
                        case _ => false
                      }
                  val (rootParents, nonRootParents) =
                    pkgObj.impl.parents.partition(isRootModuleIdent)
                  if (rootParents.isEmpty) {
                    val isMetaBuild =
                      adjustedFile.endsWith("mill-build/build.sc") ||
                        adjustedFile.endsWith("mill-build\\build.sc")
                    val expected = if (isMetaBuild) "MillBuildRootModule" else "RootModule"
                    g.reporter.error(
                      pkgObj.pos,
                      s"Root module in ${g.currentSource.path} must extend `$expected`"
                    )
                  }
                  val newPkgCls = g.treeCopy.ClassDef(
                    pkgCls,
                    pkgCls.mods,
                    pkgCls.name,
                    pkgCls.tparams,
                    g.treeCopy.Template(
                      pkgCls.impl,
                      pkgCls.impl.parents ++ nonRootParents,
                      g.ValDef(g.Modifiers(), pkgObj.name, g.EmptyTree, g.EmptyTree),
                      innerStmts ++
                        pkgObj.impl.body
                    )
                  )
                  (pkgClsIndex, outerStmts, newPkgCls, pkgObj)
              }
          }
          resOpt.flatten match {
            case Nil => super.transform(tree)

            case List((pkgClsIndex, newOuterStmts, newPkgCls, v))
                if v.name.toString() == expectedName =>
              val (before, after) = pkgDef.stats.splitAt(pkgClsIndex)

              g.treeCopy.PackageDef(
                pkgDef,
                pkgDef.pid,
                before ++ newOuterStmts ++ Seq(newPkgCls) ++ after.drop(1)
              )
            case multiple =>
              for (m <- multiple) {
                g.reporter.error(
                  m._4.pos,
                  s"Only one RootModule named `$expectedName` can be defined in a build, not: " +
                    multiple.map(_._4.name).mkString(", ")
                )
              }
              tree
          }
        case tree => super.transform(tree)
      }
    }

    val res = Transformer.transform(unit.body)
//    println("=" * 50 + adjustedFile + "=" * 50)
//    println(res)
    res
  }
}
