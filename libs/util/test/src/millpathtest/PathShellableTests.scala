package millpathtest

import mill.*
import utest.*

object PathShellableTests extends TestSuite {
  val tests: Tests = Tests {
    test("usesAbsolutePath") {
      val tmpDir = os.temp.dir()
      try {
        val script = tmpDir / "script"
        os.write(script, "script")
        val serializer = os.Path.pathRemapSerializerNio(
          Seq(tmpDir.wrapped -> java.nio.file.Paths.get("..", "mill-workspace"))
        )

        os.Path.pathSerializer.withValue(serializer) {
          assert(
            script.toString.startsWith(".."),
            os.proc(script).commandChunks == Seq(PathRef.toAbsString(script))
          )
        }
      } finally os.remove.all(tmpDir)
    }
  }
}
