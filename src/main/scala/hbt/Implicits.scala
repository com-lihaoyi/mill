package hbt


import scala.collection.mutable

/**
  * Container for all the type-level logic around appending things
  * to tuples or flattening `Seq[Unit]`s into `Unit`s.
  *
  * Some of these implicits make liberal use of mutable state, so as
  * to minimize allocations while parsing.
  */
object Implicits {
  trait Sequencer[-T, V, R]{
    def apply(t: T, v: V): R
  }
  object Sequencer extends LowPriSequencer{
    def apply[T, V, R](f: (T, V) => R) = new Sequencer[T, V, R]{
      def apply(t: T, v: V): R = f(t, v)
    }
    implicit def SingleSequencer[T]: Sequencer[Unit, T, T] = Sequencer( (_, t) => t )
  }
  trait LowPriSequencer extends LowerPriSequencer{
    implicit def UnitSequencer[T]: Sequencer[T, Unit, T] = Sequencer( (t, _) => t )
  }
  trait LowerPriSequencer extends SequencerGen[Sequencer]{
    protected[this] def Sequencer0[A, B, C](f: (A, B) => C) = Sequencer(f)
  }
  trait Repeater[-T, R]{
    type Acc
    def initial: Acc
    def accumulate(t: T, acc: Acc): Unit
    def result(acc: Acc): R
  }
  object Repeater extends LowPriRepeater{
    implicit object UnitRepeater extends Repeater[Unit, Unit]{
      type Acc = Unit
      def initial = ()
      def accumulate(t: Unit, acc: Unit) = acc
      def result(acc: Unit) = ()
    }
  }
  trait LowPriRepeater{
    implicit def GenericRepeaterImplicit[T] = GenericRepeater[T]()
    case class GenericRepeater[T]() extends Repeater[T, Seq[T]]{
      type Acc = mutable.Buffer[T]
      def initial = mutable.Buffer.empty[T]
      def accumulate(t: T, acc: mutable.Buffer[T]) = acc += t
      def result(acc: mutable.Buffer[T]) = acc
    }
  }

  trait Optioner[-T, R]{
    def none: R
    def some(value: T): R
  }

  object Optioner extends LowPriOptioner{
    implicit object UnitOptioner extends Optioner[Unit, Unit]{
      def none = ()
      def some(value: Unit) = ()
    }
  }
  trait LowPriOptioner{
    implicit def GenericOptionerImplicit[T] = GenericOptioner[T]()
    case class GenericOptioner[T]() extends Optioner[T, Option[T]]{
      def none = None
      def some(value: T) = Some(value)
    }
  }
}