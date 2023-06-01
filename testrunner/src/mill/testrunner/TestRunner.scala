package mill.testrunner

import mill.api.Loose.Agg
import mill.api.{Ctx, TestReporter, internal}
import mill.util.Jvm

@internal object TestRunner {

  def runTestFramework(
      frameworkInstances: ClassLoader => sbt.testing.Framework,
      entireClasspath: Agg[os.Path],
      testClassfilePath: Agg[os.Path],
      args: Seq[String],
      testReporter: TestReporter,
      classFilter: Class[_] => Boolean = _ => true
  )(implicit ctx: Ctx.Log with Ctx.Home): (String, Seq[mill.testrunner.TestResult]) = {
    // Leave the context class loader set and open so that shutdown hooks can access it
    Jvm.inprocess(
      entireClasspath,
      classLoaderOverrideSbtTesting = true,
      isolated = true,
      closeContextClassLoaderWhenDone = false,
      TestRunnerUtils.runTestFramework0(
        frameworkInstances,
        testClassfilePath,
        args,
        classFilter,
        _,
        testReporter
      )
    )
  }
}
