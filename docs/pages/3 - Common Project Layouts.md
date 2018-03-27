
## Common Project Layouts
Earlier, we have shown how to work with the Mill default Scala module layout.
Here we will explore some other common project layouts that you may want in your
Scala build:

### Cross Scala-Version Modules

```scala
import mill._
import mill.scalalib._
object foo extends Cross[FooModule]("2.10.6", "2.11.11", "2.12.4")
class FooModule(val crossScalaVersion: String) extends CrossScalaModule{
   ...
   object test extends Tests{
     ...
   }
}
```

Mill provides a `CrossScalaModule` template, which can be used with `Cross` to
cross-build Scala modules across different versions of Scala. The default
configuration for `CrossScalaModule` expects a filesystem layout as follows:

```text
build.sc
foo/
    src/
    src-2.10/
    src-2.11/
    src-2.12/
    test/
        src/
        src-2.10/
        src-2.11/
        src-2.12/
```

Code common to all Scala versions lives in `src`, while code specific to one
version lives in `src-x.y`.

### Scala.js Modules

```scala
import mill._
import mill.scalajslib._

object foo extends ScalaJSModule {
  def scalaVersion = "2.12.4"
  def scalaJSVersion = "0.6.22"
}
```

`ScalaJSModule` is a variant of `ScalaModule` that builds your code using
Scala.js. In addition to the standard `foo.compile` and `foo.run` commands (the
latter of which runs your code on Node.js, which must be pre-installed)
`ScalaJSModule` also exposes the `foo.fastOpt` and `foo.fullOpt` tasks for
generating the optimized Javascript file.

### SBT-Compatible Modules

```scala
import mill._
import mill.scalalib._

object foo extends SbtModule {
  def scalaVersion = "2.12.4"
}
```

These are basically the same as normal `ScalaModule`s, but configured to follow
the SBT project layout:

```text
build.sc
foo/
    src/
        main/
            scala/
        test/
            scala/
```

Useful if you want to migrate an existing project built with SBT without having
to re-organize all your files


### SBT-Compatible Cross Scala-Version Modules

```scala
import mill._
import mill.scalalib._
object foo extends Cross[FooModule]("2.10.6", "2.11.11", "2.12.4")
class FooModule(val crossScalaVersion: String) extends CrossSbtModule{
   ...
   object test extends Tests{
     ...
   }
}
```

A `CrossSbtModule` is a version of `CrossScalaModule` configured with the SBT
project layout:

```text
build.sc
foo/
    src/
        main/
            scala/
            scala-2.10/
            scala-2.11/
            scala-2.12/
        test/
            scala/
            scala-2.10/
            scala-2.11/
            scala-2.12/
```

### Publishing
```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule with PublishModule{
  def scalaVersion = "2.12.4"
  def publishVersion = "0.0.1
  def pomSettings = PomSettings(
    description = "My first library",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/mill",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "mill"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )
}
```

You can make a module publishable by extending `PublishModule`.

`PublishModule` then needs you to define a `publishVersion` and `pomSettings`.
The `artifactName` defaults to the name of your module (in this case `foo`) but
can be overriden. The `organization` is defined in `pomSettings`.

Once you've mixed in `PublishModule`, you can publish your libraries to maven
central via:

```bash
mill mill.scalalib.PublishModule/publishAll \
        lihaoyi:$SONATYPE_PASSWORD \
        $GPG_PASSWORD \ 
        foo.publishArtifacts
```

This uploads them to `oss.sonatype.org` where you can log-in and stage/release
them manually. You can also pass in the `--release true` flag to perform the
staging/release automatically:

```bash
mill mill.scalalib.PublishModule/publishAll \
        lihaoyi:$SONATYPE_PASSWORD \
        $GPG_PASSWORD \ 
        foo.publishArtifacts \
        --release true
```

If you want to publish/release multiple modules, you can use the `_` or `__`
wildcard syntax:

```bash
mill mill.scalalib.PublishModule/publishAll \
        lihaoyi:$SONATYPE_PASSWORD \
        $GPG_PASSWORD \ 
        __.publishArtifacts \
        --release true
```


## Example Builds

Mill comes bundled with example builds for existing open-source projects, as
integration tests and examples:


### Acyclic

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/acyclic/build.sc#L1)

A small single-module cross-build, with few sources minimal dependencies, and
wired up for publishing to Maven Central


### Better-Files

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/better-files/build.sc#L1)

A collection of small modules compiled for a single Scala version.

Also demonstrates how to define shared configuration in a `trait`, enable Scala
compiler flags, and download artifacts as part of the build.

### Jawn

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/jawn/build.sc#L1)

A collection of relatively small modules, all cross-built across the same few
versions of Scala.

### Upickle

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/upickle/build.sc#L1)

A single cross-platform Scala.js/Scala-JVM module cross-built against multiple
versions of Scala, including the setup necessary for publishing to Maven Central

### Ammonite

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/ammonite/build.sc#L1)

A relatively complex build with numerous submodules, some cross-built across
Scala major versions while others are cross-built against Scala minor versions.

Also demonstrates how to pass one module's compiled artifacts to the
`run`/`test` commands of another, via their `forkEnv`.
