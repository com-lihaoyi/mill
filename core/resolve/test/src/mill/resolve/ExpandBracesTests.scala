package mill.resolve

import mill.api.Result
import utest.*

object ExpandBracesTests extends TestSuite {

  val tests = Tests {
    test("expandBraces") {
      def check(input: String, expectedExpansion: List[String]) = {
        val Result.Success(expanded) = ExpandBraces.expandBraces(input): @unchecked

        assert(expanded == expectedExpansion)
      }

      test("expandLeft") - check(
        "{application,core}.compile",
        List("application.compile", "core.compile")
      )
      test("expandRight") - check(
        "application.{jar,docJar,sourcesJar}",
        List("application.jar", "application.docJar", "application.sourcesJar")
      )
      test("expandBoth") - check(
        "{core,application}.{jar,docJar}",
        List(
          "core.jar",
          "core.docJar",
          "application.jar",
          "application.docJar"
        )
      )
      test("expandNested") {
        check("{hello,world.{cow,moo}}", List("hello", "world.cow", "world.moo"))
        check("{a,b{c,d}}", List("a", "bc", "bd"))
        check("{a,b,{c,d}}", List("a", "b", "c", "d"))
        check("{a,b{c,d{e,f}}}", List("a", "bc", "bde", "bdf"))
        check("{a{b,c},d}", List("ab", "ac", "d"))
        check("{a,{b,c}d}", List("a", "bd", "cd"))
        check("{a{b,c},d{e,f}}", List("ab", "ac", "de", "df"))
        check("{a,b{c,d},e{f,g}}", List("a", "bc", "bd", "ef", "eg"))
      }
      test("expandMixed") {
        test - check(
          "{a,b}.{c}.{}.e",
          List("a.{c}.{}.e", "b.{c}.{}.e")
        )
        test - check("{{b,c}}d", List("{b}d", "{c}d"))
      }
      test("malformed") {
        val malformed = Seq("core.{compile", "core.{compile,test]")

        malformed.foreach { m =>
          val (failure: Result.Failure) = ExpandBraces.expandBraces(m): @unchecked
          assert(failure.error.contains("Parsing exception"))
        }
      }
      test("dontExpand") {
        test - check("core.compile", List("core.compile"))
        test - check("{}.compile", List("{}.compile"))
        test - check("{core}.compile", List("{core}.compile"))

      }
      test("keepUnknownSymbols") {
        check("{a,b}.e<>", List("a.e<>", "b.e<>"))
        check("a[99]&&", List("a[99]&&"))
        check(
          "{a,b}.<%%>.{c,d}",
          List("a.<%%>.c", "a.<%%>.d", "b.<%%>.c", "b.<%%>.d")
        )
      }
    }
  }
}
