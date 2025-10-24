//| extends: [mill.script.KotlinModule.Junit5]
//| moduleDeps: [./Bar.kt]
//| mvnDeps:
//| - io.kotest:kotest-runner-junit5:5.9.1
//| - com.github.sbt.junit:jupiter-interface:0.11.2
package bar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BarTests :
    FunSpec({

        test("simple") {
            val result = generateHtml("hello")
            result shouldBe "<h1>hello</h1>"
        }

        test("escaping") {
            val result = generateHtml("<hello>")
            result shouldBe "<h1>&lt;hello&gt;</h1>"
        }
    })
