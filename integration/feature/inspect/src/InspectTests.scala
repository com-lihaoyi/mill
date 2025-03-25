package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object InspectTests extends UtestIntegrationTestSuite {
  def globMatches(glob: String, input: String): Boolean = {
    StringContext
      .glob(
        // Normalize the line separator to be `\n` for comparisons
        glob.stripMargin.linesIterator.mkString("\n").split("\\.\\.\\.").toIndexedSeq,
        input.linesIterator.mkString("\n")
      )
      .isDefined
  }
  def assertGlobMatches(glob: String, input: String): Unit = {
    val matches = globMatches(glob, input)
    if (!matches) Console.err.println("[[[" + input.split("\n").mkString("$\n") + "]]]")
    assert(globMatches(glob, input))
  }

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      val res = eval(("inspect", "core.test.ivyDeps"))
      assert(res.isSuccess == true)

      val inheritedIvyDeps = out("inspect").json.str
      assertGlobMatches(
        """core.test.ivyDeps(build.mill:11)
          |    Overridden ivyDeps Docs!!!
          |
          |    Any ivy dependencies you want to add to this Module, in the format
          |    ivy"org::name:version" for Scala dependencies or ivy"org:name:version"
          |    for Java dependencies
          |
          |Inputs:
          |""".stripMargin,
        inheritedIvyDeps
      )

      assert(eval(("inspect", "core.task")).isSuccess)
      val task = out("inspect").json.str
      assertGlobMatches(
        """core.task(build.mill:49)
          |    Core Task Docz!
          |
          |Inputs:
          |""",
        task
      )

      assert(eval(("inspect", "inspect")).isSuccess)
      val doc = out("inspect").json.str
      assertGlobMatches(
        """inspect(MainModule.scala:...)
          |    Displays metadata about the given task without actually running it.
          |
          |    tasks <str>...
          |
          |Inputs:
          |""".stripMargin,
        doc
      )

      val res2 = eval(("inspect", "core.run"))
      assert(res2.isSuccess)
      val run = out("inspect").json.str

      assertGlobMatches(
        """core.run(RunModule.scala:...)
          |    Runs this module's code in a subprocess and waits for it to finish
          |
          |    args <str>...
          |
          |Inputs:
          |    core.finalMainClassOpt
          |    core.runClasspath
          |    core.forkArgs
          |    core.forkEnv
          |    core.runUseArgsFile
          |    core.finalMainClass
          |    core.forkWorkingDir
          |""",
        run
      )

      assert(eval(("inspect", "core.ivyDepsTree")).isSuccess)

      val ivyDepsTree = out("inspect").json.str

      assertGlobMatches(
        """core.ivyDepsTree(JavaModule.scala:...)
          |    Command to print the transitive dependency tree to STDOUT.
          |
          |    --inverse                Invert the tree representation, so that the root is on the bottom val
          |                             inverse (will be forced when used with whatDependsOn)
          |    --what-depends-on <str>  Possible list of modules (org:artifact) to target in the tree in order
          |                             to see where a dependency stems from.
          |    --with-compile           Include the compile-time only dependencies (`compileIvyDeps`, provided
          |                             scope) into the tree.
          |    --with-runtime           Include the runtime dependencies (`runIvyDeps`, runtime scope) into the
          |                             tree.
          |
          |Inputs:
          |    core.mandatoryIvyDeps
          |    core.ivyDeps
          |    core.compileIvyDeps
          |    core.runIvyDeps
          |    core.bomIvyDeps
          |    core.depManagement
          |    core.checkGradleModules
          |""".stripMargin,
        ivyDepsTree
      )

      assert(eval(("inspect", "core.test.theWorker")).isSuccess)
      val theWorkerInspect = out("inspect").json.str

      assertGlobMatches(
        """core.test.theWorker(build.mill:39)
          |    -> The worker <-
          |
          |    *The worker*
          |
          |Inputs:
          |""".stripMargin,
        theWorkerInspect
      )

      // Make sure both kebab-case and camelCase flags work, even though the
      // docs from `inspect` only show the kebab-case version
      assert(eval(("core.ivyDepsTree", "--withCompile", "--withRuntime")).isSuccess)
      assert(eval(("core.ivyDepsTree", "--with-compile", "--with-runtime")).isSuccess)

      val basic = eval(("inspect", "basic"))
      assert(basic.isSuccess)
      val basicInspect = out("inspect").json.str
      assertGlobMatches(
        """basic(build.mill:26)
          |
          |Inherited Modules:""",
        basicInspect
      )

      assert(eval(("inspect", "core")).isSuccess)
      val coreInspect = out("inspect").json.str
      assert(
        globMatches(
          """core(build.mill:31)
            |    The Core Module Docz!
            |
            |Inherited Modules:
            |...JavaModule...
            |
            |Default Task: core.run
            |
            |Tasks (re-/defined):
            |    core.task
            |""",
          coreInspect
        )
      )

      assert(eval(("inspect", "MyJavaTaskModule")).isSuccess)
      val jtmInspect = out("inspect").json.str
      assert(
        globMatches(
          """MyJavaTaskModule(build.mill:54)
            |
            |Inherited Modules:
            |...JavaModule...
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
            |""",
          jtmInspect
        )
      )

      val core3Res = eval(("inspect", "core3"))
      assert(core3Res.isSuccess)
      val core3Inspect = out("inspect").json.str
      assertGlobMatches(
        """core3(core3/package.mill:6)
          |    Subfolder Module Scaladoc
          |
          |Inherited Modules:
          |    build_.core3.package_
          |
          |Default Task: core3.run
          |""",
        core3Inspect
      )
    }
  }
}
