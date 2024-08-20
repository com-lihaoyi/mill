package mill.scalalib

import mill.{Agg, T}
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
import mill.testkit.TestEvaluator
import mill.testkit.MillTestKit
import utest._
import utest.framework.TestPath

import java.io.PrintStream
import scala.xml.NodeSeq

object PublishModuleTests extends TestSuite {

  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

  trait PublishBase extends mill.testkit.BaseModule {
    override def millSourcePath: os.Path =
      MillTestKit.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloScalaModule extends ScalaModule {
    def scalaVersion = scala212Version
    override def semanticDbVersion: T[String] = T {
      // The latest semanticDB release for Scala 2.12.6
      "4.1.9"
    }
  }

  object HelloWorldWithPublish extends PublishBase {
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

      def checkSonatypeCreds(sonatypeCreds: String) = T.command {
        PublishModule.checkSonatypeCreds(sonatypeCreds)
      }
    }
  }

  object PomOnly extends PublishBase {
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
      // ensure, these target wont be called
      override def jar: T[PathRef] = T { ???.asInstanceOf[PathRef] }
      override def docJar: T[PathRef] = T { ???.asInstanceOf[PathRef] }
      override def sourceJar: T[PathRef] = T { ???.asInstanceOf[PathRef] }
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "publish"

  def workspaceTest[T](
      m: mill.testkit.BaseModule,
      resourcePath: os.Path = resourcePath,
      env: Map[String, String] = Evaluator.defaultEnv,
      debug: Boolean = false,
      errStream: PrintStream = System.err
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, env = env, debugEnabled = debug, errStream = errStream)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    test("pom") {
      test("should include scala-library dependency") - workspaceTest(HelloWorldWithPublish) {
        eval =>
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
      test("versionScheme") - workspaceTest(HelloWorldWithPublish) { eval =>
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
      ) - workspaceTest(
        HelloWorldWithPublish,
        env = Evaluator.defaultEnv ++ Seq(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "password"
        )
      ) { eval =>
        val Right(result) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

        assert(
          result.value == "user:password",
          result.evalCount > 0
        )
      }
      test(
        "should prefer direct argument as credentials over environment variables"
      ) - workspaceTest(
        HelloWorldWithPublish,
        env = Evaluator.defaultEnv ++ Seq(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "password"
        )
      ) { eval =>
        val directValue = "direct:value"
        val Right(result) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(directValue))

        assert(
          result.value == directValue,
          result.evalCount > 0
        )
      }
      test(
        "should throw exception if neither environment variables or direct argument were not passed"
      ) - workspaceTest(
        HelloWorldWithPublish
      ) { eval =>
        val Left(Result.Failure(msg, None)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

        assert(
          msg.contains("Consider using SONATYPE_USERNAME/SONATYPE_PASSWORD environment variables")
        )
      }
    }

    test("ivy") {
      test("should include scala-library dependency") - workspaceTest(HelloWorldWithPublish) {
        eval =>
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
      test("pom") - workspaceTest(PomOnly) { eval =>
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
