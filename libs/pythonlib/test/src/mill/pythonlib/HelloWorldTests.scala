package mill
package pythonlib

import mill.api.{BuildCtx, Discover, PathRef}
import mill.javalib.publish.License
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.util.zip.{GZIPInputStream, ZipFile}

object HelloWorldTests extends TestSuite {

  object HelloWorldPython extends TestRootModule {
    object foo extends PythonModule {
      override def moduleDeps: Seq[PythonModule] = Seq(bar)
      object bar extends PythonModule
    }

    object qux extends PythonModule {
      override def moduleDeps: Seq[PythonModule] = Seq(foo)
      override def mainScript = Task.Source("src/qux.py")
      object test extends PythonTests with TestModule.Unittest
    }

    object indexed extends PythonModule {
      override def indexes = Task {
        Seq("company=https://primary.example/simple", "https://fallback.example/simple")
      }

      object test extends PythonTests
    }

    object multiSourceTests extends PythonModule with TestModule.Unittest with CoverageTests {
      override def sources = Task.Sources("multi-test/src1", "multi-test/src2")
    }

    object bare extends PythonModule.Bare {
      override def pythonProjectDeps = Task.Sources(BuildCtx.workspaceRoot / "raw-project")
      object nested extends PythonModule.Bare
    }

    object rawProjectConsumer extends PythonModule {
      override def pythonProjectDeps = Task.Sources(BuildCtx.workspaceRoot / "raw-project")
      object test extends PythonTests with TestModule.Unittest with CoverageTests
    }

    object bareTests extends PythonModule.Bare with TestModule.Unittest

    object regularPackage extends PythonModule {
      object test extends PythonTests with PythonModule.Bare with TestModule.Unittest
    }

    object missingTests extends PythonModule with TestModule.Unittest

    object missingPytest extends PythonModule with TestModule.Pytest

    object workspaceBare extends PythonModule.Bare {
      override def moduleDir = super.moduleDir / os.up
    }

    object bare_publish extends PythonModule.Bare with PublishModule {
      override def pythonProjectDeps = Task.Sources(BuildCtx.workspaceRoot / "raw-project")

      override def publishMeta = PublishMeta(
        name = "bare-publish",
        description = "bare publishing test",
        requiresPython = ">= 3.12",
        license = License.MIT,
        authors = Seq(Developer("Mill", "mill@example.com"))
      )
      override def publishVersion = "0.1.0"
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-python"
  def tests: Tests = Tests {
    test("run") {
      val baos = ByteArrayOutputStream()
      UnitTester(HelloWorldPython, resourcePath, outStream = PrintStream(baos)).scoped { eval =>

        val Right(_) = eval.apply(HelloWorldPython.qux.run(Args())).runtimeChecked

        assert(baos.toString().contains("Hello,  Qux!\n"))
      }
    }

    test("test") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>

        val result = eval.apply(HelloWorldPython.qux.test.testForked())
        assert(result.isRight)
      }
    }

    test("typeCheck") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>
        val result = eval.apply(HelloWorldPython.qux.typeCheck)
        assert(result.isRight)
      }
    }

    test("uvInstallArgs") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldPython.qux.uvInstallArgs).runtimeChecked
        val args = result.value.args
        assert(
          args.contains("--default-index"),
          !args.exists(_.startsWith("ty")),
          !args.exists(_.startsWith("pex")),
          !args.exists(_.startsWith("mypy"))
        )
      }
    }

    test("installArgsTrackLocalProjectContents") {
      val project = os.temp.dir()
      val metadata = project / "pyproject.toml"
      os.write(metadata, "[project]\nname = \"local-project\"\nversion = \"1\"\n")
      val before = UvModule.InstallArgs(Seq(project.toString), Seq(PathRef(project))).sig
      os.write.over(metadata, "[project]\nname = \"local-project\"\nversion = \"2\"\n")
      val after = UvModule.InstallArgs(Seq(project.toString), Seq(PathRef(project))).sig
      assert(before != after)
    }

    test("uvIndexPriority") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldPython.indexed.uvInstallArgs).runtimeChecked
        val Right(testResult) =
          eval.apply(HelloWorldPython.indexed.test.uvInstallArgs).runtimeChecked
        assert(
          result.value.args.take(4) == Seq(
            "--index",
            "company=https://primary.example/simple",
            "--default-index",
            "https://fallback.example/simple"
          ),
          testResult.value.args.take(4) == result.value.args.take(4)
        )
      }
    }

    test("publishMetadataEncoding") {
      assert(
        PublishModule.normalizeModuleName("Foo.Bar-Baz") == "foo_bar_baz",
        PublishModule.tomlString("quote \" slash \\ newline\n") ==
          "\"quote \\\" slash \\\\ newline\\n\"",
        PublishModule.uvBuildPythonArgs("3.13") == Seq("--python", "3.13")
      )
    }

    test("processEnvironment") {
      assert(
        PythonModule.pathEnvironmentValue(Map("Path" -> "windows-path")) == "windows-path",
        PythonModule.uvEnvironment(
          Map("UV_OFFLINE" -> "default", "DEFAULT" -> "1"),
          Map("UV_OFFLINE" -> "false", "FORK" -> "1"),
          offline = true
        ) == Map("UV_OFFLINE" -> "1", "DEFAULT" -> "1", "FORK" -> "1")
      )
    }

    test("uvDistributions") {
      assert(
        UvModule.distribution("Mac OS X", "aarch64").map(_.archiveName) ==
          Right("uv-aarch64-apple-darwin.tar.gz"),
        UvModule.distribution("Linux", "amd64").map(_.archiveName) ==
          Right("uv-x86_64-unknown-linux-musl.tar.gz"),
        UvModule.distribution("Linux", "arm64").map(_.archiveName) ==
          Right("uv-aarch64-unknown-linux-musl.tar.gz"),
        UvModule.distribution("Linux", "i686").map(_.archiveName) ==
          Right("uv-i686-unknown-linux-musl.tar.gz"),
        UvModule.distribution("Linux", "armv7l").map(_.archiveName) ==
          Right("uv-armv7-unknown-linux-musleabihf.tar.gz"),
        UvModule.distribution("Linux", "riscv64").map(_.archiveName) ==
          Right("uv-riscv64gc-unknown-linux-gnu.tar.gz"),
        UvModule.distribution("Linux", "ppc64").isLeft,
        UvModule.distribution("Linux", "ppc64le").map(_.archiveName) ==
          Right("uv-powerpc64le-unknown-linux-gnu.tar.gz"),
        UvModule.distribution("Linux", "s390x").map(_.archiveName) ==
          Right("uv-s390x-unknown-linux-gnu.tar.gz"),
        UvModule.distribution("Windows 11", "x86_64").map(_.archiveName) ==
          Right("uv-x86_64-pc-windows-msvc.zip"),
        UvModule.distribution("Windows 11", "x86_64").map(_.executableRelativeSegments) ==
          Right(Seq("uv.exe")),
        UvModule.distribution("Mac OS X", "aarch64").map(_.executableRelativeSegments) ==
          Right(Seq("uv-aarch64-apple-darwin", "uv")),
        UvModule.distribution("Windows 11", "i386").map(_.archiveName) ==
          Right("uv-i686-pc-windows-msvc.zip"),
        UvModule.distribution("Plan 9", "amd64").isLeft,
        UvModule.distribution("Linux", "sparc").isLeft
      )
    }

    test("multiSourceUnittestAndCoverage") {
      val baos = ByteArrayOutputStream()
      UnitTester(HelloWorldPython, resourcePath, outStream = PrintStream(baos)).scoped { eval =>
        val result = eval.apply(HelloWorldPython.multiSourceTests.coverageReport())
        assert(result.isRight)
        val output = baos.toString.replace('\\', '/')
        assert(
          output.contains("src1/test_duplicate.py"),
          output.contains("src1/test_first.py"),
          output.contains("src2/test_duplicate.py"),
          output.contains("src2/test_second.py")
        )
      }
    }

    test("bareModules") {
      val baos = ByteArrayOutputStream()
      val outputStream = PrintStream(baos)
      UnitTester(
        HelloWorldPython,
        resourcePath,
        outStream = outputStream,
        errStream = outputStream
      ).scoped { eval =>
        val Right(parentSources) = eval.apply(HelloWorldPython.bare.sources).runtimeChecked
        val Right(nestedSources) = eval.apply(HelloWorldPython.bare.nested.sources).runtimeChecked
        val relativeParentSources =
          parentSources.value.map(_.path.relativeTo(HelloWorldPython.bare.moduleDir).toString)
        val relativeNestedSources =
          nestedSources.value.map(
            _.path.relativeTo(HelloWorldPython.bare.nested.moduleDir).toString
          )
        assert(
          relativeParentSources == Seq("main.py", "message.py"),
          relativeNestedSources == Seq("main.py", "message.py")
        )

        assert(eval.apply(HelloWorldPython.bare.run(Args())).isRight)
        assert(eval.apply(HelloWorldPython.bare.nested.run(Args())).isRight)
        assert(eval.apply(HelloWorldPython.rawProjectConsumer.run(Args())).isRight)
        assert(eval.apply(HelloWorldPython.rawProjectConsumer.test.coverageReport()).isRight)
        assert(eval.apply(HelloWorldPython.bareTests.testForked()).isRight)
        assert(eval.apply(HelloWorldPython.regularPackage.test.testForked()).isRight)
        val output = baos.toString
        def occurrences(needle: String) = output.sliding(needle.length).count(_ == needle)
        assert(
          output.contains("Hello from the bare parent!"),
          output.contains("Hello from the nested bare module!"),
          output.contains("Hello from a raw uv project!"),
          output.contains("test_add"),
          output.contains("test_bare_suite_below_regular_package"),
          occurrences("test_parent_common (") == 1,
          occurrences("test_nested_common (") == 1
        )
      }
    }

    test("bareSourceExclusionsAndMissingTests") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>
        val workspaceRoot = HelloWorldPython.workspaceBare.moduleDir
        val staleBspSource = workspaceRoot / ".bsp/out/stale.py"
        os.write.over(staleBspSource, "raise AssertionError()", createFolders = true)
        val Right(workspaceSources) =
          eval.apply(HelloWorldPython.workspaceBare.sources).runtimeChecked
        assert(!workspaceSources.value.exists(_.path.startsWith(workspaceRoot / ".bsp/out")))

        assert(eval.apply(HelloWorldPython.missingTests.testForked()).isLeft)
        assert(eval.apply(HelloWorldPython.missingPytest.testForked()).isLeft)
        assert(
          eval.apply(HelloWorldPython.missingPytest.testForked("-k", "anything")).isLeft
        )
      }
    }

    test("barePublishing") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>
        val Right(installArgs) =
          eval.apply(HelloWorldPython.bare_publish.uvInstallArgs).runtimeChecked
        assert(
          installArgs.value.args.exists(_.replace('\\', '/').endsWith("/raw-project"))
        )

        val Right(sdist) = eval.apply(HelloWorldPython.bare_publish.sdist).runtimeChecked
        val gzip = GZIPInputStream(os.read.inputStream(sdist.value.path))
        val sdistContents =
          try {
            String(gzip.readAllBytes(), StandardCharsets.ISO_8859_1)
          } finally gzip.close()
        assert(sdistContents.contains("/src/bare_publish/__init__.py"))

        val Right(wheel) = eval.apply(HelloWorldPython.bare_publish.wheel).runtimeChecked
        val zip = ZipFile(wheel.value.path.toIO)
        val wheelEntries =
          try {
            val entries = zip.entries()
            Iterator.continually(
              entries
            ).takeWhile(_.hasMoreElements).map(_.nextElement().getName).toSeq
          } finally zip.close()
        assert(wheelEntries.contains("bare_publish/__init__.py"))
      }
    }
  }
}
