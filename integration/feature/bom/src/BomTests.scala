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

    def compileClasspathContains(
        module: String,
        fileName: String,
        jarCheck: Option[String => Boolean]
    )(implicit
        tester: IntegrationTester
    ) = {
      val fileNames = compileClasspathFileNames(module)
      assert(fileNames.contains(fileName))
      for (check <- jarCheck; fileName <- fileNames)
        assert(check(fileName))
    }

    def publishLocalAndResolve(
        module: String,
        dependencyModules: Seq[String],
        scalaSuffix: String
    )(implicit tester: IntegrationTester): Seq[os.Path] = {
      val localIvyRepo = tester.workspacePath / "ivy2Local"
      for (moduleName <- module +: dependencyModules)
        tester.eval((s"$moduleName.publishLocal", "--localIvyRepo", localIvyRepo), check = true)

      coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of(
            "com.lihaoyi.mill-tests",
            module.replace('.', '-') + scalaSuffix,
            "0.1.0-SNAPSHOT"
          )
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
        dependencyModules: Seq[String],
        scalaSuffix: String
    )(implicit tester: IntegrationTester): Seq[os.Path] = {
      val localM2Repo = tester.workspacePath / "m2Local"
      for (moduleName <- module +: dependencyModules)
        tester.eval((s"$moduleName.publishM2Local", "--m2RepoPath", localM2Repo), check = true)

      coursierapi.Fetch.create()
        .addDependencies(
          coursierapi.Dependency.of(
            "com.lihaoyi.mill-tests",
            module.replace('.', '-') + scalaSuffix,
            "0.1.0-SNAPSHOT"
          )
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
        dependencyModules: Seq[String] = Nil,
        jarCheck: Option[String => Boolean] = None,
        ivy2LocalCheck: Boolean = true,
        scalaSuffix: String = ""
    )(implicit tester: IntegrationTester): Unit = {
      compileClasspathContains(module, jarName, jarCheck)

      if (ivy2LocalCheck) {
        val resolvedCp = publishLocalAndResolve(module, dependencyModules, scalaSuffix)
        assert(resolvedCp.map(_.last).contains(jarName))
        for (check <- jarCheck; fileName <- resolvedCp.map(_.last))
          assert(check(fileName))
      }

      val resolvedM2Cp = publishM2LocalAndResolve(module, dependencyModules, scalaSuffix)
      assert(resolvedM2Cp.map(_.last).contains(jarName))
      for (check <- jarCheck; fileName <- resolvedM2Cp.map(_.last))
        assert(check(fileName))
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

        test("subDependee") - integrationTest { implicit tester =>
          isInClassPath(
            "bom.placeholder.subDependee",
            expectedProtobufJarName,
            Seq("bom.placeholder", "bom.placeholder.dependee")
          )
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

        test("subDependee") - integrationTest { implicit tester =>
          isInClassPath(
            "bom.versionOverride.subDependee",
            expectedProtobufJarName,
            Seq("bom.versionOverride", "bom.versionOverride.dependee")
          )
        }
      }

      test("invalid") {
        test - integrationTest { tester =>
          import tester._

          val res = eval(
            ("show", "bom.invalid.exclude"),
            check = false
          )
          assert(
            res.err.contains(
              "Found parent or BOM dependencies with invalid parameters:"
            )
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

      test("subDependee") - integrationTest { implicit tester =>
        isInClassPath(
          "parent.subDependee",
          expectedCommonsCompressJarName,
          Seq("parent", "parent.dependee")
        )
      }

      test("scala") - integrationTest { implicit tester =>
        isInClassPath("parent.scala", expectedCommonsCompressJarName, scalaSuffix = "_2.13")
      }

      test("invalid") {
        test - integrationTest { tester =>
          import tester._

          val res = eval(
            ("show", "parent.invalid.exclude"),
            check = false
          )
          assert(
            res.err.contains(
              "Found parent or BOM dependencies with invalid parameters:"
            )
          )
        }
      }
    }

    test("depMgmt") {
      test("override") - integrationTest { implicit tester =>
        isInClassPath("depMgmt", expectedProtobufJarName)
      }

      test("transitiveOverride") - integrationTest { implicit tester =>
        isInClassPath("depMgmt.transitive", expectedProtobufJarName, Seq("depMgmt"))
      }

      test("extraExclude") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.extraExclude",
          "cask_2.13-0.9.4.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          }
        )
      }

      test("transitiveExtraExclude") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.extraExclude.transitive",
          "cask_2.13-0.9.4.jar",
          Seq("depMgmt.extraExclude"),
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          }
        )
      }

      test("exclude") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.exclude",
          "Java-WebSocket-1.5.2.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("transitiveExclude") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.exclude.transitive",
          "Java-WebSocket-1.5.2.jar",
          Seq("depMgmt.exclude"),
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("onlyExclude") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.onlyExclude",
          "Java-WebSocket-1.5.3.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("transitiveOnlyExclude") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.onlyExclude.transitive",
          "Java-WebSocket-1.5.3.jar",
          Seq("depMgmt.onlyExclude"),
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("invalid") {
        test - integrationTest { tester =>
          import tester._

          val res = eval(
            ("show", "depMgmt.invalid.transitive"),
            check = false
          )
          assert(
            res.err.contains(
              "Found dependency management entries with invalid values."
            )
          )
        }
      }

      test("placeholder") - integrationTest { implicit tester =>
        isInClassPath("depMgmt.placeholder", expectedProtobufJarName)
      }

      test("transitivePlaceholder") - integrationTest { implicit tester =>
        isInClassPath(
          "depMgmt.placeholder.transitive",
          expectedProtobufJarName,
          Seq("depMgmt.placeholder")
        )
      }
    }
  }
}
