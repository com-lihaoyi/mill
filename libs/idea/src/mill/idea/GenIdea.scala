package mill.idea

import mill.*
import mill.api.{DefaultTaskModule, Discover, ExternalModule, PathRef, allEvaluatorsCommand}
import mill.api.daemon.internal.{AllEvaluators, EvaluatorApi}
import mill.javalib.{CoursierModule, Dep}

/**
 * Alias for mill.idea.GenIdea to support `./mill mill.idea/` syntax.
 */
object `package` extends ExternalModule.Alias(GenIdea)

/**
 * External module for generating IntelliJ IDEA project files.
 *
 * This module uses the @allEvaluatorsCommand annotation to access evaluators
 * from all bootstrap levels, enabling it to analyze modules across the entire
 * build hierarchy.
 *
 * Usage:
 * {{{
 * ./mill mill.idea.GenIdea/idea
 * ./mill mill.idea.GenIdea/
 * ./mill mill.idea/
 * }}}
 */
object GenIdea extends ExternalModule with CoursierModule with DefaultTaskModule {

  def defaultTask() = "idea"

  /**
   * Classpath for the GenIdea worker implementation.
   */
  def workerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-runner-idea")
    ))
  }

  /**
   * Creates a classloader for the GenIdea worker.
   */
  def workerClassLoader: Worker[ClassLoader & AutoCloseable] = Task.Worker {
    mill.util.Jvm.createClassLoader(workerClasspath().map(_.path), getClass.getClassLoader)
  }

  /**
   * Generates IntelliJ IDEA project files for the current Mill build.
   *
   * This command analyzes all modules in the build (including meta-builds)
   * and generates the appropriate .idea directory structure with:
   * - Module files (.iml)
   * - Library definitions
   * - Compiler settings
   * - Project settings
   */
  @allEvaluatorsCommand
  def idea(): Command[Unit] = Task.Command(exclusive = true) {
    val evaluators = AllEvaluators.value
    if (evaluators.isEmpty) {
      throw new Exception(
        "No evaluators available. The idea command requires access to build evaluators."
      )
    }

    val cl = workerClassLoader()
    val implClass = cl.loadClass("mill.idea.GenIdeaImpl")
    val impl = implClass
      .getConstructor(classOf[Seq[EvaluatorApi]])
      .newInstance(evaluators)

    implClass.getMethod("run").invoke(impl)
  }

  lazy val millDiscover: Discover = Discover[this.type]
}
