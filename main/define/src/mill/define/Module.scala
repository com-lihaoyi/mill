package mill.define

import mill.api.internal

import scala.reflect.ClassTag

/**
 * Represents a namespace within the Mill build hierarchy, containing nested
 * modules or tasks.
 *
 * `Module` is a class meant to be extended by `trait`s *only*, in order to
 * propagate the implicit parameters forward to the final concrete
 * instantiation site so they can capture the enclosing/line information of
 * the concrete instance.
 */
trait Module extends Module.BaseClass {

  /**
   * Miscellaneous machinery around traversing & querying the build hierarchy,
   * that should not be needed by normal users of Mill
   */
  @internal
  object millInternal extends Module.Internal(this)

  def millModuleDirectChildren: Seq[Module] = millModuleDirectChildrenImpl

  // We keep a private `lazy val` and a public `def` so
  // subclasses can call `super.millModuleDirectChildren`
  private lazy val millModuleDirectChildrenImpl: Seq[Module] =
    millInternal.reflectNestedObjects[Module]().toSeq

  def millOuterCtx: Ctx

  def millSourcePath: os.Path = millOuterCtx.millSourcePath / (millOuterCtx.segment match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(_) => Seq.empty[String] // drop cross segments
  })

  implicit def millModuleExternal: Ctx.External = Ctx.External(millOuterCtx.external)
  implicit def millModuleShared: Ctx.Foreign = Ctx.Foreign(millOuterCtx.foreign)
  implicit def millModuleBasePath: Ctx.BasePath = Ctx.BasePath(millSourcePath)
  implicit def millModuleSegments: Segments = {
    millOuterCtx.segments ++ Seq(millOuterCtx.segment)
  }

  override def toString = millModuleSegments.render
}

object Module {

  /**
   * Base class of the [[Module]] trait, allowing us to take implicit arguments
   * (traits cannot). Cannot be used directly, because traits inheriting from
   * classes results in the class being invisible to java reflection, which
   * messes up the module discovery process
   */
  @internal
  class BaseClass(implicit outerCtx0: mill.define.Ctx) extends mill.moduledefs.Cacher {
    def millOuterCtx = outerCtx0
  }

  @internal
  class Internal(outer: Module) {
    def traverse[T](f: Module => Seq[T]): Seq[T] = {
      def rec(m: Module): Seq[T] = f(m) ++ m.millModuleDirectChildren.flatMap(rec)
      rec(outer)
    }

    lazy val modules: Seq[Module] = traverse(Seq(_))
    lazy val segmentsToModules = modules.map(m => (m.millModuleSegments, m)).toMap

    lazy val targets: Set[Target[_]] =
      traverse { _.millInternal.reflectAll[Target[_]].toIndexedSeq }.toSet

    def reflect[T: ClassTag](filter: String => Boolean): Seq[T] = {
      Reflect.reflect(
        outer.getClass,
        implicitly[ClassTag[T]].runtimeClass,
        filter,
        noParams = true
      )
        .map(_.invoke(outer).asInstanceOf[T])
    }

    def reflectAll[T: ClassTag]: Seq[T] = reflect[T](Function.const(true))

    def reflectNestedObjects[T: ClassTag](filter: String => Boolean = Function.const(true)) = {
      Reflect.reflectNestedObjects0(outer.getClass, filter).map {
        case (name, m: java.lang.reflect.Method) => m.invoke(outer).asInstanceOf[T]
        case (name, m: java.lang.reflect.Field) => m.get(outer).asInstanceOf[T]
      }
    }
  }
}
