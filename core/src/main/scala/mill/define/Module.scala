package mill.define

import java.lang.reflect.Modifier

import ammonite.main.Router.{EntryPoint, Overrides}
import ammonite.ops.Path

import scala.language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.macros.blackbox
object Module{
  case class Cmds(value: Seq[EntryPoint[Module]])
  object Cmds{
    implicit def make: Cmds = macro makeImpl
    implicit def makeImpl(c: blackbox.Context): c.Expr[Cmds] = {
      import c.universe._
      reify(Cmds(Nil))
    }
  }
}
/**
  * `Module` is a class meant to be extended by `trait`s *only*, in order to
  * propagate the implicit parameters forward to the final concrete
  * instantiation site so they can capture the enclosing/line information of
  * the concrete instance.
  */
class Module(implicit ctx0: mill.define.Ctx, cmds: Module.Cmds) extends mill.moduledefs.Cacher{

  def traverse[T](f: Module => Seq[T]): Seq[T] = {
    def rec(m: Module): Seq[T] = {
      f(m) ++
      this.reflect[Module].flatMap(f)
    }
    rec(this)
  }

  lazy val segmentsToModules = traverse{m => Seq(m.ctx.segments -> m)}
    .toMap

  lazy val modules = segmentsToModules.valuesIterator.toSet
  lazy val segmentsToTargets = traverse{_.reflect[Target[_]]}
    .map(t => (t.ctx.segments, t))
    .toMap

  lazy val targets = segmentsToTargets.valuesIterator.toSet
  lazy val segmentsToCommands = traverse{
    m => m.reflectNames[Command[_]].map(c => m.ctx.segments ++ Seq(Segment.Label(c)))
  }.toSet

  def ctx = ctx0
  // Ensure we do not propagate the implicit parameters as implicits within
  // the body of any inheriting class/trait/objects, as it would screw up any
  // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
  val millModuleEnclosing = ctx.enclosing
  val millModuleLine = ctx.lineNum
  def basePath: Path = ctx.basePath / (ctx.segment match{
    case Segment.Label(s) => List(s)
    case Segment.Cross(vs) => vs.map(_.toString)
  })
  implicit def millModuleBasePath: BasePath = BasePath(basePath)
  implicit def millModuleSegments: Segments = {
    ctx.segments0 ++ Seq(ctx.segment)
  }
  def reflect[T: ClassTag] = {
    this
      .getClass
      .getMethods
      .filter(_.getParameterCount == 0)
      .filter(x => (x.getModifiers & Modifier.STATIC) == 0)
      .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _.getReturnType)
      .map(_.invoke(this).asInstanceOf[T])
  }
  def reflectNames[T: ClassTag] = {
    this
      .getClass
      .getMethods
      .filter(x => (x.getModifiers & Modifier.STATIC) == 0)
      .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _.getReturnType)
      .map(_.getName)
  }
  def reflectNestedObjects[T: ClassTag] = {
    reflect[T] ++
    this
      .getClass
      .getClasses
      .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _)
      .flatMap(c => c.getFields.find(_.getName == "MODULE$").map(_.get(c).asInstanceOf[T]))
  }
}
trait TaskModule extends Module {
  def defaultCommandName(): String
}

class BaseModule(basePath: Path)
                (implicit millModuleEnclosing0: sourcecode.Enclosing,
                 millModuleLine0: sourcecode.Line,
                 millName0: sourcecode.Name,
                 overrides0: Overrides,
                 cmds: Module.Cmds)
  extends Module()(
    mill.define.Ctx.make(implicitly, implicitly, implicitly, BasePath(basePath), Segments(), implicitly),
    cmds
  )