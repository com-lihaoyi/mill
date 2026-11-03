package mill.util

import coursier.jvm.JvmChannel
import coursier.version.VersionConstraint
import mill.util.Jvm
import utest.{TestSuite, Tests, test}

import java.util.jar.{Attributes, JarFile}
import scala.concurrent.ExecutionContext

object JvmTests extends TestSuite {

  val tests = Tests {

    test("parseJavaVersionRelease") {
      test("quoted") {
        assert(Jvm.parseJavaVersionRelease(Seq("""JAVA_VERSION="17.0.7"""")) == Some("17.0.7"))
      }
      test("unquoted") {
        assert(Jvm.parseJavaVersionRelease(Seq("JAVA_VERSION=21")) == Some("21"))
      }
      test("firstOfMany") {
        val lines =
          Seq("OS_ARCH=\"x86_64\"", """JAVA_VERSION="1.8.0_402"""", "MODULES=\"java.base\"")
        assert(Jvm.parseJavaVersionRelease(lines) == Some("1.8.0_402"))
      }
      test("notThere") {
        assert(Jvm.parseJavaVersionRelease(Seq("""JAVA_VERSION_DATE="2023-04-18"""")) == None)
      }
      test("empty") {
        assert(Jvm.parseJavaVersionRelease(Seq("""JAVA_VERSION=""""")) == None)
      }
    }

    test("parseJavaVersionOutput") {
      test("openjdk") {
        val output =
          """openjdk version "17.0.7" 2023-04-18
            |OpenJDK Runtime Environment Temurin-17.0.7+7 (build 17.0.7+7)
            |""".stripMargin
        assert(Jvm.parseJavaVersionOutput(output) == Some("17.0.7"))
      }
      test("java8") {
        val output = """java version "1.8.0_402""""
        assert(Jvm.parseJavaVersionOutput(output) == Some("1.8.0_402"))
      }
      test("garbage") {
        assert(Jvm.parseJavaVersionOutput("command not found") == None)
      }
    }

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

    test("jvmIndexChannel") {
      test("github") {
        assert(Jvm.jvmIndexChannel(Jvm.gitHubJvmIndexVersion) == JvmChannel.gitHub())
      }
      test("central") {
        val expected =
          JvmChannel.module(JvmChannel.centralModule(), VersionConstraint("0.0.5"))
        assert(Jvm.jvmIndexChannel("0.0.5") == expected)
      }
    }

    test("gitHubJvmIndex") {
      val index = Jvm.jvmIndex0(jvmIndexVersion = Jvm.gitHubJvmIndexVersion)
        .unsafeRun()(using ExecutionContext.global)
      assert(index.lookup("temurin", "17").exists(_.nonEmpty))
    }

  }

}
