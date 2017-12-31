## Jawn

"Jawn is for parsing jay-sawn."

### Origin

The term "jawn" comes from the Philadelphia area. It conveys about as
much information as "thing" does. I chose the name because I had moved
to Montreal so I was remembering Philly fondly. Also, there isn't a
better way to describe objects encoded in JSON than "things". Finally,
we get a catchy slogan.

Jawn was designed to parse JSON into an AST as quickly as possible.

[![Build Status](https://api.travis-ci.org/non/jawn.svg)](https://travis-ci.org/non/jawn)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/non/jawn?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Latest version](https://index.scala-lang.org/non/jawn/jawn-parser/latest.svg?color=orange)](https://index.scala-lang.org/non/jawn/jawn-parser)

### Overview

Jawn consists of four parts:

1. A fast, generic JSON parser (`jawn-parser`)
2. A small, somewhat anemic AST (`jawn-ast`)
3. Support packages which parse to third-party ASTs
4. A few helpful utilities (`jawn-util`)

Currently Jawn is competitive with the fastest Java JSON libraries
(GSON and Jackson) and in the author's benchmarks it often wins. It
seems to be faster than any other Scala parser that exists (as of July
2014).

Given the plethora of really nice JSON libraries for Scala, the
expectation is that you're probably here for `jawn-parser` or a
support package.

### Quick Start

Jawn supports Scala 2.10, 2.11, and 2.12.

Here's a `build.sbt` snippet that shows you how to depend on Jawn in
your own SBT project:

```scala
resolvers += Resolver.sonatypeRepo("releases")

// use this if you just want jawn's parser, and will implement your own facade
libraryDependencies += "org.spire-math" %% "jawn-parser" % "0.11.0"

// use this if you want jawn's parser and also jawn's ast
libraryDependencies += "org.spire-math" %% "jawn-ast" % "0.11.0"
```

If you want to use Jawn's parser with another project's AST, see the
"Supporting external ASTs with Jawn" section. For example, with Spray
you would say:

```scala
libraryDependencies += "org.spire-math" %% "jawn-spray" % "0.11.0"
```

There are a few reasons you might want to do this:

 * The library's built-in parser is significantly slower than Jawn's.
 * Jawn supports more input types (`ByteBuffer`, `File`, etc.).
 * You need asynchronous JSON parsing.

(NOTE: previous to version 0.8.3 the support libraries would have been
named `"spray-support"` instead of `"jawn-spray"`.)

### Dependencies

*jawn-parser* has no dependencies other than Scala.

*jawn-ast* depends on *jawn-parser* but nothing else.

The various support projects (e.g. *jawn-argonaut*) depend on
the library they are supporting.

### Parsing

Jawn's parser is both fast and relatively featureful. Assuming you
want to get back an AST of type `J` and you have a `Facade[J]`
defined, you can use the following `parse` signatures:

```scala
Parser.parseUnsafe[J](String) → J
Parser.parseFromString[J](String) → Try[J]
Parser.parsefromPath[J](String) → Try[J]
Parser.parseFromFile[J](File) → Try[J]
Parser.parseFromChannel[J](ReadableByteChannel) → Try[J]
Parser.parseFromByteBuffer[J](ByteBuffer) → Try[J]
```

Jawn also supports asynchronous parsing, which allows users to feed
the parser with data as it is available. There are three modes:

* `SingleValue` waits to return a single `J` value once parsing is done.
* `UnwrapArray` if the top-level element is an array, return values as they become available.
* `ValueStream` parse one-or-more json values separated by whitespace.

Here's an example:

```scala
import jawn.ast
import jawn.AsyncParser
import jawn.ParseException

val p = ast.JParser.async(mode = AsyncParser.UnwrapArray)

def chunks: Stream[String] = ???
def sink(j: ast.JValue): Unit = ???

def loop(st: Stream[String]): Either[ParseException, Unit] =
  st match {
    case s #:: tail =>
      p.absorb(s) match {
        case Right(js) =>
          js.foreach(sink)
          loop(tail)
        case Left(e) =>
          Left(e)
      }
    case _ =>
      p.finish().right.map(_.foreach(sink))
  }

loop(chunks)
```

You can also call `jawn.Parser.async[J]` to use async parsing with an
arbitrary data type (provided you also have an implicit `Facade[J]`).

### Supporting external ASTs with Jawn

Jawn currently supports six external ASTs directly:

| AST       | 2.10   | 2.11   | 2.12  |
|-----------|--------|--------|-------|
| Argonaut  | 6.2    | 6.2    | 6.2   |
| Json4s    | 3.5.2  | 3.5.2  | 3.5.2 |
| Play-json | 2.4.11 | 2.5.15 | 2.6.0 |
| Rojoma    | 2.4.3  | 2.4.3  | 2.4.3 |
| Rojoma-v3 | 3.7.2  | 3.7.2  | 3.7.2 |
| Spray     | 1.3.3  | 1.3.3  | 1.3.3 |

Each of these subprojects provides a `Parser` object (an instance of
`SupportParser[J]`) that is parameterized on the given project's
AST (`J`). The following methods are available:

```scala
Parser.parseUnsafe(String) → J
Parser.parseFromString(String) → Try[J]
Parser.parsefromPath(String) → Try[J]
Parser.parseFromFile(File) → Try[J]
Parser.parseFromChannel(ReadableByteChannel) → Try[J]
Parser.parseFromByteBuffer(ByteBuffer) → Try[J]
```

These methods parallel those provided by `jawn.Parser`.

For the following snippets, `XYZ` is one of (`argonaut`, `json4s`,
`play`, `rojoma`, `rojoma-v3` or `spray`):

This is how you would include the subproject in build.sbt:

```scala
resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies += "org.spire-math" %% jawn-"XYZ" % "0.11.0"
```

This is an example of how you might use the parser into your code:

```scala
import jawn.support.XYZ.Parser

val myResult = Parser.parseFromString(myString)
```

### Do-It-Yourself Parsing

Jawn supports building any JSON AST you need via type classes. You
benefit from Jawn's fast parser while still using your favorite Scala
JSON library. This mechanism is also what allows Jawn to provide
"support" for other libraries' ASTs.

To include Jawn's parser in your project, add the following
snippet to your `build.sbt` file:

```scala
resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies += "org.spire-math" %% "jawn-parser" % "0.11.0"
```

To support your AST of choice, you'll want to define a `Facade[J]`
instance, where the `J` type parameter represents the base of your JSON
AST. For example, here's a facade that supports Spray:

```scala
import spray.json._
object Spray extends SimpleFacade[JsValue] {
  def jnull() = JsNull
  def jfalse() = JsFalse
  def jtrue() = JsTrue
  def jnum(s: String) = JsNumber(s)
  def jint(s: String) = JsNumber(s)
  def jstring(s: String) = JsString(s)
  def jarray(vs: List[JsValue]) = JsArray(vs)
  def jobject(vs: Map[String, JsValue]) = JsObject(vs)
}
```

Most ASTs will be easy to define using the `SimpleFacade` or
`MutableFacade` traits. However, if an ASTs object or array instances
do more than just wrap a Scala collection, it may be necessary to
extend `Facade` directly.

You can also look at the facades used by the support projects to help
you create your own. This could also be useful if you wanted to
use an older version of a supported library.

### Using the AST

#### Access

For accessing atomic values, `JValue` supports two sets of
methods: *get-style* methods and *as-style* methods.

The *get-style* methods return `Some(_)` when called on a compatible
JSON value (e.g. strings can return `Some[String]`, numbers can return
`Some[Double]`, etc.), and `None` otherwise:

```scala
getBoolean → Option[Boolean]
getString → Option[String]
getLong → Option[Long]
getDouble → Option[Double]
getBigInt → Option[BigInt]
getBigDecimal → Option[BigDecimal]
```

In constrast, the *as-style* methods will either return an unwrapped
value (instead of returning `Some(_)`) or throw an exception (instead
of returning `None`):

```scala
asBoolean → Boolean // or exception
asString → String // or exception
asLong → Long // or exception
asDouble → Double // or exception
asBigInt → BigInt // or exception
asBigDecimal → BigDecimal // or exception
```

To access elements of an array, call `get` with an `Int` position:

```scala
get(i: Int) → JValue // returns JNull if index is illegal
```

To access elements of an object, call `get` with a `String` key:

```scala
get(k: String) → JValue // returns JNull if key is not found
```

Both of these methods also return `JNull` if the value is not the
appropraite container. This allows the caller to chain lookups without
having to check that each level is correct:

```scala
val v: JValue = ???

// returns JNull if a problem is encountered in structure of 'v'.
val t: JValue = v.get("novels").get(0).get("title")

// if 'v' had the right structure and 't' is JString(s), then Some(s).
// otherwise, None.
val titleOrNone: Option[String] = t.getString

// equivalent to titleOrNone.getOrElse(throw ...)
val titleOrDie: String = t.asString
```

#### Updating

The atomic values (`JNum`, `JBoolean`, `JNum`, and `JString`) are
immutable.

Objects are fully-mutable and can have items added, removed, or
changed:

```scala
set(k: String, v: JValue) → Unit
remove(k: String) → Option[JValue]
```

If `set` is called on a non-object, an exception will be thrown.
If `remove` is called on a non-object, `None` will be returned.

Arrays are semi-mutable. Their values can be changed, but their size
is fixed:

```scala
set(i: Int, v: JValue) → Unit
```

If `set` is called on a non-array, or called with an illegal index, an
exception will be thrown.

(A future version of Jawn may provide an array whose length can be
changed.)

### Profiling

Jawn uses [JMH](http://openjdk.java.net/projects/code-tools/jmh/)
along with the [sbt-jmh](https://github.com/ktoso/sbt-jmh) plugin.

#### Running Benchmarks

The benchmarks are located in the `benchmark` project. You can run the
benchmarks by typing `benchmark/run` from SBT. There are many
supported arguments, so here are a few examples:

Run all benchmarks, with 10 warmups, 10 iterations, using 3 threads:

`benchmark/run -wi 10 -i 10 -f1 -t3`

Run just the `CountriesBench` test (5 warmups, 5 iterations, 1 thread):

`benchmark/run -wi 5 -i 5 -f1 -t1 .*CountriesBench`

#### Benchmark Issues

Currently, the benchmarks are a bit fiddily. The most obvious symptom
is that if you compile the benchmarks, make changes, and compile
again, you may see errors like:

```
[error] (benchmark/jmh:generateJavaSources) java.lang.NoClassDefFoundError: jawn/benchmark/Bla25Bench
```

The fix here is to run `benchmark/clean` and try again.

You will also see intermittent problems like:

```
[error] (benchmark/jmh:compile) java.lang.reflect.MalformedParameterizedTypeException
```

The solution here is easier (though frustrating): just try it
again. If you continue to have problems, consider cleaning the project
and trying again.

(In the future I hope to make the benchmarking here a bit more
resilient. Suggestions and pull requests gladly welcome!)

#### Files

The benchmarks use files located in `benchmark/src/main/resources`. If
you want to test your own files (e.g. `mydata.json`), you would:

 * Copy the file to `benchmark/src/main/resources/mydata.json`.
 * Add the following code to `JmhBenchmarks.scala`:

```scala
class MyDataBench extends JmhBenchmarks("mydata.json")
```

Jawn has been tested with much larger files, e.g. 100M - 1G, but these
are obviously too large to ship with the project.

With large files, it's usually easier to comment out most of the
benchmarking methods and only test one (or a few) methods. Some of the
slower JSON parsers get *much* slower for large files.

#### Interpreting the results

Remember that the benchmarking results you see will vary based on:

 * Hardware
 * Java version
 * JSON file size
 * JSON file structure
 * JSON data values

I have tried to use each library in the most idiomatic and fastest way
possible (to parse the JSON into a simple AST). Pull requests to
update library versions and improve usage are very welcome.

### Future Work

More support libraries could be added.

It's likely that some of Jawn's I/O could be optimized a bit more, and
also made more configurable. The heuristics around all-at-once loading
versus input chunking could definitely be improved.

In cases where the user doesn't need fast lookups into JSON objects,
an even lighter AST could be used to improve parsing and rendering
speeds.

Strategies to cache/intern field names of objects could pay big
dividends in some cases (this might require AST changes).

If you have ideas for any of these (or other ideas) please feel free
to open an issue or pull request so we can talk about it.

### Disclaimers

Jawn only supports UTF-8 when parsing bytes. This might change in the
future, but for now that's the target case. You can always decode your
data to a string, and handle the character set decoding using Java's
standard tools.

Jawn's AST is intended to be very lightweight and simple. It supports
simple access, and limited mutable updates. It intentionally lacks the
power and sophistication of many other JSON libraries.

### Copyright and License

All code is available to you under the MIT license, available at
http://opensource.org/licenses/mit-license.php.

Copyright Erik Osheim, 2012-2017.
