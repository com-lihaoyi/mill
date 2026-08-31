package mill.javalib

import mill.api.PathRef
import mill.javalib.api.internal.SharedCompilerClassLoaderCache
import utest.*

object SharedCompilerClassLoaderCacheTests extends TestSuite {
  def tests: Tests = Tests {
    test("reuseAcrossOwners") {
      val dir = os.temp.dir(prefix = "shared-compiler-classloader-cache-")
      val classPath = Seq(PathRef(dir))

      val loader1 = SharedCompilerClassLoaderCache.get(classPath)
      val loader2 = SharedCompilerClassLoaderCache.get(classPath)
      assert(loader1 eq loader2)

      SharedCompilerClassLoaderCache.release(classPath)
      val loader3 = SharedCompilerClassLoaderCache.get(classPath)
      assert(loader3 eq loader1)

      SharedCompilerClassLoaderCache.release(classPath)
      SharedCompilerClassLoaderCache.release(classPath)

      val loader4 = SharedCompilerClassLoaderCache.get(classPath)
      assert(!(loader4 eq loader1))

      SharedCompilerClassLoaderCache.release(classPath)
      os.remove.all(dir)
    }
  }
}
