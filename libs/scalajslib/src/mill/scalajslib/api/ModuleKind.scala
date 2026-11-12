package mill.scalajslib.api

import upickle.ReadWriter

enum ModuleKind derives ReadWriter {
  case NoModule
  case CommonJSModule
  case ESModule

  /**
   * A WebAssembly Component (WASI Component Model). Only supported by Scala.js
   * linkers that implement the Component Model, such as the
   * [[https://github.com/scala-wasm/scala-wasm scala-wasm]] fork
   * (see [[mill.scalajslib.ScalaJSModule.scalaJSOrganization]]).
   * Requires [[mill.scalajslib.ScalaJSModule.scalaJSUseWebAssembly]].
   */
  case WasmComponent
}
