package mill.api.internal

import mill.api.daemon.Segments

private[mill] sealed trait Resolved {
  def rootModule: RootModule0
  def taskSegments: Segments
  def cls: Class[?]
  def fullSegments: Segments = rootModule.moduleSegments ++ taskSegments
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
      if (keyX == keyY) Segments.ordering.compare(x.fullSegments, y.fullSegments)
      else Ordering.Int.compare(keyX, keyY)
    }
  }

  case class Module(rootModule: RootModule0, taskSegments: Segments, cls: Class[?]) extends Resolved
  case class Command(rootModule: RootModule0, taskSegments: Segments, cls: Class[?]) extends Resolved
  case class NamedTask(rootModule: RootModule0, taskSegments: Segments, cls: Class[?]) extends Resolved
}
