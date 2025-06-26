package hello

object Hello {
  def main(): Int = used
  lazy val used: Int = used2
  def used2 = 2
  lazy val unused: Int = 1
}

/* expected-direct-call-graph
{
  "hello.Hello$#main()int": [
    "hello.Hello$#used()int"
  ],
  "hello.Hello$#unused()int": [
    "hello.Hello$#unused$lzyINIT1()java.lang.Object"
  ],
  "hello.Hello$#used$lzyINIT1()java.lang.Object": [
    "hello.Hello$#used2()int"
  ],
  "hello.Hello$#used()int": [
    "hello.Hello$#used$lzyINIT1()java.lang.Object"
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
  "hello.Hello.used2()int": [
    "hello.Hello$#<init>()void",
    "hello.Hello$#used2()int"
  ]
}
*/

/* expected-transitive-call-graph
{
  "hello.Hello$#main()int": [
    "hello.Hello$#used$lzyINIT1()java.lang.Object",
    "hello.Hello$#used()int",
    "hello.Hello$#used2()int"
  ],
  "hello.Hello$#unused()int": [
    "hello.Hello$#unused$lzyINIT1()java.lang.Object"
  ],
  "hello.Hello$#used$lzyINIT1()java.lang.Object": [
    "hello.Hello$#used2()int"
  ],
  "hello.Hello$#used()int": [
    "hello.Hello$#used$lzyINIT1()java.lang.Object",
    "hello.Hello$#used2()int"
  ],
  "hello.Hello.main()int": [
    "hello.Hello$#<init>()void",
    "hello.Hello$#main()int",
    "hello.Hello$#used$lzyINIT1()java.lang.Object",
    "hello.Hello$#used()int",
    "hello.Hello$#used2()int"
  ],
  "hello.Hello.unused()int": [
    "hello.Hello$#<init>()void",
    "hello.Hello$#unused$lzyINIT1()java.lang.Object",
    "hello.Hello$#unused()int"
  ],
  "hello.Hello.used()int": [
    "hello.Hello$#<init>()void",
    "hello.Hello$#used$lzyINIT1()java.lang.Object",
    "hello.Hello$#used()int",
    "hello.Hello$#used2()int"
  ],
  "hello.Hello.used2()int": [
    "hello.Hello$#<init>()void",
    "hello.Hello$#used2()int"
  ]
}
*/
