package hello

class Foo extends Root {
  def called(): Unit = ()
  override def onInit(): Unit = called()
}

class Bar extends Root {
  def other(): Unit = ()
  override def onInit(): Unit = other()
  override def jdkCommandsJavaHome(): String = "bar"
}

object Hello {
  def main(): Unit = {
    new Foo()
  }
}

// Regression test:
// `new Foo()` should not conservatively fan out from Root.<init> to methods on sibling class Bar.
/* expected-direct-call-graph
{
    "hello.Bar#<init>()void": [
        "hello.Bar#jdkCommandsJavaHome()java.lang.String",
        "hello.Bar#onInit()void"
    ],
    "hello.Bar#onInit()void": [
        "hello.Bar#other()void"
    ],
    "hello.Foo#<init>()void": [
        "hello.Foo#onInit()void"
    ],
    "hello.Foo#onInit()void": [
        "hello.Foo#called()void"
    ],
    "hello.Hello$#main()void": [
        "hello.Foo#<init>()void"
    ],
    "hello.Hello.main()void": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()void"
    ]
}
 */
