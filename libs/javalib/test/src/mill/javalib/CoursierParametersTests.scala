package mill.javalib

import coursier.cache.{CacheLogger, FileCache}
import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

import java.util.concurrent.atomic.AtomicInteger

object CoursierParametersTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestRootModule {
    object core extends JavaModule {
      def mvnDeps = Task {
        Seq(mvn"com.google.guava:guava:33.0.0-jre")
      }
      def resolutionParams = Task.Anon {
        super.resolutionParams()
          .addForceVersion((
            coursier.Module(
              coursier.Organization("com.google.guava"),
              coursier.ModuleName("guava")
            ),
            "32.1.3-jre"
          ))
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  // Shared counters for the custom logger test, reset before each run
  val customLoggerInitCount = new AtomicInteger(0)
  val customLoggerStopCount = new AtomicInteger(0)

  object CustomLoggerTest extends TestRootModule {
    object core extends JavaModule {
      def mvnDeps = Seq(mvn"com.google.guava:guava:33.0.0-jre")
      def coursierCacheCustomizer = Task.Anon {
        Some { (cache: FileCache[coursier.util.Task]) =>
          cache.withLogger(
            new CacheLogger {
              override def init(sizeHint: Option[Int]): Unit =
                customLoggerInitCount.incrementAndGet()
              override def stop(): Unit =
                customLoggerStopCount.incrementAndGet()
            }
          )
        }
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("coursierParams") - UnitTester(CoursierTest, null).scoped { eval =>
      val Right(result) = eval.apply(CoursierTest.core.compileClasspath).runtimeChecked
      val classPath = result.value.toSeq.map(_.path)
      val guavaVersion = classPath
        .map(_.last)
        .filter(_.endsWith(".jar"))
        .filter(_.startsWith("guava-"))
        .map(_.stripPrefix("guava-").stripSuffix(".jar").stripSuffix("-jre"))
        .headOption
        .getOrElse {
          sys.error(s"guava not found in class path $classPath")
        }
      val expectedGuavaVersion = "32.1.3"
      assert(guavaVersion == expectedGuavaVersion)
    }

    test("coursierCacheCustomizerLoggerCalled") {
      customLoggerInitCount.set(0)
      customLoggerStopCount.set(0)
      UnitTester(CustomLoggerTest, null).scoped { eval =>
        val Right(_) = eval.apply(CustomLoggerTest.core.compileClasspath).runtimeChecked
        assert(customLoggerInitCount.get() > 0)
        assert(customLoggerStopCount.get() > 0)
      }
    }
  }
}
