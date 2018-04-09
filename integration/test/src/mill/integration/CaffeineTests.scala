package mill.integration

import utest._

class CaffeineTests(fork: Boolean) extends IntegrationTestSuite("MILL_CAFFEINE_REPO", "caffeine", fork) {
  val tests = Tests{
    initWorkspace()
    'test - {
      // Caffeine only can build using Java 9 or up. Java 8 results in weird
      // type inference issues during the compile
      if (mill.client.ClientServer.isJava9OrAbove){
        assert(eval(s"caffeine.test.compile"))
        assert(eval(s"guava.test.compile"))
        assert(eval(s"jcache.test.compile"))
        assert(eval(s"simulator.test.compile"))
      }
    }

  }
}
