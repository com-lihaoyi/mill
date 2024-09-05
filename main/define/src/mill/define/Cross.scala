package mill.define

import mill.api.{BuildScriptException, Lazy}

import scala.collection.mutable
import scala.reflect.ClassTag

import scala.quoted.*

object Cross {

  /**
   * A simple cross-module with 1 cross-type [[T1]], which is available in the
   * module body as [[crossValue]]
   */
  trait Module[T1] extends mill.define.Module {
    def crossValue: T1
    def crossWrapperSegments: List[String] = Nil

    /**
     * trait that can be mixed into any sub-modules within the body of a
     * [[Cross.Module]], to automatically inherit the [[crossValue]]
     */
    trait CrossValue extends Module[T1] {
      def crossValue: T1 = Module.this.crossValue
      override def crossWrapperSegments: List[String] = Module.this.millModuleSegments.parts
    }
  }

  /**
   * A cross-module with 2 cross-types [[T1]] and [[T2]], which are available
   * in the module body as [[crossValue]] and [[crossValue2]].
   */
  trait Module2[T1, T2] extends Module[T1] {
    def crossValue2: T2

    /**
     * trait that can be mixed into any sub-modules within the body of a
     * [[Cross.Arg2]], to automatically inherit the [[crossValue2]]
     */
    trait InnerCrossModule2 extends CrossValue with Module2[T1, T2] {
      def crossValue2: T2 = Module2.this.crossValue2
    }
  }

  /**
   * A cross-module with 3 cross-types [[T1]] [[T2]] and [[T3]], which are
   * available in the module body as [[crossValue]] [[crossValue2]] and
   * [[crossValue3]].
   */
  trait Module3[T1, T2, T3] extends Module2[T1, T2] {
    def crossValue3: T3

    /**
     * trait that can be mixed into any sub-modules within the body of a
     * [[Cross.Arg3]], to automatically inherit the [[crossValue3]]
     */
    trait InnerCrossModule3 extends InnerCrossModule2 with Module3[T1, T2, T3] {
      def crossValue3: T3 = Module3.this.crossValue3
    }
  }

  /**
   * A cross-module with 4 cross-types [[T1]] [[T2]] [[T3]] and [[T4]], which
   * are available in the module body as [[crossValue]] [[crossValue2]]
   * [[crossValue3]] and [[crossValue4]].
   */
  trait Module4[T1, T2, T3, T4] extends Module3[T1, T2, T3] {
    def crossValue4: T4

    /**
     * trait that can be mixed into any sub-modules within the body of a
     * [[Cross.Arg4]], to automatically inherit the [[crossValue4]]
     */
    trait InnerCrossModule4 extends InnerCrossModule3 with Module4[T1, T2, T3, T4] {
      def crossValue4: T4 = Module4.this.crossValue4
    }
  }

  /**
   * A cross-module with 5 cross-types [[T1]] [[T2]] [[T3]] [[T4]] and [[T5]],
   * which are available in the module body as [[crossValue]] [[crossValue2]]
   * [[crossValue3]] [[crossValue4]] and [[crossValue5]].
   */
  trait Module5[T1, T2, T3, T4, T5] extends Module4[T1, T2, T3, T4] {
    def crossValue5: T5

    /**
     * trait that can be mixed into any sub-modules within the body of a
     * [[Cross.Arg5]], to automatically inherit the [[crossValue5]]
     */
    trait InnerCrossModule5 extends InnerCrossModule4 with Module5[T1, T2, T3, T4, T5] {
      def crossValue5: T5 = Module5.this.crossValue5
    }
  }

  /**
   * Convert the given value [[t]] to its cross segments
   */
  def ToSegments[T: ToSegments](t: T): List[String] = implicitly[ToSegments[T]].convert(t)

  /**
   * A type-class defining what types [[T]] are allowed to be used in a
   * cross-module definition
   */
  class ToSegments[-T](val convert: T => List[String])
  object ToSegments {
    implicit object StringToPathSegment extends ToSegments[String](List(_))
    implicit object CharToPathSegment extends ToSegments[Char](v => List(v.toString))
    implicit object LongToPathSegment extends ToSegments[Long](v => List(v.toString))
    implicit object IntToPathSegment extends ToSegments[Int](v => List(v.toString))
    implicit object ShortToPathSegment extends ToSegments[Short](v => List(v.toString))
    implicit object ByteToPathSegment extends ToSegments[Byte](v => List(v.toString))
    implicit object BooleanToPathSegment extends ToSegments[Boolean](v => List(v.toString))
    implicit object SubPathToPathSegment extends ToSegments[os.SubPath](v => v.segments.toList)
    implicit def SeqToPathSegment[T: ToSegments]: ToSegments[Seq[T]] = new ToSegments[Seq[T]](
      _.flatMap(implicitly[ToSegments[T]].convert).toList
    )
    implicit def ListToPathSegment[T: ToSegments]: ToSegments[List[T]] = new ToSegments[List[T]](
      _.flatMap(implicitly[ToSegments[T]].convert).toList
    )
  }

  class Factory[T: ClassTag](
      val makeList: Seq[(Class[_], mill.define.Ctx => T)],
      val crossValuesListLists: Seq[Seq[Any]],
      val crossSegmentsList: Seq[Seq[String]],
      val crossValuesRaw: Seq[Any]
  )

  object Factory {
    import scala.language.implicitConversions

    /**
     * Implicitly constructs a Factory[M] for a target-typed `M`. Takes in an
     * expression of type `Any`, but type-checking on the macro- expanded code
     * provides some degree of type-safety.
     */
    implicit inline def make[M <: Module[_]](inline t: Any): Factory[M] = ${
      macros.CrossMacros.makeImpl[M]('t)
    }

  }

  trait Resolver[-T <: Cross.Module[_]] {
    def resolve[V <: T](c: Cross[V]): V
  }
}

/**
 * Models "cross-builds": sets of duplicate builds which differ only in the
 * value of one or more "case" variables whose values are determined at runtime.
 * Used via:
 *
 * {{{
 * object foo extends Cross[FooModule]("bar", "baz", "qux")
 * trait FooModule extends Cross.Module[String]{
 *   ... crossValue ...
 * }
 * }}}
 */
class Cross[M <: Cross.Module[_]](factories: Cross.Factory[M]*)(implicit
    ctx: mill.define.Ctx
) extends mill.define.Module {

  trait Item {
    def crossValues: List[Any]
    def crossSegments: List[String]
    def module: Lazy[M]
    def cls: Class[_]
  }

  val items: List[Item] = {
    val seen = mutable.Map[Seq[String], Seq[Any]]()
    for {
      factory <- factories.toList
      (crossSegments0, (crossValues0, (cls0, make))) <-
        factory.crossSegmentsList.zip(factory.crossValuesListLists.zip(factory.makeList))
    } yield {
      seen.get(crossSegments0) match {
        case None => // no collision
        case Some(other) => // collision
          throw new BuildScriptException(
            s"Cross module ${ctx.enclosing} contains colliding cross values: ${other} and ${crossValues0}",
            Option(ctx.fileName).filter(_.nonEmpty)
          )
      }
      val relPath = ctx.segment.pathSegments
      val module0 = new Lazy(() =>
        make(
          ctx
            .withSegments(ctx.segments ++ Seq(ctx.segment))
            .withMillSourcePath(ctx.millSourcePath / relPath)
            .withSegment(Segment.Cross(crossSegments0))
            .withCrossValues(factories.flatMap(_.crossValuesRaw))
            .withEnclosingModule(this)
        )
      )

      val item = new Item {
        def crossValues = crossValues0.toList
        def crossSegments = crossSegments0.toList
        def module = module0
        def cls = cls0
      }
      seen.update(crossSegments0, crossValues0)
      item
    }
  }

  override lazy val millModuleDirectChildren: Seq[Module] =
    super.millModuleDirectChildren ++ crossModules

  /**
   * A list of the cross modules, in
   * the order the original cross values were given in
   */
  lazy val crossModules: Seq[M] = items.map(_.module.value)

  /**
   * A mapping of the raw cross values to the cross modules, in
   * the order the original cross values were given in
   */
  val valuesToModules: collection.MapView[List[Any], M] = items
    .map { i => (i.crossValues, i.module) }
    .to(collection.mutable.LinkedHashMap)
    .view
    .mapValues(_.value)

  /**
   * A mapping of the string-ified string segments to the cross modules, in
   * the order the original cross values were given in
   */
  val segmentsToModules: collection.MapView[List[String], M] = items
    .map { i => (i.crossSegments, i.module) }
    .to(collection.mutable.LinkedHashMap)
    .view
    .mapValues(_.value)

  /**
   * The default cross segments to use, when no cross value is specified.
   * Defaults to the first cross value per cross level.
   */
  def defaultCrossSegments: Seq[String] = items.head.crossSegments

  /**
   * Fetch the cross module corresponding to the given cross values
   */
  def get(args: Seq[Any]): M = valuesToModules(args.toList)

  /**
   * Fetch the cross module corresponding to the given cross values
   */
  def apply(arg0: Any, args: Any*): M = valuesToModules(arg0 :: args.toList)

  /**
   * Fetch the relevant cross module given the implicit resolver you have in
   * scope. This is often the first cross module whose cross-version is
   * compatible with the current module.
   */
  def apply[V >: M <: Cross.Module[_]]()(implicit resolver: Cross.Resolver[V]): M = {
    resolver.resolve(this.asInstanceOf[Cross[V]]).asInstanceOf[M]
  }
}
