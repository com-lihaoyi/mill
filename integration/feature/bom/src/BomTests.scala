package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

import scala.jdk.CollectionConverters._

object BomTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {

    def expectedProtobufJavaVersion = "4.28.3"
    def expectedCommonsCompressVersion = "1.23.0"

    def expectedProtobufJarName = s"protobuf-java-$expectedProtobufJavaVersion.jar"
    def expectedCommonsCompressJarName = s"commons-compress-$expectedCommonsCompressVersion.jar"

    def compileClasspathFileNames(moduleName: String)(implicit
        tester: IntegrationTester
    ): Seq[String] = {
      import tester._
      val res = eval(
        ("show", s"$moduleName.compileClasspath"),
        stderr = os.Inherit,
        check = true
      )
      ujson.read(res.out).arr.map(v => os.Path(v.str.split(":").last).last).toSeq
    }

    def compileClasspathContains(module: String, fileName: String)(implicit
        tester: IntegrationTester
    ) = {
      val fileNames = compileClasspathFileNames(module)
      assert(fileNames.contains(fileName))
    }

    def publishLocalAndResolve(
        module: String,
        dependencyModules: Seq[String]
    )(implicit tester: IntegrationTester): Seq[os.Path] = {
      val localIvyRepo = tester.workspacePath / "ivy2Local"
      for (moduleName <- module +: dependencyModules)
        tester.eval((s"$moduleName.publishLocal", "--localIvyRepo", localIvyRepo), check = true)

      coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of("com.lihaoyi.mill-tests", module.replace('.', '-'), "0.1.0-SNAPSHOT")
        )
        .addRepositories(
          coursierapi.IvyRepository.of(localIvyRepo.toNIO.toUri.toASCIIString + "[defaultPattern]")
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector
    }

    def publishM2LocalAndResolve(
        module: String,
        dependencyModules: Seq[String]
    )(implicit tester: IntegrationTester): Seq[os.Path] = {
      val localM2Repo = tester.workspacePath / "m2Local"
      for (moduleName <- module +: dependencyModules)
        tester.eval((s"$moduleName.publishM2Local", "--m2RepoPath", localM2Repo), check = true)

      coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of("com.lihaoyi.mill-tests", module.replace('.', '-'), "0.1.0-SNAPSHOT")
        )
        .addRepositories(
          coursierapi.MavenRepository.of(localM2Repo.toNIO.toUri.toASCIIString)
        )
        .fetch()
        .asScala
        .map(os.Path(_))
        .toVector
    }

    def isInClassPath(
        module: String,
        jarName: String,
        dependencyModules: Seq[String] = Nil
    )(implicit tester: IntegrationTester): Unit = {
      compileClasspathContains(module, jarName)

      val resolvedCp = publishLocalAndResolve(module, dependencyModules)
      assert(resolvedCp.map(_.last).contains(jarName))

      val resolvedM2Cp = publishM2LocalAndResolve(module, dependencyModules)
      assert(resolvedM2Cp.map(_.last).contains(jarName))
    }

    test("bom") {
      test("placeholder") {
        test("check") - integrationTest { tester =>
          import tester._

          val res = eval(
            ("show", "bom.placeholder.check.compileClasspath"),
            check = false
          )
          assert(
            res.err.contains(
              "not found: https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/_/protobuf-java-_.pom"
            )
          )
        }

        test("simple") - integrationTest { implicit tester =>
          isInClassPath("bom.placeholder", expectedProtobufJarName)
        }

        test("dependee") - integrationTest { implicit tester =>
          isInClassPath("bom.placeholder.dependee", expectedProtobufJarName, Seq("bom.placeholder"))
        }
      }

      test("versionOverride") {
        test("check") - integrationTest { implicit tester =>
          val fileNames = compileClasspathFileNames("bom.versionOverride.check")
          assert(fileNames.exists(v => v.startsWith("protobuf-java-") && v.endsWith(".jar")))
          assert(!fileNames.contains(expectedProtobufJarName))
        }

        test("simple") - integrationTest { implicit tester =>
          isInClassPath("bom.versionOverride", expectedProtobufJarName)
        }

        test("dependee") - integrationTest { implicit tester =>
          isInClassPath(
            "bom.versionOverride.dependee",
            expectedProtobufJarName,
            Seq("bom.versionOverride")
          )
        }
      }
    }

    test("parent") {
      test("simple") - integrationTest { implicit tester =>
        isInClassPath("parent", expectedCommonsCompressJarName)
      }

      test("dependee") - integrationTest { implicit tester =>
        isInClassPath("parent.dependee", expectedCommonsCompressJarName, Seq("parent"))
      }
    }

    test("depMgmt") {
      test("override") - integrationTest { implicit tester =>
        isInClassPath("depMgmt", expectedProtobufJarName)
      }

      test("placeholder") - integrationTest { implicit tester =>
        isInClassPath("depMgmt.placeholder", expectedProtobufJarName)
      }
    }
  }
}
