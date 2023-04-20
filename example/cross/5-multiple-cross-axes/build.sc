// == Multiple Cross Axes
//
// You can have a cross-module with multiple inputs:
import mill._

val crossMatrix = for {
  crossVersion <- Seq("2.10", "2.11", "2.12")
  platform <- Seq("jvm", "js", "native")
  if !(platform == "native" && crossVersion != "2.12")
} yield (crossVersion, platform)

object foo extends mill.Cross[FooModule](crossMatrix)
trait FooModule extends Cross.Module2[String, String] {
  val (crossVersion, platform) = (crossValue, crossValue2)
  def suffix = T { "_" + crossVersion + "_" + platform }
}

def bar = T { s"hello ${foo("2.10", "jvm").suffix()}" }

// This example shows off using a for-loop to generate a list of
// cross-key-tuples, as a `Seq[(String, String)]` that we then pass it into the
// `Cross` constructor. These can be referenced from the command line as shown
// below, or referenced in other parts of your `build.sc` as shown in `def bar`
// above.
//
// In this example we assigned `crossValue` and `crossValue2` to the names
// `crossVersion` and `platform` for readability.

/** Example Usage

> ./mill show foo[2.10,jvm].suffix
"_2.10_jvm"

> ./mill showNamed foo[__].suffix
{
  "foo[2.10,jvm].suffix": "_2.10_jvm",
  "foo[2.10,js].suffix": "_2.10_js",
  "foo[2.11,jvm].suffix": "_2.11_jvm",
  "foo[2.11,js].suffix": "_2.11_js",
  "foo[2.12,jvm].suffix": "_2.12_jvm",
  "foo[2.12,js].suffix": "_2.12_js",
  "foo[2.12,native].suffix": "_2.12_native"
}

*/