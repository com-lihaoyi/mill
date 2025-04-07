package mill.testrunner

import mill.api.{Ctx, internal}
import mill.runner.api.TestReporter
import mill.util.Jvm

@internal object TestRunner {

  def runTestFramework(
      frameworkInstances: ClassLoader => sbt.testing.Framework,
      entireClasspath: Seq[os.Path],
      testClassfilePath: Seq[os.Path],
      args: Seq[String],
      testReporter: TestReporter,
      classFilter: Class[?] => Boolean = _ => true
  )(implicit ctx: Ctx.Log): (String, Seq[mill.testrunner.TestResult]) = {
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
