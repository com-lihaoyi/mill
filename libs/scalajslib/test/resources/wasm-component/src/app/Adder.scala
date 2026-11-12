package app

import scala.scalajs.wit.annotation.{WitExport, WitName, WitScope}

// A reactor component: its only entry point is the exported WIT function
// `mill:test/adder@0.1.0#add`, which needs scala-wasm's compiler plugin and linker
object Adder {
  @WitExport(WitScope("mill", "test", "adder", "0.1.0"), "add")
  def add(@WitName("a") a: Int, @WitName("b") b: Int): Int = a + b
}
