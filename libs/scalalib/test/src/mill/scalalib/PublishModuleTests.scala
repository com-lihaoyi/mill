package mill.scalalib

import mill.{T, Task}
import mill.api.Discover
import mill.javalib.publish.{
  Developer,
  License,
  PackagingType,
  PomSettings,
  VersionControl,
  VersionScheme
}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.util.TokenReaders.*
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

  object HelloWorldWithPublish extends TestRootModule {
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
        val Right(result) = eval.apply(HelloWorldWithPublish.core.pom).runtimeChecked

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
        val Right(result) = eval.apply(HelloWorldWithPublish.core.pom).runtimeChecked

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.value.path.toString)
        val versionScheme = pomXml \ "properties" \ "info.versionScheme"
        assert(versionScheme.text == "early-semver")
      }
    }

    test("ivy") {
      test("should include scala-library dependency") - UnitTester(
        HelloWorldWithPublish,
        resourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithPublish.core.ivy).runtimeChecked

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
  }

}
