//// SNIPPET:ALL
import mill._, javalib._

object foo extends MavenModule {
  object test extends MavenTests with TestModule.Junit4
}


// `MavenModule` is a variant of `JavaModule`
// that uses the more verbose folder layout of Maven, SBT, and other tools:
//
// - `foo/src/main/java`
// - `foo/src/test/java`
//
// Rather than Mill's
//
// - `foo/src`
// - `foo/test/src`
//
// This is especially useful if you are migrating from Maven to Mill (or vice
// versa), during which a particular module may be built using both Maven and
// Mill at the same time

/** Usage

> mill foo.compile
compiling 1 Java source...

> mill foo.test.compile
compiling 1 Java source...

> mill foo.test.test
...foo.FooTests.hello ...

> mill foo.test
...foo.FooTests.hello ...

*/