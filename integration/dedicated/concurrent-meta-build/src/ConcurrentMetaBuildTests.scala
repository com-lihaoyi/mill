package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object ConcurrentMetaBuildTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  def tests: Tests = Tests {
    test("concurrentUseAndRebuild") - integrationTest { tester =>
      if (!tester.daemonMode) ()
      else {
        import tester.*

        val slowSignal = workspacePath / "slow-signal"
        val metaWait = workspacePath / "meta-wait"
        val metaEntered =
          workspacePath / "out" / "mill-build" / "generatedSources.dest" / "meta-entered"

        eval(("show", "fast"), check = true)

        val slowRun = spawn(("show", "blockWhileExists", "--path", slowSignal))
        assertEventually { os.exists(slowSignal) }

        val concurrentUse = eval(("show", "fast"), check = true)
        assert(concurrentUse.out.contains("0.13.1"))

        os.remove(slowSignal)
        slowRun.process.waitFor()

        modifyFile(
          workspacePath / "mill-build/build.mill",
          _.replace("""def scalatagsVersion = "0.13.1"""", """def scalatagsVersion = "0.13.2"""")
        )
        os.write.over(metaWait, "")

        val rebuildingRun = spawn(("show", "fast"))
        assertEventually { os.exists(metaEntered) }

        val waitingRun = spawn(("show", "fast"))
        assertEventually { waitingRun.process.isAlive() }

        os.remove(metaWait)
        rebuildingRun.process.waitFor()
        waitingRun.process.waitFor()

        assert(rebuildingRun.process.exitCode() == 0)
        assert(waitingRun.process.exitCode() == 0)
        assert(rebuildingRun.out.text().contains("0.13.2"))
        assert(waitingRun.out.text().contains("0.13.2"))
      }
    }
  }
}
