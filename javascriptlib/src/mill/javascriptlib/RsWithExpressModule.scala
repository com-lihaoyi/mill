package mill.javascriptlib

import mill.*
import os.*

trait RsWithExpressModule extends TypeScriptModule {
  def clientMod: TypeScriptModule
  def serverMod: TypeScriptModule

  override def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val clientBundle = clientMod.bundle().path
    val serverBundle = serverMod.bundle().path
    val serverEnv = serverMod.mkENV()

    val env = serverEnv ++ Map(
      "CLIENT_PATH" -> clientBundle.toString,
      "SERVER" -> serverEnv.getOrElse("PORT", "")
    )

    os.call(
      (
        "node",
        serverBundle.toString
      ),
      stdout = os.Inherit,
      env = env
    )

  }

}
