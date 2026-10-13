package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api.*

object Wasmtime {
  def apply(config: JsEnvConfig.Wasmtime) =
    new org.scalajs.jsenv.wasmtime.WasmtimeEnv(
      org.scalajs.jsenv.wasmtime.WasmtimeEnv.Config()
        .withExecutable(config.executable)
        .withArgs(config.args)
        .withEnv(config.env)
    )

  def input(component: java.nio.file.Path): org.scalajs.jsenv.Input =
    org.scalajs.jsenv.wasmtime.WasmtimeInput.WasmComponent(component)
}
