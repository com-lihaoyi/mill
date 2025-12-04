package mill.codesig

import JvmModel._
import utest._

object MethodHashTests extends TestSuite {
  val tests = Tests {
    test("unchanged") {
      test("simple-java") - testUnchanged()
      test("simple-scala") - testUnchanged()
      test("sourcecode-line") - testUnchanged()
    }
    test("changed") {
      val st = new SymbolTable
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

      val computed = for (c <- cases)
        yield (c, TestUtil.computeCodeSig(Seq("methodhash", "changed", c)))

      for {
        (c1, sig1) <- computed
        (c2, sig2) <- computed
        if c1 != c2
      } {

        val mainMethod = st.MethodDef(
          st.JCls("hello.Hello"),
          st.MethodSig(
            true,
            "main",
            st.Desc.read("([Ljava/lang/String;)V")
          )
        )
        val hash1 = sig1.transitiveCallGraphHashes(mainMethod.toString)
        val hash2 = sig2.transitiveCallGraphHashes(mainMethod.toString)
        if (hash1 == hash2) throw Exception(
          s"main methods for $c1 and $c2 have identical main method hash: $hash1"
        )

      }
    }
  }

  def testUnchanged()(using tp: utest.framework.TestPath) = {
    def computeCodeSig2(suffix: String) = TestUtil.computeCodeSig(
      Seq("methodhash", tp.value.head, tp.value.tail.mkString("-") + suffix)
    )

    val codeSig = computeCodeSig2("")
    val reformattedCodeSig = computeCodeSig2("-2")

    val pretty1 = codeSig.methods.map { case (k, v) => (k.toString, v.codeHash) }
    val pretty2 = reformattedCodeSig.methods.map { case (k, v) => (k.toString, v.codeHash) }
    assert(pretty1 == pretty2)
  }
}
