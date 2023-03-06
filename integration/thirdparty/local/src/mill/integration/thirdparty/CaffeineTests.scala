package mill.integration.thirdparty

import utest._

class CaffeineTests(fork: Boolean)
    extends IntegrationTestSuite("MILL_CAFFEINE_REPO", "caffeine", fork) {

  override def initWorkspace() = {
    val path = super.initWorkspace()
    // Fixes for caffeine test suite under Java11
    os.walk(workspacePath).foreach { p =>
      p.last match {
        case "AsMapTest.java" =>
          println(s"Patching ${p} ...")
          os.write.over(
            p,
            os.read.lines(p)
              .map(
                _.replace(
                  "map.keySet().toArray(null);",
                  "map.keySet().toArray((Integer[]) null);"
                )
                  .replace(
                    "map.values().toArray(null);",
                    "map.values().toArray((Integer[]) null);"
                  )
                  .replace(
                    "map.entrySet().toArray(null);",
                    "map.entrySet().toArray((Integer[]) null);"
                  )
              )
              .mkString("\n")
          )
        case "EmptyCachesTest.java" =>
          println(s"Patching ${p} ...")
          os.write.over(
            p,
            os.read.lines(p)
              .map(
                _.replace("keys.toArray(null);", "keys.toArray((Object[]) null);")
                  .replace(
                    "values.toArray(null);",
                    "values.toArray((Object[]) null);"
                  )
                  .replace(
                    "entries.toArray(null);",
                    "entries.toArray((Entry<Object, Object>[]) null);"
                  )
              )
              .mkString("\n")
          )
        case _ => // no patching needed
      }
    }
    path
  }

  val tests = Tests {
    initWorkspace()
    "test" - {
      // Caffeine only can build using Java 9 or up. Java 8 results in weird
      // type inference issues during the compile
      if (!mill.main.client.Util.isJava9OrAbove) {
        println("Skipping caffeine tests for Java version < 9")
      } else {
        assert(eval("caffeine.test.compile"))
        val suites = Seq(
          "com.github.benmanes.caffeine.SingleConsumerQueueTest",
          "com.github.benmanes.caffeine.cache.AsyncTest",
          "com.github.benmanes.caffeine.cache.CaffeineTest",
          "com.github.benmanes.caffeine.cache.TimerWheelTest"
        )
        assert(eval(
          "caffeine.test",
          "-testclass",
          suites.mkString(",")
        ))
        assert(eval("guava.test.compile"))
        val guavaSuites = Seq(
          "com.google.common.cache.CacheExpirationTest",
          "com.google.common.cache.NullCacheTest",
          "com.google.common.cache.CacheReferencesTest",
          "com.google.common.cache.CacheBuilderGwtTest",
          "com.google.common.cache.PopulatedCachesTest",
          "com.google.common.cache.CacheStatsTest",
          "com.google.common.cache.CacheBuilderTest"
        )
        assert(eval("guava.test", "-testclass", guavaSuites.mkString(",")))

        assert(eval("jcache.test.compile"))
        assert(eval("simulator.test.compile"))

      }
    }

  }
}
