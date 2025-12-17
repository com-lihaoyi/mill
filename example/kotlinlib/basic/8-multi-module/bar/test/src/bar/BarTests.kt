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
