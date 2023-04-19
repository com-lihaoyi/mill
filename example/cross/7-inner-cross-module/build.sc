import mill._

trait MyModule extends Module{
  def crossValue: String
  def name: T[String]
  def param = T { name() + " Param Value: " + crossValue }
}

object myCross extends Cross[MyCrossModule]("a", "b")
trait MyCrossModule extends Cross.Module[String] {
  object foo extends MyModule with InnerCrossModule{
    def name = "Foo"
  }
  object bar extends MyModule with InnerCrossModule{
    def name = "Bar"
  }
}

def qux = T { s"hello ${myCross("a").foo.param()}" }

// You can use the `InnerCrossModule` trait within any `Cross.Module` to
// propagate the `crossValue` defined by an enclosing `Cross.Module` to some
// nested module. In this case, we use it to bind `crossValue` so it can be
// used in `def param`. This lets you reduce verbosity by defining the `Cross`
// once for a group of modules rather than once for every single module  in
// that group. There are corresponding `InnerCrossModuleN` traits for cross
// modules that take multiple inputs.
//
// You can reference the modules and tasks defined within such a
// `InnerCrossModule` as is done in `def qux` above

/* Example Usage

> ./mill show myCross[a].foo.param
"Foo Param Value: a"

> ./mill show myCross[b].bar.param
"Bar Param Value: b"

> ./mill show qux
"hello Foo Param Value: a"

*/