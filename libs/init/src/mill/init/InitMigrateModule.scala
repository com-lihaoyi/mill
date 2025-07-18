package mill.init

import mill.{Command, T}
import mill.api.*
import mill.javalib.{CoursierModule, Dep}
import mill.scalalib.scalafmt.ScalafmtWorkerModule
import mill.util.Jvm

trait InitMigrateModule extends CoursierModule, DefaultTaskModule:

  def defaultTask() = "init"

  def initDeps: T[Seq[Dep]]

  def initClasspath: T[Seq[PathRef]] = Task:
    defaultResolver().classpath(initDeps())

  def initMainClass: T[String]

  def initScalafmtConfig: T[PathRef] = PathRef(mill.init.Util.scalafmtConfigFile)

  def init(args: String*) = Command(exclusive = true):
    Task.log.info("migrating project to Mill ...")
    Jvm.callProcess(
      mainClass = initMainClass(),
      mainArgs = args,
      classPath = initClasspath().map(_.path),
      stdin = os.Inherit,
      stdout = os.Inherit
    )
    val files = mill.init.Util.buildFiles(moduleDir).map(PathRef(_)).toSeq
    ScalafmtWorkerModule.worker().reformat(files, initScalafmtConfig())
    Task.log.info("init completed, run \"mill resolve _\" to list available tasks")
