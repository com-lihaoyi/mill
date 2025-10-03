//| extends: mill.simple.KotlinModule.Junit5
//| moduleDeps: [Foo.kt]
//| mvnDeps:
//| - "io.kotest:kotest-runner-junit5:5.9.1"
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FooTest :
    FunSpec({
        test("testSimple") {
            generateHtml("hello") shouldBe "<h1>hello</h1>"
        }

        test("testEscaping") {
            generateHtml("<hello>") shouldBe "<h1>&lt;hello&gt;</h1>"
        }
    })
