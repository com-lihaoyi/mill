import mill.util.{TestEvaluator, TestUtil}
import utest._

object WebExamplesTests extends TestSuite {
  val tests = Tests {
    test("scalajs-wasm") {
      val workdir = os.pwd / "example" / "scalalib" / "web" / "8-scalajs-wasm"
      val eval = new TestEvaluator(workdir)
      
      val compileResult = eval.apply(wasmhello.compileWasm)
      assert(compileResult.isRight)
      
      val wasmFile = workdir / "out" / "wasmhello" / "compileWasm.dest" / "main.wasm"
      assert(os.exists(wasmFile))

      // Note: This part assumes wasmtime is installed and available
      val runResult = os.proc("wasmtime", wasmFile).call(cwd = workdir)
      assert(runResult.exitCode == 0)
      assert(runResult.out.text().contains("Hello from Scala.js WASM!"))
    }
  }
}