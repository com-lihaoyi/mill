package hello

object Hello{
  def main(): Int = {

    val foo = new Function0[Int]{def apply() = used() }
    foo()
  }
  def used(): Int = 2
}

// Similar to the `java-anon-class-lambda` test case, but for a Scala "lambda"

/* expected-direct-call-graph
{
   "hello.Hello$#<init>()void": [
       "hello.Hello$$anon$1#toString()java.lang.String"
   ],
   "hello.Hello$#main()int": [
       "hello.Hello$$anon$1#<init>()void",
       "hello.Hello$$anon$1#apply$mcB$sp()byte",
       "hello.Hello$$anon$1#apply$mcC$sp()char",
       "hello.Hello$$anon$1#apply$mcD$sp()double",
       "hello.Hello$$anon$1#apply$mcF$sp()float",
       "hello.Hello$$anon$1#apply$mcI$sp()int",
       "hello.Hello$$anon$1#apply$mcJ$sp()long",
       "hello.Hello$$anon$1#apply$mcS$sp()short",
       "hello.Hello$$anon$1#apply$mcV$sp()void",
       "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
       "hello.Hello$$anon$1#toString()java.lang.String"
   ],
   "hello.Hello$$anon$1#<init>()void": [
       "hello.Hello$$anon$1#apply$mcB$sp()byte",
       "hello.Hello$$anon$1#apply$mcC$sp()char",
       "hello.Hello$$anon$1#apply$mcD$sp()double",
       "hello.Hello$$anon$1#apply$mcF$sp()float",
       "hello.Hello$$anon$1#apply$mcI$sp()int",
       "hello.Hello$$anon$1#apply$mcJ$sp()long",
       "hello.Hello$$anon$1#apply$mcS$sp()short",
       "hello.Hello$$anon$1#apply$mcV$sp()void",
       "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
       "hello.Hello$$anon$1#apply()java.lang.Object",
       "hello.Hello$$anon$1#toString()java.lang.String"
   ],
   "hello.Hello$$anon$1#apply()int": [
       "hello.Hello$$anon$1#apply$mcI$sp()int"
   ],
   "hello.Hello$$anon$1#apply()java.lang.Object": [
       "hello.Hello$$anon$1#apply()int"
   ],
   "hello.Hello$$anon$1#toString()java.lang.String": [
       "hello.Hello$$anon$1#apply$mcB$sp()byte",
       "hello.Hello$$anon$1#apply$mcC$sp()char",
       "hello.Hello$$anon$1#apply$mcD$sp()double",
       "hello.Hello$$anon$1#apply$mcF$sp()float",
       "hello.Hello$$anon$1#apply$mcI$sp()int",
       "hello.Hello$$anon$1#apply$mcJ$sp()long",
       "hello.Hello$$anon$1#apply$mcS$sp()short",
       "hello.Hello$$anon$1#apply$mcV$sp()void",
       "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
       "hello.Hello$$anon$1#toString()java.lang.String"
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
    "hello.Hello$#<init>()void": [
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$#main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#<init>()void",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#apply()java.lang.Object",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#<init>()void": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#apply()java.lang.Object",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcB$sp()byte": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcC$sp()char": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcD$sp()double": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcF$sp()float": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcI$sp()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcJ$sp()long": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcS$sp()short": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcV$sp()void": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply$mcZ$sp()boolean": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#apply()java.lang.Object": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello$$anon$1#toString()java.lang.String": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean"
    ],
    "hello.Hello.main()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()int",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#<init>()void",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#apply()int",
        "hello.Hello$$anon$1#apply()java.lang.Object",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ],
    "hello.Hello.used()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#used()int",
        "hello.Hello$$anon$1#apply$mcB$sp()byte",
        "hello.Hello$$anon$1#apply$mcC$sp()char",
        "hello.Hello$$anon$1#apply$mcD$sp()double",
        "hello.Hello$$anon$1#apply$mcF$sp()float",
        "hello.Hello$$anon$1#apply$mcI$sp()int",
        "hello.Hello$$anon$1#apply$mcJ$sp()long",
        "hello.Hello$$anon$1#apply$mcS$sp()short",
        "hello.Hello$$anon$1#apply$mcV$sp()void",
        "hello.Hello$$anon$1#apply$mcZ$sp()boolean",
        "hello.Hello$$anon$1#toString()java.lang.String"
    ]
}
*/
