// Test to make sure that the worker instances whose classes come from an
// `import $ivy` dependency are properly invalidated when the `build.sc` is
// modified and re-compiled, causing a new classloader to be created which
// would have an incompatible worker class than before.
//
// In this test case, `mill.playlib.RouteCompilerWorker` is the relevant worker
// class, instantiated in `RouterModule.routeCompilerWorker`

import $ivy.`com.lihaoyi::mill-contrib-playlib:`, mill.playlib._

object app extends PlayApiModule {
  def scalaVersion = "2.13.10"
  def playVersion = "2.8.19"
}