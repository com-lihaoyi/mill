package mill.util

import mill.util.Jvm
import utest.{TestSuite, Tests, test}

import java.util.jar.{Attributes, JarFile}

object JvmTests extends TestSuite {

  val tests = Tests {

    test("createClasspathPassingJar") {
      val tmpDir = os.temp.dir()
      val aJar = tmpDir / "a.jar"
      assert(!os.exists(aJar))

      val dep1 = tmpDir / "dep-1.jar"
      val dep2 = tmpDir / "dep-2.jar"
      os.write(dep1, "JAR 1")
      os.write(dep2, "JAR 2")

      Jvm.createClasspathPassingJar(aJar, Seq(dep1, dep2))
      assert(os.exists(aJar))

      val jar = JarFile(aJar.toIO)
      assert(jar.getManifest().getMainAttributes().containsKey(Attributes.Name.CLASS_PATH))
      assert(jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH) ==
        Seq(dep1, dep2).map(_.toURL.toExternalForm()).mkString(" "))
    }

  }

}
