## Contrib Modules

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

### twirllib 

Twirl templates support.

To declare a module that needs to compile twirl templates you must extend the `mill.twirllib.TwirlModule` trait when defining your module. 
Also note that twirl templates get compiled into scala code, so you also need to extend `ScalaModule`.
 
```scala
import $ivy.`com.lihaoyi::mill-contrib-twirllib:0.3.2`,  mill.twirllib._
object app extends ScalaModule with TwirlModule {

} 
``` 

#### Configuration options

* ` def twirlVersion: T[String]` (mandatory) - the version of the twirl compiler to use, like "1.3.15"

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

#### Caveats

There is a couple of caveats, to be aware of, as of now (in `v0.3.2`).

##### Packages 
First, if you structure your twirl templates into packages, like this:
```text
build.sc
app/
    src/hello/
        Main.scala
    views/
        hello/
            another/ 
                view1.scala.html
                view2.scala.html
```

the generated sources in the `out` directory will look like this:
```text
build.sc
out/app/compileTwirl/dest/
    hello/
      another/
        html/
          view1.template.scala
          view2.template.scala
```

Looking at the `mill show app.compileTwirl` in this setup shows this:
```
{
    ...
    "classes": "ref: ... : .../out/app/compileTwirl/dest/html"
}
```

Basically it means that currently `TwirlModule` expects all templates to be html and with no packages.
So adding this directly to the generated sources will not exactly work as expected (as there might not even be a `out/app/compileTwirl/dest/html` directory
at all, unless you have templates in the default package).

The workaround is simple, though:
```scala
object app extends ScalaModule with TwirlModule {
  def twirlVersion = "1.3.15"
  override def generatedSources = T{
    val classes = compileTwirl().classes
    Seq(classes.copy(path = classes.path / up)) // we just move one dir up
  }
}
``` 

This should cover the problem with templates under packages, and also should make other-than-html 
templates available as well.

##### Default imports

Another problem is with some default imports that the twirl sbt plugin assumes, but it seems not to work with `TwirlModule`.

If you reference `Html` in your templates, like

```scala
// wrapper.scala.html
@(content: Html)
<div class="wrapper">
  @content
</div>
```

the template will not compile. You'll need to add this import:
```
@import play.twirl.api._
```

in the template that uses twirl classes.

Another one is `@defining`, which might be used like this:
```
@defining({
  val calculatedClass = {
    // do some calculations here
  }
  calculatedClass
}) { calculatedClass =>
    <div class="@calculatedClass">stuff 1</div>
    <div class="@calculatedClass">stuff 2</div>
}
```

You'll need this import:
```scala
@import play.twirl.api.TwirlFeatureImports._
```

At some point `TwirlModule` might get support for the additional "default" imports, which will make this much easier, 
but right now it is unimplemented

```scala
  // REMIND currently it's not possible to override these default settings
  private def twirlAdditionalImports: Seq[String] = Nil
```

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

