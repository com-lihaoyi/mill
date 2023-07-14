/**
 * Make sure wehandle the case
 */

package hello

trait MyTrait{
  Hello.used()
  def used() = 1
}
object MyObject extends MyTrait{

}
object Hello{
  def main(): Int = MyObject.used()
  def used(): Int = 2
  def unused(): Int = 1
}

/* expected-direct-call-graph
{
    "hello.Hello$#main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.MyObject$#<init>()void",
        "hello.MyObject$#used()int"
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
    ],
    "hello.MyObject$#used()int": [
        "hello.MyTrait.used$(hello.MyTrait)int"
    ],
    "hello.MyObject.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.MyObject$#<init>()void",
        "hello.MyObject$#used()int"
    ],
    "hello.MyTrait.used$(hello.MyTrait)int": [
        "hello.MyTrait#used()int"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Hello$#main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.MyObject$#<init>()void",
        "hello.MyObject$#used()int",
        "hello.MyTrait#used()int",
        "hello.MyTrait.used$(hello.MyTrait)int"
    ],
    "hello.Hello.main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()int",
        "hello.Hello$#used()int",
        "hello.MyObject$#<init>()void",
        "hello.MyObject$#used()int",
        "hello.MyTrait#used()int",
        "hello.MyTrait.used$(hello.MyTrait)int"
    ],
    "hello.Hello.unused()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#unused()int"
    ],
    "hello.Hello.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int"
    ],
    "hello.MyObject$#used()int": [
        "hello.MyTrait#used()int",
        "hello.MyTrait.used$(hello.MyTrait)int"
    ],
    "hello.MyObject.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.MyObject$#<init>()void",
        "hello.MyObject$#used()int",
        "hello.MyTrait#used()int",
        "hello.MyTrait.used$(hello.MyTrait)int"
    ],
    "hello.MyTrait.used$(hello.MyTrait)int": [
        "hello.MyTrait#used()int"
    ]
}
*/
