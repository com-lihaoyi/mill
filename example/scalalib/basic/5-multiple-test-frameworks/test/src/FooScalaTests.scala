package foo

import org.scalatest.freespec._

class FooScalaTests extends AnyFreeSpec {
  "Foo" - {
    "simple" in {
      val result = Foo.generateHtml("hello")
      assert(result == "<h1>hello</h1>")
      result
    }
    "escaping" in {
      val result = Foo.generateHtml("<hello>")
      assert(result == "<h1>&lt;hello&gt;</h1>")
      result
    }
  }
}
