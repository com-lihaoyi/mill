package mill.javalib.errorprone

import mill.{T, Task}
import mill.api.Discover
import mill.javalib.JavaModule
import mill.testkit.{TestRootModule, UnitTester}
import os.Path
import mill.javalib.DepSyntax
import utest.*
import mill.util.TokenReaders.*
import scala.util.Properties
object ErrorProneTests extends TestSuite {

  object noErrorProne extends TestRootModule with JavaModule {
    lazy val millDiscover = Discover[this.type]
  }
  object errorProne extends TestRootModule with JavaModule with ErrorProneModule {
    lazy val millDiscover = Discover[this.type]
  }
  object errorProneCustom extends TestRootModule with JavaModule with ErrorProneModule {
    override def errorProneOptions: T[Seq[String]] = Task {
      Seq("-XepAllErrorsAsWarnings")
    }
    lazy val millDiscover = Discover[this.type]
  }
  // Test module with ErrorProne 2.36.0+ which requires --should-stop=ifError=FLOW
  object errorProne236 extends TestRootModule with JavaModule with ErrorProneModule {
    override def errorProneVersion: T[String] = Task { "2.36.0" }
    override def errorProneOptions: T[Seq[String]] = Task {
      Seq("-XepAllErrorsAsWarnings")
    }
    lazy val millDiscover = Discover[this.type]
  }
  object errorProneOptionalPlugins extends TestRootModule with JavaModule with ErrorProneModule {
    override def errorProneUseNullAway: T[Boolean] = Task { true }
    override def errorProneUsePicnic: T[Boolean] = Task { true }
    lazy val millDiscover = Discover[this.type]
  }
  object errorProneAnnotationProcessorApi extends TestRootModule with JavaModule
      with ErrorProneModule {
    override def annotationProcessorsMvnDeps: T[Seq[mill.javalib.Dep]] = Task {
      Seq(mvn"com.google.auto.value:auto-value:1.11.0")
    }
    lazy val millDiscover = Discover[this.type]
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "errorprone"

  def tests = Tests {
    test("reference") {
      test("compile") {
        UnitTester(noErrorProne, testModuleSourcesPath).scoped { eval =>

          val res = eval(noErrorProne.compile)
          assert(res.isRight)
        }
      }
    }
    test("errorprone") {
      test("compileFail") {
        UnitTester(errorProne, testModuleSourcesPath).scoped { eval =>
          val res = eval(errorProne.compile)
          assert(res.isLeft)
        }
      }
      test("depsByDefault") {
        UnitTester(errorProne, testModuleSourcesPath).scoped { eval =>
          val Right(depsResult) = eval(errorProne.errorProneDeps).runtimeChecked
          val deps = depsResult.value
          assert(deps.length == 1)
          assert(deps.head.formatted.contains("com.google.errorprone:error_prone_core:"))
        }
      }
      test("compileWarn") {
        UnitTester(errorProneCustom, testModuleSourcesPath).scoped { eval =>
          val Right(opts) = eval(errorProneCustom.mandatoryJavacOptions).runtimeChecked
          assert(opts.value.exists(_.contains("-XepAllErrorsAsWarnings")))
          if (Properties.isJavaAtLeast(16)) {
            assert(!opts.value.exists(opt =>
              opt.startsWith("--add-exports") || opt.startsWith("--add-opens")
            ))
          }
          val Right(jvmOptions) = eval(errorProneCustom.javaCompilerRuntimeOptions).runtimeChecked
          if (Properties.isJavaAtLeast(16)) {
            assert(
              jvmOptions.value.exists(opt =>
                opt.startsWith("--add-exports") || opt.startsWith("--add-opens")
              )
            )
          }
          val res = eval(errorProneCustom.compile)
          assert(res.isRight)
        }
      }
      test("shouldStopOption236") {
        // ErrorProne 2.36.0+ requires --should-stop=ifError=FLOW
        // See https://github.com/com-lihaoyi/mill/issues/4926
        UnitTester(errorProne236, testModuleSourcesPath).scoped { eval =>
          val Right(opts) = eval(errorProne236.mandatoryJavacOptions).runtimeChecked
          assert(opts.value.contains("--should-stop=ifError=FLOW"))
          val res = eval(errorProne236.compile)
          assert(res.isRight)
        }
      }
      test("optionalPlugins") {
        UnitTester(errorProneOptionalPlugins, testModuleSourcesPath).scoped { eval =>
          val Right(depsResult) = eval(errorProneOptionalPlugins.errorProneDeps).runtimeChecked
          val formatted = depsResult.value.map(_.formatted)
          assert(
            formatted.exists(_.startsWith("com.uber.nullaway:nullaway:"))
          )
          val picnicContrib = formatted.exists(
            _.startsWith("tech.picnic.error-prone-support:error-prone-contrib:")
          )
          val picnicRefaster = formatted.exists(
            _.startsWith("tech.picnic.error-prone-support:refaster-runner:")
          )
          assert(
            picnicContrib && picnicRefaster
          )
        }
      }
    }
    test("annotationProcessorApi") {
      UnitTester(errorProneAnnotationProcessorApi, testModuleSourcesPath).scoped { eval =>
        val Right(opts) =
          eval(errorProneAnnotationProcessorApi.annotationProcessorsJavacOptions).runtimeChecked
        assert(opts.value.isEmpty)
        val Right(classpath) =
          eval(errorProneAnnotationProcessorApi.errorProneClasspath).runtimeChecked
        assert(classpath.value.exists(_.path.toString.contains("auto-value-1.11.0")))
      }
    }
  }
}
