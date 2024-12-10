package mill.javascriptlib.testmods

import mill.*

trait SharedTestUtils extends TestModule {
  override def upstreamPathsBuilder: T[Seq[(String, String)]] =
    Task {
      val stuUpstreams = for {
        ((_, ts), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
      } yield (
        mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/test/utils/*",
        (ts.path / "test" / "utils").toString
      )

      stuUpstreams ++ super.upstreamPathsBuilder()
    }
}
