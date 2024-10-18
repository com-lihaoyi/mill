package mill.init.maven

import mill.define.{Discover, ExternalModule, TaskModule}
import mill.{Command, Module, T, Task}

object InitModule extends ExternalModule with InitModule with TaskModule {

  override def defaultCommandName(): String = "init"

  lazy val millDiscover: Discover = Discover[this.type]
}

/**
 * Provides a [[InitModule.init task]] to generate Mill build files for an existing Maven project.
 */
trait InitModule extends Module {

  /**
   * Converts a Maven build to Mill automatically.
   *
   * @note The conversion may be incomplete, requiring manual edits to generated Mill build file(s).
   */
  def init(
      @mainargs.arg(doc = "build base module (with shared settings) name")
      baseModule: String = "BaseMavenModule"
  ): Command[Unit] = Task.Command {

    val buildFiles = codegen(millSourcePath, baseModule, PomReader())

    T.log.info(s"generated ${buildFiles.size} Mill build file(s)")

    buildFiles.foreach {
      case (source, file) =>
        T.log.info(s"writing build file $file ...")
        os.write(file, source)
    }

    T.log.info(s"converted Maven project to Mill")
    T.log.info(s"run \"mill resolve _\" to (verify conversion and) list available tasks")
  }
}
