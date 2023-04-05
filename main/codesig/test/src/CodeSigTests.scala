package mill.codesig
import utest._
object CodeSigTests extends TestSuite{
  val tests = Tests{
    test("hello"){


      val testCaseClassFilesRoot = os.Path(sys.env("TEST_CASE_CLASS_FILES"))
      println(testCaseClassFilesRoot)
      val classFiles = os.walk(testCaseClassFilesRoot).filter(_.ext == "class")
      CodeSig.process(classFiles.map(os.read.bytes(_)))
      println("Hello World" + classFiles)
    }
  }
}
