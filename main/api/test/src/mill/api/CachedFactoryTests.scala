package mill.api

import utest._

object CachedFactoryTests extends TestSuite {
  val tests: Tests = Tests {
    var resourceCount = 0
    var closedResourceIds = Set.empty[Int]
    class Resource {
      resourceCount += 1
      val id = resourceCount
      def close() = closedResourceIds += id
    }
    class Cache(val maxCacheSize: Int) extends CachedFactory[Unit, Resource] {
      def setup(key: Unit): Resource = new Resource

      def teardown(key: Unit, value: Resource): Unit = value.close()
    }

    test("sizeZero") {
      // with cache of size 0, cache entries are never re-used
      object cache extends Cache(maxCacheSize = 0)

      assert(closedResourceIds == Set())
      assert(resourceCount == 0)
      cache.withValue(()) { resource1 =>
        assert(resource1.id == 1)
        assert(closedResourceIds == Set())
        assert(resourceCount == 1)
        cache.withValue(()) { resource2 =>
          assert(resource2.id == 2)
          assert(closedResourceIds == Set())
          assert(resourceCount == 2)
        }

        assert(closedResourceIds == Set(2))
        assert(resourceCount == 2)

        cache.withValue(()) { resource3 =>
          assert(resource3.id == 3)
          assert(closedResourceIds == Set(2))
          assert(resourceCount == 3)
        }

        assert(closedResourceIds == Set(2, 3))
        assert(resourceCount == 3)
      }
      assert(closedResourceIds == Set(1, 2, 3))
      assert(resourceCount == 3)

      // close() does nothing since all resources are already closed
      cache.close()
      assert(closedResourceIds == Set(1, 2, 3))
      assert(resourceCount == 3)
    }

    test("sizeOne") {
      // with cache of size 1, cache entries are sometimes re-used
      object cache extends Cache(maxCacheSize = 1)

      assert(closedResourceIds == Set())
      assert(resourceCount == 0)
      cache.withValue(()) { resource1 =>
        assert(resource1.id == 1)
        assert(closedResourceIds == Set())
        assert(resourceCount == 1)
        cache.withValue(()) { resource2 =>
          assert(resource2.id == 2)
          assert(closedResourceIds == Set())
          assert(resourceCount == 2)
        }

        assert(closedResourceIds == Set())
        assert(resourceCount == 2)

        // Resource id=2 is the last one to be created and cached, and so
        // gets re-used when withValue is asked for again
        cache.withValue(()) { resource3 =>
          assert(resource3.id == 2)
          assert(closedResourceIds == Set())
          assert(resourceCount == 2)
        }

        assert(closedResourceIds == Set())
        assert(resourceCount == 2)
      }

      // When both id=1 and id=2 are done, one of them has to be torn down
      // due to maxCacheSize=1, so 2 is turn down
      assert(closedResourceIds == Set(2))
      assert(resourceCount == 2)

      // close() closes the 1 cached resource id=1 who was not yet torn down
      cache.close()
      assert(closedResourceIds == Set(1, 2))
      assert(resourceCount == 2)
    }
    test("sizeTwo") {
      // with cache of size 1, cache entries are always re-used for this small exmaple
      object cache extends Cache(maxCacheSize = 2)

      assert(closedResourceIds == Set())
      assert(resourceCount == 0)
      cache.withValue(()) { resource1 =>
        assert(resource1.id == 1)
        assert(closedResourceIds == Set())
        assert(resourceCount == 1)
        cache.withValue(()) { resource2 =>
          assert(resource2.id == 2)
          assert(closedResourceIds == Set())
          assert(resourceCount == 2)
        }

        assert(closedResourceIds == Set())
        assert(resourceCount == 2)

        // Resource id=2 is the last one to be created and cached, and so
        // gets re-used when withValue is asked for again
        cache.withValue(()) { resource3 =>
          assert(resource3.id == 2)
          assert(closedResourceIds == Set())
          assert(resourceCount == 2)
        }

        assert(closedResourceIds == Set())
        assert(resourceCount == 2)
      }

      // Cache is big enough to fit both id=1 and id=2, so none of them get torn down
      assert(closedResourceIds == Set())
      assert(resourceCount == 2)

      // close() closes the both cached resources id=1 and id=2 who were not yet torn down
      cache.close()
      assert(closedResourceIds == Set(1, 2))
      assert(resourceCount == 2)
    }

    test("sizeOneKeyed") {
      // with cache of size 1, cache entries are sometimes re-used,
      // but only if key matches
      object cache extends CachedFactory[Char, Resource] {
        def setup(key: Char): Resource = new Resource

        def teardown(key: Char, value: Resource): Unit = value.close()

        def maxCacheSize = 1
      }

      assert(closedResourceIds == Set())
      assert(resourceCount == 0)
      cache.withValue('a') { resource1 =>
        assert(resource1.id == 1)
        assert(closedResourceIds == Set())
        assert(resourceCount == 1)
      }

      assert(closedResourceIds == Set())
      assert(resourceCount == 1)

      cache.withValue('a') { resource2 =>
        // With the same key, we re-use the cached resource id=1
        assert(resource2.id == 1)
        assert(closedResourceIds == Set())
        assert(resourceCount == 1)
      }

      assert(closedResourceIds == Set())
      assert(resourceCount == 1)

      cache.withValue('b') { resource3 =>
        // with a different key, we need to create a new resource
        assert(resource3.id == 2)
        assert(closedResourceIds == Set())
        assert(resourceCount == 2)
      }

      // Because maxCacheSize = 1, we close the LRU resource, which is id=1
      assert(closedResourceIds == Set(1))
      assert(resourceCount == 2)

      // close() closes the 1 cached resource id=1 who was not yet torn down
      cache.close()
      assert(closedResourceIds == Set(1, 2))
      assert(resourceCount == 2)
    }
  }
}
