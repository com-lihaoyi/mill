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

    object lombok extends JavaModule {
      def mvnDeps = Seq(
        mvn"org.projectlombok:lombok:1.18.38",
        mvn"org.slf4j:slf4j-api:2.0.17"
      )

      override def annotationProcessorsMvnDeps: T[Seq[Dep]] = Task {
        Seq(mvn"org.projectlombok:lombok:1.18.38")
      }
    }

    object localMetadataConfigProcessor extends JavaModule

    object localMetadataConfig extends JavaModule {
      def moduleDeps = Seq(localMetadataConfigProcessor)

      override def javacOptions: T[Seq[String]] = Task {
        super.javacOptions() ++ Seq(
          "-processorpath",
          localMetadataConfigProcessor.compile().classes.path.toString,
          "-processor",
          "example.ResourceProcessor"
        )
      }
    }

    object dynamicProcessor extends JavaModule

    object dynamicmeta extends JavaModule {
      def moduleDeps = Seq(dynamicProcessor)

      override def javacOptions: T[Seq[String]] = Task {
        super.javacOptions() ++ Seq(
          "-processorpath",
          dynamicProcessor.compile().classes.path.toString,
          "-processor",
          "example.DynamicProcessor"
        )
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "incremental-annotation-processing"

  def testEval() = UnitTester(Modules, resourcePath)

  private def mtimeMillis(path: os.Path): Long = os.stat(path).mtime.toMillis

  private def assertUpdated(path: os.Path, before: Long): Unit =
    assert(os.exists(path), mtimeMillis(path) != before)

  private def assertUnchanged(path: os.Path, before: Long): Unit =
    assert(os.exists(path), mtimeMillis(path) == before)

  val tests: Tests = Tests {
    test("mapstruct") {
      val baseOutputs = Seq(
        "Car.class",
        "CarDto.class",
        "CarMapper.class",
        "CarMapperImpl.class",
        "Helper.class",
        "Support.class",
        "Truck.class",
        "TruckDto.class",
        "TruckMapper.class",
        "TruckMapperImpl.class"
      )

      def outputs(eval: mill.testkit.UnitTester): Map[String, os.Path] =
        baseOutputs.map { name =>
          name -> (eval.outPath / os.RelPath(s"mapstruct/compile.dest/classes/example/$name"))
        }.toMap

      def assertScenario(
          eval: mill.testkit.UnitTester,
          mutate: os.Path => Unit,
          updated: Set[String] = Set.empty,
          deleted: Set[String] = Set.empty
      ): Unit = {
        val compiledOutputs = outputs(eval)

        val Right(first) = eval(Modules.mapstruct.compile).runtimeChecked
        assert(first.evalCount > 0, compiledOutputs.values.forall(os.exists))

        val before = compiledOutputs.view.mapValues(mtimeMillis).toMap

        mutate(Modules.mapstruct.moduleDir)

        val Right(second) = eval(Modules.mapstruct.compile).runtimeChecked
        assert(second.evalCount > 0)

        updated.foreach(name => assertUpdated(compiledOutputs(name), before(name)))
        deleted.foreach(name => assert(!os.exists(compiledOutputs(name))))

        val unchanged = compiledOutputs.keySet -- updated -- deleted
        unchanged.foreach(name => assertUnchanged(compiledOutputs(name), before(name)))
      }

      test("deleteCarMapper") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir => os.remove(moduleDir / "src/example/CarMapper.java"),
          deleted = Set("CarMapper.class", "CarMapperImpl.class")
        )
      }

      test("changeCarMapper") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir =>
            os.write.over(
              moduleDir / "src/example/CarMapper.java",
              """package example;
                |
                |import org.mapstruct.Mapper;
                |
                |@Mapper
                |public interface CarMapper {
                |    CarDto map(Car car);
                |    Car map(CarDto carDto);
                |}
                |""".stripMargin
            ),
          updated = Set("CarMapper.class", "CarMapperImpl.class")
        )
      }

      test("deleteTruckMapper") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir => os.remove(moduleDir / "src/example/TruckMapper.java"),
          deleted = Set("TruckMapper.class", "TruckMapperImpl.class")
        )
      }

      test("changeTruckMapper") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir =>
            os.write.over(
              moduleDir / "src/example/TruckMapper.java",
              """package example;
                |
                |import org.mapstruct.Mapper;
                |
                |@Mapper
                |public interface TruckMapper {
                |    TruckDto map(Truck truck);
                |    Truck map(TruckDto truckDto);
                |}
                |""".stripMargin
            ),
          updated = Set("TruckMapper.class", "TruckMapperImpl.class")
        )
      }

      test("deleteHelper") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir => os.remove(moduleDir / "src/example/Helper.java"),
          deleted = Set("Helper.class")
        )
      }

      test("changeHelper") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir =>
            os.write.over(
              moduleDir / "src/example/Helper.java",
              """package example;
                |
                |public class Helper {
                |    public static String value() {
                |        return "helper changed";
                |    }
                |}
                |""".stripMargin
            ),
          updated = Set("Helper.class")
        )
      }

      test("deleteSupport") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir => os.remove(moduleDir / "src/example/Support.java"),
          deleted = Set("Support.class")
        )
      }

      test("changeSupport") - testEval().scoped { eval =>
        assertScenario(
          eval,
          moduleDir =>
            os.write.over(
              moduleDir / "src/example/Support.java",
              """package example;
                |
                |public class Support {
                |    public static String value() {
                |        return "support changed";
                |    }
                |}
                |""".stripMargin
            ),
          updated = Set("Support.class")
        )
      }
    }

    test("autoservice") - testEval().scoped { eval =>
      val serviceFile =
        eval.outPath / "autoservice/compile.dest/classes/META-INF/services/example.GreetingProvider"
      val helperClass =
        eval.outPath / "autoservice/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.autoservice.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(serviceFile), os.exists(helperClass))

      os.remove(Modules.autoservice.moduleDir / "src/example/DefaultGreetingProvider.java")

      val Right(second) = eval(Modules.autoservice.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(serviceFile))
      assert(os.exists(helperClass))
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

    test("lombok") - testEval().scoped { eval =>
      val appClass = eval.outPath / "lombok/compile.dest/classes/example/App.class"

      val Right(first) = eval(Modules.lombok.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(appClass))

      val before = mtimeMillis(appClass)
      os.write.over(
        Modules.lombok.moduleDir / "src/example/App.java",
        """package example;
          |
          |import lombok.extern.slf4j.Slf4j;
          |
          |@Slf4j
          |public class App {
          |    public static void main(String[] args) {
          |        LOGGER.info("hello again");
          |    }
          |}
          |""".stripMargin
      )

      val Right(second) = eval(Modules.lombok.compile).runtimeChecked
      assert(second.evalCount > 0)
      assertUpdated(appClass, before)
    }

    test("localMetadataConfig") - testEval().scoped { eval =>
      val generatedResourceOne =
        eval.outPath / "localMetadataConfig/compile.dest/classes/META-INF/incremental/example.Annotated.txt"
      val generatedResourceTwo =
        eval.outPath / "localMetadataConfig/compile.dest/classes/META-INF/incremental/example.AnnotatedTwo.txt"
      val helperClass =
        eval.outPath / "localMetadataConfig/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.localMetadataConfig.compile).runtimeChecked
      assert(
        first.evalCount > 0,
        os.exists(generatedResourceOne),
        os.exists(generatedResourceTwo),
        os.exists(helperClass)
      )

      os.remove(Modules.localMetadataConfig.moduleDir / "src/example/Annotated.java")

      val Right(second) = eval(Modules.localMetadataConfig.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(!os.exists(generatedResourceOne))
      assert(os.exists(generatedResourceTwo))
      assert(os.exists(helperClass))
    }

    test("localMetadataConfigRetainsUntouchedOutputs") - testEval().scoped { eval =>
      val generatedResourceOne =
        eval.outPath / "localMetadataConfig/compile.dest/classes/META-INF/incremental/example.Annotated.txt"
      val generatedResourceTwo =
        eval.outPath / "localMetadataConfig/compile.dest/classes/META-INF/incremental/example.AnnotatedTwo.txt"

      val Right(first) = eval(Modules.localMetadataConfig.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(generatedResourceOne), os.exists(generatedResourceTwo))

      os.write.over(
        Modules.localMetadataConfig.moduleDir / "src/example/Annotated.java",
        """package example;
          |
          |@Generate
          |public class Annotated {
          |    public static String value() {
          |        return "changed";
          |    }
          |}
          |""".stripMargin
      )

      val Right(second) = eval(Modules.localMetadataConfig.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(os.exists(generatedResourceOne))
      assert(os.exists(generatedResourceTwo))

      os.remove(Modules.localMetadataConfig.moduleDir / "src/example/AnnotatedTwo.java")

      val Right(third) = eval(Modules.localMetadataConfig.compile).runtimeChecked
      assert(third.evalCount > 0)
      assert(!os.exists(generatedResourceTwo))
      assert(os.exists(generatedResourceOne))
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

    test("dynamic") - testEval().scoped { eval =>
      val generatedResource =
        eval.outPath / "dynamicmeta/compile.dest/classes/META-INF/dynamic/all.txt"
      val helperClass = eval.outPath / "dynamicmeta/compile.dest/classes/example/Helper.class"

      val Right(first) = eval(Modules.dynamicmeta.compile).runtimeChecked
      assert(first.evalCount > 0, os.exists(generatedResource), os.exists(helperClass))
      val firstContent = os.read(generatedResource)
      assert(firstContent.contains("example.Annotated"))
      assert(firstContent.contains("example.AnnotatedTwo"))

      os.remove(Modules.dynamicmeta.moduleDir / "src/example/Annotated.java")

      val Right(second) = eval(Modules.dynamicmeta.compile).runtimeChecked
      assert(second.evalCount > 0)
      assert(os.exists(generatedResource))
      val secondContent = os.read(generatedResource)
      assert(!secondContent.contains("example.Annotated\n"))
      assert(!secondContent.endsWith("example.Annotated"))
      assert(secondContent.contains("example.AnnotatedTwo"))
      assert(os.exists(helperClass))
    }
  }
}
