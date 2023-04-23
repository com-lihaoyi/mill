import mill._, scalalib._

def rootTarget = T{ println("rooty"); "rooty2" }

object foo extends Module{
  def fooTarget = T{ 123 }
  throw new Exception("Foo Boom")
}

object bar extends Module {
  def barTarget = T { "abc" }

  object Qux extends Module{
    def quxTarget = T { "xyz" }
    throw new Exception("Qux Boom")
  }
}
