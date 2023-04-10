import mill._, scalalib._

val moduleNames = interp.watchValue(os.list(millSourcePath / "modules").map(_.last))

object modules extends Cross[FolderModule](moduleNames:_*)
trait FolderModule extends ScalaModule with Cross.Module[String]{
  def millSourcePath = super.millSourcePath / crossValue
  def scalaVersion = "2.13.2"
}

