import mill._, scalalib._

val moduleNames = interp.watchValue(os.list(millSourcePath / "modules").map(_.last))

object modules extends Cross[FolderModule](moduleNames:_*)
class FolderModule(name: String) extends ScalaModule{
  def millSourcePath = super.millSourcePath / name
  def scalaVersion = "2.13.10"
}

