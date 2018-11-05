## Contrib Modules

### BuildInfo

Generate scala code from your buildfile.
This plugin generates a single object containing information from your build.

To declare a module that uses BuildInfo you must extend the `mill.contrib.BuildInfo` trait when defining your module.

Quickstart:
```scala
object project extends BuildInfo {
  val name = "poject-name"
  def  buildInfoMembers: T[Map[String, String]] = T {
    Map(
      "name" -> name),
      "scalaVersion" -> scalaVersion()
    )
  }
}
```

#### Configuration options

* `def buildInfoMembers: T[Map[String, String]]`
  The map containing all member names and values for the generated info object.

* `def buildInfoObjectName: String`, default: `BuildInfo`
  The name of the object which contains all the members from `buildInfoMembers`.

* `def buildInfoPackageName: Option[String]`, default: `None`
  The package name of the object.

### ScalaPB

This module allows [ScalaPB](https://scalapb.github.io) to be used in Mill builds. ScalaPB is a [Protocol Buffers](https://developers.google.com/protocol-buffers/) compiler plugin that generates Scala case classes, encoders and decoders for protobuf messages.

To declare a module that uses ScalaPB you can extend the `mill.contrib.scalapblib.ScalaPBModule` trait when defining your module.

This creates a Scala module which compiles `.proto` files in the `protobuf` folder of the module with ScalaPB and adds the resulting `.scala` sources to your module's `generatedSources`.

```scala
// build.sc
import mill._, scalalib._, contrib.scalapblib.__

object example extends ScalaPBModule {
  def scalaVersion = "2.12.6"
  def scalaPBVersion = "0.7.4"
}
```

This defines a project with the following layout:

```
build.sc
example/
    src/
    protobuf/
    resources/
```

#### Configuration options

* scalaPBVersion (mandatory) - The ScalaPB version `String` e.g. `"0.7.4"`

* scalaPBFlatPackage - A `Boolean` option which determines whether the `.proto` file name should be appended as the final segment of the package name in the generated sources.

* scalaPBJavaConversions - A `Boolean` option which determines whether methods for converting between the generated Scala classes and the Protocol Buffers Java API classes should be generated.

* scalaPBGrpc - A `Boolean` option which determines whether [grpc](https://grpc.io) stubs should be generated.

* scalaPBSingleLineToProtoString - A `Boolean` option which determines whether the generated `.toString` methods should use a single line format.

If you'd like to configure the options that are passed to the ScalaPB compiler directly, you can override the `scalaPBOptions` task, for example:

```scala
object example extends ScalaPBModule {
  def scalaVersion = "2.12.6"
  def scalaPBVersion = "0.7.4"
  override def scalaPBOptions = "flat_package,java_conversions"
}
```

### TestNG

Provides support for [TestNG](https://testng.org/doc/index.html).

To use TestNG as test framework, you need to add it to the `TestModule.testFrameworks` property.

```scala
object project extends ScalaModule {
  object test extends Tests{
    def testFrameworks = Seq("mill.testng.TestNGFramework")
  }
}
```

### Tut

This module allows [Tut](https://tpolecat.github.io/tut) to be used in Mill builds. Tut is a documentation tool which compiles and evaluates Scala code in documentation files and provides various options for configuring how the results will be displayed in the compiled documentation.

To declare a module that uses Tut you can extend the `mill.contrib.tut.TutModule` trait when defining your module.

This creates a Scala module which compiles markdown, HTML and `.txt` files in the `tut` folder of the module with Tut.

By default the resulting documents are simply placed in the Mill build output folder but they can be placed elsewhere by overriding the `tutTargetDirectory` task.

```scala
// build.sc
import mill._, scalalib._, contrib.tut.__

object example extends TutModule {
  def scalaVersion = "2.12.6"
  def tutVersion = "0.6.7"
}
```

This defines a project with the following layout:

```
build.sc
example/
    src/
    tut/
    resources/
```

In order to compile documentation we can execute the `tut` task in the module:

```
sh> mill example.tut
```

#### Configuration options

* tutSourceDirectory - This task determines where documentation files must be placed in order to be compiled with Tut. By default this is the `tut` folder at the root of the module.

* tutTargetDirectory - A task which determines where the compiled documentation files will be placed. By default this is simply the Mill build's output folder for the `tutTargetDirectory` task but this can be reconfigured so that documentation goes to the root of the module (e.g. `millSourcePath`) or to a dedicated folder (e.g. `millSourcePath / 'docs`)

* tutClasspath - A task which determines what classpath is used when compiling documentation. By default this is configured to use the same inputs as the `runClasspath`, except for using `tutIvyDeps` rather than the module's `ivyDeps`.

* tutScalacPluginIvyDeps - A task which determines the scalac plugins which will be used when compiling code examples with Tut. The default is to use the `scalacPluginIvyDeps` for the module.

* tutNameFilter - A `scala.util.matching.Regex` task which will be used to determine which files should be compiled with tut. The default pattern is as follows: `.*\.(md|markdown|txt|htm|html)`.

* tutScalacOptions - The scalac options which will be used when compiling code examples with Tut. The default is to use the `scalacOptions` for the module but filtering out options which are problematic in the REPL, e.g. `-Xfatal-warnings`, `-Ywarn-unused-imports`.

* tutVersion - The version of Tut to use.

* tutIvyDeps - A task which determines how to fetch the Tut jar file and all of the dependencies required to compile documentation for the module and returns the resulting files.

* tutPluginJars - A task which performs the dependency resolution for the scalac plugins to be used with Tut.

### Twirl

Twirl templates support.

To declare a module that needs to compile twirl templates you must extend the `mill.twirllib.TwirlModule` trait when defining your module. 
Also note that twirl templates get compiled into scala code, so you also need to extend `ScalaModule`.
 
```scala
import $ivy.`com.lihaoyi::mill-contrib-twirllib:0.3.2`,  mill.twirllib._
object app extends ScalaModule with TwirlModule {
// ...
} 
``` 

#### Configuration options

* `def twirlVersion: T[String]` (mandatory) - the version of the twirl compiler to use, like "1.3.15"
* `def twirlAdditionalImports: Seq[String] = Nil` - the additional imports that will be added by twirl compiler to the top
  of all templates
  
#### Details

The following filesystem layout is expected:

```text
build.sc
app/
    views/
        view1.scala.html
        view2.scala.html
```

`TwirlModule` adds the `compileTwirl` task to the module:
```
mill app.compileTwirl
```

(it will be automatically run whenever you compile your module)

This task will compile `*.scala.html` templates (and others, like `*.scala.txt`) into the `out/app/compileTwirl/dest` 
directory. This directory must be added to the generated sources of the module to be compiled and made accessible from the rest of the code:
```scala
object app extends ScalaModule with TwirlModule {
  def twirlVersion = "1.3.15"
  def generatedSources = T{ Seq(compileTwirl().classes) }
}
``` 

To add additional imports to all of the twirl templates:
```scala
object app extends ScalaModule with TwirlModule {
  def twirlVersion = "1.3.15"
  override def twirlAdditionalImports = Seq("my.additional.stuff._", "my.other.stuff._")
  def generatedSources = T{ Seq(compileTwirl().classes) }
}
``` 

as the result all templates will get this line at the top:
```scala
@import "my.additional.stuff._"
@import "my.other.stuff._"
```

Besides that, twirl compiler has default imports, at the moment these:
```scala
Seq(
    "_root_.play.twirl.api.TwirlFeatureImports._",
    "_root_.play.twirl.api.TwirlHelperImports._",
    "_root_.play.twirl.api.Html",
    "_root_.play.twirl.api.JavaScript",
    "_root_.play.twirl.api.Txt",
    "_root_.play.twirl.api.Xml"
)
``` 

These imports will always be added to every template.  You don't need to list them if you override `twirlAdditionalImports`.

#### Example
There's an [example project](https://github.com/lihaoyi/cask/tree/master/example/twirl)



## Thirdparty Mill Plugins

### DGraph

Show transitive dependencies of your build in your browser.

Project home: https://github.com/ajrnz/mill-dgraph

#### Quickstart

```scala
import $ivy.`com.github.ajrnz::mill-dgraph:0.2.0`
```

```sh
sh> mill plugin.dgraph.browseDeps(proj)()
```

### Ensime

Create an [.ensime](http://ensime.github.io/ "ensime") file for your build.

Project home: https://github.com/yyadavalli/mill-ensime

#### Quickstart

```scala
import $ivy.`fun.valycorp::mill-ensime:0.0.1`
```

```sh
sh> mill fun.valycorp.mill.GenEnsime/ensimeConfig
```

### OSGi

Produce OSGi Bundles with mill.

Project home: https://github.com/lefou/mill-osgi

#### Quickstart

```scala
import $ivy.`de.tototec::de.tobiasroeser.mill.osgi:0.0.2`
import de.tobiasroeser.mill.osgi._

object project extends ScalaModule with OsgiBundleModule {

  def bundleSymbolicName = "com.example.project"

  def osgiHeaders = T{ osgiHeaders().copy(
    `Export-Package`   = Seq("com.example.api"),
    `Bundle-Activator` = Some("com.example.internal.Activator")
  )}

}
```


### PublishM2

Mill plugin to publish artifacts into a local Maven repository.

Project home: https://github.com/lefou/mill-publishM2

#### Quickstart

Just mix-in the `PublishM2Module` into your project.
`PublishM2Module` already extends mill's built-in `PublishModule`.

File: `build.sc`
```scala
import mill._, scalalib._, publish._

import $ivy.`de.tototec::de.tobiasroeser.mill.publishM2:0.0.1`
import de.tobiasroeser.mill.publishM2._

object project extends PublishModule with PublishM2Module {
  // ...
}
```

Publishing to default local Maven repository

```bash
> mill project.publishM2Local
[40/40] project.publishM2Local
Publishing to /home/user/.m2/repository
```

Publishing to custom local Maven repository

```bash
> mill project.publishM2Local /tmp/m2repo
[40/40] project.publishM2Local
Publishing to /tmp/m2repo
```
