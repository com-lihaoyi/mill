package mill.androidlib

import mill.androidlib.AndroidSdkModule
import mill.api.Task.Command
import mill.api.*
import mill.crFormat
import os.CommandResult

@mill.api.daemon.experimental
trait AndroidSdkManager extends AndroidSdkModule {
  override def buildToolsVersion: Task.Simple[String] = Task.Input {
    Versions.millBuildToolsVersion
  }

  def installPackage(packages: Seq[String]): Command[CommandResult] = Task.Command {
    androidSdkManagerModule().androidSdkManagerInstall(
      sdkManagerExe,
      Task.Anon(packages)
    )()
  }
}

object AndroidSdkManager extends ExternalModule, AndroidSdkManager, DefaultTaskModule {
  override lazy val millDiscover: Discover = Discover[this.type]

  override def defaultTask(): String = "installPackage"

}
