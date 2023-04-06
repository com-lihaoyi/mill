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
    "hello.Hello$.<clinit>()V": [
        "hello.Hello$#<init>()V"
    ],
    "hello.Hello.main()I": [
        "hello.Hello$#main()I",
        "hello.Hello$#used()I"
    ],
    "hello.Hello.unused()I": [
        "hello.Hello$#unused()I"
    ],
    "hello.Hello.used()I": [
        "hello.Hello$#used()I"
    ]
}
*/
