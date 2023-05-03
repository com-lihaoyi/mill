import mill._

println("Setting up build.sc")

def foo = T.sources(millSourcePath / "foo1.txt", millSourcePath / "foo2.txt")
def bar = T.source(millSourcePath / "bar.txt")

def qux = T{
  val fooMsg = "Running qux foo contents " + foo().map(p => os.read(p.path)).mkString(" ")
  println(fooMsg)

  val barMsg = "Running qux bar contents " + os.read(bar().path)
  println(barMsg)

  writeCompletionMarker("quxRan")

  fooMsg + " " + barMsg
}

interp.watchValue(PathRef(millSourcePath / "watchValue.txt"))

def baz = T.input(PathRef(millSourcePath / "baz.txt"))

def lol = T{
  val barMsg = "Running lol baz contents " + os.read(baz().path)
  println(barMsg)

  writeCompletionMarker("lolRan")

  barMsg
}


def writeCompletionMarker(name: String) = {

  Range(0, 10)
    .map(i => os.pwd / "out" / s"$name$i")
    .find(!os.exists(_))
    .foreach(os.write(_, ""))
}

writeCompletionMarker("initialized")

if (os.read(millSourcePath / "watchValue.txt").contains("exit")){
  Thread.sleep(1000)
  System.exit(0)
}