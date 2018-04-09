package mill.integration

import utest._

class CaffeineTests(fork: Boolean) extends IntegrationTestSuite("MILL_CAFFEINE_REPO", "caffeine", fork) {
  val tests = Tests{
    initWorkspace()
    'test - {
      // Caffeine only can build using Java 9 or up. Java 8 results in weird
      // type inference issues during the compile
      if (mill.client.ClientServer.isJava9OrAbove){
        assert(eval("caffeine.test.compile"))
        assert(eval("guava.test"))
        val suites = Seq(
          "com.github.benmanes.caffeine.SingleConsumerQueueTest",
          "com.github.benmanes.caffeine.cache.AsyncTest",
          "com.github.benmanes.caffeine.cache.CaffeineTest",
          "com.github.benmanes.caffeine.cache.TimerWheelTest"
        )
        assert(eval(
          "caffeine.test",
          "-testclass", suites.mkString(",")
        ))
        assert(eval("guava.test.compile"))
        assert(eval("jcache.test.compile"))
        assert(eval("simulator.test.compile"))
      }
    }

  }
}
