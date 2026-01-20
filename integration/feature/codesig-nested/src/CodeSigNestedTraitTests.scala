import mill.testkit.UtestIntegrationTestSuite

import utest.*

object CodeSigNestedTraitTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("trait") - integrationTest { tester =>
      import tester.*
      val initial = eval("traitOuter.traitInner.inner")
      assert(
        initial.out.linesIterator.toSet == Set(
          "running foo",
          "running helperFoo",
          "running outer",
          "running helperTraitOuter",
          "running inner",
          "running helperTraitInner"
        )
      )
      assertGoldenLiteral(
        os.read.lines(tester.workspacePath / "out/mill-invalidation-tree.json"),
        Seq(
          "{",
          "  \"foo\": {",
          "    \"traitOuter.outer\": {},",
          "    \"traitOuter.traitInner.inner\": {}",
          "  }",
          "}"
        )
      )

      val cached = eval("traitOuter.traitInner.inner")
      assert(cached.out == "")
      assertGoldenLiteral(
        os.read.lines(tester.workspacePath / "out/mill-invalidation-tree.json"),
        Seq("{}")
      )

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueTraitInner = 0", "val valueTraitInner = 10")
      )
      val mangleTraitInnerValue = eval("traitOuter.traitInner.inner")
      assert(
        mangleTraitInnerValue.out.linesIterator.toSet == Set(
          "running inner",
          "running helperTraitInner"
        )
      )
      assertGoldenLiteral(
        os.read.lines(tester.workspacePath / "out/mill-invalidation-tree.json"),
        Seq(
          "{",
          "  \"def build_.package_$TraitOuter$traitInner$#<init>(build_.package_$TraitOuter)void\": {",
          "    \"traitOuter.traitInner.inner\": {}",
          "  }",
          "}"
        )
      )

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueTraitOuter = 0", "val valueTraitOuter = 10")
      )
      val mangleTraitOuterValue = eval("traitOuter.traitInner.inner")
      assert(
        mangleTraitOuterValue.out.linesIterator.toSet == Set(
          "running outer",
          "running helperTraitOuter",
          "running inner",
          "running helperTraitInner"
        )
      )
      assertGoldenLiteral(
        os.read.lines(tester.workspacePath / "out/mill-invalidation-tree.json"),
        Seq(
          "{",
          "  \"def build_.package_$TraitOuter.$init$(build_.package_$TraitOuter)void\": {",
          "    \"call build_.package_$TraitOuter.$init$(build_.package_$TraitOuter)void\": {",
          "      \"def build_.package_$traitOuter$#<init>(build_.package_)void\": {",
          "        \"traitOuter.outer\": {",
          "          \"traitOuter.traitInner.inner\": {}",
          "        }",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )
    }
  }
}
