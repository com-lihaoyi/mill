package mill.util

import mill.*
import mill.api.{PathRef, nonBootstrapped}
import mill.util.Jvm.jdkTool

/**
 * A trait providing convenient access to common JDK command-line tools.
 *
 * These commands use the JDK specified by [[jdkCommandsJavaHome]], which can be
 * overridden to use a different JDK installation.
 */
trait JdkCommandsModule extends mill.api.Module {

  /**
   * The Java home to use for JDK commands. If `None`, uses the default
   * JDK (typically the one running Mill).
   */
  def jdkCommandsJavaHome: Task[Option[PathRef]] = Task.Anon { None }

  private def callJdk(toolName: String, javaHome: Option[PathRef], args: Seq[String]): Int = {
    os.call(
      cmd = Seq(jdkTool(toolName, javaHome.map(_.path))) ++ args,
      stdin = os.Inherit,
      stdout = os.Inherit,
      check = false
    )
      .exitCode
  }

  /** Runs the `java` command from Mill's JVM */
  @Task.rename("java")
  @mainargs.main(name = "java")
  @nonBootstrapped
  def javaRun(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Task.ctx().systemExit(callJdk("java", jdkCommandsJavaHome(), args))
  }

  /** Runs the `javac` command from Mill's JVM */
  @nonBootstrapped
  def javac(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Task.ctx().systemExit(callJdk("javac", jdkCommandsJavaHome(), args))
  }

  /** Runs the `javap` command from Mill's JVM */
  @nonBootstrapped
  def javap(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Task.ctx().systemExit(callJdk("javap", jdkCommandsJavaHome(), args))
  }

  /** Runs the `jstack` command from Mill's JVM */
  @nonBootstrapped
  def jstack(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Task.ctx().systemExit(callJdk("jstack", jdkCommandsJavaHome(), args))
  }

  /** Runs the `jps` command from Mill's JVM */
  @nonBootstrapped
  def jps(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Task.ctx().systemExit(callJdk("jps", jdkCommandsJavaHome(), args))
  }

  /** Runs the `jfr` command from Mill's JVM */
  @nonBootstrapped
  def jfr(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Task.ctx().systemExit(callJdk("jfr", jdkCommandsJavaHome(), args))
  }
}
