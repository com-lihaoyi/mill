This folder contains executable examples of Mill builds, meant for learning
purposes but also tested and verified for correctness.

The three sub-folders are:

1. `basic/`: the basic getting-started hello-world examples of using Mill

2. `scalabuilds/`: examples that walk you through getting from a
   minimal single-module Scala project to testing and publishing a
   multi-module multi-scala-version project

3. `web/`: this contains examples of doing basic web development with Mill as
   the build tool: going from a simple static HTML server to a client-server
   application sharing code between Scala-JVM and Scala.js, and publishing
   cross-platform Scala modules built across Scala-JVM and Scala.js

4. `tasks/`: examples demonstrating how Mill's various `Task[T]` flavors work,
   and in what kind of scenarios you would use each one

5. `cross/`: examples of Mill's cross-build capability, as a generic feature, 
   beyond its specific usage in `CrossScalaModule`

6. `misc/` examples that go deeper into the workings of
   the Mill build tool: dynamic cross modules, `$file` and `$ivy` imports, and
   using the `mill-build/` folder.

7. `thirdparty/`: some example `build.sc` files for real-world Java and Scala
   codebases

Within each sub-folder, examples are numbered in the order they are intended to
be read. Each example illustrates one key concept or technique, with the code
followed by a comment explaining it, and an `Example Usage` block showing how
the example can be used. By going through all of the examples, you should be
able to learn everything that you need to be productive with the Mill build
tool.
