package hello;

class Foo {
  @Override
  public String toString() {
    return "Foo";
  }
}

public class Hello {
  public static String render(Foo foo) {
    return String.valueOf(foo);
  }

  public static String main() {
    return render(new Foo());
  }
}

// Static external `String.valueOf(Object)` invokes `obj.toString()` for non-null obj.
// We should therefore keep `Hello.render(Foo) -> Foo#toString` in direct call-graph.

/* expected-direct-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello.main()java.lang.String": [
        "hello.Foo#<init>()void",
        "hello.Hello.render(hello.Foo)java.lang.String"
    ],
    "hello.Hello.render(hello.Foo)java.lang.String": [
        "hello.Foo#toString()java.lang.String"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.Foo#<init>()void": [
        "hello.Foo#toString()java.lang.String"
    ],
    "hello.Hello.main()java.lang.String": [
        "hello.Foo#<init>()void",
        "hello.Foo#toString()java.lang.String",
        "hello.Hello.render(hello.Foo)java.lang.String"
    ],
    "hello.Hello.render(hello.Foo)java.lang.String": [
        "hello.Foo#toString()java.lang.String"
    ]
}
*/
