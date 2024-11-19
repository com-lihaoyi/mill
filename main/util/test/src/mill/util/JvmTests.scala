package mill.util

import mill.Agg
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

      Jvm.createClasspathPassingJar(aJar, Agg(dep1, dep2))
      assert(os.exists(aJar))

      val jar = new JarFile(aJar.toIO)
      assert(jar.getManifest().getMainAttributes().containsKey(Attributes.Name.CLASS_PATH))
      assert(jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH) ==
        Seq(dep1, dep2).map(_.toNIO.toUri().toURL().toExternalForm()).mkString(" "))
    }

    test("call") {
      val tmpDir = os.temp.dir()
      val mainClass = "mill.util.TestMain"
      val classPath = Agg(tmpDir)
      val jvmArgs = Seq("-Xmx512m")
      val mainArgs = Seq("arg1", "arg2")
      val result = Jvm.call(mainClass, classPath, jvmArgs, mainArgs)
      assert(result.exitCode == 0)
    }

    test("spawn") {
      val tmpDir = os.temp.dir()
      val mainClass = "mill.util.TestMain"
      val classPath = Agg(tmpDir)
      val jvmArgs = Seq("-Xmx512m")
      val mainArgs = Seq("arg1", "arg2")
      val process = Jvm.spawn(mainClass, classPath, jvmArgs, mainArgs)
      assert(process.isAlive())
      process.destroy()
    }

    test("callClassloader") {
      val tmpDir = os.temp.dir()
      val classPath = Agg(tmpDir)
      val sharedPrefixes = Seq("mill.util")
      val result = Jvm.callClassloader(classPath, sharedPrefixes) { cl =>
        cl.loadClass("mill.util.TestMain")
      }
      assert(result.getName == "mill.util.TestMain")
    }

    test("spawnClassloader") {
      val tmpDir = os.temp.dir()
      val classPath = Agg(tmpDir)
      val sharedPrefixes = Seq("mill.util")
      val classLoader = Jvm.spawnClassloader(classPath, sharedPrefixes)
      val result = classLoader.loadClass("mill.util.TestMain")
      assert(result.getName == "mill.util.TestMain")
    }

  }

}
