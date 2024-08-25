import mill._, scalalib._

val moduleNames = interp.watchValue(os.list(millSourcePath / "modules").map(_.last))

object modules extends Cross[FolderModule](moduleNames)
trait FolderModule extends ScalaModule with Cross.Module[String]{
  def millSourcePath = super.millSourcePath / crossValue
  def scalaVersion = "2.13.8"
}

// It is sometimes necessary for the instances of a cross-module to vary based
// on some kind of runtime information: perhaps the list of modules is stored
// in some config file, or is inferred based on the folders present on the
// filesystem.
//
// In those cases, you can write arbitrary code to populate the cross-module
// cases, as long as you wrap the value in a `interp.watchValue`. This ensures
// that Mill is aware that the module structure depends on that value, and will
// re-compute the value and re-create the module structure if the value changes.

/** Usage

> mill resolve modules[_]
modules[bar]
modules[foo]
modules[qux]

> mill modules[bar].run
Hello World Bar

> mill modules[new].run
error: Cannot resolve modules[new]...

> cp -r modules/bar modules/new

> sed -i.bak 's/Bar/New/g' modules/new/src/Example.scala

> mill resolve modules[_]
modules[bar]
modules[foo]
modules[qux]
modules[new]

> mill modules[new].run
Hello World New

*/

// Note that because the inputs to the `Cross` constructor affects the number
// of cross-modules that are generated, it has to be a raw value e.g.
// `List[T]` and not a target `T[List[T]]`. That also means that the list of
// cross-modules cannot depend on the output of any targets.