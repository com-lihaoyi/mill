package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

import scala.jdk.CollectionConverters._

object BomTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {

    def expectedProtobufJavaVersion = "4.28.3"
    def expectedCommonsCompressVersion = "1.23.0"

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
      import tester._

      val compileClasspathFileNames0 = compileClasspathFileNames(tester, "google-cloud-java")
      assert(compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))

      val repo = workspacePath / "ivy2Local"
      eval(("google-cloud-java.publishLocal", "--localIvyRepo", repo), check = true)

      val obtainedClassPath = coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of("com.lihaoyi.mill-tests", "google-cloud-java", "0.1.0-SNAPSHOT")
        )
        .addRepositories(
          coursierapi.IvyRepository.of(repo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector

      val obtainedClassPathFileNames = obtainedClassPath.map(_.last)
      assert(obtainedClassPathFileNames.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))
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
      import tester._

      val compileClasspathFileNames0 = compileClasspathFileNames(tester, "google-cloud-scala")
      assert(compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))

      val repo = workspacePath / "ivy2Local"
      eval(("google-cloud-scala.publishLocal", "--localIvyRepo", repo), check = true)

      val obtainedClassPath = coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of(
            "com.lihaoyi.mill-tests",
            "google-cloud-scala",
            "0.1.0-SNAPSHOT"
          )
        )
        .addRepositories(
          coursierapi.IvyRepository.of(repo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector

      val obtainedClassPathFileNames = obtainedClassPath.map(_.last)
      assert(obtainedClassPathFileNames.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))
    }

    test("googleCloudJavaDependee") - integrationTest { tester =>
      import tester._

      val compileClasspathFileNames0 =
        compileClasspathFileNames(tester, "google-cloud-java-dependee")
      assert(compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))

      val repo = workspacePath / "ivy2Local"
      eval(("google-cloud-java.publishLocal", "--localIvyRepo", repo), check = true)
      eval(("google-cloud-java-dependee.publishLocal", "--localIvyRepo", repo), check = true)

      val obtainedClassPath = coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of(
            "com.lihaoyi.mill-tests",
            "google-cloud-java-dependee",
            "0.1.0-SNAPSHOT"
          )
        )
        .addRepositories(
          coursierapi.IvyRepository.of(repo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector

      val obtainedClassPathFileNames = obtainedClassPath.map(_.last)
      assert(obtainedClassPathFileNames.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))
    }

    test("googleCloudScalaDependee") - integrationTest { tester =>
      import tester._

      val compileClasspathFileNames0 =
        compileClasspathFileNames(tester, "google-cloud-scala-dependee")
      assert(compileClasspathFileNames0.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))

      val repo = workspacePath / "ivy2Local"
      eval(("google-cloud-scala.publishLocal", "--localIvyRepo", repo), check = true)
      eval(("google-cloud-scala-dependee.publishLocal", "--localIvyRepo", repo), check = true)

      val obtainedClassPath = coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of(
            "com.lihaoyi.mill-tests",
            "google-cloud-scala-dependee",
            "0.1.0-SNAPSHOT"
          )
        )
        .addRepositories(
          coursierapi.IvyRepository.of(repo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector

      val obtainedClassPathFileNames = obtainedClassPath.map(_.last)
      assert(obtainedClassPathFileNames.contains(s"protobuf-java-$expectedProtobufJavaVersion.jar"))
    }

    test("parent") - integrationTest { tester =>
      import tester._

      val compileClasspathFileNames0 = compileClasspathFileNames(tester, "parent")
      assert(
        compileClasspathFileNames0.contains(s"commons-compress-$expectedCommonsCompressVersion.jar")
      )

      val repo = workspacePath / "ivy2Local"
      eval(("parent.publishLocal", "--localIvyRepo", repo), check = true)

      val obtainedClassPath = coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of("com.lihaoyi.mill-tests", "parent", "0.1.0-SNAPSHOT")
        )
        .addRepositories(
          coursierapi.IvyRepository.of(repo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector

      val obtainedClassPathFileNames = obtainedClassPath.map(_.last)
      assert(
        obtainedClassPathFileNames.contains(s"commons-compress-$expectedCommonsCompressVersion.jar")
      )
    }

    test("parentDependee") - integrationTest { tester =>
      import tester._

      val compileClasspathFileNames0 = compileClasspathFileNames(tester, "parent-dependee")
      assert(
        compileClasspathFileNames0.contains(s"commons-compress-$expectedCommonsCompressVersion.jar")
      )

      val repo = workspacePath / "ivy2Local"
      eval(("parent.publishLocal", "--localIvyRepo", repo), check = true)
      eval(("parent-dependee.publishLocal", "--localIvyRepo", repo), check = true)

      val obtainedClassPath = coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of("com.lihaoyi.mill-tests", "parent-dependee", "0.1.0-SNAPSHOT")
        )
        .addRepositories(
          coursierapi.IvyRepository.of(repo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector

      val obtainedClassPathFileNames = obtainedClassPath.map(_.last)
      assert(
        obtainedClassPathFileNames.contains(s"commons-compress-$expectedCommonsCompressVersion.jar")
      )
    }

  }
}
