import mill._, javalib._


object foo extends RootModule with JavaModule {
  def compileIvyDeps = Agg(
    ivy"org.projectlombok:lombok:1.18.34"
  )

  object test extends JavaTests with TestModule.Junit4
}

// This is an example of how to use Mill to build Java projects using Java annotations and
// annotation processors. In this case, we use the annotations provided by
// https://projectlombok.org[Project Lombok] to automatically generate getters and setters
// from class private fields

/** Usage

> ./mill test
Test foo.HelloWorldTest.testSimple started
Test foo.HelloWorldTest.testSimple finished
...

*/
