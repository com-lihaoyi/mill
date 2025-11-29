package millbuild
import mill.*
import mill.javalib.*
trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(Deps.commonsText)

  def javacOptions = Seq("-source", "17", "-target", "17")

}
