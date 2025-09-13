//| mvnDeps:
//| - "com.lihaoyi::utest:0.9.1"
//| tests: Foo.scala
//| testTrait: Utest
package foo

import utest.*

object FooTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = Foo.generateHtml("hello")
      assert(result == "<h1>hello</h1>")
      result
    }
    test("escaping") {
      val result = Foo.generateHtml("<hello>")
      assert(result == "<h1>&lt;hello&gt;</h1>")
      result
    }
  }
}
