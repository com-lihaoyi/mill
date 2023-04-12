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
        "hello.Hello$#used()I",
        "hello.Hello$.$anonfun$main$1()I"
    ],
    "hello.Hello$.<clinit>()V": [
        "hello.Hello$#<init>()V"
    ],
    "hello.Hello.main()I": [
        "hello.Hello$#main()I",
        "hello.Hello$#used()I",
        "hello.Hello$.$anonfun$main$1()I"
    ],
    "hello.Hello.used()I": [
        "hello.Hello$#used()I"
    ]
}
*/
