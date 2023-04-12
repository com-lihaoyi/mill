package hello

object Hello{
  def main(): Int = used()
  def used(): Int = 2
  def unused(): Int = 1
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello$#main()I": [
        "hello.Hello$#used()I"
    ],
    "hello.Hello.main()I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#main()I"
    ],
    "hello.Hello.unused()I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#unused()I"
    ],
    "hello.Hello.used()I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#used()I"
    ]
}
*/
