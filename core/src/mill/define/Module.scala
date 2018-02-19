package mill.define

import java.lang.reflect.Modifier

import ammonite.ops.Path

import scala.language.experimental.macros
import scala.reflect.ClassTag

/**
  * `Module` is a class meant to be extended by `trait`s *only*, in order to
  * propagate the implicit parameters forward to the final concrete
  * instantiation site so they can capture the enclosing/line information of
  * the concrete instance.
  */
class Module(implicit outerCtx0: mill.define.Ctx)
  extends mill.moduledefs.Cacher{ outer =>

  /**
    * Miscellaneous machinery around traversing & querying the build hierarchy,
    * that should not be needed by normal users of Mill
    */
  object millInternal extends Module.Internal(this)

  lazy val millModuleDirectChildren = millInternal.reflectNestedObjects[Module].toSeq
  def millOuterCtx = outerCtx0
  def millSourcePath: Path = millOuterCtx.millSourcePath / millOuterCtx.segment.pathSegments
  implicit def millModuleExternal: Ctx.External = Ctx.External(millOuterCtx.external)
  implicit def millModuleBasePath: BasePath = BasePath(millSourcePath)
  implicit def millModuleSegments: Segments = {
    millOuterCtx.segments ++ Seq(millOuterCtx.segment)
  }
  def name: String = millModuleSegments.render
  override def toString = name
}

object Module{
  class Internal(outer: Module){
    def traverse[T](f: Module => Seq[T]): Seq[T] = {
      def rec(m: Module): Seq[T] = f(m) ++ m.millModuleDirectChildren.flatMap(rec)
      rec(outer)
    }

    lazy val modules = traverse(Seq(_))
    lazy val segmentsToModules = modules.map(m => (m.millModuleSegments, m)).toMap

    lazy val targets = traverse{_.millInternal.reflect[Target[_]]}.toSet

    lazy val segmentsToTargets = targets
      .map(t => (t.ctx.segments, t))
      .toMap

    // Ensure we do not propagate the implicit parameters as implicits within
    // the body of any inheriting class/trait/objects, as it would screw up any
    // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
    lazy val millModuleEnclosing = outer.millOuterCtx.enclosing
    lazy val millModuleLine = outer.millOuterCtx.lineNum

    def reflect[T: ClassTag] = {
      outer
        .getClass
        .getMethods
        .filter(!_.getName.contains('$'))
        .filter(_.getParameterCount == 0)
        .filter(x => (x.getModifiers & Modifier.STATIC) == 0)
        .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _.getReturnType)
        .map(_.invoke(outer).asInstanceOf[T])
    }
    def reflectNames[T: ClassTag] = {
      outer
        .getClass
        .getMethods
        .filter(x => (x.getModifiers & Modifier.STATIC) == 0)
        .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _.getReturnType)
        .map(_.getName)
    }
    // For some reason, this fails to pick up concrete `object`s nested directly within
    // another top-level concrete `object`. This is fine for now, since Mill's Ammonite
    // script/REPL runner always wraps user code in a wrapper object/trait
    def reflectNestedObjects[T: ClassTag] = {
      (reflect[T] ++
        outer
          .getClass
          .getClasses
          .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _)
          .flatMap(c => c.getFields.find(_.getName == "MODULE$").map(_.get(c).asInstanceOf[T]))
        ).distinct
    }
  }
}
trait TaskModule extends Module {
  def defaultCommandName(): String
}
