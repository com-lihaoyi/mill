package mill.contrib.gitlab

import mill.T
import mill.api.Result.Failure
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assertMatch, test}

object GitlabModuleTests extends TestSuite {

  val emptyLookup = new GitlabTokenLookup {
    override def tokenSearchOrder = Seq.empty
  }

  object GitlabModule extends TestUtil.BaseModule with GitlabPublishModule {
    override def publishRepository: ProjectRepository =
      ProjectRepository("http://gitlab.local", 0)

    // Not hit, can be ???
    override def pomSettings: T[PomSettings] = ???

    override def publishVersion: T[String] = "0.0.1"

    override def tokenLookup: GitlabTokenLookup = emptyLookup
  }

  // GitlabMavenRepository does not need to be a module, but it needs to be invoked from one.
  // So for test purposes we make make a module with it to get a Ctx for evaluation
  object GLMvnRepo extends TestUtil.BaseModule with GitlabMavenRepository {
    override def gitlabRepository: GitlabPackageRepository =
      InstanceRepository("https://gl.local")

    override def tokenLookup = emptyLookup
  }

  def testModule[T](
      m: TestUtil.BaseModule
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    t(eval)
  }

  override def tests: Tests = Tests {

    test("GitlabPublishModule produces sane error message") - testModule(GitlabModule) { eval =>
      val e = eval(GitlabModule.gitlabHeaders(Map.empty))

      assertMatch(e) {
        case Left(Failure(s, _))
            if s.startsWith("Token lookup for PUBLISH repository") =>
      }
    }

    test("GitlabMavenRepository produces sane error message") - testModule(GLMvnRepo) { eval =>
      val e = eval(GLMvnRepo.mavenRepository)

      assertMatch(e) {
        case Left(Failure(s, _))
            if s.startsWith("Token lookup for PACKAGE repository") =>
      }
    }
  }

}
