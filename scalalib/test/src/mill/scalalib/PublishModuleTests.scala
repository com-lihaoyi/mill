package mill.scalalib

import mill.{Agg, T, Task}
import mill.api.{PathRef, Result}
import mill.define.Discover
import mill.eval.Evaluator
import mill.scalalib.publish.{Developer, License, PackagingType, PomSettings, VersionControl, VersionScheme}
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

import java.io.PrintStream
import scala.xml.NodeSeq

object PublishModuleTests extends TestSuite {

  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

  trait PublishBase extends TestUtil.BaseModule {
    override def millSourcePath: os.Path =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloScalaModule extends ScalaModule {
    def scalaVersion = scala212Version
    override def semanticDbVersion: T[String] = Task {
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

      def checkSonatypeCreds(sonatypeCreds: String) = Task.command {
        PublishModule.checkSonatypeCreds(sonatypeCreds)()
      }
    }

    val millDiscover = Discover[this.type]
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
      override def jar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
      override def docJar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
      override def sourceJar: T[PathRef] = Task { ???.asInstanceOf[PathRef] }
    }

    val millDiscover = Discover[this.type]
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "publish"

  def workspaceTest[T](
      m: TestUtil.BaseModule,
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
    "pom" - {
      "should include scala-library dependency" - workspaceTest(HelloWorldWithPublish) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithPublish.core.pom)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.path.toString)
        val scalaLibrary = pomXml \ "dependencies" \ "dependency"
        assert(
          (pomXml \ "packaging").text == PackagingType.Jar,
          (scalaLibrary \ "artifactId").text == "scala-library",
          (scalaLibrary \ "groupId").text == "org.scala-lang"
        )
      }
      "versionScheme" - workspaceTest(HelloWorldWithPublish) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithPublish.core.pom)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.path.toString)
        val versionScheme = pomXml \ "properties" \ "info.versionScheme"
        assert(versionScheme.text == "early-semver")
      }
    }

    "publish" - {
      "should retrieve credentials from environment variables if direct argument is empty" - workspaceTest(
        HelloWorldWithPublish,
        env = Evaluator.defaultEnv ++ Seq(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "password"
        )
      ) { eval =>
        val Right((credentials, evalCount)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

        assert(
          credentials == "user:password",
          evalCount > 0
        )
      }
      "should prefer direct argument as credentials over environment variables" - workspaceTest(
        HelloWorldWithPublish,
        env = Evaluator.defaultEnv ++ Seq(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "password"
        )
      ) { eval =>
        val directValue = "direct:value"
        val Right((credentials, evalCount)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(directValue))

        assert(
          credentials == directValue,
          evalCount > 0
        )
      }
      "should throw exception if neither environment variables or direct argument were not passed" - workspaceTest(
        HelloWorldWithPublish
      ) { eval =>
        val Left(Result.Failure(msg, None)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

        assert(
          msg.contains("Consider using SONATYPE_USERNAME/SONATYPE_PASSWORD environment variables")
        )
      }
    }

    "ivy" - {
      "should include scala-library dependency" - workspaceTest(HelloWorldWithPublish) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithPublish.core.ivy)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        val ivyXml = scala.xml.XML.loadFile(result.path.toString)
        val deps: NodeSeq = (ivyXml \ "dependencies" \ "dependency")
        assert(deps.exists(n =>
          (n \ "@conf").text == "compile->default(compile)" &&
            (n \ "@name").text == "scala-library" && (n \ "@org").text == "org.scala-lang"
        ))
      }
    }

    test("pom-packaging-type") - {
      test("pom") - workspaceTest(PomOnly) { eval =>
        val Right((result, evalCount)) = eval.apply(PomOnly.core.pom)
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
