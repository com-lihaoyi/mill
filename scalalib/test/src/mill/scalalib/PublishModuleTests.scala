package mill.scalalib

import mill.{Agg, T, Task}
import mill.api.{PathRef, Result}
import mill.eval.Evaluator
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
import utest._
import utest.framework.TestPath

import java.io.PrintStream
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
      override def ivyDeps = Agg(
        ivy"org.slf4j:slf4j-api:2.0.7"
      )
      // ensure, these target won't be called
      override def jar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
      override def docJar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
      override def sourceJar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish"

  def tests: Tests = Tests {
    test("pom") {
      test("should include scala-library dependency") - UnitTester(
        HelloWorldWithPublish,
        resourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithPublish.core.pom)

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
        val Right(result) = eval.apply(HelloWorldWithPublish.core.pom)

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
            eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

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
            eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(directValue))

          assert(
            result.value == directValue,
            result.evalCount > 0
          )
        }
      }
      test(
        "should throw exception if neither environment variables or direct argument were not passed"
      ) - UnitTester(HelloWorldWithPublish, resourcePath).scoped { eval =>
        val Left(Result.Failure(msg, None)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

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
        val Right(result) = eval.apply(HelloWorldWithPublish.core.ivy)

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        val ivyXml = scala.xml.XML.loadFile(result.value.path.toString)
        val deps: NodeSeq = (ivyXml \ "dependencies" \ "dependency")
        assert(deps.exists(n =>
          (n \ "@conf").text == "compile->default(compile)" &&
            (n \ "@name").text == "scala-library" && (n \ "@org").text == "org.scala-lang"
        ))
      }
    }

    test("pom-packaging-type") - {
      test("pom") - UnitTester(PomOnly, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(PomOnly.core.pom)
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
  }

}
