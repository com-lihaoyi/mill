package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

object BomTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {

    def expectedProtobufJavaVersion = "4.28.3"

    def compileClasspathFileNames(tester: IntegrationTester, moduleName: String): Seq[String] = {
      import tester._
      val res = eval(
        ("show", s"$moduleName.compileClasspath"),
        stderr = os.Inherit,
        check = true
      )
      ujson.read(res.out).arr.map(v => os.Path(v.str.split(":").last).last).toSeq
    }

    test("googleCloudJavaCheck") - integrationTest { tester =>
      import tester._

      val res = eval(
        ("show", "google-cloud-java-no-bom.compileClasspath"),
        check = false
      )
      assert(
        res.err.contains(
          "not found: https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/_/protobuf-java-_.pom"
        )
      )
    }

    test("googleCloudJava") - integrationTest { tester =>
      val compileClasspathFileNames0 = compileClasspathFileNames(tester, "google-cloud-java")
      assert(compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))
    }

    test("googleCloudScalaCheck") - integrationTest { tester =>
      val compileClasspathFileNames0 =
        compileClasspathFileNames(tester, "google-cloud-scala-no-bom")
      assert(
        compileClasspathFileNames0.exists(v => v.startsWith("protobuf-java-") && v.endsWith(".jar"))
      )
      assert(
        !compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar")
      )
    }

    test("googleCloudScala") - integrationTest { tester =>
      val compileClasspathFileNames0 = compileClasspathFileNames(tester, "google-cloud-scala")
      assert(compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))
    }

  }
}
