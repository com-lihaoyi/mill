import mill._

val valueFoo = 0
val valueFooUsedInBar = 0
def helperFoo = { println("running helperFoo"); 1 + valueFoo }
def foo = T{ println("running foo"); helperFoo }

object outer extends Module{
  val valueBar = 0
  val valueBarUsedInQux = 0
  def helperBar = { println("running helperBar"); 20 }
  def bar = T{ println("running bar"); helperBar + valueBar + valueFooUsedInBar }

  trait InnerModule extends Module{
    val valueQux = 0
    def helperQux = { println("running helperQux"); 300 + valueQux + valueBarUsedInQux }
    def qux = T{ println("running qux"); foo() + bar() + helperQux }
  }
  object inner extends InnerModule
}