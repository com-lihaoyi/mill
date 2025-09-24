package mill.define

import mill.api.{BuildCtx, PathRef}
import mill.util.Watchable

import scala.collection.mutable

object BaseModule {
  case class Implicit(value: BaseModule)
}

abstract class BaseModule(
    millSourcePath0: os.Path,
    external0: Boolean = false,
    foreign0: Option[Segments] = None
)(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    caller: Caller
) extends Module.BaseClass()(
      mill.define.Ctx.make(
        implicitly,
        implicitly,
        Ctx.BasePath(millSourcePath0),
        Segments(),
        Ctx.External(external0),
        Ctx.Foreign(foreign0),
        millFile0,
        caller
      )
    ) with Module with BaseModule0 {

  // A BaseModule should provide an empty Segments list to its children, since
  // it is the root of the module tree, and thus must not include its own
  // sourcecode.Name as part of the list,
  override implicit def millModuleSegments: Segments = Segments()
  override def millSourcePath = millOuterCtx.millSourcePath
  override implicit def millModuleBasePath: Ctx.BasePath = Ctx.BasePath(millSourcePath)
  implicit def millImplicitBaseModule: BaseModule.Implicit = BaseModule.Implicit(this)
  def millDiscover: Discover

}

trait BaseModule0 extends Module {
  implicit def millDiscover: Discover
  protected[mill] val watchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]
  protected[mill] val evalWatchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]

  class Interp {

    def watchValue[T](v0: => T)(implicit fn: sourcecode.FileName, ln: sourcecode.Line): T = {
      val v = v0
      val watchable = Watchable.Value(
        () => v0.hashCode,
        v.hashCode(),
        fn.value + ":" + ln.value
      )
      watchedValues.append(watchable)
      v
    }

    def watch(p: os.Path): os.Path = {
      val watchable = Watchable.Path(PathRef(p))
      watchedValues.append(watchable)
      p
    }

    def watch0(w: Watchable): Unit = {
      watchedValues.append(w)
    }

    def evalWatch0(w: Watchable): Unit = {
      evalWatchedValues.append(w)
    }
  }
}

abstract class ExternalModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line
) extends BaseModule(BuildCtx.workspaceRoot, external0 = true, foreign0 = None)(
      implicitly,
      implicitly,
      implicitly,
      Caller(null)
    ) {

  implicit def millDiscoverImplicit: Discover = millDiscover
  assert(
    !" #".exists(millModuleEnclosing0.value.contains(_)),
    "External modules must be at a top-level static path, not " + millModuleEnclosing0.value
  )
  override implicit def millModuleSegments: Segments = {
    Segments(millModuleEnclosing0.value.split('.').map(Segment.Label).toIndexedSeq)
  }
}

object ExternalModule {

  /**
   * Allows you to define a new top-level [[ExternalModule]] that is simply an alias
   * to an existing one. Useful for renaming an [[ExternalModule]] while preserving
   * backwards compatibility to the existing implementation and name
   */
  class Alias(val value: ExternalModule)
}
