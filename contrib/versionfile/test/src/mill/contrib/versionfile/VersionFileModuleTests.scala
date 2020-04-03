package mill.contrib.versionfile

import mill.eval.Result
import mill.util.{TestEvaluator, TestUtil}
import ammonite.ops.{cp, mkdir, pwd, rm, up, Path}
import utest.{assert, assertMatch, intercept, test, Tests, TestSuite}
import utest.framework.TestPath
import os.write

object VersionFileModuleTests extends TestSuite {

  object TestModule extends TestUtil.BaseModule {
    case object versionFile extends VersionFileModule
  }

  def evaluator[T, M <: TestUtil.BaseModule](m: M, vf: M => VersionFileModule, version: Version)
                                            (implicit tp: TestPath): TestEvaluator = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    write.over(
      vf(m).millSourcePath / "version", version.toString, createFolders = true
    )
    eval
  }

  def workspaceTest0(versions: Version*)
         (test: TestEvaluator => Version => Any)
         (implicit tp: TestPath): Unit = {
    for (version <- versions)
      test(evaluator(TestModule, (m: TestModule.type) => m.versionFile, version))(version)
  }

  def workspaceTest(versions: Version*)(test: TestEvaluator => Any)(implicit tp: TestPath): Unit =
    workspaceTest0(versions: _*)(eval => _ => test(eval))

  implicit class ResultOps[A](result: Either[Result.Failing[A], (A, Int)]) {
    def value: Either[Result.Failing[A], A] = result.map(_._1)
    def count: Either[Result.Failing[A], Int] = result.map(_._2)
  }

  def tests: Tests = Tests {

    import Bump._

    test("reading") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("currentVersion") - workspaceTest0(versions: _*) { eval => expectedVersion =>
        val out = eval(TestModule.versionFile.currentVersion)
        assert(out.value == Right(expectedVersion))
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

    }

    test("writing") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))

      test("setReleaseVersion") - workspaceTest(versions: _*) { eval =>
        val expected = eval(TestModule.versionFile.releaseVersion)
        val write    = eval(TestModule.versionFile.setReleaseVersion)
        val actual   = eval(TestModule.versionFile.currentVersion)
        assert(expected.value == actual.value)
      }

      test("setNextVersion") - workspaceTest(versions: _*) { eval =>
        val bump     = minor
        val expected = eval(TestModule.versionFile.nextVersion(bump))
        val write    = eval(TestModule.versionFile.setNextVersion(bump))
        val actual   = eval(TestModule.versionFile.currentVersion)
        assert(expected.value == actual.value)
      }

      test("setVersion") - workspaceTest(versions: _*) { eval =>
        val expected = Version.Release(1, 2, 4)
        val write    = eval(TestModule.versionFile.setVersion(expected))
        val actual   = eval(TestModule.versionFile.currentVersion)
        assert(actual.value == Right(expected))
      }

    }

    test("procs") {

      val versions = Seq(Version.Release(1, 2, 3), Version.Snapshot(1, 2, 3))
      
      test("tag") - workspaceTest0(versions: _*) { eval => version =>
        val procs         = eval(TestModule.versionFile.tag)
        val commitMessage = TestModule.versionFile.generateCommitMessage(version)
        assert(
          procs.value == Right(
            Seq (
              os.proc("git", "commit", "-am", commitMessage),
              os.proc("git", "tag", version.toString)
            )
          )
        )
      }
      
      test("push") - workspaceTest0(versions: _*) { eval => version =>
        val procs         = eval(TestModule.versionFile.push)
        val commitMessage = TestModule.versionFile.generateCommitMessage(version)
        assert(
          procs.value == Right(
            Seq (
              os.proc("git", "commit", "-am", commitMessage),
              os.proc("git", "push", "origin", "master", "--tags")
            )
          )
        )
      }

    }
  }
}
