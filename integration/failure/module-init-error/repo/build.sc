import mill._, scalalib._

def rootTarget = T{ println("Running rootTarget"); "rootTarget" }
def rootCommand(s: String) = task.command{ println(s"Running rootCommand $s") }

object foo extends Module{
  def fooTarget = T{ println(s"Running fooTarget"); 123 }
  def fooCommand(s: String) = task.command{ println(s"Running fooCommand $s") }
  throw new Exception("Foo Boom")
}

object bar extends Module {
  def barTarget = task { println(s"Running barTarget"); "abc" }
  def barCommand(s: String) = task.command{ println(s"Running barCommand $s") }

  object qux extends Module{
    def quxTarget = task { println(s"Running quxTarget"); "xyz" }
    def quxCommand(s: String) = task.command{ println(s"Running quxCommand $s") }
    throw new Exception("Qux Boom")
  }
}
