package millbuild
import mill.*
import mill.javalib.*
import mill.javalib.publish.*
trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(Deps.commonsText)

  def javacOptions = Seq("-source", "21", "-target", "21")

}
