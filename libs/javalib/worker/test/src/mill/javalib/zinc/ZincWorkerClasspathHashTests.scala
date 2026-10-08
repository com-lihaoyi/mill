package mill.javalib.zinc

import mill.api.PathRef
import utest.*

object ZincWorkerClasspathHashTests extends TestSuite {
  val tests: Tests = Tests {
    test("classpathFileHashesReusePathRefSigForFilesAndSentinelForDirectories") {
      val workDir = os.temp.dir()
      try {
        val jar = workDir / "dep.jar"
        os.write(jar, "jar-bytes")
        val classesDir = workDir / "classes"
        os.makeDir.all(classesDir)

        val jarRef = PathRef(jar, quick = true)
        val dirRef = PathRef(classesDir, quick = true)

        val hashes = ZincWorker.classpathFileHashes(Seq(jarRef, dirRef), classesDir)

        assert(hashes.length == 3)
        assert(hashes(0).file() == jar.toNIO.toAbsolutePath.normalize())
        assert(hashes(0).hash() == jarRef.sig)
        assert(hashes(1).file() == classesDir.toNIO.toAbsolutePath.normalize())
        assert(hashes(1).hash() == 42)
        assert(hashes(2).file() == classesDir.toNIO.toAbsolutePath.normalize())
        assert(hashes(2).hash() == 42)
      } finally os.remove.all(workDir)
    }
  }
}
