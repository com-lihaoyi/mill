package mill.scalalib

import mill.{Agg, T}

/** TestModule using TestNG to run tests. */
trait TestNg extends TestModule {

  override def testFramework: T[String] = "mill.testng.TestNGFramework"

  override def ivyDeps: T[Agg[Dep]] = T {
    super.ivyDeps() ++ Agg(
      ivy"com.lihaoyi:mill-contrib-testng_2.13:${mill.BuildInfo.millVersion}"
    )
  }

}
