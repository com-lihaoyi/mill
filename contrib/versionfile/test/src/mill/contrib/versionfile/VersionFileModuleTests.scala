package mill.contrib.versionfile

import mill.Task
import mill.testkit.{UnitTester, TestBaseModule}
import utest.{TestSuite, Tests, assert, test}
import mill.util.TokenReaders._
import mill.define.Discover
object VersionFileModuleTests extends TestSuite {

  object TestModule extends TestBaseModule {
    case object versionFile extends VersionFileModule
    lazy val millDiscover = Discover[this.type]
  }

  def evaluator[T, M <: mill.testkit.TestBaseModule](
      m: M,
      vf: M => VersionFileModule,
      versionText: String
  ): UnitTester = UnitTester(m, null).scoped { eval =>
    os.write.over(
      vf(m).moduleDir / "version",
      versionText,
      createFolders = true
    )
    eval
  }

  def workspaceTest0(versions: Version*)(test: UnitTester => Version => Any): Unit = {
    for (version <- versions)
      test(evaluator(TestModule, (m: TestModule.type) => m.versionFile, version.toString))(version)
  }

  def workspaceTest(versions: Version*)(test: UnitTester => Any): Unit =
    workspaceTest0(versions*)(eval => _ => test(eval))

  //  check version file ends with newline
  def workspaceTestEndsWithNewline0(versions: Version*)(test: UnitTester => Version => Any)
      : Unit = {
    for (version <- versions)
      test(evaluator(TestModule, (m: TestModule.type) => m.versionFile, version.toString + "\n"))(
        version
      )
  }

  def workspaceTestEndsWithNewline(versions: Version*)(test: UnitTester => Any): Unit =
    workspaceTestEndsWithNewline0(versions*)(eval => _ => test(eval))

  def tests: Tests = Tests {

    import Bump._

    test("reading") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("currentVersion") - workspaceTest0(versions*) { eval => expectedVersion =>
        val Right(out) = eval(TestModule.versionFile.currentVersion): @unchecked
        assert(out.value == expectedVersion)
      }

      test("releaseVersion") - workspaceTest(versions*) { eval =>
        val Right(out) = eval(TestModule.versionFile.releaseVersion): @unchecked
        assert(out.value == Version.Release(1, 2, 3))
      }

      test("nextVersion") - workspaceTest(versions*) { eval =>
        val Right(out) = eval(TestModule.versionFile.nextVersion(minor)): @unchecked
        assert(out.value == Version.Snapshot(1, 3, 0))
      }

      test("currentVersion - file ends with newline") - workspaceTestEndsWithNewline0(
        versions*
      ) { eval => expectedVersion =>
        val Right(out) = eval(TestModule.versionFile.currentVersion): @unchecked
        assert(out.value == expectedVersion)
      }

      test("releaseVersion - file ends with newline") - workspaceTestEndsWithNewline(versions*) {
        eval =>
          val Right(out) = eval(TestModule.versionFile.releaseVersion): @unchecked
          assert(out.value == Version.Release(1, 2, 3))
      }

      test("nextVersion - file ends with newline") - workspaceTestEndsWithNewline(versions*) {
        eval =>
          val Right(out) = eval(TestModule.versionFile.nextVersion(minor)): @unchecked
          assert(out.value == Version.Snapshot(1, 3, 0))
      }
    }

    test("writing") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("setReleaseVersion") - workspaceTest(versions*) { eval =>
        val Right(expected) = eval(TestModule.versionFile.releaseVersion): @unchecked
        eval(TestModule.versionFile.setReleaseVersion())
        val Right(actual) = eval(TestModule.versionFile.currentVersion): @unchecked
        assert(expected.value == actual.value)
      }

      test("setNextVersion") - workspaceTest(versions*) { eval =>
        val bump = minor
        val Right(expected) = eval(TestModule.versionFile.nextVersion(bump)): @unchecked
        eval(TestModule.versionFile.setNextVersion(bump))
        val Right(actual) = eval(TestModule.versionFile.currentVersion): @unchecked
        assert(expected.value == actual.value)
      }

      test("setVersion") - workspaceTest(versions*) { eval =>
        val expected = Version.Release(1, 2, 4)
        eval(TestModule.versionFile.setVersion(Task.Anon(expected)))
        val Right(actual) = eval(TestModule.versionFile.currentVersion): @unchecked
        assert(actual.value == expected)
      }

    }

    test("procs") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("tag") - workspaceTest0(versions*) { eval => version =>
        val Right(out) = eval(TestModule.versionFile.tag): @unchecked
        val commitMessage = TestModule.versionFile.generateCommitMessage(version)
        assert(
          out.value ==
            Seq(
              os.proc("git", "commit", "-am", commitMessage),
              os.proc("git", "tag", version.toString)
            )
        )

      }

      test("push") - workspaceTest0(versions*) { eval => version =>
        val Right(out) = eval(TestModule.versionFile.push): @unchecked
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
