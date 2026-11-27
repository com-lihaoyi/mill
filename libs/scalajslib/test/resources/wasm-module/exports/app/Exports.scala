package app

import scala.scalajs.wasm.annotation.WasmExport

object Exports {
  @WasmExport("twice")
  def twice(x: Int): Int = App.twice(x)
}
