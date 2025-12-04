package mill.contrib.gitlab

import mill.api.Result
import mill.contrib.gitlab.GitlabTokenLookup.*
import mill.api.Logger.DummyLogger
import mill.javalib.publish.*
import utest.{TestSuite, Tests, assert, assertMatch, test}

import scala.collection.mutable.ListBuffer

object GitlabTokenTests extends TestSuite {
  override def tests: Tests = Tests {

    object defaultLookup extends GitlabTokenLookup

    val ws = os.pwd

    test("Token search returns first applicable") {
      object testLookup extends GitlabTokenLookup {
        override def tokenSearchOrder =
          Seq(
            Personal(Property("gl.token1")),
            Personal(Property("gl.token2"))
          )
      }

      val none = testLookup.resolveGitlabToken(Map.empty, Map.empty, ws)
      val first =
        testLookup.resolveGitlabToken(Map.empty, Map("gl.token1" -> "1"), ws)
      val second =
        testLookup.resolveGitlabToken(Map.empty, Map("gl.token2" -> "2"), ws)
      val both =
        testLookup.resolveGitlabToken(
          Map.empty,
          Map("gl.token1" -> "1", "gl.token2" -> "2"),
          ws
        )

      assert(none.isInstanceOf[Result.Failure])
      assertMatch(first) { case Result.Success(GitlabAuthHeaders(Seq(("Private-Token", "1")))) => }
      assertMatch(second) { case Result.Success(GitlabAuthHeaders(Seq(("Private-Token", "2")))) => }
      assertMatch(both) { case Result.Success(GitlabAuthHeaders(Seq(("Private-Token", "1")))) => }
    }

    test("Token from environment variable") {
      val token = defaultLookup.resolveGitlabToken(
        Map("GITLAB_PERSONAL_ACCESS_TOKEN" -> "private-token-from-env"),
        Map.empty,
        ws
      )

      assertMatch(token) {
        case Result.Success(GitlabAuthHeaders(Seq(("Private-Token", "private-token-from-env")))) =>
      }
    }

    test("Token from property") {
      val token = defaultLookup.resolveGitlabToken(
        Map("GITLAB_DEPLOY_TOKEN" -> "t"),
        Map("gitlab.personal-access-token" -> "pt"),
        ws
      )

      // personal access token property resolves before deploy token in default lookup
      assertMatch(token) { case Result.Success(GitlabAuthHeaders(Seq(("Private-Token", "pt")))) => }
    }

    test("Token from property, with no environment variables") {
      val token = defaultLookup.resolveGitlabToken(
        Map.empty,
        Map("gitlab.personal-access-token" -> "pt"),
        ws
      )

      // personal access token property resolves before deploy token in default lookup
      assertMatch(token) { case Result.Success(GitlabAuthHeaders(Seq(("Private-Token", "pt")))) => }
    }

    test("Token from file") {
      val tokenFile = os.pwd / "token.temp"
      os.write(tokenFile, "foo")

      object fileEnv extends GitlabTokenLookup {
        override def tokenSearchOrder = Seq(
          Deploy(File(tokenFile))
        )
      }

      val token = fileEnv.resolveGitlabToken(Map.empty, Map.empty, ws)

      os.remove(tokenFile)

      assertMatch(token) { case Result.Success(GitlabAuthHeaders(Seq(("Deploy-Token", "foo")))) => }
    }

    test("Token from workspace") {
      val fileName = "test.workspace.token"
      val tokenFile = os.RelPath(fileName)
      os.write(ws / tokenFile, "foo")

      object fileEnv extends GitlabTokenLookup {
        override def tokenSearchOrder = Seq(
          Deploy(WorkspaceFile(tokenFile))
        )
      }

      val token = fileEnv.resolveGitlabToken(Map.empty, Map.empty, ws)

      os.remove(ws / tokenFile)

      assertMatch(token) { case Result.Success(GitlabAuthHeaders(Seq(("Deploy-Token", "foo")))) => }
    }

    test("Custom token source.") {
      object customEnv extends GitlabTokenLookup {
        override def tokenSearchOrder = Seq(
          Deploy(Custom(() => Result.Success("tok")))
        )
      }

      val token = customEnv.resolveGitlabToken(Map.empty, Map.empty, ws)

      assertMatch(token) { case Result.Success(GitlabAuthHeaders(Seq(("Deploy-Token", "tok")))) => }
    }

    test("Publish url is correct") {
      object uploader extends ((String, Array[Byte]) => requests.Response) {
        def apply(url: String, data: Array[Byte]): requests.Response = {
          urls.append(url)
          requests.Response(url, 200, "Success", new geny.Bytes("".getBytes()), Map.empty, None)
        }

        val urls: ListBuffer[String] = ListBuffer[String]()
      }

      val repo = ProjectRepository("https://gitlab.local", 10)
      val publisher = GitlabPublisher(uploader, repo, DummyLogger)

      val fakeFile = os.pwd / "dummy.data"
      os.write(fakeFile, Array[Byte]())

      val artifact = Artifact("test.group", "id", "0.0.0")

      publisher.publish(
        Map(os.SubPath("data.file") -> fakeFile),
        artifact
      )

      os.remove(fakeFile)

      assert(uploader.urls.size == 1)
      assert(
        uploader.urls.head == "https://gitlab.local/api/v4/projects/10/packages/maven/test/group/id/0.0.0/data.file"
      )
    }
  }
}
