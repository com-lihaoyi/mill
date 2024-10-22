package mill
package contrib.docker

import mill.scalalib.JavaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import os.Path
import utest._
import utest.framework.TestPath

object DockerModuleTest extends TestSuite {

  private def testExecutable =
    if (isInstalled("podman")) "podman"
    else "docker"

  object Docker extends TestBaseModule with JavaModule with DockerModule {

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

    object dockerJvmOptions extends DockerConfig {
      override def executable = testExecutable
      override def jvmOptions = Seq("-Xmx1024M")
    }
  }

  val testArtifactName = "mill-docker-contrib-test"

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "docker"

  val multineRegex = "\\R+".r

  private def isInstalled(executable: String): Boolean = {
    val getPathCmd = if (scala.util.Properties.isWin) "where" else "which"
    os.proc(getPathCmd, executable).call(check = false).exitCode == 0
  }

  private def workspaceTest(m: mill.testkit.TestBaseModule)(t: UnitTester => Unit)(
      implicit tp: TestPath
  ): Unit = {
    if (isInstalled(testExecutable) && !scala.util.Properties.isWin)
      t(UnitTester(m, testModuleSourcesPath))
    else {
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
      test("default options") - workspaceTest(Docker) { eval =>
        val Right(result) = eval(Docker.dockerDefault.build)
        assert(result.value == List(testArtifactName))
      }

      test("all options") - workspaceTest(Docker) { eval =>
        val Right(result) = eval(Docker.dockerAll.build)
        assert(result.value == List(testArtifactName))
      }
    }

    test("dockerfile contents") {
      test("default options") - UnitTester(Docker, null).scoped { eval =>
        val Right(result) = eval(Docker.dockerDefault.dockerfile)
        val expected = multineRegex.replaceAllIn(
          """
            |FROM gcr.io/distroless/java:latest
            |COPY out.jar /out.jar
            |ENTRYPOINT ["java", "-jar", "/out.jar"]""".stripMargin,
          sys.props.getOrElse("line.separator", ???)
        )
        val dockerfileStringRefined = multineRegex.replaceAllIn(
          result.value,
          sys.props.getOrElse("line.separator", ???)
        )
        assert(dockerfileStringRefined == expected)
      }

      test("all options") - UnitTester(Docker, null).scoped { eval =>
        val Right(result) = eval(Docker.dockerAll.dockerfile)
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
          sys.props.getOrElse("line.separator", ???)
        )
        val dockerfileStringRefined = multineRegex.replaceAllIn(
          result.value,
          sys.props.getOrElse("line.separator", ???)
        )
        assert(dockerfileStringRefined == expected)
      }

      test("extra jvm options") - UnitTester(Docker, null).scoped { eval =>
        val Right(result) = eval(Docker.dockerJvmOptions.dockerfile)
        val expected = multineRegex.replaceAllIn(
          """
            |FROM gcr.io/distroless/java:latest
            |COPY out.jar /out.jar
            |ENTRYPOINT ["java", "-Xmx1024M", "-jar", "/out.jar"]""".stripMargin,
          sys.props.getOrElse("line.separator", ???)
        )
        val dockerfileStringRefined = multineRegex.replaceAllIn(
          result.value,
          sys.props.getOrElse("line.separator", ???)
        )
        assert(dockerfileStringRefined == expected)
      }
    }
  }
}
