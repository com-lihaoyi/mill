package mill.androidlib

import mill.androidlib.AndroidSdkModule
import mill.api.Task.Command
import mill.api.*
import mill.crFormat
import os.CommandResult

trait AndroidSdk extends AndroidSdkModule {
  override def buildToolsVersion: Task.Simple[String] = "35.0.0"

  def installPackage(packages: Seq[String]): Command[CommandResult] = Task.Command {
    androidSdkManagerModule().androidSdkManagerInstall(
      Task.Anon(PathRef(androidSdk().sdkPath)),
      Task.Anon(packages)
    )()
  }
}

object AndroidSdk extends ExternalModule, AndroidSdk, DefaultTaskModule {
  override lazy val millDiscover: Discover = Discover[this.type]

  override def defaultTask(): String = "installPackage"

}
