package mill.bsp.newbsp

import mill.bsp.newbsp.BspServer.BspServerExit
import org.scalatest.freespec.AnyFreeSpec

class BspServerTest() extends AnyFreeSpec {

  "The BspServer" - {
    "should support --help" in {
      val ex = intercept[BspServerExit] {
        BspServer.run(Array("--help"))
      }
      assert(ex.code === 0, s"hint: code: ${ex.code} msg: ${ex.msg}")
    }
    "should generate a connection JSON file" in {
      val tmpDir = os.temp.dir(deleteOnExit = false)
      assert(!os.exists(tmpDir / ".bsp" / "mill.json"))
      BspServer.run(Array("--dir", tmpDir.toString(), "--install"))
      assert(os.exists(tmpDir / ".bsp" / "mill.json"))
      os.remove.all(tmpDir)
    }
  }

}
