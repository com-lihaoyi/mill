import mill.api.PathRef
import mill.{Agg, T}
import mill.define.{Cross, Module}
import mill.scalalib._

object topCross extends Cross[TopCross]("a", "b")
trait TopCross extends Module {
  def param1 = T { crossValue }
  def path = T { PathRef(millSourcePath) }
}

object topCross2 extends Cross[TopCross2](("a", "1"), ("b", "2"))
trait TopCross extends Cross.Module[String] {
  val (p1, p2) = crossValue
  def param1 = T { p1 }
  def param2 = T { p2 }
  def path = T { PathRef(millSourcePath) }
}

object topCrossU extends Cross[TopCrossU]("a", "b")
trait TopCrossU  extends Cross.Module[String] {
  override def millSourcePath = super.millSourcePath / crossValue
  def param1 = T { crossValue }
  def path = T { PathRef(millSourcePath) }
}

object topCross2U extends Cross[TopCross2U](("a", "1"), ("b", "2"))
trait TopCross2U extends Cross.Module[String] {
  val (p1, p2) = crossValue
  override def millSourcePath = super.millSourcePath / p1 / p2
  def param1 = T { p1 }
  def param2 = T { p2 }
  def path = T { PathRef(millSourcePath) }
}
