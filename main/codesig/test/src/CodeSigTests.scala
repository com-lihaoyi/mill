package mill.codesig
import org.objectweb.asm.Opcodes
import utest._

import collection.JavaConverters._
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("hello"){
      val testCaseClassFilesRoot = os.Path(sys.env("TEST_CASE_CLASS_FILES"))
      val classFiles = os.walk(testCaseClassFilesRoot).filter(_.ext == "class")
      val classNodes = classFiles.map(p => Summarizer.loadClass(os.read.bytes(p)))
      val summary = Summarizer.summarize0(classNodes)
      val pretty = summary.callGraph.map{case (k, vs) => (k.toString, vs._2)}

      val expected = Map(
        "hello.Hello.used()I" -> Set(),
        "hello.Hello.unused()I" -> Set(),
        "hello.Hello.main([Ljava/lang/String;)V" -> Set(
          MethodCall("hello.Hello", InvokeType.Static, "used", "()I"),
          MethodCall("java.io.PrintStream", InvokeType.Virtual, "println", "(I)V")
        ),
        "hello.Hello#<init>()V" -> Set(
          MethodCall("java.lang.Object", InvokeType.Special, "<init>", "()V")
        )
      )
      assert(pretty == expected)

      val analyzer = new Analyzer(summary)

      val prettyTransitive = analyzer
        .transitiveCallGraphMethods
        .map{case (k, vs) => (k.toString, vs.map(_.toString))}

      val expectedTransitive = Map(
        "hello.Hello.used()I" -> Set(),
        "hello.Hello.unused()I" -> Set(),
        "hello.Hello.main([Ljava/lang/String;)V" -> Set("hello.Hello.used()I"),
        "hello.Hello#<init>()V" -> Set()
      )
      assert(prettyTransitive == expectedTransitive)



      for{
        cn <- classNodes
        mn <- cn.methods.asScala
        an <- Option(mn.visibleAnnotations).map(_.asScala).getOrElse(Nil)
        if an.desc == "Lmill/codesig/ExpectedDeps;"
      }{
        val expected = an.values.asScala.drop(1).flatMap(_.asInstanceOf[java.util.List[_]].asScala).toSet
        val sig = MethodSig(cn.name.replace('/', '.'), (mn.access & Opcodes.ACC_STATIC) != 0, mn.name, mn.desc)
        val foundTransitive = prettyTransitive(sig.toString)
        assert(expected == foundTransitive)
      }
    }
  }
}
