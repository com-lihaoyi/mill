package millbuild
import mill.*
// Version of MillScalaScala used for modules that need compatibility down to Java 11 due to
// running on the same JVM as user modules. Tests still run on Java 17+
trait MillJava11ScalaModule extends MillScalaModule {
  def scalaVersion = Deps.scalaVersionJava11

  override def javaRelease: String = "11"

  def jvmId = "11"

  override object test extends MillScalaTests {
    def scalaVersion = Deps.scalaVersion
    def jvmId = ""
    def javaHome = None
  }
}
