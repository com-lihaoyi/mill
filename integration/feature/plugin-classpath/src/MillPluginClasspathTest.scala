package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicReference

/**Trait that provides a `skip` method that can be used to skip a test, the test will pass.
 * Used to assert that a test still compiles, and is intended to be re-enabled later,
 * but is temporarily prevented from running for a suitable reason.
 * At the end of a suite, print a summary of the number of skipped tests, and their names.
 * @note I'd propose to make "skipping" a part core utest library, so that the summary includes the skipped tests
 */
trait UTestIgnore(name: String) extends utest.TestSuite {

  val skipList = AtomicReference(List.empty[String])

  private final class SkipException(val name: String) extends Exception with scala.util.control.NoStackTrace

  def skip(op: => Any)(using path: utest.framework.TestPath): Nothing = {
    throw new SkipException(name + "." + path.value.mkString("."))
  }

  private def red(str: String) = Console.RED + str + Console.RESET

  override def utestWrap(path: Seq[String], runBody: => Future[Any])(implicit ec: ExecutionContext): Future[Any] = {
    super.utestWrap(path, runBody.recoverWith {
      case e: SkipException =>
        skipList.updateAndGet(e.name :: _)
        Future.successful(())
    })
  }

  override def utestAfterAll(): Unit = {
    val skipped = skipList.getAndUpdate(_ => Nil).reverse
    if (skipped.nonEmpty) {
      println(s"${red("!")} Skipped tests in $name:")
      skipped.foreach { s =>
        println(s"  - $s")
      }
      println("Skipped: " + skipped.size)
    }
  }
}

object MillPluginClasspathTest extends UtestIntegrationTestSuite
  with UTestIgnore("mill.integration.MillPluginClasspathTest") {

  val embeddedModules: Seq[(String, String)] = Seq(
    ("com.lihaoyi", "mill-main-client"),
    ("com.lihaoyi", "mill-main-api_2.13"),
    ("com.lihaoyi", "mill-main-util_2.13"),
    ("com.lihaoyi", "mill-main-codesig_2.13"),
    ("com.lihaoyi", "mill-bsp_2.13"),
    ("com.lihaoyi", "mill-scalanativelib-worker-api_2.13"),
    ("com.lihaoyi", "mill-testrunner-entrypoint"),
    ("com.lihaoyi", "mill-scalalib-api_2.13"),
    ("com.lihaoyi", "mill-testrunner_2.13"),
    ("com.lihaoyi", "mill-main-define_2.13"),
    ("com.lihaoyi", "mill-main-resolve_2.13"),
    ("com.lihaoyi", "mill-main-eval_2.13"),
    ("com.lihaoyi", "mill-main_2.13"),
    ("com.lihaoyi", "mill-scalalib_2.13"),
    ("com.lihaoyi", "mill-scalanativelib_2.13"),
    ("com.lihaoyi", "mill-scalajslib-worker-api_2.13"),
    ("com.lihaoyi", "mill-scalajslib_2.13"),
    ("com.lihaoyi", "mill-runner_2.13"),
    ("com.lihaoyi", "mill-idea_2.13")
  )

  val tests: Tests = Tests {

    test("exclusions") - skip {
      integrationTest { tester =>
        import tester._
        retry(3) {
          val res1 = eval(("--meta-level", "1", "resolveDepsExclusions"))
          assert(res1.isSuccess)

          val exclusions = out("mill-build.resolveDepsExclusions").value[Seq[(String, String)]]
          val expectedExclusions = embeddedModules

          val diff = expectedExclusions.toSet.diff(exclusions.toSet)
          assert(diff.isEmpty)
        }
      }
    }
    test("runClasspath") - skip {
      integrationTest { tester =>
        import tester._
        retry(3) {
          // We expect Mill core transitive dependencies to be filtered out
          val res1 = eval(("--meta-level", "1", "runClasspath"))
          assert(res1.isSuccess)

          val runClasspath = out("mill-build.runClasspath").value[Seq[String]]

          val unexpectedArtifacts = embeddedModules.map {
            case (o, n) => s"${o.replaceAll("[.]", "/")}/${n}"
          }

          val unexpected = unexpectedArtifacts.flatMap { a =>
            runClasspath.find(p => p.toString.contains(a)).map((a, _))
          }.toMap
          assert(unexpected.isEmpty)

          val expected =
            Seq("com/disneystreaming/smithy4s/smithy4s-mill-codegen-plugin_mill0.11_2.13")
          assert(expected.forall(a =>
            runClasspath.exists(p => p.toString().replace('\\', '/').contains(a))
          ))
        }
      }
    }

    test("semanticDbData") - skip {
      integrationTest { tester =>
        import tester._
        retry(3) {
          val res1 = eval(("--meta-level", "1", "semanticDbData"))
          assert(res1.isSuccess)
        }
      }
    }
  }
}
