package mill.contrib.gitlab

import mill.T
import mill.api.ExecResult.Failure
import mill.define.Discover
import mill.scalalib.publish.PomSettings
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.{TestSuite, Tests, assertMatch, test}
import mill.util.TokenReaders._
object GitlabModuleTests extends TestSuite {

  val emptyLookup = new GitlabTokenLookup {
    override def tokenSearchOrder = Seq.empty
  }

  object GitlabModule extends TestBaseModule with GitlabPublishModule {
    override def publishRepository: ProjectRepository =
      ProjectRepository("http://gitlab.local", 0)

    // Not hit, can be ???
    override def pomSettings: T[PomSettings] = ???

    override def publishVersion: T[String] = "0.0.1"

    override def tokenLookup: GitlabTokenLookup = emptyLookup

    lazy val millDiscover = Discover[this.type]
  }

  // GitlabMavenRepository does not need to be a module, but it needs to be invoked from one.
  // So for test purposes we make a module with it to get a Ctx for evaluation
  object GLMvnRepo extends TestBaseModule with GitlabMavenRepository {
    override def gitlabRepository: GitlabPackageRepository =
      InstanceRepository("https://gl.local")

    override def tokenLookup = emptyLookup

    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {

    test("GitlabPublishModule produces sane error message") - UnitTester(
      GitlabModule,
      null
    ).scoped { eval =>
      val e = eval(GitlabModule.gitlabHeaders(Map.empty))

      assertMatch(e) {
        case Left(Failure(s))
            if s.startsWith("Token lookup for PUBLISH repository") =>
      }
    }

    test("GitlabMavenRepository produces sane error message") - UnitTester(GLMvnRepo, null).scoped {
      eval =>
        val e = eval(GLMvnRepo.mavenRepository)

        assertMatch(e) {
          case Left(Failure(s))
              if s.startsWith("Token lookup for PACKAGE repository") =>
        }
    }
  }

}
