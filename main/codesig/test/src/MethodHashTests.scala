package mill.codesig
import JType.{Cls => JCls}
import utest._

object MethodHashTests extends TestSuite{
  val tests = Tests{
    test("unchanged"){
      test("simple-java") - testUnchanged()
      test("simple-scala") - testUnchanged()
      test("sourcecode-line") - testUnchanged()
    }
    test("changed"){
      val cases = Seq(
        "basic",
        "constant-large",
        "constant-small",
        "constant-string",
        "different-field-read",
        "if",
        "if-else",
        "if-else-biased",
        "if-else-reverse-conditional",
        "local-call",
        "local-call-instance",
        "local-call-parameter",
        "local-call-renamed",
        "local-call-transitive-change",
        "switch",
        "switch-keys",
        "switch-remove-break",
        "two-calls"
      )

      val computed = for(c <- cases) yield (c, TestUtil.computeCodeSig(Seq("methodhash", "changed", c)))

      for{
        (c1, sig1) <- computed
        (c2, sig2) <- computed
        if c1 != c2
      }{

        val mainMethod = MethodDef(
          JCls("hello.Hello"),
          MethodSig(true, "main", Desc(Seq(JType.Arr(JCls("java.lang.String"))), JType.Prim.V))
        )
        val hash1 = sig1.transitiveCallGraphHashes(sig1.nodeToIndex(mainMethod))
        val hash2 = sig2.transitiveCallGraphHashes(sig2.nodeToIndex(mainMethod))
        if (hash1 == hash2) throw new Exception(
          s"main methods for $c1 and $c2 have identical main method hash: $hash1"
        )

      }
    }
  }

  def testUnchanged()(implicit tp: utest.framework.TestPath) = {
    def computeCodeSig2(suffix: String) = TestUtil.computeCodeSig(
      Seq("methodhash", tp.value.head, tp.value.tail.mkString("-") + suffix)
    )

    val codeSig = computeCodeSig2("")
    val reformattedCodeSig = computeCodeSig2("-2")

    val pretty1 = codeSig.prettyHashes
    val pretty2 = reformattedCodeSig.prettyHashes
    assert(pretty1 == pretty2)
  }
}
