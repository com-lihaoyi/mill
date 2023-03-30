import mill._, scalalib._

val moduleNames = interp.watchValue(os.list(millSourcePath / "modules").map(_.last))

object modules extends Cross[FolderModule](moduleNames:_*)
class FolderModule(name: String) extends ScalaModule{
  def millSourcePath = super.millSourcePath / name
  def scalaVersion = "2.13.2"
}

/* Example Usage

> ./mill resolve modules[_]
modules[bar]
modules[foo]
modules[qux]

> ./mill modules[bar].run
Hello World Bar

> ./mill modules[new].run
Cannot resolve modules[new]

> cp -r modules/bar modules/new

> sed -i 's/Bar/New/g' modules/new/src/Example.scala

> ./mill resolve modules[_]
modules[bar]
modules[foo]
modules[qux]
modules[new]

> ./mill modules[new].run
Hello World New

*/