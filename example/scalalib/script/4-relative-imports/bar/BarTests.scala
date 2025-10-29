//| extends: [mill.script.ScalaModule.Utest]
//| moduleDeps: [./Bar.scala]
//| mvnDeps:
//| - com.lihaoyi::utest:0.9.1
package bar
import utest.*
object BarTests extends TestSuite {
  def tests = Tests {
    assert(generateHtml("hello") == "<h1>hello</h1>")
  }
}
