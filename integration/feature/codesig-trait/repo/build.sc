import mill._

trait MyModule extends Module{
  def helperFoo: Int
  def foo = T{ println("running foo"); helperFoo }
}

object myObject extends MyModule{
  def helperFoo = { println("running helperFoo"); 1 }
}