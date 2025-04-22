package hello

object Hello {

  trait MyFunction0[T] {
    def apply(): T
  }

  def main(): Int = {

    val foo: MyFunction0[Int] = () => used()
    foo()
  }
  def used(): Int = 2
}

/* expected-direct-call-graph
{
    "hello.Hello$#main()int": [
        "hello.Hello$#used()int"
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
        "hello.Hello$#used()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()int",
        "hello.Hello$#used()int"
    ],
    "hello.Hello.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ]
}
 */
