package mill.init

import mill.api.{Discover, ExternalModule}
import mill.javalib.Dep

object InitSbtModule extends ExternalModule, InitMigrateModule:
  lazy val millDiscover = Discover[this.type]
  def initDeps = Seq(Dep.millProjectModule("mill-libs-init-migrate-sbt"))
  def initMainClass = "mill.init.migrate.sbt.SbtImportMain"
