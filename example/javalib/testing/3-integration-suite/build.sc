//// SNIPPET:BUILD3
import mill._, javalib._
object qux extends JavaModule {
  object test extends JavaTests with TestModule.Junit5
  object integration extends JavaTests with TestModule.Junit5
}

// This example also demonstrates using Junit 5 instead of Junit 4,
// with
