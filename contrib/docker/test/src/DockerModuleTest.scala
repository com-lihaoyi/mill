package mill
package contrib.docker

import mill.api.PathRef
import mill.scalalib.JavaModule
import mill.util.{TestEvaluator, TestUtil}
import os.Path
import utest._
import utest.framework.TestPath

object DockerModuleTest extends TestSuite {

  private def testExecutable =
    if (isInstalled("podman")) "podman"
    else "docker"

  object Docker extends TestUtil.BaseModule with JavaModule with DockerModule {

    override def millSourcePath = TestUtil.getSrcPathStatic()
    override def artifactName = testArtifactName

    object dockerDefault extends DockerConfig {
      override def executable = testExecutable
    }

    object dockerAll extends DockerConfig {
      override def baseImage = "docker.io/openjdk:11"
      override def labels = Map("version" -> "1.0")
      override def exposedPorts = Seq(8080, 443)
      override def exposedUdpPorts = Seq(80)
      override def volumes = Seq("/v1", "/v2")
      override def envVars = Map("foo" -> "bar", "foobar" -> "barfoo")
      override def run = Seq(
        "/bin/bash -c 'echo Hello World!'",
        "useradd -ms /bin/bash user1"
      )
      override def user = "user1"
      override def executable = testExecutable
    }
  }

  val testArtifactName = "mill-docker-contrib-test"

  val testModuleSourcesPath: Path =
    os.pwd / "contrib" / "docker" / "test" / "resources" / "docker"

  val multineRegex = "\\R+".r

  private def isInstalled(executable: String): Boolean = {
    val getPathCmd = if (scala.util.Properties.isWin) "where" else "which"
    os.proc(getPathCmd, executable).call(check = false).exitCode == 0
  }

  private def workspaceTest(m: TestUtil.BaseModule)(t: TestEvaluator => Unit)(
      implicit tp: TestPath
  ): Unit = {
    if (isInstalled(testExecutable) && !scala.util.Properties.isWin) {
      val eval = new TestEvaluator(m)
      os.remove.all(m.millSourcePath)
      os.remove.all(eval.outPath)
      os.makeDir.all(m.millSourcePath / os.up)
      os.copy(testModuleSourcesPath, m.millSourcePath)
      t(eval)
    } else {
      val identifier = tp.value.mkString("/")
      println(s"Skipping '$identifier' since no docker installation was found")
      assert(true)
    }
  }

  override def utestAfterAll(): Unit = {
    if (isInstalled(testExecutable) && !scala.util.Properties.isWin)
      os
        .proc(testExecutable, "rmi", testArtifactName)
        .call(stdout = os.Inherit, stderr = os.Inherit)
    else ()
  }

  def tests = Tests {

    test("docker build") {
      "default options" - workspaceTest(Docker) { eval =>
        val Right((imageName :: Nil, _)) = eval(Docker.dockerDefault.build)
        assert(imageName == testArtifactName)
      }

      "all options" - workspaceTest(Docker) { eval =>
        val Right((imageName :: Nil, _)) = eval(Docker.dockerAll.build)
        assert(imageName == testArtifactName)
      }
    }

    test("dockerfile contents") {
      "default options" - {
        val eval = new TestEvaluator(Docker)
        val Right((dockerfileString, _)) = eval(Docker.dockerDefault.dockerfile)
        val expected = multineRegex.replaceAllIn(
          """
            |FROM gcr.io/distroless/java:latest
            |COPY out.jar /out.jar
            |ENTRYPOINT ["java", "-jar", "/out.jar"]""".stripMargin,
          sys.props("line.separator")
        )
        val dockerfileStringRefined = multineRegex.replaceAllIn(
          dockerfileString,
          sys.props("line.separator")
        )
        assert(dockerfileStringRefined == expected)
      }

      "all options" - {
        val eval = new TestEvaluator(Docker)
        val Right((dockerfileString, _)) = eval(Docker.dockerAll.dockerfile)
        val expected = multineRegex.replaceAllIn(
          """
            |FROM docker.io/openjdk:11
            |LABEL "version"="1.0"
            |EXPOSE 8080/tcp 443/tcp
            |EXPOSE 80/udp
            |ENV foo=bar
            |ENV foobar=barfoo
            |VOLUME ["/v1", "/v2"]
            |RUN /bin/bash -c 'echo Hello World!'
            |RUN useradd -ms /bin/bash user1
            |USER user1
            |COPY out.jar /out.jar
            |ENTRYPOINT ["java", "-jar", "/out.jar"]""".stripMargin,
          sys.props("line.separator")
        )
        val dockerfileStringRefined = multineRegex.replaceAllIn(
          dockerfileString,
          sys.props("line.separator")
        )
        assert(dockerfileStringRefined == expected)
      }
    }
  }
}
