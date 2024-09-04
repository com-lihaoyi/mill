package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object DocAnnotationsTests extends IntegrationTestSuite {
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
    initWorkspace()
    test("test") - {
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

      assert(eval(("inspect", "core.target")).isSuccess)
      val target = out("inspect").json.str
      assert(
        globMatches(
          """core.target(build.mill:...)
            |    Core Target Docz!
            |
            |Inputs:
            |""",
          target
        )
      )

      assert(eval(("inspect", "inspect")).isSuccess)
      val doc = out("inspect").json.str
      assert(
        globMatches(
          """inspect(MainModule.scala:...)
            |    Displays metadata about the given task without actually running it.
            |
            |    targets <str>...
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
            |    core.finalMainClass
            |    core.runClasspath
            |    core.forkArgs
            |    core.forkEnv
            |    core.forkWorkingDir
            |    core.runUseArgsFile
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

      // Make sure both kebab-case and camelCase flags work, even though the
      // docs from `inspect` only show the kebab-case version
      assert(eval(("core.ivyDepsTree", "--withCompile", "--withRuntime")).isSuccess)
      assert(eval(("core.ivyDepsTree", "--with-compile", "--with-runtime")).isSuccess)
    }
  }
}
