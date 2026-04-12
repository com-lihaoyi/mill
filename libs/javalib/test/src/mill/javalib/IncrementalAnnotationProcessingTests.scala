package mill.javalib

import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import mill.{T, Task}
import utest.*

object IncrementalAnnotationProcessingTests extends TestSuite {

  object Modules extends TestRootModule {
    object mapstruct extends JavaModule {
      def mvnDeps = Seq(
        mvn"org.mapstruct:mapstruct:1.6.3"
      )

      def annotationProcessorsMvnDeps = Seq(
        mvn"org.mapstruct:mapstruct-processor:1.6.3"
      )
    }

    object dagger extends JavaModule {
      def mvnDeps = Seq(
        mvn"com.google.dagger:dagger:2.57",
        mvn"javax.inject:javax.inject:1"
      )

      def annotationProcessorsMvnDeps = Seq(
        mvn"com.google.dagger:dagger-compiler:2.57"
      )
    }

    object autoservice extends JavaModule {
      def mvnDeps = Seq(
        mvn"com.google.auto.service:auto-service-annotations:1.1.1"
      )

      override def annotationProcessorsMvnDeps: T[Seq[Dep]] = Task {
        if (os.exists(mill.api.BuildCtx.workspaceRoot / "autoservice-enabled"))
          Seq(mvn"com.google.auto.service:auto-service:1.1.1")
        else Seq.empty
      }
    }

    object localmetaProcessor extends JavaModule

    object localmeta extends JavaModule {
      def moduleDeps = Seq(localmetaProcessor)

      override def javacOptions: T[Seq[String]] = Task {
        super.javacOptions() ++ Seq(
          "-processorpath",
          localmetaProcessor.compile().classes.path.toString,
          "-processor",
          "example.ResourceProcessor"
        )
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "incremental-annotation-processing"

  def testEval() = UnitTester(Modules, resourcePath)

  val tests: Tests = Tests {
    test("mapstruct") - testEval().scoped { eval =>
      val generatedMapper = eval.outPath / "mapstruct/compile.dest/classes/example/CarMapperImpl.class"
      val helperClass = eval.outPath / "mapstruct/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.mapstruct.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(generatedMapper), os.exists(helperClass))

      val helperStatBefore = os.stat(helperClass)
      os.remove(Modules.mapstruct.moduleDir / "src/example/CarMapper.java")

      val Right(second) = eval(Modules.mapstruct.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(generatedMapper))
      assert(os.exists(helperClass))
      assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }

    test("autoservice") - testEval().scoped { eval =>
      val serviceFile =
        eval.outPath / "autoservice/compile.dest/classes/META-INF/services/example.GreetingProvider"
      val helperClass =
        eval.outPath / "autoservice/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.autoservice.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(serviceFile), os.exists(helperClass))

      val helperStatBefore = os.stat(helperClass)
      os.remove(Modules.autoservice.moduleDir / "src/example/DefaultGreetingProvider.java")

      val Right(second) = eval(Modules.autoservice.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(serviceFile))
      assert(os.exists(helperClass))
      assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }

    test("autoserviceDisable") - testEval().scoped { eval =>
      val serviceFile =
        eval.outPath / "autoservice/compile.dest/classes/META-INF/services/example.GreetingProvider"

      val Right(first) = eval(Modules.autoservice.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(serviceFile))

      os.write.over(
        Modules.autoservice.moduleDir / "src/example/DefaultGreetingProvider.java",
        """package example;
          |
          |public class DefaultGreetingProvider {
          |    public static String value() {
          |        return "no processor";
          |    }
          |}
          |""".stripMargin
      )
      os.remove(eval.evaluator.workspace / "autoservice-enabled")

      val Right(second) = eval(Modules.autoservice.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(serviceFile))
    }

    test("localmeta") - testEval().scoped { eval =>
      val generatedResource =
        eval.outPath / "localmeta/compile.dest/classes/META-INF/incremental/example.Annotated.txt"
      val helperClass = eval.outPath / "localmeta/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.localmeta.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(generatedResource), os.exists(helperClass))

      val helperStatBefore = os.stat(helperClass)
      os.remove(Modules.localmeta.moduleDir / "src/example/Annotated.java")

      val Right(second) = eval(Modules.localmeta.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(generatedResource))
      assert(os.exists(helperClass))
      assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }

    test("dagger") - testEval().scoped { eval =>
      val generatedComponent =
        eval.outPath / "dagger/compile.dest/classes/example/DaggerMessageComponent.class"
      val helperClass = eval.outPath / "dagger/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.dagger.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(generatedComponent), os.exists(helperClass))

      val helperStatBefore = os.stat(helperClass)
      os.remove(Modules.dagger.moduleDir / "src/example/MessageComponent.java")

      val Right(second) = eval(Modules.dagger.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(generatedComponent))
      assert(os.exists(helperClass))
      assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }
  }
}
