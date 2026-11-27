package app

import scala.scalajs.wasm.annotation.WasmExport

object App {
  def main(args: Array[String]): Unit = ()

  @WasmExport("twice")
  def twice(x: Int): Int = 2 * x
}
