import mill._

trait MyModule extends Module{
  def crossValue: String
  def name: T[String]
  def param = T { name() + " Param Value: " + crossValue }
}

object foo extends Cross[FooModule]("a", "b")
trait FooModule extends Cross.Module[String] {
  object bar extends MyModule with InnerCrossModule{
    def name = "Bar"
  }
  object qux extends MyModule with InnerCrossModule{
    def name = "Qux"
  }
}

def baz = T { s"hello ${foo("a").bar.param()}" }

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

/** Usage

> ./mill show foo[a].bar.param
"Bar Param Value: a"

> ./mill show foo[b].qux.param
"Qux Param Value: b"

> ./mill show baz
"hello Bar Param Value: a"

*/