package mill.scalalib

import mill.{Agg, T}

/** TestModule that uses JUnit 4 to run tests. */
trait Junit extends TestModule {

  override def testFramework: T[String] = "com.novocode.junit.JUnitFramework"

  override def ivyDeps: T[Agg[Dep]] = T {
    super.ivyDeps() ++ Agg(
      ivy"com.novocode:junit-interface:0.11"
    )
  }

}
