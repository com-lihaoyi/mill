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
      classFilter: Class[?] => Boolean = _ => true
  )(implicit ctx: Ctx.Log & Ctx.Home): (String, Seq[mill.testrunner.TestResult]) = {
    Jvm.withClassLoader(
      classPath = entireClasspath.toVector,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils.runTestFramework0(
        frameworkInstances,
        testClassfilePath,
        args,
        classFilter,
        classLoader,
        testReporter
      )
    }
  }
}
