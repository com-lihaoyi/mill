package mill.api.internal

import mill.api.daemon.Segments

private[mill] sealed trait Resolved {
  def rootModule: RootModule0
  def segments: Segments
  def cls: Class[?]
  def fullSegments: Segments = rootModule.moduleSegments ++ segments 
}

private[mill] object Resolved {
  implicit object resolvedOrder extends Ordering[Resolved] {
    def orderingKey(r: Resolved) = r match {
      case _: Module => 1
      case _: Command => 2
      case _: NamedTask => 3
    }

    override def compare(x: Resolved, y: Resolved): Int = {
      val keyX = orderingKey(x)
      val keyY = orderingKey(y)
      if (keyX == keyY) Segments.ordering.compare(x.segments, y.segments)
      else Ordering.Int.compare(keyX, keyY)
    }
  }

  case class Module(rootModule: RootModule0, segments: Segments, cls: Class[?]) extends Resolved
  case class Command(rootModule: RootModule0, segments: Segments, cls: Class[?]) extends Resolved
  case class NamedTask(rootModule: RootModule0, segments: Segments, cls: Class[?]) extends Resolved
}
