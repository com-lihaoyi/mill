package mill.init

import mill.api.PathRef
import mill.api.Result
import mill.scalalib.{CoursierModule, Dep}
import mill.scalalib.scalafmt.ScalafmtWorkerModule
import mill.util.Jvm
import mill.{Command, T, Task, DefaultTaskModule}

trait BuildGenModule extends CoursierModule with DefaultTaskModule {

  def defaultTask(): String = "init"

  def buildGenDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  def buildGenClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(buildGenDeps())
  }

  def buildGenMainClass: T[String]

  def buildGenScalafmtConfig: T[PathRef] = PathRef(mill.init.Util.scalafmtConfigFile)

  def init(args: String*): Command[Unit] = Task.Command(exclusive = true) {
    val root = moduleDir

    val mainClass = buildGenMainClass()
    val classPath = buildGenClasspath().map(_.path)
    val exitCode = Jvm.callProcess(
      mainClass = mainClass,
      classPath = classPath.toVector,
      mainArgs = args,
      cwd = root,
      stdin = os.Inherit,
      stdout = os.Inherit
    ).exitCode

    if (exitCode == 0) {
      val files = mill.init.Util.buildFiles(root).map(PathRef(_)).toSeq
      val config = buildGenScalafmtConfig()
      Task.log.info("formatting Mill build files")
      ScalafmtWorkerModule.worker().reformat(files, config)

      Task.log.info("init completed, run \"mill resolve _\" to list available tasks")
    } else {
      throw BuildGenException(s"$mainClass exit($exitCode)")
    }
  }
}
