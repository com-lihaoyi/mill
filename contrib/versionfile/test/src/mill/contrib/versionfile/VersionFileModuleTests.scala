package mill.contrib.versionfile

import mill.T
import mill.testkit.{UnitTester, TestBaseModule}
import utest.{TestSuite, Tests, assert, assertMatch, test}
import utest.framework.TestPath

object VersionFileModuleTests extends TestSuite {

  object TestModule extends TestBaseModule {
    case object versionFile extends VersionFileModule
  }

  def evaluator[T, M <: mill.testkit.TestBaseModule](
      m: M,
      vf: M => VersionFileModule,
      versionText: String
  )(implicit tp: TestPath): UnitTester = {
    val eval = new UnitTester(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.write.over(
      vf(m).millSourcePath / "version",
      versionText,
      createFolders = true
    )
    eval
  }

  def workspaceTest0(versions: Version*)(test: UnitTester => Version => Any)(implicit
      tp: TestPath
  ): Unit = {
    for (version <- versions)
      test(evaluator(TestModule, (m: TestModule.type) => m.versionFile, version.toString))(version)
  }

  def workspaceTest(versions: Version*)(test: UnitTester => Any)(implicit tp: TestPath): Unit =
    workspaceTest0(versions: _*)(eval => _ => test(eval))

  //  check version file ends with newline
  def workspaceTestEndsWithNewline0(versions: Version*)(test: UnitTester => Version => Any)(
      implicit tp: TestPath
  ): Unit = {
    for (version <- versions)
      test(evaluator(TestModule, (m: TestModule.type) => m.versionFile, version.toString + "\n"))(
        version
      )
  }

  def workspaceTestEndsWithNewline(versions: Version*)(test: UnitTester => Any)(implicit
      tp: TestPath
  ): Unit =
    workspaceTestEndsWithNewline0(versions: _*)(eval => _ => test(eval))

  def tests: Tests = Tests {

    import Bump._

    test("reading") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("currentVersion") - workspaceTest0(versions: _*) { eval => expectedVersion =>
        val Right(out) = eval(TestModule.versionFile.currentVersion)
        assert(out.value == expectedVersion)
      }

      test("releaseVersion") - workspaceTest(versions: _*) { eval =>
        val out = eval(TestModule.versionFile.releaseVersion)
        assertMatch(out) {
          case Right((Version.Release(1, 2, 3), _)) =>
        }
      }

      test("nextVersion") - workspaceTest(versions: _*) { eval =>
        val out = eval(TestModule.versionFile.nextVersion(minor))
        assertMatch(out) {
          case Right((Version.Snapshot(1, 3, 0), _)) =>
        }
      }

      test("currentVersion - file ends with newline") - workspaceTestEndsWithNewline0(
        versions: _*
      ) { eval => expectedVersion =>
        val Right(out) = eval(TestModule.versionFile.currentVersion)
        assert(out.value == expectedVersion)
      }

      test("releaseVersion - file ends with newline") - workspaceTestEndsWithNewline(versions: _*) {
        eval =>
          val out = eval(TestModule.versionFile.releaseVersion)
          assertMatch(out) {
            case Right((Version.Release(1, 2, 3), _)) =>
          }
      }

      test("nextVersion - file ends with newline") - workspaceTestEndsWithNewline(versions: _*) {
        eval =>
          val out = eval(TestModule.versionFile.nextVersion(minor))
          assertMatch(out) {
            case Right((Version.Snapshot(1, 3, 0), _)) =>
          }
      }
    }

    test("writing") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("setReleaseVersion") - workspaceTest(versions: _*) { eval =>
        val Right(expected) = eval(TestModule.versionFile.releaseVersion)
        eval(TestModule.versionFile.setReleaseVersion())
        val Right(actual) = eval(TestModule.versionFile.currentVersion)
        assert(expected.value == actual.value)
      }

      test("setNextVersion") - workspaceTest(versions: _*) { eval =>
        val bump = minor
        val Right(expected) = eval(TestModule.versionFile.nextVersion(bump))
        eval(TestModule.versionFile.setNextVersion(bump))
        val Right(actual) = eval(TestModule.versionFile.currentVersion)
        assert(expected.value == actual.value)
      }

      test("setVersion") - workspaceTest(versions: _*) { eval =>
        val expected = Version.Release(1, 2, 4)
        eval(TestModule.versionFile.setVersion(T.task(expected)))
        val Right(actual) = eval(TestModule.versionFile.currentVersion)
        assert(actual.value == expected)
      }

    }

    test("procs") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("tag") - workspaceTest0(versions: _*) { eval => version =>
        val Right(out) = eval(TestModule.versionFile.tag)
        val commitMessage = TestModule.versionFile.generateCommitMessage(version)
        assert(
          out.value ==
            Seq(
              os.proc("git", "commit", "-am", commitMessage),
              os.proc("git", "tag", version.toString)
            )
        )

      }

      test("push") - workspaceTest0(versions: _*) { eval => version =>
        val Right(out) = eval(TestModule.versionFile.push)
        val commitMessage = TestModule.versionFile.generateCommitMessage(version)
        assert(
          out.value ==
            Seq(
              os.proc("git", "commit", "-am", commitMessage),
              os.proc("git", "push", "origin", "master", "--tags")
            )
        )
      }

    }
  }
}
