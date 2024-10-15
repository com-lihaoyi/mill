package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object DocAnnotationsTests extends UtestIntegrationTestSuite {
  def globMatches(glob: String, input: String): Boolean = {
    StringContext
      .glob(
        // Normalize the line separator to be `\n` for comparisons
        glob.stripMargin.linesIterator.mkString("\n").split("\\.\\.\\."),
        input.linesIterator.mkString("\n")
      )
      .isDefined
  }

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      val res = eval(("inspect", "core.test.ivyDeps"))
      assert(res.isSuccess == true)

      val inheritedIvyDeps = out("inspect").json.str
      assert(
        globMatches(
          """core.test.ivyDeps(build.mill:...)
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
      )

      assert(eval(("inspect", "core.task")).isSuccess)
      val task = out("inspect").json.str
      assert(
        globMatches(
          """core.task(build.mill:...)
            |    Core Task Docz!
            |
            |Inputs:
            |""",
          task
        )
      )

      assert(eval(("inspect", "inspect")).isSuccess)
      val doc = out("inspect").json.str
      assert(
        globMatches(
          """inspect(MainModule.scala:...)
            |    Displays metadata about the given task without actually running it.
            |
            |    tasks <str>...
            |
            |Inputs:
            |""".stripMargin,
          doc
        )
      )

      assert(eval(("inspect", "core.run")).isSuccess)
      val run = out("inspect").json.str

      assert(
        globMatches(
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
      )

      assert(eval(("inspect", "core.ivyDepsTree")).isSuccess)

      val ivyDepsTree = out("inspect").json.str

      assert(
        globMatches(
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
            |    core.transitiveIvyDeps
            |""".stripMargin,
          ivyDepsTree
        )
      )

      assert(eval(("inspect", "core.test.theWorker")).isSuccess)
      val theWorkerInspect = out("inspect").json.str

      assert(
        globMatches(
          """core.test.theWorker(build.mill:...)
            |    -> The worker <-
            |
            |    *The worker*
            |
            |Inputs:
            |""".stripMargin,
          theWorkerInspect
        )
      )

      // Make sure both kebab-case and camelCase flags work, even though the
      // docs from `inspect` only show the kebab-case version
      assert(eval(("core.ivyDepsTree", "--withCompile", "--withRuntime")).isSuccess)
      assert(eval(("core.ivyDepsTree", "--with-compile", "--with-runtime")).isSuccess)

      assert(eval(("inspect", "basic")).isSuccess)
      val basicInspect = out("inspect").json.str
      assert(
        globMatches(
          """basic(build.mill:...)
            |
            |Inherited Modules: Module
            |""",
          basicInspect
        )
      )

      assert(eval(("inspect", "core")).isSuccess)
      val coreInspect = out("inspect").json.str
      assert(
        globMatches(
          """core(build.mill:...)
            |    The Core Module Docz!
            |
            |Inherited Modules: JavaModule
            |
            |Default Task: core.run
            |
            |Tasks: core.task
            |""",
          coreInspect
        )
      )

      assert(eval(("inspect", "MyJavaTaskModule")).isSuccess)
      val jtmInspect = out("inspect").json.str
      assert(
        globMatches(
          """MyJavaTaskModule(build.mill:...)
            |
            |Inherited Modules: JavaModule
            |
            |Module Dependencies: core, core2
            |
            |Default Task: MyJavaTaskModule.run
            |
            |Tasks: MyJavaTaskModule.lineCount, MyJavaTaskModule.task
            |""",
          jtmInspect
        )
      )
    }
  }
}
