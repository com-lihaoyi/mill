import mill._, scalalib._

def rootTarget = T{ println("Running rootTarget"); "rootTarget" }
def rootCommand(s: String) = T.command{ println(s"Running rootCommand $s") }

object foo extends Module{
  def fooTarget = T{ println(s"Running fooTarget"); 123 }
  def fooCommand(s: String) = T.command{ println(s"Running fooCommand $s") }
  throw new Exception("Foo Boom")
}

object bar extends Module {
  def barTarget = T { println(s"Running barTarget"); "abc" }
  def barCommand(s: String) = T.command{ println(s"Running barCommand $s") }

  object qux extends Module{
    def quxTarget = T { println(s"Running quxTarget"); "xyz" }
    def quxCommand(s: String) = T.command{ println(s"Running quxCommand $s") }
    throw new Exception("Qux Boom")
  }
}
