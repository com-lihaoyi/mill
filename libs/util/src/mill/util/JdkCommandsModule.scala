package mill.util

import mill.*
import mill.api.PathRef

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

  /**
   * Runs the `java` command from this module's [[jdkCommandsJavaHome]].
   * Renamed to `java` on the command line.
   */
  @Task.rename("java")
  @mainargs.main(name = "java")
  def javaRun(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Jvm.callJdkTool("java", args, jdkCommandsJavaHome().map(_.path))
  }

  /** Runs the `javac` command from this module's [[jdkCommandsJavaHome]] */
  def javac(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Jvm.callJdkTool("javac", args, jdkCommandsJavaHome().map(_.path))
  }

  /** Runs the `javap` command from this module's [[jdkCommandsJavaHome]] */
  def javap(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Jvm.callJdkTool("javap", args, jdkCommandsJavaHome().map(_.path))
  }

  /** Runs the `jstack` command from this module's [[jdkCommandsJavaHome]] */
  def jstack(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Jvm.callJdkTool("jstack", args, jdkCommandsJavaHome().map(_.path))
  }

  /** Runs the `jps` command from this module's [[jdkCommandsJavaHome]] */
  def jps(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Jvm.callJdkTool("jps", args, jdkCommandsJavaHome().map(_.path))
  }

  /** Runs the `jfr` command from this module's [[jdkCommandsJavaHome]] */
  def jfr(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    Jvm.callJdkTool("jfr", args, jdkCommandsJavaHome().map(_.path))
  }
}
