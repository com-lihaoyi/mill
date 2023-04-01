This folder contains executable examples of Mill builds, meant for learning
purposes but also tested and verified for correctness.

The three sub-folders are:

1. `basic/`: this contains examples that walk you through getting from a
   minimal single-module Scala project to testing and publishing a
   multi-module multi-scala-version project

2. `web/`: this contains examples of doing basic web development with Mill as
   the build tool: going from a simple static HTML server to a client-server
   application sharing code between Scala-JVM and Scala.js, and publishing
   cross-platform Scala modules built across Scala-JVM and Scala.js

3. `misc/` contains a variety of examples that go deeper into the workings of
   the Mill build tool: dynamic cross modules, `$file` and `$ivy` imports, and
   using the `mill-build/` folder.

Within each sub-folder, examples are numbered in the order they are intended to
be read. Each example illustrates one key concept or technique, and by going
through all of them you should be able to learn everything that you need to be
productive with the Mill build tool.
