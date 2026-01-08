package mill.javalib

import mill.*
import mill.api.Discover
import mill.javalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using

object ShadingModuleTests extends TestSuite {

  // Simple dependency for testing shading
  val gsonDep = Seq(mvn"com.google.code.gson:gson:2.10.1")
  val slf4jDep = Seq(mvn"org.slf4j:slf4j-api:2.0.9")

  trait TestJavaModule extends JavaModule {
    def jvmId = "11"
  }

  object ShadingTestModule extends TestRootModule {
    object core extends TestJavaModule with ShadingModule {
      override def shadedMvnDeps = gsonDep
      override def shadeRelocations = Seq(
        ("com.google.gson.**", "shaded.gson.@1")
      )
    }
    lazy val millDiscover = Discover[this.type]
  }

  object ShadingNoRelocationsModule extends TestRootModule {
    object core extends TestJavaModule with ShadingModule {
      override def shadedMvnDeps = gsonDep
      // No relocations - should still bundle deps (with warning)
    }
    lazy val millDiscover = Discover[this.type]
  }

  object NoShadingModule extends TestRootModule {
    object core extends TestJavaModule with ShadingModule {
      // No shaded deps - should behave like normal JavaModule
    }
    lazy val millDiscover = Discover[this.type]
  }

  object ShadingPublishTestModule extends TestRootModule {
    object core extends TestJavaModule with ShadingPublishModule {
      override def shadedMvnDeps = gsonDep
      override def mvnDeps = slf4jDep
      override def shadeRelocations = Seq(
        ("com.google.gson.**", "shaded.gson.@1")
      )
      override def publishVersion = "1.0.0"
      override def pomSettings = PomSettings(
        description = "Test",
        organization = "com.test",
        url = "https://test.com",
        licenses = Seq(License.MIT),
        versionControl = VersionControl.github("test", "test"),
        developers = Seq(Developer("test", "Test", "https://test.com"))
      )
    }
    lazy val millDiscover = Discover[this.type]
  }

  def jarEntries(jar: JarFile): Set[String] = {
    jar.entries().asScala.map(_.getName).toSet
  }

  def tests: Tests = Tests {

    test("shadedJar") {
      test("containsRelocatedClasses") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(result) = eval.apply(ShadingTestModule.core.shadedJar): @unchecked

        Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
          val entries = jarEntries(jarFile)

          // Original class should NOT be present
          assert(!entries.contains("com/google/gson/Gson.class"))
          // Relocated class SHOULD be present
          assert(entries.contains("shaded/gson/Gson.class"))
          assert(entries.contains("shaded/gson/GsonBuilder.class"))
        }
      }

      test("emptyWhenNoShadedDeps") - UnitTester(NoShadingModule, null).scoped { eval =>
        val Right(result) = eval.apply(NoShadingModule.core.shadedJar): @unchecked

        Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
          val entries = jarEntries(jarFile)
          // Empty JAR should only have manifest
          assert(entries.size <= 2) // META-INF/ and META-INF/MANIFEST.MF
        }
      }
    }

    test("shadedArtifacts") {
      test("containsTransitiveDeps") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(result) = eval.apply(ShadingTestModule.core.shadedArtifacts): @unchecked
        val artifacts = result.value

        // Should contain gson and potentially its transitives
        assert(artifacts.exists(a => a.group == "com.google.code.gson" && a.id == "gson"))
      }

      test("emptyWhenNoShadedDeps") - UnitTester(NoShadingModule, null).scoped { eval =>
        val Right(result) = eval.apply(NoShadingModule.core.shadedArtifacts): @unchecked
        val artifacts = result.value

        assert(artifacts.isEmpty)
      }
    }

    test("localClasspath") {
      test("includesShadedJar") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(shadedJarResult) = eval.apply(ShadingTestModule.core.shadedJar): @unchecked
        val Right(localCpResult) = eval.apply(ShadingTestModule.core.localClasspath): @unchecked

        val shadedJarPath = shadedJarResult.value.path
        val localCpPaths = localCpResult.value.map(_.path)

        assert(localCpPaths.contains(shadedJarPath))
      }
    }

    test("runClasspath") {
      test("excludesOriginalShadedDeps") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(resolvedDeps) = eval.apply(ShadingTestModule.core.resolvedShadedDeps): @unchecked
        val Right(runCpResult) = eval.apply(ShadingTestModule.core.runClasspath): @unchecked

        val shadedDepPaths = resolvedDeps.value.map(_.path).toSet
        val runCpPaths = runCpResult.value.map(_.path).toSet

        // Original shaded dep paths should NOT be in runClasspath
        val intersection = shadedDepPaths.intersect(runCpPaths)
        assert(intersection.isEmpty)
      }

      test("includesShadedJar") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(shadedJarResult) = eval.apply(ShadingTestModule.core.shadedJar): @unchecked
        val Right(runCpResult) = eval.apply(ShadingTestModule.core.runClasspath): @unchecked

        val shadedJarPath = shadedJarResult.value.path
        val runCpPaths = runCpResult.value.map(_.path)

        assert(runCpPaths.contains(shadedJarPath))
      }
    }

    test("jar") {
      test("includesShadedClasses") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(result) = eval.apply(ShadingTestModule.core.jar): @unchecked

        Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
          val entries = jarEntries(jarFile)

          // Relocated classes SHOULD be in the jar
          assert(entries.contains("shaded/gson/Gson.class"))
        }
      }
    }

    test("compileClasspath") {
      test("excludesOriginalShadedDeps") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(resolvedDeps) = eval.apply(ShadingTestModule.core.resolvedShadedDeps): @unchecked
        val Right(compileCpResult) = eval.apply(ShadingTestModule.core.compileClasspath): @unchecked

        val shadedDepPaths = resolvedDeps.value.map(_.path).toSet
        val compileCpPaths = compileCpResult.value.map(_.path).toSet

        // Original shaded dep paths should NOT be in compileClasspath
        val intersection = shadedDepPaths.intersect(compileCpPaths)
        assert(intersection.isEmpty)
      }

      test("includesShadedJar") - UnitTester(ShadingTestModule, null).scoped { eval =>
        val Right(shadedJarResult) = eval.apply(ShadingTestModule.core.shadedJar): @unchecked
        val Right(compileCpResult) = eval.apply(ShadingTestModule.core.compileClasspath): @unchecked

        val shadedJarPath = shadedJarResult.value.path
        val compileCpPaths = compileCpResult.value.map(_.path)

        assert(compileCpPaths.contains(shadedJarPath))
      }
    }

    test("noRelocations") {
      test("bundlesWithoutRelocating") - UnitTester(ShadingNoRelocationsModule, null).scoped {
        eval =>
          val Right(result) = eval.apply(ShadingNoRelocationsModule.core.shadedJar): @unchecked

          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
            val entries = jarEntries(jarFile)

            // Original class names should be present (no relocation)
            assert(entries.contains("com/google/gson/Gson.class"))
            // Relocated names should NOT be present
            assert(!entries.contains("shaded/gson/Gson.class"))
          }
      }
    }

    test("publishXmlDeps") {
      test("excludesShadedDepsFromPom") - UnitTester(ShadingPublishTestModule, null).scoped {
        eval =>
          val Right(result) = eval.apply(ShadingPublishTestModule.core.publishXmlDeps): @unchecked
          val deps = result.value

          // Shaded deps (gson) should NOT be in publishXmlDeps
          assert(!deps.exists(d =>
            d.artifact.group == "com.google.code.gson" && d.artifact.id == "gson"
          ))
          // Non-shaded deps (slf4j) SHOULD be present
          assert(deps.exists(d => d.artifact.group == "org.slf4j" && d.artifact.id == "slf4j-api"))
      }
    }
  }
}
