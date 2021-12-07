import mill._

trait X extends Module{
  def f = T{ 1 }
}
trait A extends X{
  override def f = T{ super.f() +  2 }
}

trait B extends X{
  override def f = T{ super.f() + 3 }
}
object m extends A with B{
}