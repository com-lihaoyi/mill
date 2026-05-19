package hello

import scala.collection.AbstractIterator

object Hello {

  object TestManifestFactory {
    val Nothing = PhantomManifest()
    class PhantomManifest() extends ClassTypeManifest(None)
    class ClassTypeManifest(prefix: Option[Int])
  }

  def testManifestFactory(): String = {
    val s = TestManifestFactory.toString
    s.substring(0, s.indexOf('@'))
  }
}

/* expected-direct-call-graph
{
    "hello.Hello$#testManifestFactory()java.lang.String": [
        "hello.Hello$TestManifestFactory$#<init>()void",
        "hello.Hello$TestManifestFactory$PhantomManifest#<init>()void"
    ],
    "hello.Hello$TestManifestFactory$PhantomManifest#<init>()void": [
        "hello.Hello$TestManifestFactory$ClassTypeManifest#<init>(scala.Option)void"
    ],
    "hello.Hello.testManifestFactory()java.lang.String": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#testManifestFactory()java.lang.String"
    ]
}
 */
