import mill._

val crossMatrix = for {
  crossVersion <- Seq("210", "211", "212")
  platform <- Seq("jvm", "js", "native")
  if !(platform == "native" && crossVersion != "212")
} yield (crossVersion, platform)

object foo extends mill.Cross[FooModule](crossMatrix)
trait FooModule extends Cross.Module2[String, String] {
  val (crossVersion, platform) = (crossValue, crossValue2)
  def suffix = T { crossVersion + "_" + platform }
}

def bar = T { s"hello ${foo("2.10", "jvm").suffix()}" }

// This example shows off the core logic of `Cross` modules

/* Example Usage

> mill showNamed foo[_].suffix
{
  "foo[210,jvm].suffix": "210_jvm",
  "foo[210,js].suffix": "210_js",
  "foo[211,jvm].suffix": "211_jvm",
  "foo[211,js].suffix": "211_js",
  "foo[212,jvm].suffix": "212_jvm",
  "foo[212,js].suffix": "212_js",
  "foo[212,native].suffix": "212_native"
}

*/