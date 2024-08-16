import mill._, scalalib._

def rootTarget = Task { println("Running rootTarget"); "rootTarget" }
def rootCommand(s: String) = Task.command{ println(s"Running rootCommand $s") }

object foo extends Module{
  def fooTarget = Task { println(s"Running fooTarget"); 123 }
  def fooCommand(s: String) = Task.command{ println(s"Running fooCommand $s") }
  throw new Exception("Foo Boom")
}

object bar extends Module {
  def barTarget = Task { println(s"Running barTarget"); "abc" }
  def barCommand(s: String) = Task.command{ println(s"Running barCommand $s") }

  object qux extends Module{
    def quxTarget = Task { println(s"Running quxTarget"); "xyz" }
    def quxCommand(s: String) = Task.command{ println(s"Running quxCommand $s") }
    throw new Exception("Qux Boom")
  }
}
