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
      pprint.log(summary.callGraph.map{case (k, (hash, vs)) => (k.toString, vs.map(_.toString))})
      val analyzer = new Analyzer(summary)

      val foundTransitive0 = analyzer
        .transitiveCallGraphMethods
        .map{case (k, vs) => (k.toString, vs.map(_.toString))}

      val expectedTransitive0 = for{
        cn <- classNodes
        mn <- cn.methods.asScala
        an <- Option(mn.visibleAnnotations).map(_.asScala).getOrElse(Nil)
        if an.desc == "Lmill/codesig/ExpectedDeps;"
      }yield {
        val expected = an.values match{
          case null => Set.empty[String]
          case values =>
            values
              .asScala
              .drop(1)
              .flatMap(_.asInstanceOf[java.util.List[_]].asScala).toSet
        }

        val sig = MethodSig(cn.name.replace('/', '.'), (mn.access & Opcodes.ACC_STATIC) != 0, mn.name, mn.desc)
        (sig.toString -> expected)
      }

      val expectedTransitive = expectedTransitive0.toMap
      val foundTransitive = foundTransitive0.filter { case (k, v) => expectedTransitive.contains(k) }
      pprint.log(expectedTransitive)
      pprint.log(foundTransitive)
      assert(expectedTransitive == foundTransitive)
      pprint.apply(foundTransitive.filter(_._2.nonEmpty))
    }
  }
}
