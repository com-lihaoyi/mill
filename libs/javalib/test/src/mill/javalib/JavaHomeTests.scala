package mill.javalib

import mill.api.{Args, Discover, Task}
import mill.api.ExecResult
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object JavaHome11Tests extends JavaHomeTests("temurin:11.0.24", "11.", Seq[Byte](0, 0, 0, 55))
object JavaHome17Tests extends JavaHomeTests("temurin:17.0.9", "17.", Seq[Byte](0, 0, 0, 61))
trait JavaHomeTests(jvmVersion0: String, expectedPrefix: String, expectedBytes: Seq[Byte])
    extends TestSuite {

  private def findClassBytes(classesPath: os.Path, className: String): Option[Array[Byte]] = {
    if (os.isDir(classesPath)) {
      os.walk(classesPath).find(_.last == className).map(os.read.bytes(_))
    } else {
      val zip = new java.util.zip.ZipFile(classesPath.toIO)
      try {
        val entries = zip.entries()
        var result: Option[Array[Byte]] = None
        while (entries.hasMoreElements && result.isEmpty) {
          val e = entries.nextElement()
          if (e.getName.endsWith(className)) result = Some(zip.getInputStream(e).readAllBytes())
        }
        result
      } finally zip.close()
    }
  }

  object HelloJavaJavaHome11Override extends TestRootModule {
    object core extends JavaModule {
      def jvmVersion = jvmVersion0
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def tests: Tests = Tests {
    test("javaHome") {
      UnitTester(HelloJavaJavaHome11Override, resourcePath).scoped { eval =>

        val Right(result) = eval.apply(HelloJavaJavaHome11Override.core.compile).runtimeChecked

        val coreClassBytes = findClassBytes(result.value.classes.path, "Core.class")

        assert(
          coreClassBytes.isDefined,

          // The first eight bytes are magic numbers followed by two bytes for major version and two bytes for minor version
          // We are overriding to java 11 which corresponds to class file version 55
          coreClassBytes.get.slice(4, 8).toSeq == expectedBytes
        )

        val path = eval.evaluator.workspace / "java.version"
        val Right(_) =
          eval.apply(HelloJavaJavaHome11Override.core.run(Task.Anon(Args(path)))).runtimeChecked

        assert(
          os.read(path).startsWith(expectedPrefix)
        )

        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJavaJavaHome11Override.core.test.testForked()).runtimeChecked

        //        assert(
        //          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
        //          v1._2(0).status == "Success",
        //          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
        //          v1._2(1).status == "Failure"
        //        )
      }
    }
  }
}
