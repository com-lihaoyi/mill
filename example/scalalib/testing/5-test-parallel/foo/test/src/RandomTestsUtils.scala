package foo
import utest.*

abstract class RandomTestsUtils extends TestSuite {

  // sleepTime try to mimics real-life test time,
  // sleepTime can be chosen randomly, but test classes should be "slow" (~100ms)
  // enough so that cluster decide to spawn all available test runner processeses
  // fail to do this can lead to flakky test.
  def testGreeting(name: String, sleepTime: Int) = {
    val greeted = Foo.greet(name)
    Thread.sleep(sleepTime)
    assert(greeted == s"Hello $name")
  }

}
