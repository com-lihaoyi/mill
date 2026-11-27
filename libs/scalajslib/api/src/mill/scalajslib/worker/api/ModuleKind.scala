package mill.scalajslib.worker.api

private[scalajslib] enum ModuleKind {
  case NoModule
  case CommonJSModule
  case ESModule
  case WasmComponent

  /**
   * A bare WebAssembly module, without JavaScript interop or glue code.
   * Requires Scala.js >= 1.23.0. This module kind enables the Wasm backend
   * automatically. Loading and running the module requires a suitable Wasm host;
   * Mill's JavaScript run and test environments do not support it.
   */
  case WasmModule
}
