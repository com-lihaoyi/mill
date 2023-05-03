
import mill._

interp.watchValue{ System.currentTimeMillis() / 5000 }

println("Setting up build.sc")
def foo = T.input{
  println("Running foo")
  System.currentTimeMillis() / 1000
}

def bar = T{
  println("Running bar")
  foo() + " seconds since the epoch"
}
