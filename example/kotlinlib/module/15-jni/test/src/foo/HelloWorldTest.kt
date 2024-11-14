package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HelloWorldTest : FunSpec({ test("simple") { HelloWorld().sayHello() shouldBe "Hello, World!" } })
