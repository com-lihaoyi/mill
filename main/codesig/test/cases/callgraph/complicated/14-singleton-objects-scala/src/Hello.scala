package hello

trait MyModule{
  def x = "123"
  def y = "456"
}

object Foo extends MyModule{
  override def x = super.x + "abc"
}

object Bar extends MyModule{
  override def y = super.y + "abc" + Foo.x
}


/* EXPECTED CALL GRAPH
{
    "hello.Bar$#x()java.lang.String": [
        "hello.MyModule.x$(hello.MyModule)java.lang.String"
    ],
    "hello.Bar$#y()java.lang.String": [
        "hello.Foo$#<init>()void",
        "hello.Foo$#x()java.lang.String",
        "hello.MyModule.y$(hello.MyModule)java.lang.String"
    ],
    "hello.Bar.x()java.lang.String": [
        "hello.Bar$#<init>()void",
        "hello.Bar$#x()java.lang.String"
    ],
    "hello.Bar.y()java.lang.String": [
        "hello.Bar$#<init>()void",
        "hello.Bar$#y()java.lang.String"
    ],
    "hello.Foo$#x()java.lang.String": [
        "hello.MyModule.x$(hello.MyModule)java.lang.String"
    ],
    "hello.Foo$#y()java.lang.String": [
        "hello.MyModule.y$(hello.MyModule)java.lang.String"
    ],
    "hello.Foo.x()java.lang.String": [
        "hello.Foo$#<init>()void",
        "hello.Foo$#x()java.lang.String"
    ],
    "hello.Foo.y()java.lang.String": [
        "hello.Foo$#<init>()void",
        "hello.Foo$#y()java.lang.String"
    ],
    "hello.MyModule.x$(hello.MyModule)java.lang.String": [
        "hello.MyModule#x()java.lang.String"
    ],
    "hello.MyModule.y$(hello.MyModule)java.lang.String": [
        "hello.MyModule#y()java.lang.String"
    ]
}
*/
