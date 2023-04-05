package mill.codesig
import utest._
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("hello"){

      val testCaseClassFilesRoot = os.Path(sys.env("TEST_CASE_CLASS_FILES"))
      println(testCaseClassFilesRoot)
      val classFiles = os.walk(testCaseClassFilesRoot).filter(_.ext == "class")
      val summary = Summarizer.summarize(classFiles.map(os.read.bytes(_)))
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
    }
  }
}
