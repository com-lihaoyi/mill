## Contrib Modules

### BuildInfo

Generate scala code from your buildfile.
This plugin generates a single object containing information from your build.

To declare a module that uses BuildInfo you must extend the `mill.contrib.buildinfo.BuildInfo` trait when defining your module.

Quickstart:
```scala
// build.sc
// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-buildinfo:VERSION`
import mill.contrib.buildinfo.BuildInfo

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

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-scalapblib:VERSION`
import contrib.scalapblib._

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
// build.sc

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-scalapblib:VERSION`
import contrib.scalapblib._

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
// build.sc
import mill.scalalib._

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

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-tut:VERSION`
import contrib.tut._

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
// build.sc
import mill.scalalib._

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-twirllib:VERSION`,  mill.twirllib._

object app extends ScalaModule with TwirlModule {
// ...
} 
``` 

#### Configuration options

* `def twirlVersion: T[String]` (mandatory) - the version of the twirl compiler to use, like "1.3.15"
* `def twirlAdditionalImports: Seq[String] = Nil` - the additional imports that will be added by twirl compiler to the top of all templates
* `def twirlConstructorAnnotations: Seq[String] = Nil` - annotations added to the generated classes' constructors (note it only applies to templates with `@this(...)` constructors) 
* `def twirlCodec = Codec(Properties.sourceEncoding)` - the codec used to generate the files (the default is the same sbt plugin uses) 
* `def twirlInclusiveDot: Boolean = false`  
  
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
// build.sc
import mill.scalalib._

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-twirllib:VERSION`,  mill.twirllib._

object app extends ScalaModule with TwirlModule {
  def twirlVersion = "1.3.15"
  def generatedSources = T{ Seq(compileTwirl().classes) }
}
``` 

To add additional imports to all of the twirl templates:
```scala
// build.sc
import mill.scalalib._

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-twirllib:VERSION`,  mill.twirllib._

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

### Play Framework

Play framework routes generation support.

 
To declare a module that needs to generate Play Framework routes, you must mix-in the 
`mill.playlib.routesModule` trait when defining your module. 

 
```scala
// build.sc

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-playlib:VERSION`,  mill.playlib._

object app extends RouterModule {
// ...
} 
``` 

#### Configuration options

  * `def playVersion: T[String]` (mandatory) - The version of play to use to compile the routes file.
  * `def scalaVersion: T[String]` - The scalaVersion in use in your project.
  * `def conf: Sources` - The directory which contains your route files. (Defaults to : `routes/` )  
  * `def routesAdditionalImport: Seq[String]` - Additional imports to use in the generated routers. (Defaults to `Seq("controllers.Assets.Asset", "play.libs.F")`
  * `def generateForwardsRouter: Boolean = true` - Enables the forward router generation.
  * `def generateReverseRouter: Boolean = true` - Enables the reverse router generation.
  * `def namespaceReverseRouter: Boolean = false` - Enables the namespacing of reverse routers.
  * `def generatorType: RouteCompilerType = RouteCompilerType.InjectedGenerator` - The routes compiler type, one of RouteCompilerType.InjectedGenerator or RouteCompilerType.StaticGenerator
  
#### Details

The following filesystem layout is expected by default:

```text
.
├── app
│   └── routes
│       └── routes
└── build.sc
```

`RouterModule` adds the `compileRouter` task to the module:
```
mill app.compileRouter
```

(it will be automatically run whenever you compile your module)

This task will compile `routes` templates into the `out/app/compileRouter/dest` 
directory. This directory must be added to the generated sources of the module to be compiled and made accessible from the rest of the code:
```scala
object app extends ScalaModule with RouterModule {
  def playVersion= T{"2.7.0"}
  def scalaVersion= T{"2.12.8"}
}
``` 

To add additional imports to all of the routes:
```scala
// build.sc
import mill.scalalib._

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-playlib:VERSION`,  mill.playlib._

object app extends ScalaModule with RouterModule {
  def playVersion = "2.7.0"
  override def routesAdditionalImport = Seq("my.additional.stuff._", "my.other.stuff._") 
}
``` 

If you want to use playframework's default of storing the routes in `conf/` you can do the 
follwing: 
```scala
// build.sc
import mill.scalalib._

// You have to replace VERSION
import $ivy.`com.lihaoyi::mill-contrib-playlib:VERSION`,  mill.playlib._

object app extends ScalaModule with RouterModule {
  def playVersion = "2.7.0"
  override def routesAdditionalImport = Seq("my.additional.stuff._", "my.other.stuff._")
  override def routes = T.sources{ millSourcePath / 'conf } 
}
``` 

which will work with the following directory structure:
```text
.
├── app
│   └── conf
│       └── routes
└── build.sc
```

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

### Integration Testing Mill Plugins

Integration testing for mill plugins.

#### Quickstart

We assume, you have a mill plugin named `mill-demo`

```scala
// build.sc
import mill._, mill.scalalib._
object demo extends ScalaModule with PublishModule {
  // ...
}
```

Add an new test sub-project, e.g. `it`.

```scala
import $ivy.`de.tototec::de.tobiasroeser.mill.integrationtest:0.1.0`
import de.tobiasroeser.mill.integrationtest._

object it extends MillIntegrationTest {

  def millTestVersion = "{exampleMillVersion}"

  def pluginsUnderTest = Seq(demo)

}
```

Your project should now look similar to this:

```text
.
+-- demo/
|   +-- src/
|
+-- it/
    +-- src/
        +-- 01-first-test/
        |   +-- build.sc
        |   +-- src/
        |
        +-- 02-second-test/
            +-- build.sc
```

As the buildfiles `build.sc` in your test cases typically want to access the locally built plugin(s),
the plugins publishes all plugins referenced under `pluginsUnderTest` to a temporary ivy repository, just before the test is executed.
The mill version used in the integration test then used that temporary ivy repository.

Instead of referring to your plugin with `import $ivy.'your::plugin:version'`,
you can use the following line instead, which ensures you will use the correct locally build plugins.

```scala
// build.sc
import $exec.plugins
```

Effectively, at execution time, this line gets replaced by the content of `plugins.sc`, a file which was generated just before the test started to execute.

#### Configuration and Targets

The mill-integrationtest plugin provides the following targets.

##### Mandatory configuration

* `def millTestVersion: T[String]`
  The mill version used for executing the test cases.
  Used by `downloadMillTestVersion` to automatically download.

* `def pluginsUnderTest: Seq[PublishModule]` -
  The plugins used in the integration test.
  You should at least add your plugin under test here.
  You can also add additional libraries, e.g. those that assist you in the test result validation (e.g. a local test support project).
  The defined modules will be published into a temporary ivy repository before the tests are executed.
  In your test `build.sc` file, instead of the typical `import $ivy.` line,
  you should use `import $exec.plugins` to include all plugins that are defined here.

##### Optional configuration

* `def sources: Sources` -
  Locations where integration tests are located.
  Each integration test is a sub-directory, containing a complete test mill project.

* `def testCases: T[Seq[PathRef]]` -
  The directories each representing a mill test case.
  Derived from `sources`.

* `def testTargets: T[Seq[String]]` -
  The targets which are called to test the project.
  Defaults to `verify`, which should implement test result validation.

* `def downloadMillTestVersion: T[PathRef]` -
  Download the mill version as defined by `millTestVersion`.
  Override this, if you need to use a custom built mill version.
  Returns the `PathRef` to the mill executable (must have the executable flag).

##### Commands

* `def test(): Command[Unit]` -
  Run the integration tests.


### JBake

Create static sites/blogs with JBake.

Plugin home: https://github.com/lefou/mill-jbake

JBake home: https://jbake.org

#### Quickstart

```scala
// build.sc
import mill._
import $ivy.`de.tototec::de.tobiasroeser.mill.jbake:0.1.0`
import de.tobiasroeser.mill.jbake._

object site extends JBakeModule {

  def jbakeVersion = "2.6.4"

}
```

Generate the site:

```sh
bash> mill site.jbake
```

Start a local Web-Server on Port 8820 with the generated site:

```sh
bash> mill site.jbakeServe
```


### OSGi

Produce OSGi Bundles with mill.

Project home: https://github.com/lefou/mill-osgi

#### Quickstart

```scala
import mill._, mill.scalalib._
import $ivy.`de.tototec::de.tobiasroeser.mill.osgi:0.0.5`
import de.tobiasroeser.mill.osgi._

object project extends ScalaModule with OsgiBundleModule {

  def bundleSymbolicName = "com.example.project"

  def osgiHeaders = T{ super.osgiHeaders().copy(
    `Export-Package`   = Seq("com.example.api"),
    `Bundle-Activator` = Some("com.example.internal.Activator")
  )}

  // other settings ...

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
