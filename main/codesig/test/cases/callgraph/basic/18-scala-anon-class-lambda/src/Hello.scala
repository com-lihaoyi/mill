package hello

object Hello {

  trait MyFunction0[T] {
    def apply(): T
  }

  def main(): Int = {

    val foo = new MyFunction0[Int] { def apply() = used() }
    foo()
  }
  def used(): Int = 2
}

// Similar to the `java-anon-class-lambda` test case, but for a Scala "lambda"

/* expected-direct-call-graph
{
    "hello.Hello$#main()int": [
        "hello.Hello$$anon$1#<init>()void"
    ],
    "hello.Hello$$anon$1#<init>()void": [
        "hello.Hello$$anon$1#apply()java.lang.Object"
    ],
    "hello.Hello$$anon$1#apply()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ],
    "hello.Hello$$anon$1#apply()java.lang.Object": [
        "hello.Hello$$anon$1#apply()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()int"
    ],
    "hello.Hello.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ]
}
 */

/* expected-transitive-call-graph
{
    "hello.Hello$#main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#<init>()void",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#apply()java.lang.Object"
    ],
    "hello.Hello$$anon$1#<init>()void": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#apply()java.lang.Object"
    ],
    "hello.Hello$$anon$1#apply()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ],
    "hello.Hello$$anon$1#apply()java.lang.Object": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()int",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#<init>()void",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#apply()java.lang.Object"
    ],
    "hello.Hello.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ]
}
 */
