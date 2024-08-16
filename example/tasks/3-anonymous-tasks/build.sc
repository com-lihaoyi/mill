import mill._, define.Task

def data = Task.source(millSourcePath / "data")

def anonTask(fileName: String): Task[String] = Task.anon {
  os.read(data().path / fileName)
}

def helloFileData = Task { anonTask("hello.txt")() }
def printFileData(fileName: String) = Task.command {
  println(anonTask(fileName)())
}

// You can define anonymous tasks using the `Task.anon {...}` syntax. These are
// not runnable from the command-line, but can be used to share common code you
// find yourself repeating in ``Target``s and ``Command``s.
//
// Anonymous task's output does not need to be JSON-serializable, their output is
// not cached, and they can be defined with or without arguments.
// Unlike <<_targets>> or <<_commands>>, anonymous tasks can be defined
// anywhere and passed around any way you want, until you finally make use of them
// within a downstream target or command.
// 
// While an anonymous task ``foo``'s own output is not cached, if it is used in a
// downstream target `baz` and the upstream target `bar` hasn't changed,
// ``baz``'s cached output will be used and ``foo``'s evaluation will be skipped
// altogether.


/** Usage

> ./mill show helloFileData
"Hello"

> ./mill printFileData hello.txt
Hello

> ./mill printFileData world.txt
World!

*/
