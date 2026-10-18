package mill.util

import mill.util.Jvm
import utest.{TestSuite, Tests, test}

import java.util.jar.{Attributes, JarFile}

object JvmTests extends TestSuite {

  val tests = Tests {

    test("javaVersion") {

      test("runningJvm") {
        // `None` is "the JVM Mill runs on", which we can check against what that JVM says itself
        assert(Jvm.javaVersion(None) == Some(sys.props("java.version")))
      }

      test("jdkOnDisk") {
        // Same JVM, but read off its `release` file rather than the system properties - the path
        // taken for any JDK other than the one this process runs on
        val javaHome = os.Path(sys.props("java.home"))
        assert(Jvm.javaVersion(Some(javaHome)) == Some(sys.props("java.version")))
      }

      test("releaseFile") {
        val javaHome = os.temp.dir()
        os.write(
          javaHome / "release",
          """IMPLEMENTOR="Eclipse Adoptium"
            |JAVA_VERSION="17.0.7"
            |JAVA_VERSION_DATE="2023-04-18"
            |""".stripMargin
        )
        assert(Jvm.javaVersion(Some(javaHome)) == Some("17.0.7"))
      }

      test("noJdkThere") {
        // No `release` file to read and no `java` to ask, so there is nothing to report
        assert(Jvm.javaVersion(Some(os.temp.dir())) == None)
      }
    }

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

  }

}
