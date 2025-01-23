package mill
package scalalib

import mill.scalalib.publish._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

import scala.jdk.CollectionConverters._

object BomTests extends TestSuite {

  trait TestPublishModule extends PublishModule {
    def pomSettings = PomSettings(
      description = artifactName(),
      organization = "com.lihaoyi.mill-tests",
      url = "https://github.com/com-lihaoyi/mill",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("com-lihaoyi", "mill"),
      developers = Nil
    )
    def publishVersion = "0.1.0-SNAPSHOT"
  }

  object modules extends TestBaseModule {
    object bom extends Module {
      object placeholder extends JavaModule with TestPublishModule {
        // Empty version in ivyDeps should be filled with BOM
        def bomIvyDeps = Agg(
          ivy"com.google.cloud:libraries-bom:26.50.0"
        )
        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java"
        )

        object dependee extends JavaModule with TestPublishModule {
          // BOM details should be passed to dependees
          def moduleDeps = Seq(
            placeholder
          )
        }

        object subDependee extends JavaModule with TestPublishModule {
          // BOM details should be passed to dependees
          def moduleDeps = Seq(
            dependee
          )
        }

        object check extends JavaModule {
          // Empty version with no BOM - should fail
          def ivyDeps = Agg(
            ivy"com.google.protobuf:protobuf-java"
          )
        }
      }

      object versionOverride extends JavaModule with TestPublishModule {
        // protobuf-java is a dependency of scalapbc
        // The BOM overrides its version
        def bomIvyDeps = Agg(
          ivy"com.google.cloud:libraries-bom:26.50.0"
        )
        def ivyDeps = Agg(
          ivy"com.thesamet.scalapb:scalapbc_2.13:0.9.8"
        )

        object dependee extends JavaModule with TestPublishModule {
          // BOM stuff should be passed transitively to dependee modules
          def moduleDeps = Seq(
            versionOverride
          )
        }

        object subDependee extends JavaModule with TestPublishModule {
          // BOM stuff should be passed transitively to dependee modules
          def moduleDeps = Seq(
            dependee
          )
        }

        object check extends JavaModule {
          // No BOM - checking that the protobuf version is different than the one with the BOM
          def ivyDeps = Agg(
            ivy"com.thesamet.scalapb:scalapbc_2.13:0.9.8"
          )
        }
      }

      object invalid extends Module {
        object exclude extends JavaModule {
          // excludes aren't accepted alongside BOM coordinates
          def bomIvyDeps = Agg(
            ivy"com.google.cloud:libraries-bom:26.50.0".exclude(("foo", "thing"))
          )
        }
      }
    }

    object depMgmt extends JavaModule with TestPublishModule {
      // scalapbc depends on protobuf-java
      // depManagement should override protobuf-java version
      def ivyDeps = Agg(
        ivy"com.thesamet.scalapb:scalapbc_2.13:0.9.8"
      )
      def depManagement = Agg(
        ivy"com.google.protobuf:protobuf-java:4.28.3"
      )

      object transitive extends JavaModule with TestPublishModule {
        // depManagement stuff should be passed transitively to dependees
        def moduleDeps = Seq(depMgmt)
      }

      object extraExclude extends JavaModule with TestPublishModule {
        // Adding an exclude to an ivyDep from depManagement, while
        // the version in ivyDep is preserved
        def ivyDeps = Agg(
          ivy"com.lihaoyi:cask_2.13:0.9.5"
        )
        def depManagement = Agg(
          // The exclude should be automatically added to the dependency above
          // thanks to dependency management, but the version should be left
          // untouched
          ivy"com.lihaoyi:cask_2.13:0.9.3"
            .exclude(("org.slf4j", "slf4j-api"))
        )

        object transitive extends JavaModule with TestPublishModule {
          // depManagement stuff should be passed transitively to dependees
          def moduleDeps = Seq(extraExclude)
        }
      }

      object exclude extends JavaModule with TestPublishModule {
        // Adding an exclude to and overriding the version of a transitive dependency
        // from depManagement
        def ivyDeps = Agg(
          ivy"com.lihaoyi:cask_2.13:0.9.5"
        )
        def depManagement = Agg(
          ivy"org.java-websocket:Java-WebSocket:1.5.2"
            .exclude(("org.slf4j", "slf4j-api"))
        )

        object transitive extends JavaModule with TestPublishModule {
          // depManagement stuff should be passed transitively to dependees
          def moduleDeps = Seq(exclude)
        }
      }

      object onlyExclude extends JavaModule with TestPublishModule {
        def ivyDeps = Agg(
          ivy"com.lihaoyi:cask_2.13:0.9.5"
        )
        def depManagement = Agg(
          ivy"org.java-websocket:Java-WebSocket"
            .exclude(("org.slf4j", "slf4j-api"))
        )

        object transitive extends JavaModule with TestPublishModule {
          def moduleDeps = Seq(onlyExclude)
        }
      }

      object invalid extends Module {
        object transitive extends JavaModule {
          def depManagement = {
            val dep = ivy"org.java-websocket:Java-WebSocket:1.5.3"
            Agg(
              dep.copy(
                dep = dep.dep.withTransitive(false)
              )
            )
          }
        }
      }

      object placeholder extends JavaModule with TestPublishModule {
        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java"
        )
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:4.28.3"
        )

        object transitive extends JavaModule with TestPublishModule {
          def moduleDeps = Seq(placeholder)
        }
      }
    }

    object precedence extends Module {
      object higher extends JavaModule with TestPublishModule {
        def bomIvyDeps = Agg(
          ivy"com.google.protobuf:protobuf-bom:4.28.1"
        )
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:4.28.3"
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java"
        )
      }

      object higherTransitive extends JavaModule with TestPublishModule {
        def bomIvyDeps = Agg(
          ivy"com.google.protobuf:protobuf-bom:4.28.1"
        )
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:4.28.3"
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java-util"
        )
      }

      object lower extends JavaModule with TestPublishModule {
        def bomIvyDeps = Agg(
          ivy"com.google.protobuf:protobuf-bom:4.28.1"
        )
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:3.22.0"
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java"
        )
      }

      object lowerTransitive extends JavaModule with TestPublishModule {
        def bomIvyDeps = Agg(
          ivy"com.google.protobuf:protobuf-bom:4.28.1"
        )
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:3.22.0"
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java-util"
        )
      }

      object addExclude extends JavaModule with TestPublishModule {
        def bomIvyDeps = Agg(
          ivy"com.google.protobuf:protobuf-bom:4.28.3"
        )
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java-util"
            .exclude(("com.google.protobuf", "protobuf-java"))
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java-util"
        )

        object transitive extends JavaModule with TestPublishModule {
          def moduleDeps = Seq(addExclude)
        }
      }

      object firstInDepMgmt extends JavaModule with TestPublishModule {
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:3.22.0",
          ivy"com.google.protobuf:protobuf-java:4.28.3"
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java"
        )

        object transitive extends JavaModule with TestPublishModule {
          def moduleDeps = Seq(firstInDepMgmt)
        }
      }

      object firstInDepMgmtTransitively extends JavaModule with TestPublishModule {
        def depManagement = Agg(
          ivy"com.google.protobuf:protobuf-java:3.22.0",
          ivy"com.google.protobuf:protobuf-java:4.28.3"
        )

        def ivyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java-util:4.28.3"
        )

        object transitive extends JavaModule with TestPublishModule {
          def moduleDeps = Seq(firstInDepMgmtTransitively)
        }
      }
    }

    object bomScope extends Module {
      object provided extends JavaModule with TestPublishModule {
        // This BOM has a versions for protobuf-java-util marked as provided,
        // and one for scala-parallel-collections_2.13 in the default scope.
        // Both should be taken into account here.
        def bomIvyDeps = Agg(
          ivy"org.apache.spark:spark-parent_2.13:3.5.3"
        )
        def compileIvyDeps = Agg(
          ivy"com.google.protobuf:protobuf-java-util",
          ivy"org.scala-lang.modules:scala-parallel-collections_2.13"
        )

        object leak extends JavaModule with TestPublishModule {
          // Same as above, except the dependencies are in the
          // default scope for us here, so the protobuf-java-util version
          // shouldn't be read, as it's in provided scope in the BOM.
          def bomIvyDeps = Agg(
            ivy"org.apache.spark:spark-parent_2.13:3.5.3"
          )
          def ivyDeps = Agg(
            ivy"com.google.protobuf:protobuf-java-util",
            ivy"org.scala-lang.modules:scala-parallel-collections_2.13"
          )
        }
      }

      object runtimeScope extends JavaModule with TestPublishModule {
        // BOM has a version for org.mvnpm.at.hpcc-js:wasm marked as runtime.
        // This version should be taken into account in runtime deps here.
        def bomIvyDeps = Agg(
          ivy"io.quarkus:quarkus-bom:3.15.1"
        )
        def runIvyDeps = Agg(
          ivy"org.mvnpm.at.hpcc-js:wasm"
        )
      }

      object runtimeScopeLeak extends JavaModule with TestPublishModule {
        // BOM has a version for org.mvnpm.at.hpcc-js:wasm marked as runtime.
        // This version shouldn't be taken into account in main deps here.
        def bomIvyDeps = Agg(
          ivy"io.quarkus:quarkus-bom:3.15.1"
        )
        def ivyDeps = Agg(
          ivy"org.mvnpm.at.hpcc-js:wasm"
        )
      }

      object testScope extends JavaModule with TestPublishModule {
        // BOM has a version for scalatest_2.13 marked as test scope.
        // This version should be taken into account in test modules here.
        def bomIvyDeps = Agg(
          ivy"org.apache.spark:spark-parent_2.13:3.5.3"
        )
        object test extends JavaTests {
          def testFramework = "com.novocode.junit.JUnitFramework"
          def ivyDeps = Agg(
            ivy"com.novocode:junit-interface:0.11",
            ivy"org.scalatest:scalatest_2.13"
          )
        }
      }

      object testScopeLeak extends JavaModule with TestPublishModule {
        // BOM has a version for scalatest_2.13 marked as test scope.
        // This version shouldn't be taken into account in main module here.
        def bomIvyDeps = Agg(
          ivy"org.apache.spark:spark-parent_2.13:3.5.3"
        )
        def ivyDeps = Agg(
          ivy"org.scalatest:scalatest_2.13"
        )
      }
    }

    object depMgmtScope extends Module {
      object provided extends JavaModule with TestPublishModule {
        // Version in depManagement should be used in compileIvyDeps
        def depManagement = Agg(
          ivy"org.scala-lang.modules:scala-parallel-collections_2.13:1.0.4"
        )
        def compileIvyDeps = Agg(
          ivy"org.scala-lang.modules:scala-parallel-collections_2.13"
        )
      }

      object runtimeScope extends JavaModule with TestPublishModule {
        // Dep mgmt has a version for org.mvnpm.at.hpcc-js:wasm
        // This version should be taken into account in runtime deps here.
        def depManagement = Agg(
          ivy"org.mvnpm.at.hpcc-js:wasm:2.15.3"
        )
        def runIvyDeps = Agg(
          ivy"org.mvnpm.at.hpcc-js:wasm"
        )
      }

      object testScope extends JavaModule with TestPublishModule {
        // Dep mgmt in main module has a version for scalatest_2.13.
        // This version should be taken into account in test modules here.
        def depManagement = Agg(
          ivy"org.scalatest:scalatest_2.13:3.2.16"
        )
        object test extends JavaTests {
          def testFramework = "com.novocode.junit.JUnitFramework"
          def ivyDeps = Agg(
            ivy"com.novocode:junit-interface:0.11",
            ivy"org.scalatest:scalatest_2.13"
          )
        }
      }
    }

    object bomOnModuleDependency extends JavaModule with TestPublishModule {
      def ivyDeps = Agg(
        ivy"com.google.protobuf:protobuf-java:3.23.4"
      )

      object dependee extends JavaModule with TestPublishModule {
        def bomIvyDeps = Agg(
          ivy"com.google.cloud:libraries-bom:26.50.0"
        )
        def moduleDeps = Seq(bomOnModuleDependency)
      }
    }
  }

  def expectedProtobufJavaVersion = "4.28.3"
  def expectedCommonsCompressVersion = "1.23.0"

  def expectedProtobufJarName = s"protobuf-java-$expectedProtobufJavaVersion.jar"
  def expectedCommonsCompressJarName = s"commons-compress-$expectedCommonsCompressVersion.jar"

  def compileClasspathFileNames(module: JavaModule)(implicit
      eval: UnitTester
  ): Seq[String] =
    eval(module.compileClasspath).toTry.get.value
      .toSeq.map(_.path.last)

  def compileClasspathContains(
      module: JavaModule,
      fileName: String,
      jarCheck: Option[String => Boolean] = None
  )(implicit
      eval: UnitTester
  ) = {
    val fileNames = compileClasspathFileNames(module)
    assert(fileNames.contains(fileName))
    for (check <- jarCheck; fileName <- fileNames)
      assert(check(fileName))
  }

  def runtimeClasspathFileNames(module: JavaModule)(implicit
      eval: UnitTester
  ): Seq[String] =
    eval(module.runClasspath).toTry.get.value
      .toSeq.map(_.path.last)

  def runtimeClasspathContains(
      module: JavaModule,
      fileName: String,
      jarCheck: Option[String => Boolean] = None
  )(implicit
      eval: UnitTester
  ) = {
    val fileNames = runtimeClasspathFileNames(module)
    assert(fileNames.contains(fileName))
    for (check <- jarCheck; fileName <- fileNames)
      assert(check(fileName))
  }

  def publishLocalAndResolve(
      module: PublishModule,
      dependencyModules: Seq[PublishModule],
      scalaSuffix: String,
      fetchRuntime: Boolean
  )(implicit eval: UnitTester): Seq[os.Path] = {
    val localIvyRepo = eval.evaluator.workspace / "ivy2Local"
    eval(module.publishLocal(localIvyRepo.toString)).toTry.get
    for (dependencyModule <- dependencyModules)
      eval(dependencyModule.publishLocal(localIvyRepo.toString)).toTry.get

    val moduleString = eval(module.artifactName).toTry.get.value

    coursierapi.Fetch.create()
      .addDependencies(
        coursierapi.Dependency.of(
          "com.lihaoyi.mill-tests",
          moduleString.replace('.', '-') + scalaSuffix,
          "0.1.0-SNAPSHOT"
        )
      )
      .addRepositories(
        coursierapi.IvyRepository.of(localIvyRepo.toNIO.toUri.toASCIIString + "[defaultPattern]")
      )
      .withResolutionParams {
        val defaultParams = coursierapi.ResolutionParams.create()
        defaultParams.withDefaultConfiguration(
          if (fetchRuntime) "runtime"
          else defaultParams.getDefaultConfiguration
        )
      }
      .fetch()
      .asScala
      .map(os.Path(_))
      .toVector
  }

  def publishM2LocalAndResolve(
      module: PublishModule,
      dependencyModules: Seq[PublishModule],
      scalaSuffix: String
  )(implicit eval: UnitTester): Seq[os.Path] = {
    val localM2Repo = eval.evaluator.workspace / "m2Local"
    eval(module.publishM2Local(localM2Repo.toString)).toTry.get
    for (dependencyModule <- dependencyModules)
      eval(dependencyModule.publishM2Local(localM2Repo.toString)).toTry.get

    val moduleString = eval(module.artifactName).toTry.get.value

    coursierapi.Fetch.create()
      .addDependencies(
        coursierapi.Dependency.of(
          "com.lihaoyi.mill-tests",
          moduleString.replace('.', '-') + scalaSuffix,
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
      module: JavaModule with PublishModule,
      jarName: String,
      dependencyModules: Seq[PublishModule] = Nil,
      jarCheck: Option[String => Boolean] = None,
      ivy2LocalCheck: Boolean = true,
      scalaSuffix: String = "",
      runtimeOnly: Boolean = false
  )(implicit eval: UnitTester): Unit = {
    if (runtimeOnly)
      runtimeClasspathContains(module, jarName, jarCheck)
    else
      compileClasspathContains(module, jarName, jarCheck)

    if (ivy2LocalCheck) {
      val resolvedCp =
        publishLocalAndResolve(module, dependencyModules, scalaSuffix, fetchRuntime = runtimeOnly)
      assert(resolvedCp.map(_.last).contains(jarName))
      for (check <- jarCheck; fileName <- resolvedCp.map(_.last))
        assert(check(fileName))
    }

    val resolvedM2Cp = publishM2LocalAndResolve(module, dependencyModules, scalaSuffix)
    assert(resolvedM2Cp.map(_.last).contains(jarName))
    for (check <- jarCheck; fileName <- resolvedM2Cp.map(_.last))
      assert(check(fileName))
  }

  def tests = Tests {

    test("bom") {
      test("placeholder") {
        test("check") - UnitTester(modules, null).scoped { eval =>
          val res = eval(modules.bom.placeholder.check.compileClasspath)
          assert(
            res.left.exists(_.toString.contains(
              "not found: https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java//protobuf-java-.pom"
            ))
          )
        }

        test("simple") - UnitTester(modules, null).scoped { implicit eval =>
          isInClassPath(modules.bom.placeholder, expectedProtobufJarName)
        }

        test("dependee") - UnitTester(modules, null).scoped { implicit eval =>
          isInClassPath(
            modules.bom.placeholder.dependee,
            expectedProtobufJarName,
            Seq(modules.bom.placeholder)
          )
        }

        test("subDependee") - UnitTester(modules, null).scoped { implicit eval =>
          isInClassPath(
            modules.bom.placeholder.subDependee,
            expectedProtobufJarName,
            Seq(modules.bom.placeholder, modules.bom.placeholder.dependee)
          )
        }
      }

      test("versionOverride") {
        test("check") - UnitTester(modules, null).scoped { implicit eval =>
          val fileNames = compileClasspathFileNames(modules.bom.versionOverride.check)
          assert(fileNames.exists(v => v.startsWith("protobuf-java-") && v.endsWith(".jar")))
          assert(!fileNames.contains(expectedProtobufJarName))
        }

        test("simple") - UnitTester(modules, null).scoped { implicit eval =>
          isInClassPath(modules.bom.versionOverride, expectedProtobufJarName)
        }

        test("dependee") - UnitTester(modules, null).scoped { implicit eval =>
          isInClassPath(
            modules.bom.versionOverride.dependee,
            expectedProtobufJarName,
            Seq(modules.bom.versionOverride)
          )
        }

        test("subDependee") - UnitTester(modules, null).scoped { implicit eval =>
          isInClassPath(
            modules.bom.versionOverride.subDependee,
            expectedProtobufJarName,
            Seq(modules.bom.versionOverride, modules.bom.versionOverride.dependee)
          )
        }
      }

      test("invalid") {
        test - UnitTester(modules, null).scoped { eval =>
          val res = eval(modules.bom.invalid.exclude.compileClasspath)
          assert(
            res.left.exists(_.toString.contains(
              "Found BOM dependencies with invalid parameters:"
            ))
          )
        }
      }
    }

    test("depMgmt") {
      test("override") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(modules.depMgmt, expectedProtobufJarName)
      }

      test("transitiveOverride") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(modules.depMgmt.transitive, expectedProtobufJarName, Seq(modules.depMgmt))
      }

      test("extraExclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.extraExclude,
          "cask_2.13-0.9.5.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          }
        )
      }

      test("transitiveExtraExclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.extraExclude.transitive,
          "cask_2.13-0.9.5.jar",
          Seq(modules.depMgmt.extraExclude),
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // we could make that work
        )
      }

      test("exclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.exclude,
          "Java-WebSocket-1.5.2.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("transitiveExclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.exclude.transitive,
          "Java-WebSocket-1.5.2.jar",
          Seq(modules.depMgmt.exclude),
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("onlyExclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.onlyExclude,
          "Java-WebSocket-1.5.3.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("transitiveOnlyExclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.onlyExclude.transitive,
          "Java-WebSocket-1.5.3.jar",
          Seq(modules.depMgmt.onlyExclude),
          jarCheck = Some { jarName =>
            !jarName.startsWith("slf4j-api-")
          },
          ivy2LocalCheck = false // dep mgmt excludes can't be put in ivy.xml
        )
      }

      test("invalid") {
        test - UnitTester(modules, null).scoped { eval =>
          val res = eval(modules.depMgmt.invalid.transitive.compileClasspath)
          assert(
            res.left.exists(_.toString.contains(
              "Found dependency management entries with invalid values."
            ))
          )
        }
      }

      test("placeholder") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(modules.depMgmt.placeholder, expectedProtobufJarName)
      }

      test("transitivePlaceholder") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmt.placeholder.transitive,
          expectedProtobufJarName,
          Seq(modules.depMgmt.placeholder)
        )
      }
    }

    test("precedence") {
      test("higher") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.higher,
          "protobuf-java-4.28.3.jar"
        )
      }
      test("higherTransitive") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.higherTransitive,
          "protobuf-java-4.28.3.jar"
        )
      }
      test("lower") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.lower,
          "protobuf-java-3.22.0.jar"
        )
      }
      test("lowerTransitive") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.lowerTransitive,
          "protobuf-java-3.22.0.jar"
        )
      }
      test("addExclude") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.addExclude,
          "protobuf-java-util-4.28.3.jar",
          jarCheck = Some { jarName =>
            !jarName.startsWith("protobuf-java-") ||
            jarName.startsWith("protobuf-java-util")
          }
        )
      }
      test("addExcludeTransitive") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.addExclude.transitive,
          "protobuf-java-util-4.28.3.jar",
          Seq(modules.precedence.addExclude),
          jarCheck = Some { jarName =>
            !jarName.startsWith("protobuf-java-") ||
            jarName.startsWith("protobuf-java-util")
          }
        )
      }
      test("firstInDepMgmt") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.firstInDepMgmt,
          "protobuf-java-3.22.0.jar"
        )
      }
      test("firstInDepMgmtTransitive") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.firstInDepMgmt.transitive,
          "protobuf-java-3.22.0.jar",
          Seq(modules.precedence.firstInDepMgmt)
        )
      }
      test("firstInDepMgmtTransitively") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.precedence.firstInDepMgmtTransitively,
          "protobuf-java-3.22.0.jar"
        )
      }
      test("firstInDepMgmtTransitivelyTransitive") - UnitTester(modules, null).scoped {
        implicit eval =>
          isInClassPath(
            modules.precedence.firstInDepMgmtTransitively.transitive,
            "protobuf-java-3.22.0.jar",
            Seq(modules.precedence.firstInDepMgmtTransitively)
          )
      }
    }

    test("bomScope") {
      test("provided") - UnitTester(modules, null).scoped { implicit eval =>
        // test about provided scope, nothing to see in published stuff
        compileClasspathContains(
          modules.bomScope.provided,
          "protobuf-java-3.23.4.jar"
        )
      }
      test("providedFromBomRuntimeScope") - UnitTester(modules, null).scoped { implicit eval =>
        // test about provided scope, nothing to see in published stuff
        compileClasspathContains(
          modules.bomScope.provided,
          "scala-parallel-collections_2.13-1.0.4.jar"
        )
      }
      test("leakProvidedInCompile") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.bomScope.provided.leak,
          "scala-parallel-collections_2.13-1.0.4.jar"
        )
      }

      test("test") - UnitTester(modules, null).scoped { implicit eval =>
        compileClasspathContains(
          modules.bomScope.testScope.test,
          "scalatest_2.13-3.2.16.jar"
        )
      }
      test("testCheck") - UnitTester(modules, null).scoped { implicit eval =>
        compileClasspathContains(
          modules.bomScope.testScopeLeak,
          "scalatest_2.13-3.2.16.jar"
        )
      }

      test("runtime") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.bomScope.runtimeScope,
          "wasm-2.15.3.jar",
          runtimeOnly = true
        )
      }
    }

    test("bomOnModuleDependency") {
      test("check") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.bomOnModuleDependency,
          "protobuf-java-3.23.4.jar"
        )
      }
      test("dependee") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.bomOnModuleDependency.dependee,
          expectedProtobufJarName,
          Seq(modules.bomOnModuleDependency)
        )
      }
    }

    test("depMgmtScope") {
      test("depManagementInProvided") - UnitTester(modules, null).scoped { implicit eval =>
        // test about provided scope, nothing to see in published stuff
        compileClasspathContains(
          modules.depMgmtScope.provided,
          "scala-parallel-collections_2.13-1.0.4.jar"
        )
      }

      test("test") - UnitTester(modules, null).scoped { implicit eval =>
        compileClasspathContains(
          modules.depMgmtScope.testScope.test,
          "scalatest_2.13-3.2.16.jar"
        )
      }

      test("runtime") - UnitTester(modules, null).scoped { implicit eval =>
        isInClassPath(
          modules.depMgmtScope.runtimeScope,
          "wasm-2.15.3.jar",
          runtimeOnly = true
        )
      }
    }
  }
}
