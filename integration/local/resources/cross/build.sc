import mill.api.PathRef
import mill.{Agg, T}
import mill.define.{Cross, Module}
import mill.scalalib._

object topCross extends Cross[TopCross]("a", "b")
class TopCross(p1: String) extends Module {
  def param1 = T { p1 }
  def path = T { PathRef(millSourcePath) }
}

object topCross2 extends Cross[TopCross2](("a", "1"), ("b", "2"))
class TopCross2(p1: String, p2: String) extends Module {
  def param1 = T { p1 }
  def param2 = T { p2 }
  def path = T { PathRef(millSourcePath) }
}

object topCrossU extends Cross[TopCrossU]("a", "b")
class TopCrossU(p1: String) extends Module {
  override def millSourcePath = super.millSourcePath / p1
  def param1 = T { p1 }
  def path = T { PathRef(millSourcePath) }
}

object topCross2U extends Cross[TopCross2U](("a", "1"), ("b", "2"))
class TopCross2U(p1: String, p2: String) extends Module {
  override def millSourcePath = super.millSourcePath / p1 / p2
  def param1 = T { p1 }
  def param2 = T { p2 }
  def path = T { PathRef(millSourcePath) }
}
