package hello

object Hello{
  def main(): Int = used()
  def used(): Int = 2
  def unused(): Int = 1
}

/* EXPECTED DEPENDENCIES
{
    "hello.Hello$#main()int": [
        "hello.Hello$#used()int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()int"
    ],
    "hello.Hello.unused()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#unused()int"
    ],
    "hello.Hello.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ]
}
*/
