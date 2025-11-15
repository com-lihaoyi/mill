//| extends: [mill.script.ScalaModule.Utest]
//| moduleDeps: [./Foo.scala]
//| mvnDeps:
//| - "com.lihaoyi::utest:0.9.1"

import utest.*

object FooTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = generateHtml("hello")
      assert(result == "<h1>hello</h1>")
      result
    }
    test("escaping") {
      pprint.log(Seq.tabulate(50)(_.toString).mkString("\n"))
      val result = generateHtml("<hello>")
      assert(result == "<h1>&lt;hello&gt;</h1>")
      result
    }
  }
}
