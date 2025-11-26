package millbuild
import mill.*
// Version of MillScalaScala used for modules that need compatibility down to Java 11 due to
// running on the same JVM as user modules. Tests still run on Java 17+
trait MillJava11ScalaModule extends MillPublishScalaModule {
  def scalaVersion = Deps.scalaVersionJava11

  def jvmId = "11"

  override object test extends MillScalaTests {
    def scalaVersion = Deps.scalaVersion
    def jvmId = ""
    def javaHome = None
  }
}

object MillJava11ScalaModule {
  // Depend on the `java11` submodule by manually splicing the classpath and compile output,
  // so externally it looks identical to a single module even though some parts of it were
  // compiled with java 11 and other parts were compiled with java 17+
  trait Wrapper extends MillScalaModule {
    def java11: MillScalaModule
    def compileClasspath = super.compileClasspath() ++ Seq(java11.compile().classes)

//    def localClasspath = super.localClasspath() ++ java11.localClasspath()
    def compile = Task {
      os.copy(super.compile().classes.path, Task.dest, mergeFolders = true)
      os.copy(java11.compile().classes.path, Task.dest, mergeFolders = true)
      super.compile().copy(classes = PathRef(Task.dest))
    }
  }
}
