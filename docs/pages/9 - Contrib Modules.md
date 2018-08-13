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

### ScalaJS - JsDependencies

This modules allows a ScalaJS build to *use* dependencies that declare dependencies on JavaScript libraries via `jsDependencies` in their (sbt) buildfile. 

It is usually only needed for older scalajs libraries and is mainly a re-write of the [jsdependencies](https://github.com/scala-js/jsdependencies "jsdependencies") sbt-plugin for mill.

It does not allow a mill build to declare dependencies on JavaScript libraries.

To declare a module that uses JsDependencies you can extend the `mill.contrib.ScalaJSDependencies` trait when defining your module.

#### Configuration options

* `def useMinifiedJSDependencies: T[Boolean]`
  If set to `true` the plugin will try to use the minified version of the JavaScript library.
  
* `def jsdependenciesOutputFileName: T[String]`
  Name of the generated file that contains all the dependent JavaScript libraries.
  Defaults to `out-deps.js` (or `out-deps-min.js` when `useMinifiedJSDependencies` is set to `true`) 


