package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

object InspectTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      val res = eval(("inspect", "core.test.mvnDeps"))
      assert(res.isSuccess == true)

      val inheritedMvnDeps = out("inspect").json.str
      assertGoldenLiteral(
        inheritedMvnDeps,
        """core.test.mvnDeps(build.mill:10)
          |    Overridden mvnDeps Docs!!!
          |
          |    Any ivy dependencies you want to add to this Module, in the format
          |    mvn"org::name:version" for Scala dependencies or mvn"org:name:version"
          |    for Java dependencies
          |
          |Inputs:
          |""".stripMargin
      )

      assert(eval(("inspect", "core.task")).isSuccess)
      val task = out("inspect").json.str
      assertGoldenLiteral(
        task,
        """core.task(build.mill:48)
          |    Core Task Docz!
          |
          |Inputs:
          |""".stripMargin
      )

      assert(eval(("inspect", "inspect")).isSuccess)
      val doc = out("inspect").json.str
      assertGoldenLiteral(
        doc.replaceAll("\\d+", "..."),
        """inspect(MainModule.scala:...)
          |    Displays metadata about the given task without actually running it.
          |
          |    tasks <str>...
          |
          |Inputs:
          |""".stripMargin
      )

      val res2 = eval(("inspect", "core.run"))
      assert(res2.isSuccess)
      val run = out("inspect").json.str

      assertGoldenLiteral(
        run.replaceAll("\\d+", "..."),
        """core.run(RunModule.scala:...)
          |    Runs this module's code in a subprocess and waits for it to finish
          |
          |    args <str>...
          |
          |Inputs:
          |    core.finalMainClassOpt
          |    core.runClasspath
          |    core.forkArgs
          |    core.allForkEnv
          |    core.runUseArgsFile
          |    core.javaHome
          |    core.propagateEnv
          |    core.finalMainClass
          |    core.forkWorkingDir
          |""".stripMargin
      )

      assert(eval(("inspect", "core.showMvnDepsTree")).isSuccess)

      val mvnDepsTree = out("inspect").json.str

      assertGoldenLiteral(
        mvnDepsTree.replaceAll("\\d+", "..."),
        """core.showMvnDepsTree(JavaModule.scala:...)
          |    Command to print the transitive dependency tree to STDOUT.
          |
          |    --inverse                Invert the tree representation, so that the root is on the bottom val
          |                             inverse (will be forced when used with whatDependsOn)
          |    --what-depends-on <str>  Possible list of modules (org:artifact) to target in the tree in order
          |                             to see where a dependency stems from.
          |    --with-compile           Include the compile-time only dependencies (`compileMvnDeps`, provided
          |                             scope) into the tree.
          |    --with-runtime           Include the runtime dependencies (`runMvnDeps`, runtime scope) into the
          |                             tree.
          |
          |Inputs:
          |    core.mvnDeps
          |    core.mandatoryMvnDeps
          |    core.compileMvnDeps
          |    core.runMvnDeps
          |    core.bomMvnDeps
          |    core.depManagement
          |    core.repositories
          |    core.checkGradleModules
          |""".stripMargin
      )

      assert(eval(("inspect", "core.test.theWorker")).isSuccess)
      val theWorkerInspect = out("inspect").json.str

      assertGoldenLiteral(
        theWorkerInspect,
        """core.test.theWorker(build.mill:38)
          |    -> The worker <-
          |
          |    *The worker*
          |
          |Inputs:
          |""".stripMargin
      )

      // Make sure both kebab-case and camelCase flags work, even though the
      // docs from `inspect` only show the kebab-case version
      assert(eval(("core.showMvnDepsTree", "--withCompile", "--withRuntime")).isSuccess)
      assert(eval(("core.showMvnDepsTree", "--with-compile", "--with-runtime")).isSuccess)

      val basic = eval(("inspect", "basic"))
      assert(basic.isSuccess)
      val basicInspect = out("inspect").json.str
      assertGoldenLiteral(
        basicInspect,
        """basic(build.mill:25)
          |
          |Inherited Modules:
          |""".stripMargin
      )

      assert(eval(("inspect", "core")).isSuccess)
      val coreInspect = out("inspect").json.str
      assertGoldenLiteral(
        coreInspect,
        """core(build.mill:30)
          |    The Core Module Docz!
          |
          |Inherited Modules:
          |    mill.javalib.CoursierModule
          |    mill.javalib.JavaHomeModule
          |    mill.javalib.WithJvmWorkerModule
          |    mill.javalib.bsp.BspModule
          |    mill.javalib.RunModule
          |    mill.javalib.GenIdeaModule
          |    mill.javalib.OfflineSupportModule
          |    mill.javalib.SemanticDbJavaModule
          |    mill.javalib.AssemblyModule
          |    mill.javalib.JavaModule
          |
          |Default Task: core.run
          |
          |Tasks (re-/defined):
          |    core.task
          |""".stripMargin
      )

      assert(eval(("inspect", "MyJavaTaskModule")).isSuccess)
      val jtmInspect = out("inspect").json.str
      assertGoldenLiteral(
        jtmInspect,
        """MyJavaTaskModule(build.mill:53)
          |
          |Inherited Modules:
          |    mill.javalib.CoursierModule
          |    mill.javalib.JavaHomeModule
          |    mill.javalib.WithJvmWorkerModule
          |    mill.javalib.bsp.BspModule
          |    mill.javalib.RunModule
          |    mill.javalib.GenIdeaModule
          |    mill.javalib.OfflineSupportModule
          |    mill.javalib.SemanticDbJavaModule
          |    mill.javalib.AssemblyModule
          |    mill.javalib.JavaModule
          |
          |Module Dependencies:
          |    core
          |    core2
          |
          |Default Task: MyJavaTaskModule.run
          |
          |Tasks (re-/defined):
          |    MyJavaTaskModule.lineCount
          |    MyJavaTaskModule.task
          |""".stripMargin
      )

      val core3Res = eval(("inspect", "core3"))
      assert(core3Res.isSuccess)
      val core3Inspect = out("inspect").json.str
      assertGoldenLiteral(
        core3Inspect,
        """core3(core3/package.mill:6)
          |    Subfolder Module Scaladoc
          |
          |Inherited Modules:
          |    build_.core3.package_
          |
          |Default Task: core3.run
          |""".stripMargin
      )

      val overrideRes = eval(("inspect", "configoverride.mvnDeps"))
      assert(overrideRes.isSuccess)
      val overrideInspect = out("inspect").json.str
      assertGoldenLiteral(
        overrideInspect
          .replaceAll("JavaModule.scala:\\d+", "JavaModule.scala:..."),
        """configoverride.mvnDeps(configoverride/package.mill.yaml:3)
        |    Any ivy dependencies you want to add to this Module, in the format
        |    mvn"org::name:version" for Scala dependencies or mvn"org:name:version"
        |    for Java dependencies
        |
        |Inputs:
        |""".stripMargin
      )

      val scriptOverrideRes = eval(("inspect", "configoverride/Script.scala:mvnDeps"))
      assert(scriptOverrideRes.isSuccess)
      val scriptOverrideInspect = out("inspect").json.str
      assertGoldenLiteral(
        scriptOverrideInspect
          .replaceAll("JavaModule.scala:\\d+", "JavaModule.scala:..."),
        """configoverride/Script.scala:mvnDeps(configoverride/Script.scala:1)
        |    Any ivy dependencies you want to add to this Module, in the format
        |    mvn"org::name:version" for Scala dependencies or mvn"org:name:version"
        |    for Java dependencies
        |
        |Inputs:
        |""".stripMargin
      )
    }
  }
}
