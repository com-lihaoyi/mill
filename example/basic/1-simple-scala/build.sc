
import mill._

//interp.watchValue(System.currentTimeMillis() / 1000)

println("Setting up build.sc")
def foo = T.input{ System.currentTimeMillis() / 1000 }

def bar = T{ foo() + " seconds since the epoch" }