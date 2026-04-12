package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object IncrementalAnnotationProcessingTests extends UtestIntegrationTestSuite {
  override protected def allowSharedOutputDir: Boolean = false

  val tests: Tests = Tests {
    test("mapstruct") - integrationTest {
      tester =>
        import tester.*

        val generatedMapper =
          workspacePath / "out/mapstruct/compile.dest/classes/example/CarMapperImpl.class"
        val helperClass = workspacePath / "out/mapstruct/compile.dest/classes/example/Helper.class"

        val first = eval("mapstruct.compile")
        if (!first.isSuccess) throw new java.lang.AssertionError(first.debugString)
        assert(os.exists(generatedMapper), os.exists(helperClass))

        val helperStatBefore = os.stat(helperClass)

        os.remove(workspacePath / "mapstruct/src/example/CarMapper.java")

        val second = eval("mapstruct.compile")
        if (!second.isSuccess) throw new java.lang.AssertionError(second.debugString)
        assert(!os.exists(generatedMapper))
        assert(os.exists(helperClass))
        assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }

    test("autoservice") - integrationTest {
      tester =>
        import tester.*

        val serviceFile =
          workspacePath / "out/autoservice/compile.dest/classes/META-INF/services/example.GreetingProvider"
        val helperClass =
          workspacePath / "out/autoservice/compile.dest/classes/example/Helper.class"

        val first = eval("autoservice.compile")
        if (!first.isSuccess) throw new java.lang.AssertionError(first.debugString)
        assert(os.exists(serviceFile), os.exists(helperClass))

        val helperStatBefore = os.stat(helperClass)

        os.remove(workspacePath / "autoservice/src/example/DefaultGreetingProvider.java")

        val second = eval("autoservice.compile")
        if (!second.isSuccess) throw new java.lang.AssertionError(second.debugString)
        assert(!os.exists(serviceFile))
        assert(os.exists(helperClass))
        assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }

    test("autoserviceDisable") - integrationTest { tester =>
      import tester.*

      val serviceFile =
        workspacePath / "out/autoservice/compile.dest/classes/META-INF/services/example.GreetingProvider"

      val first = eval("autoservice.compile")
      if (!first.isSuccess) throw new java.lang.AssertionError(first.debugString)
      assert(os.exists(serviceFile))

      val buildFile = workspacePath / "build.mill"
      os.write.over(
        buildFile,
        os.read(buildFile).replace(
          """object autoservice extends JavaModule {
  def mvnDeps = Seq(
    mvn"com.google.auto.service:auto-service-annotations:1.1.1"
  )

  def annotationProcessorsMvnDeps = Seq(
    mvn"com.google.auto.service:auto-service:1.1.1"
  )
}
""",
          """object autoservice extends JavaModule {
  def mvnDeps = Seq(
    mvn"com.google.auto.service:auto-service-annotations:1.1.1"
  )
}
"""
        )
      )

      val second = eval("autoservice.compile")
      if (!second.isSuccess) throw new java.lang.AssertionError(second.debugString)
      assert(!os.exists(serviceFile))
    }

    test("localmeta") - integrationTest { tester =>
      import tester.*

      val generatedResource =
        workspacePath / "out/localmeta/compile.dest/classes/META-INF/incremental/example.Annotated.txt"
      val helperClass = workspacePath / "out/localmeta/compile.dest/classes/example/Helper.class"

      val first = eval("localmeta.compile")
      if (!first.isSuccess) throw new java.lang.AssertionError(first.debugString)
      assert(os.exists(generatedResource), os.exists(helperClass))

      val helperStatBefore = os.stat(helperClass)

      os.remove(workspacePath / "localmeta/src/example/Annotated.java")

      val second = eval("localmeta.compile")
      if (!second.isSuccess) throw new java.lang.AssertionError(second.debugString)
      assert(!os.exists(generatedResource))
      assert(os.exists(helperClass))
      assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }

    test("dagger") - integrationTest { tester =>
      import tester.*

      val generatedComponent =
        workspacePath / "out/dagger/compile.dest/classes/example/DaggerMessageComponent.class"
      val helperClass = workspacePath / "out/dagger/compile.dest/classes/example/Helper.class"

      val first = eval("dagger.compile")
      if (!first.isSuccess) throw new java.lang.AssertionError(first.debugString)
      assert(os.exists(generatedComponent), os.exists(helperClass))

      val helperStatBefore = os.stat(helperClass)

      os.remove(workspacePath / "dagger/src/example/MessageComponent.java")

      val second = eval("dagger.compile")
      if (!second.isSuccess) throw new java.lang.AssertionError(second.debugString)
      assert(!os.exists(generatedComponent))
      assert(os.exists(helperClass))
      assert(os.stat(helperClass).ctime == helperStatBefore.ctime)
    }
  }
}
