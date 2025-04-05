package mill.scalalib

import mill.{T, Task}
import mill.api.{PathRef, Result}
import mill.api.ExecResult
import mill.define.{Discover, Evaluator}
import mill.scalalib.publish.{
  Developer,
  License,
  PackagingType,
  PomSettings,
  VersionControl,
  VersionScheme
}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*
import mill.util.TokenReaders._
import java.io.PrintStream
import scala.jdk.CollectionConverters.*
import scala.xml.NodeSeq

object PublishModuleTests extends TestSuite {

  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

  trait HelloScalaModule extends ScalaModule {
    def scalaVersion = scala212Version
    override def semanticDbVersion: T[String] = Task {
      // The latest semanticDB release for Scala 2.12.6
      "4.1.9"
    }
  }

  object HelloWorldWithPublish extends TestBaseModule {
    object core extends HelloScalaModule with PublishModule {
      override def artifactName = "hello-world"
      override def publishVersion = "0.0.1"
      override def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
      override def versionScheme = Some(VersionScheme.EarlySemVer)

      def checkSonatypeCreds(sonatypeCreds: String) = Task.Command {
        PublishModule.checkSonatypeCreds(sonatypeCreds)()
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  object PomOnly extends TestBaseModule {
    object core extends JavaModule with PublishModule {
      override def pomPackagingType: String = PackagingType.Pom
      override def artifactName = "pom-only"
      override def publishVersion = "0.0.1"
      override def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "pom-only artifacts ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lefou", "Tobias Roeser", "https://github.com/lefou"))
      )
      override def versionScheme = Some(VersionScheme.EarlySemVer)
      override def ivyDeps = Seq(
        ivy"org.slf4j:slf4j-api:2.0.7"
      )
      // ensure, these target won't be called
      override def jar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
      override def docJar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
      override def sourceJar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
    }

    lazy val millDiscover = Discover[this.type]
  }

  object compileAndRuntimeStuff extends TestBaseModule {
    def organization = "com.lihaoyi.pubmodtests"
    def version = "0.1.0-SNAPSHOT"
    trait TestPublishModule extends PublishModule {
      def publishVersion = version
      def pomSettings = PomSettings(
        organization = organization,
        description = "test thing",
        url = "https://github.com/com-lihaoyi/mill",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("com-lihaoyi", "mill"),
        developers = Nil
      )
    }
    object main extends JavaModule with TestPublishModule {
      def ivyDeps = Seq(
        ivy"org.slf4j:slf4j-api:2.0.15"
      )
      def runIvyDeps = Seq(
        ivy"ch.qos.logback:logback-classic:1.5.12"
      )
    }

    object transitive extends JavaModule with TestPublishModule {
      def moduleDeps = Seq(main)
    }

    object runtimeTransitive extends JavaModule with TestPublishModule {
      def runModuleDeps = Seq(main)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish"

  def tests: Tests = Tests {
    test("pom") {
      test("should include scala-library dependency") - UnitTester(
        HelloWorldWithPublish,
        resourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithPublish.core.pom): @unchecked

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.value.path.toString)
        val scalaLibrary = pomXml \ "dependencies" \ "dependency"
        assert(
          (pomXml \ "packaging").text == PackagingType.Jar,
          (scalaLibrary \ "artifactId").text == "scala-library",
          (scalaLibrary \ "groupId").text == "org.scala-lang"
        )
      }
      test("versionScheme") - UnitTester(HelloWorldWithPublish, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithPublish.core.pom): @unchecked

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.value.path.toString)
        val versionScheme = pomXml \ "properties" \ "info.versionScheme"
        assert(versionScheme.text == "early-semver")
      }
    }

    test("publish") {
      test(
        "should retrieve credentials from environment variables if direct argument is empty"
      ) {
        UnitTester(
          HelloWorldWithPublish,
          sourceRoot = resourcePath,
          env = Evaluator.defaultEnv ++ Seq(
            "SONATYPE_USERNAME" -> "user",
            "SONATYPE_PASSWORD" -> "password"
          )
        ).scoped { eval =>
          val Right(result) =
            eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds("")): @unchecked

          assert(
            result.value == "user:password",
            result.evalCount > 0
          )
        }
      }
      test(
        "should prefer direct argument as credentials over environment variables"
      ) {
        UnitTester(
          HelloWorldWithPublish,
          sourceRoot = resourcePath,
          env = Evaluator.defaultEnv ++ Seq(
            "SONATYPE_USERNAME" -> "user",
            "SONATYPE_PASSWORD" -> "password"
          )
        ).scoped { eval =>
          val directValue = "direct:value"
          val Right(result) =
            eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(directValue)): @unchecked

          assert(
            result.value == directValue,
            result.evalCount > 0
          )
        }
      }
      test(
        "should throw exception if neither environment variables or direct argument were not passed"
      ) - UnitTester(HelloWorldWithPublish, resourcePath).scoped { eval =>
        val Left(ExecResult.Failure(msg)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds("")): @unchecked

        assert(
          msg.contains(
            "Consider using MILL_SONATYPE_USERNAME/MILL_SONATYPE_PASSWORD environment variables"
          )
        )
      }
    }

    test("ivy") {
      test("should include scala-library dependency") - UnitTester(
        HelloWorldWithPublish,
        resourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithPublish.core.ivy): @unchecked

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        val ivyXml = scala.xml.XML.loadFile(result.value.path.toString)
        val deps: NodeSeq = (ivyXml \ "dependencies" \ "dependency")
        assert(deps.exists(n =>
          (n \ "@conf").text == "compile->compile;runtime->runtime" &&
            (n \ "@name").text == "scala-library" && (n \ "@org").text == "org.scala-lang"
        ))
      }
    }

    test("pom-packaging-type") - {
      test("pom") - UnitTester(PomOnly, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(PomOnly.core.pom): @unchecked
//
//        assert(
//          os.exists(result.path),
//          evalCount > 0
//        )
//
//        val pomXml = scala.xml.XML.loadFile(result.path.toString)
//        val scalaLibrary = pomXml \ "dependencies" \ "dependency"
//        assert(
//          (pomXml \ "packaging").text == PackagingType.Pom,
//          (scalaLibrary \ "artifactId").text == "slf4j-api",
//          (scalaLibrary \ "groupId").text == "org.slf4j"
//        )
      }
    }

    test("scopes") - UnitTester(compileAndRuntimeStuff, null).scoped { eval =>
      def assertClassPathContains(cp: Seq[os.Path], fileName: String) =
        assert(cp.map(_.last).contains(fileName))
      def assertClassPathDoesntContain(cp: Seq[os.Path], prefix: String) =
        assert(cp.map(_.last).forall(!_.startsWith(prefix)))

      def nothingClassPathCheck(cp: Seq[os.Path]): Unit = {
        assertClassPathDoesntContain(cp, "slf4j")
        assertClassPathDoesntContain(cp, "logback")
      }
      def compileClassPathCheck(cp: Seq[os.Path]): Unit = {
        assertClassPathContains(cp, "slf4j-api-2.0.15.jar")
        assertClassPathDoesntContain(cp, "logback")
      }
      def runtimeClassPathCheck(cp: Seq[os.Path]): Unit = {
        assertClassPathContains(cp, "slf4j-api-2.0.15.jar")
        assertClassPathContains(cp, "logback-classic-1.5.12.jar")
      }

      val compileCp =
        eval(compileAndRuntimeStuff.main.compileClasspath).right.get.value.toSeq.map(_.path)
      val runtimeCp =
        eval(compileAndRuntimeStuff.main.runClasspath).right.get.value.toSeq.map(_.path)

      compileClassPathCheck(compileCp)
      runtimeClassPathCheck(runtimeCp)

      val ivy2Repo = eval.evaluator.workspace / "ivy2Local"
      val m2Repo = eval.evaluator.workspace / "m2Local"

      eval(compileAndRuntimeStuff.main.publishLocal(ivy2Repo.toString)).right.get
      eval(compileAndRuntimeStuff.transitive.publishLocal(ivy2Repo.toString)).right.get
      eval(compileAndRuntimeStuff.runtimeTransitive.publishLocal(ivy2Repo.toString)).right.get
      eval(compileAndRuntimeStuff.main.publishM2Local(m2Repo.toString)).right.get
      eval(compileAndRuntimeStuff.transitive.publishM2Local(m2Repo.toString)).right.get
      eval(compileAndRuntimeStuff.runtimeTransitive.publishM2Local(m2Repo.toString)).right.get

      def localRepoCp(localRepo: coursierapi.Repository, moduleName: String, config: String) = {
        val dep = coursierapi.Dependency.of("com.lihaoyi.pubmodtests", moduleName, "0.1.0-SNAPSHOT")
        coursierapi.Fetch.create()
          .addDependencies(dep)
          .addRepositories(localRepo)
          .withResolutionParams(
            coursierapi.ResolutionParams.create()
              .withDefaultConfiguration(if (config.isEmpty) null else config)
          )
          .fetch()
          .asScala
          .map(os.Path(_))
          .toSeq
      }
      def ivy2Cp(moduleName: String, config: String) =
        localRepoCp(
          coursierapi.IvyRepository.of(ivy2Repo.toNIO.toUri.toASCIIString + "[defaultPattern]"),
          moduleName,
          config
        )
      def m2Cp(moduleName: String, config: String) =
        localRepoCp(
          coursierapi.MavenRepository.of(m2Repo.toNIO.toUri.toASCIIString),
          moduleName,
          config
        )

      val ivy2CompileCp = ivy2Cp("main", "compile")
      val ivy2RunCp = ivy2Cp("main", "runtime")
      val m2CompileCp = m2Cp("main", "compile")
      val m2RunCp = m2Cp("main", "runtime")

      compileClassPathCheck(ivy2CompileCp)
      compileClassPathCheck(m2CompileCp)
      runtimeClassPathCheck(ivy2RunCp)
      runtimeClassPathCheck(m2RunCp)

      val ivy2TransitiveCompileCp = ivy2Cp("transitive", "compile")
      val ivy2TransitiveRunCp = ivy2Cp("transitive", "runtime")
      val m2TransitiveCompileCp = m2Cp("transitive", "compile")
      val m2TransitiveRunCp = m2Cp("transitive", "runtime")

      compileClassPathCheck(ivy2TransitiveCompileCp)
      compileClassPathCheck(m2TransitiveCompileCp)
      runtimeClassPathCheck(ivy2TransitiveRunCp)
      runtimeClassPathCheck(m2TransitiveRunCp)

      val ivy2RuntimeTransitiveCompileCp = ivy2Cp("runtimeTransitive", "compile")
      val ivy2RuntimeTransitiveRunCp = ivy2Cp("runtimeTransitive", "runtime")
      val m2RuntimeTransitiveCompileCp = m2Cp("runtimeTransitive", "compile")
      val m2RuntimeTransitiveRunCp = m2Cp("runtimeTransitive", "runtime")

      // runtime dependency on the main module - doesn't pull anything from it
      // at compile time, hence the nothingClassPathCheck-s
      nothingClassPathCheck(ivy2RuntimeTransitiveCompileCp)
      nothingClassPathCheck(m2RuntimeTransitiveCompileCp)
      runtimeClassPathCheck(ivy2RuntimeTransitiveRunCp)
      runtimeClassPathCheck(m2RuntimeTransitiveRunCp)
    }

    test("docSourcesArgs") - UnitTester(compileAndRuntimeStuff, null).scoped { eval =>
      val ivy2Repo = eval.evaluator.workspace / "ivy2Local"
      val moduleName = "main"
      val subDir =
        os.sub / compileAndRuntimeStuff.organization / moduleName / compileAndRuntimeStuff.version
      def repoHasIvyXml(): Boolean =
        os.isFile(ivy2Repo / subDir / "ivys/ivy.xml")
      def repoHasJar(): Boolean =
        os.isFile(ivy2Repo / subDir / "jars" / s"$moduleName.jar")
      def repoHasSourcesJar(): Boolean =
        os.isFile(ivy2Repo / subDir / "srcs" / s"$moduleName-sources.jar")
      def repoHasDocJar(): Boolean =
        os.isFile(ivy2Repo / subDir / "docs" / s"$moduleName-javadoc.jar")
      def clearRepo(): Unit =
        os.remove.all(ivy2Repo)

      eval(compileAndRuntimeStuff.main.publishLocal(ivy2Repo.toString)).right.get
      assert(repoHasIvyXml())
      assert(repoHasJar())
      assert(repoHasSourcesJar())
      assert(repoHasDocJar())

      clearRepo()

      eval(compileAndRuntimeStuff.main.publishLocal(ivy2Repo.toString, doc = false)).right.get
      assert(repoHasIvyXml())
      assert(repoHasJar())
      assert(repoHasSourcesJar())
      assert(!repoHasDocJar())

      clearRepo()

      eval(compileAndRuntimeStuff.main.publishLocal(
        ivy2Repo.toString,
        doc = false,
        sources = false
      )).right.get
      assert(repoHasIvyXml())
      assert(repoHasJar())
      assert(!repoHasSourcesJar())
      assert(!repoHasDocJar())
    }

    test("transitive") - UnitTester(compileAndRuntimeStuff, null).scoped { eval =>
      val ivy2Repo = eval.evaluator.workspace / "ivy2Local"
      val mainModuleName = "main"
      val transitiveModuleName = "transitive"
      val runtimeTransitiveModuleName = "runtimeTransitive"
      def subDir(moduleName: String) =
        os.sub / compileAndRuntimeStuff.organization / moduleName / compileAndRuntimeStuff.version
      def repoHasIvyXml(moduleName: String): Boolean =
        os.isFile(ivy2Repo / subDir(moduleName) / "ivys/ivy.xml")
      def repoHasJar(moduleName: String): Boolean =
        os.isFile(ivy2Repo / subDir(moduleName) / "jars" / s"$moduleName.jar")
      def repoHasSourcesJar(moduleName: String): Boolean =
        os.isFile(ivy2Repo / subDir(moduleName) / "srcs" / s"$moduleName-sources.jar")
      def repoHasDocJar(moduleName: String): Boolean =
        os.isFile(ivy2Repo / subDir(moduleName) / "docs" / s"$moduleName-javadoc.jar")
      def clearRepo(): Unit =
        os.remove.all(ivy2Repo)

      eval(compileAndRuntimeStuff.transitive.publishLocal(ivy2Repo.toString)).right.get
      assert(!repoHasIvyXml(mainModuleName))
      assert(!repoHasJar(mainModuleName))
      assert(!repoHasSourcesJar(mainModuleName))
      assert(!repoHasDocJar(mainModuleName))
      assert(repoHasIvyXml(transitiveModuleName))
      assert(repoHasJar(transitiveModuleName))
      assert(repoHasSourcesJar(transitiveModuleName))
      assert(repoHasDocJar(transitiveModuleName))

      clearRepo()

      eval(compileAndRuntimeStuff.transitive.publishLocal(
        ivy2Repo.toString,
        transitive = true
      )).right.get
      assert(repoHasIvyXml(mainModuleName))
      assert(repoHasJar(mainModuleName))
      assert(repoHasSourcesJar(mainModuleName))
      assert(repoHasDocJar(mainModuleName))
      assert(repoHasIvyXml(transitiveModuleName))
      assert(repoHasJar(transitiveModuleName))
      assert(repoHasSourcesJar(transitiveModuleName))
      assert(repoHasDocJar(transitiveModuleName))

      clearRepo()

      eval(compileAndRuntimeStuff.transitive.publishLocal(ivy2Repo.toString, doc = false)).right.get
      assert(!repoHasIvyXml(mainModuleName))
      assert(!repoHasJar(mainModuleName))
      assert(!repoHasSourcesJar(mainModuleName))
      assert(!repoHasDocJar(mainModuleName))
      assert(repoHasIvyXml(transitiveModuleName))
      assert(repoHasJar(transitiveModuleName))
      assert(repoHasSourcesJar(transitiveModuleName))
      assert(!repoHasDocJar(transitiveModuleName))

      clearRepo()

      eval(compileAndRuntimeStuff.transitive.publishLocal(
        ivy2Repo.toString,
        doc = false,
        transitive = true
      )).right.get
      assert(repoHasIvyXml(mainModuleName))
      assert(repoHasJar(mainModuleName))
      assert(repoHasSourcesJar(mainModuleName))
      assert(!repoHasDocJar(mainModuleName))
      assert(repoHasIvyXml(transitiveModuleName))
      assert(repoHasJar(transitiveModuleName))
      assert(repoHasSourcesJar(transitiveModuleName))
      assert(!repoHasDocJar(transitiveModuleName))

      clearRepo()

      eval(compileAndRuntimeStuff.runtimeTransitive.publishLocal(ivy2Repo.toString)).right.get
      assert(!repoHasIvyXml(mainModuleName))
      assert(!repoHasJar(mainModuleName))
      assert(!repoHasSourcesJar(mainModuleName))
      assert(!repoHasDocJar(mainModuleName))
      assert(repoHasIvyXml(runtimeTransitiveModuleName))
      assert(repoHasJar(runtimeTransitiveModuleName))
      assert(repoHasSourcesJar(runtimeTransitiveModuleName))
      assert(repoHasDocJar(runtimeTransitiveModuleName))

      clearRepo()

      eval(compileAndRuntimeStuff.runtimeTransitive.publishLocal(
        ivy2Repo.toString,
        transitive = true
      )).right.get
      assert(repoHasIvyXml(mainModuleName))
      assert(repoHasJar(mainModuleName))
      assert(repoHasSourcesJar(mainModuleName))
      assert(repoHasDocJar(mainModuleName))
      assert(repoHasIvyXml(runtimeTransitiveModuleName))
      assert(repoHasJar(runtimeTransitiveModuleName))
      assert(repoHasSourcesJar(runtimeTransitiveModuleName))
      assert(repoHasDocJar(runtimeTransitiveModuleName))
    }
  }

}
