package mill.contrib.gitlab

import mill.util.{DummyLogger, TestUtil}
import utest.{TestSuite, Tests, assert, assertMatch, intercept, test}
import GitlabTokenLookup._
import mill.scalalib.publish.Artifact

import scala.collection.mutable.ListBuffer

object GitlabTests extends TestSuite {
  override def tests: Tests = Tests {

    object defaultLookup extends GitlabTokenLookup

    test("Token search returns first applicable") {
      object testLookup extends GitlabTokenLookup {
        override def tokenSearchOrder: Seq[GitlabToken] = Seq(
            Personal(Property("gl.token1")),
            Personal(Property("gl.token2"))
        )
      }

      val none   = testLookup.resolveGitlabToken(DummyLogger, Map.empty, Map.empty)
      val first  = testLookup.resolveGitlabToken(DummyLogger, Map.empty, Map("gl.token1" -> "1"))
      val second = testLookup.resolveGitlabToken(DummyLogger, Map.empty, Map("gl.token2" -> "2"))
      val both   = testLookup.resolveGitlabToken(DummyLogger, Map.empty, Map("gl.token1" -> "1", "gl.token2" -> "2"))

      assert(none.isEmpty)
      assertMatch(first) { case Some(GitlabAuthHeaders(Seq(("Private-Token", "1")))) => }
      assertMatch(second) { case Some(GitlabAuthHeaders(Seq(("Private-Token", "2")))) => }
      assertMatch(both) { case Some(GitlabAuthHeaders(Seq(("Private-Token", "1")))) => }
    }

    test("Token from environment variable") {
      val token =
        defaultLookup.resolveGitlabToken(DummyLogger, Map("GITLAB_PERSONAL_ACCESS_TOKEN" -> "t"), Map.empty)

      assertMatch(token) { case Some(GitlabAuthHeaders(Seq(("Private-Token", "t")))) => }
    }

    test("Token from property") {
      val token = defaultLookup.resolveGitlabToken(
          DummyLogger,
          Map("GITLAB_DEPLOY_TOKEN"          -> "t"),
          Map("gitlab.personal-access-token" -> "pt")
      )

      // personal access token property resolves before deploy token in default lookup
      assertMatch(token) { case Some(GitlabAuthHeaders(Seq(("Private-Token", "pt")))) => }
    }

    test("Token from file") {
      val tokenFile = os.pwd / "token.temp"
      os.write(tokenFile, "foo")

      object fileEnv extends GitlabTokenLookup {
        override def tokenSearchOrder: Seq[GitlabToken] = Seq(
            Deploy(File(tokenFile))
        )
      }

      val token = fileEnv.resolveGitlabToken(DummyLogger, Map.empty, Map.empty)

      os.remove(tokenFile)

      assertMatch(token) { case Some(GitlabAuthHeaders(Seq(("Deploy-Token", "foo")))) => }
    }

    test("Custom token source.") {
      object customEnv extends GitlabTokenLookup {
        override def tokenSearchOrder: Seq[GitlabToken] = Seq(
            Deploy(Custom(() => Right("tok")))
        )
      }

      val token = customEnv.resolveGitlabToken(DummyLogger, Map.empty, Map.empty)

      assertMatch(token) { case Some(GitlabAuthHeaders(Seq(("Deploy-Token", "tok")))) => }
    }

    test("Publish url is correct") {
      object uploader extends ((String, Array[Byte]) => requests.Response) {
        def apply(url: String, data: Array[Byte]): requests.Response = {
          urls.append(url)
          requests.Response(url, 200, "Success", new geny.Bytes("".getBytes()), Map.empty, None)
        }

        val urls = ListBuffer[String]()
      }

      val repo      = ProjectRepository("https://gitlab.local", 10)
      val publisher = new GitlabPublisher(uploader, repo, 1, 1, DummyLogger)

      val fakeFile = os.pwd / "dummy.data"
      os.write(fakeFile, Array[Byte]())

      val artifact = Artifact("test.group", "id", "0.0.0")

      publisher.publish(Seq(fakeFile -> "data.file"), artifact)

      os.remove(fakeFile)

      assert(uploader.urls.size == 1)
      assert(
          uploader.urls.head == "https://gitlab.local/api/v4/projects/10/packages/maven/test/group/id/0.0.0/data.file"
      )
    }
  }
}
