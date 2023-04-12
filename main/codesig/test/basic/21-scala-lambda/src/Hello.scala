package hello
object Hello{
  def main(): Int = {

    val foo = () => used()
    return foo()
  }
  def used(): Int = 2
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
    "hello.Hello.used()I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#used()I"
    ]
}
*/
