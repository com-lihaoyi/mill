package mill.define

import ammonite.main.Router.Overrides
import ammonite.ops.Path
import mill.main.ParseArgs

object BaseModule{
  case class Implicit(value: BaseModule)
}

abstract class BaseModule(millSourcePath0: Path, external0: Boolean = false)
                         (implicit millModuleEnclosing0: sourcecode.Enclosing,
                          millModuleLine0: sourcecode.Line,
                          millName0: sourcecode.Name)
  extends Module()(
    mill.define.Ctx.make(
      implicitly,
      implicitly,
      implicitly,
      BasePath(millSourcePath0),
      Segments(),
      Overrides(0),
      Ctx.External(external0)
    )
  ){
  // A BaseModule should provide an empty Segments list to it's children, since
  // it is the root of the module tree, and thus must not include it's own
  // sourcecode.Name as part of the list,
  override implicit def millModuleSegments: Segments = Segments()
  override def millSourcePath = millOuterCtx.millSourcePath
  override implicit def millModuleBasePath: BasePath = BasePath(millSourcePath)
  implicit def millImplicitBaseModule: BaseModule.Implicit = BaseModule.Implicit(this)
  def millDiscover: Discover[this.type]
//  implicit def millScoptModuleReads[T <: mill.Module] = new mill.main.ModuleScopt[T, this.type](
//    this, millDiscover
//  )
  implicit def millScoptTargetReads[T] = new TargetScopt[T, this.type](
    this, millDiscover
  )
}


abstract class ExternalModule(implicit millModuleEnclosing0: sourcecode.Enclosing,
                              millModuleLine0: sourcecode.Line,
                              millName0: sourcecode.Name)
  extends BaseModule(ammonite.ops.pwd, external0 = true){

  implicit def millDiscoverImplicit: Discover[_] = millDiscover
  assert(
    !" #".exists(millModuleEnclosing0.value.contains(_)),
    "External modules must be at a top-level static path, not " + millModuleEnclosing0.value
  )
  override implicit def millModuleSegments = {
    Segments(millModuleEnclosing0.value.split('.').map(Segment.Label):_*)
  }
}

class TargetScopt[T, M <: BaseModule](rootModule: M, d: => Discover[M])
  extends scopt.Read[Seq[mill.define.Target[T]]]{
  def arity = 1
  def reads = s => {
    val (expanded, Nil) = ParseArgs(Seq(s)).fold(e => throw new Exception(e), identity)
    val resolved = expanded.map{
      case (Some(scoping), segments) =>
        val moduleCls = rootModule.getClass.getClassLoader.loadClass(scoping.render + "$")
        val externalRootModule = moduleCls.getField("MODULE$").get(moduleCls).asInstanceOf[ExternalModule]
        val crossSelectors = segments.value.map {
          case mill.define.Segment.Cross(x) => x.toList.map(_.toString)
          case _ => Nil
        }
        mill.main.Resolve.resolve(segments.value.toList, externalRootModule, d, Nil, crossSelectors.toList, Nil)
      case (None, segments) =>
        val crossSelectors = segments.value.map {
          case mill.define.Segment.Cross(x) => x.toList.map(_.toString)
          case _ => Nil
        }
        mill.main.Resolve.resolve(segments.value.toList, rootModule, d, Nil, crossSelectors.toList, Nil)
    }
    mill.util.EitherOps.sequence(resolved) match{
      case Left(s) => throw new Exception(s)
      case Right(ts) => ts.flatten.collect{case t: mill.define.Target[T] => t}
    }
  }
}
