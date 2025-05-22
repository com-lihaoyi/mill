package mill.javascriptlib

import mill._
import os.*

trait RsWithServeModule extends ReactScriptsModule {
  override def npmDevDeps: T[Seq[String]] = Task {
    super.npmDevDeps() ++ Seq("serve@12.0.1")
  }

  // serve static Html page
  def run: T[CommandResult] = Task {
    val compiled = compile().path
    val build = bundle().path
    val env = forkEnv()
    os.call(
      (
        (compiled / "node_modules/serve/bin/serve.js").toString,
        "-s",
        build,
        "-l",
        env.get("PORT").orElse(Option("3000"))
      ),
      stdout = os.Inherit,
      env = env
    )
  }
}
