package hello;


import scala.collection.AbstractIterator

object Hello{

  object TestManifestFactory {
    val Nothing = new PhantomManifest()
    class PhantomManifest() extends ClassTypeManifest(None)
    class ClassTypeManifest(prefix: Option[Int])
  }

  def testManifestFactory(): String = {
    val s = TestManifestFactory.toString
    s.substring(0, s.indexOf('@'))
  }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello$#testManifestFactory()java.lang.String": [
        "hello.Hello$TestManifestFactory$#<init>()V",
        "hello.Hello$TestManifestFactory$ClassTypeManifest#<init>(scala.Option)V",
        "hello.Hello$TestManifestFactory$PhantomManifest#<init>()V"
    ],
    "hello.Hello$TestManifestFactory$PhantomManifest#<init>()V": [
        "hello.Hello$TestManifestFactory$ClassTypeManifest#<init>(scala.Option)V"
    ],
    "hello.Hello.testManifestFactory()java.lang.String": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#testManifestFactory()java.lang.String",
        "hello.Hello$TestManifestFactory$#<init>()V",
        "hello.Hello$TestManifestFactory$ClassTypeManifest#<init>(scala.Option)V",
        "hello.Hello$TestManifestFactory$PhantomManifest#<init>()V"
    ]
}
*/
