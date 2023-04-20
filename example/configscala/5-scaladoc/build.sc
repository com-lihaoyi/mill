// == Scaladoc Config
//
// To generate API documenation you can use the `docJar` task on the module you'd
// like to create the documenation for. For example, given a module called
// `example` you could do:
//
// [source, bash]
// ----
// mill example.docJar
// ----
//
// This will result in your `javaDoc` being created in
// `out/app/docJar.dest/javadoc`.

import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "3.1.3"

  def scalaDocOptions = Seq("-siteroot", "mydocs", "-no-link-warnings")
}


//
// For both Scala and Java modules there may be extra options that you'd like to
// pass specifically to either `javadoc` or `scaladoc`. You can pass these with
// `javadocOptions` and `scalaDocOptions` respectively.

/** Usage

> ./mill show foo.docJar

> unzip -p out/foo/docJar.dest/out.jar foo/Foo.html
<p>My Awesome Scaladoc for class Foo</p>

*/

// When using Scala 3 you're also able to use Scaladoc to generate a full static
// site next to your API documention. This can include general documenation for
// your project and even a blog. While you can find the full documenation for this
// in the https://docs.scala-lang.org/scala3/guides/scaladoc/index.html[Scala 3
// docs], below you'll find some useful information to help you generate this with
// Mill.
//
// By default, Mill will consider the _site root_ as it's called in
// https://docs.scala-lang.org/scala3/guides/scaladoc/static-site.html[Scala 3
// docs], to be the value of `docResources()`. It will look there for your
// `_docs/` and your `_blog/` directory if any exist. Let's pretend we have a
// project called `bar` defined like this:

object bar extends ScalaModule {
  def scalaVersion = "3.1.3"
}

// Your project structure for this would look something like this:
//
// ----
// .
// ├── build.sc
// ├── bar
// │  ├── docs
// │  │  ├── _blog
// │  │  │  ├── _posts
// │  │  │  │  └── 2022-08-14-hello-world.md
// │  │  │  └── index.md
// │  │  └── _docs
// │  │     ├── getting-started.md
// │  │     ├── index.html
// │  │     └── index.md
// │  └── src
// │     └── example
// │        └── Hello.scala
// ----
//
// After generating your docs with `mill example.docJar` you'll find by opening
// your `out/app/docJar.dest/javadoc/index.html` locally in your browser you'll
// have a full static site including your API docs, your blog, and your
// documenation.


/** Usage

> ./mill show bar.docJar

> unzip -p out/bar/docJar.dest/out.jar bar/Bar.html
<p>My Awesome Scaladoc for class Bar</p>

*/