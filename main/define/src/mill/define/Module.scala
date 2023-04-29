package mill.define

import mill.api.internal

import java.lang.reflect.Modifier

import scala.language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.NameTransformer.decode

/**
 * Represents a namespace within the Mill build hierarchy, containing nested
 * modules or tasks.
 *
 * `Module` is a class meant to be extended by `trait`s *only*, in order to
 * propagate the implicit parameters forward to the final concrete
 * instantiation site so they can capture the enclosing/line information of
 * the concrete instance.
 */
class Module(implicit outerCtx0: mill.define.Ctx) extends mill.moduledefs.Cacher { outer =>

  /**
   * Miscellaneous machinery around traversing & querying the build hierarchy,
   * that should not be needed by normal users of Mill
   */
  object millInternal extends Module.Internal(this)

  def millModuleDirectChildren: Seq[Module] = millModuleDirectChildrenImpl
  // We keep a private `lazy val` and a public `def` so
  // subclasses can call `super.millModuleDirectChildren`
  private lazy val millModuleDirectChildrenImpl: Seq[Module] =
    millInternal.reflectNestedObjects[Module]().toSeq
  def millOuterCtx: Ctx = outerCtx0
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
  @internal
  object Internal {
    import fastparse._, NoWhitespace._

    def ident[_p: P]: P[String] = P(CharsWhileIn("a-zA-Z0-9_\\-")).!

    def standaloneIdent[_p: P]: P[String] = P(Start ~ ident ~ End)

    def isLegalIdentifier(identifier: String): Boolean =
      parse(identifier, standaloneIdent(_)).isInstanceOf[Parsed.Success[_]]

    def reflect(
        outer: Class[_],
        inner: Class[_],
        filter: String => Boolean,
        noParams: Boolean
    ): Seq[java.lang.reflect.Method] = {
      for {
        m <- outer.getMethods.sortBy(_.getName)
        n = decode(m.getName)
        if filter(n) &&
          isLegalIdentifier(n) &&
          (!noParams || m.getParameterCount == 0) &&
          (m.getModifiers & Modifier.STATIC) == 0 &&
          (m.getModifiers & Modifier.ABSTRACT) == 0 &&
          inner.isAssignableFrom(m.getReturnType)
      } yield m
    }

    // For some reason, this fails to pick up concrete `object`s nested directly within
    // another top-level concrete `object`. This is fine for now, since Mill's Ammonite
    // script/REPL runner always wraps user code in a wrapper object/trait
    def reflectNestedObjects[T: ClassTag](
        outer: Class[_],
        filter: String => Boolean = Function.const(true)
    ): Seq[java.lang.reflect.Member] = {
      reflect(outer, classOf[Object], filter, noParams = true) ++
        outer
          .getClasses
          .filter(implicitly[ClassTag[T]].runtimeClass.isAssignableFrom(_))
          .flatMap(c =>
            c.getFields.find(_.getName == "MODULE$")
          ).distinct
    }
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

    lazy val segmentsToTargets: Map[Segments, Target[_]] = targets
      .map(t => (t.ctx.segments, t))
      .toMap

    // Ensure we do not propagate the implicit parameters as implicits within
    // the body of any inheriting class/trait/objects, as it would screw up any
    // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
    lazy val millModuleEnclosing: String = outer.millOuterCtx.enclosing
    lazy val millModuleLine: Int = outer.millOuterCtx.lineNum

    def reflect[T: ClassTag](filter: String => Boolean): Seq[T] = {
      Module.Internal.reflect(
        outer.getClass,
        implicitly[ClassTag[T]].runtimeClass,
        filter,
        noParams = true
      )
        .map(_.invoke(outer).asInstanceOf[T])
    }

    def reflectAll[T: ClassTag]: Seq[T] = reflect[T](Function.const(true))

    def reflectSingle[T: ClassTag](label: String): Option[T] = reflect(_ == label).headOption

    // For some reason, this fails to pick up concrete `object`s nested directly within
    // another top-level concrete `object`. This is fine for now, since Mill's Ammonite
    // script/REPL runner always wraps user code in a wrapper object/trait
    def reflectNestedObjects[T: ClassTag](filter: String => Boolean = Function.const(true)) = {
      reflectNestedObjects0(filter).map(_._2())
    }

    def reflectNestedObjects0[T: ClassTag](filter: String => Boolean = Function.const(true))
        : Seq[(String, () => T)] = {

      val first = Module.Internal
        .reflect(
          outer.getClass,
          implicitly[ClassTag[T]].runtimeClass,
          filter,
          noParams = true
        )
        .map(m => (m.getName, () => m.invoke(outer).asInstanceOf[T]))

      val second =
        outer
          .getClass
          .getClasses
          .filter(implicitly[ClassTag[T]].runtimeClass.isAssignableFrom(_))
          .flatMap { c =>
            c.getName.stripPrefix(outer.getClass.getName) match {
              case s"$name$$" if filter(name) =>
                c.getFields.find(_.getName == "MODULE$")
                  .map(f => (name, () => f.get(c).asInstanceOf[T]))

              case _ => None
            }

          }
          .distinct

      first ++ second
    }
  }
}

/**
 * A [[Module]] that has a [[defaultCommandName]] that will be automatically
 * executed if the module name is provide at the Mill command line
 */
trait TaskModule extends Module {

  /**
   * The name of the default command, which will be automatically excecuted if
   * the module name is provided at the Mill command line
   */
  def defaultCommandName(): String
}
