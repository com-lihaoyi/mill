package mill.define

import mill.api.{PathRef, WorkspaceRoot}
import mill.util.Watchable

import scala.collection.mutable

abstract class BaseModule(
    millSourcePath0: os.Path,
    external0: Boolean = false,
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
        Ctx.Foreign(None),
        millFile0,
        caller,
        EnclosingClass(null),
        discover = null
      )
    ) with Module with BaseModule0 {

  // A BaseModule should provide an empty Segments list to its children, since
  // it is the root of the module tree, and thus must not include its own
  // sourcecode.Name as part of the list,
  override implicit def millModuleSegments: Segments = Segments()

  override def millSourcePath = millOuterCtx.millSourcePath

  // `Discover` needs to be defined by every concrete `Module` object
  protected def millDiscover: Discover
  // We need to propagate the `Discover` object implicitly throughout the module tree
  // so it can be used for override detection
  override implicit lazy val implicitMillDiscover: Discover = millDiscover
  def millOuterCtx = super.millOuterCtx.withDiscover(millDiscover)
}

trait BaseModule0 extends Module {
  protected def millDiscover: Discover
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
) extends BaseModule(WorkspaceRoot.workspaceRoot, external0 = true)(
      implicitly,
      implicitly,
      implicitly,
      Caller(null)
    ) {

  assert(
    !" #".exists(millModuleEnclosing0.value.contains(_)),
    "External modules must be at a top-level static path, not " + millModuleEnclosing0.value
  )
  override implicit def millModuleSegments: Segments = {
    Segments(millModuleEnclosing0.value.split('.').map(Segment.Label).toIndexedSeq)
  }
}
