//| moduleDeps: [Foo.java]
//| mvnDeps:
//| - "com.google.guava:guava:33.3.0-jre"

import static com.google.common.html.HtmlEscapers.htmlEscaper;

public class FooTest {
  public static void main(String[] args) {
    var result = Foo.generateHtml("hello");
    assert(result == "<h1>hello</h1>");
    System.out.println(result);
    var result2 = Foo.generateHtml("<hello>");
    var expected2 = "<h1>" + htmlEscaper().escape("<hello>") + "</h1>";
    assert(result2 == expected2);
    System.out.println(result2);
  }
}
