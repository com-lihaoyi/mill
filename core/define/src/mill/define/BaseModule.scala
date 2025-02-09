package mill.define

import mill.api.PathRef

import scala.collection.mutable

abstract class BaseModule(
    millSourcePath0: os.Path,
    external0: Boolean = false
)(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File
) extends Module.BaseClass()(
      mill.define.Ctx.make(
        implicitly,
        implicitly,
        millSourcePath0,
        Segments(),
        external0,
        millFile0,
        null,
        discover = null
      )
    ) with Module {

  // A BaseModule should provide an empty Segments list to its children, since
  // it is the root of the module tree, and thus must not include its own
  // sourcecode.Name as part of the list,
  override def moduleSegments: Segments = Segments()

  override def moduleDir = moduleCtx.millSourcePath

  // `Discover` needs to be defined by every concrete `BaseModule` object, to gather
  // compile-time metadata about the tasks and commands at for use at runtime
  protected def millDiscover: Discover

  // We need to propagate the `Discover` object implicitly throughout the module tree
  // so it can be used for override detection
  def moduleCtx = super.moduleCtx.withDiscover(millDiscover).withSegments(Segments())

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
