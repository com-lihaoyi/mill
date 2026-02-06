package mill.eclipse

import mill.*
import mill.api.{DefaultTaskModule, Discover, ExternalModule, PathRef, allEvaluatorsCommand}
import mill.api.daemon.internal.{AllEvaluators, EvaluatorApi}
import mill.javalib.{CoursierModule, Dep}

/**
 * Alias for mill.eclipse.GenEclipse to support `./mill mill.eclipse/` syntax.
 */
object `package` extends ExternalModule.Alias(GenEclipse)

/**
 * External module for generating Eclipse project files.
 *
 * This module uses the @allEvaluatorsCommand annotation to access evaluators
 * from all bootstrap levels, enabling it to analyze modules across the entire
 * build hierarchy.
 *
 * Usage:
 * {{{
 * ./mill mill.eclipse.GenEclipse/eclipse
 * ./mill mill.eclipse.GenEclipse/
 * ./mill mill.eclipse/
 * }}}
 */
object GenEclipse extends ExternalModule with CoursierModule with DefaultTaskModule {

  def defaultTask() = "eclipse"

  /**
   * Classpath for the GenEclipse worker implementation.
   */
  def workerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-runner-eclipse")
    ))
  }

  /**
   * Creates a classloader for the GenEclipse worker.
   */
  def workerClassLoader: Worker[ClassLoader & AutoCloseable] = Task.Worker {
    mill.util.Jvm.createClassLoader(workerClasspath().map(_.path), getClass.getClassLoader)
  }

  /**
   * Generates Eclipse project files for the current Mill build.
   *
   * This command analyzes all Java modules in the build and generates
   * Eclipse project configuration including:
   * - .project files
   * - .classpath files
   * - .settings directory with JDT preferences
   */
  @allEvaluatorsCommand
  def eclipse(): Command[Unit] = Task.Command(exclusive = true) {
    val evaluators = AllEvaluators.value
    if (evaluators.isEmpty) {
      throw new Exception(
        "No evaluators available. The eclipse command requires access to build evaluators."
      )
    }

    val cl = workerClassLoader()
    val implClass = cl.loadClass("mill.eclipse.GenEclipseImpl")
    val impl = implClass
      .getConstructor(classOf[Seq[EvaluatorApi]])
      .newInstance(evaluators)

    implClass.getMethod("run").invoke(impl)
  }

  lazy val millDiscover: Discover = Discover[this.type]
}
