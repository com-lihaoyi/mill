package hello

object Hello{
  def main(): Int = {

    val foo = () => used()
    foo()
  }
  def used(): Int = 2
}

/* EXPECTED TRANSITIVE
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
