package better.files

import better.files.Dsl._

import java.io.File.separator

import org.scalatest.BeforeAndAfterAll

class GlobSpec extends CommonSpec with BeforeAndAfterAll {
  var testDir: File = _
  var globTree: File = _
  var specialTree: File = _

  var regexWildcardPath: File = _
  var globWildcardPath: File = _
  //
  //  Test target for glob
  //
  //      tests/
  //      ├── globtree
  //      │   ├── a
  //      │   │   ├── a2
  //      │   │   │   ├── a2.txt
  //      │   │   │   └── x.txt
  //      │   │   ├── a.not
  //      │   │   ├── a.txt
  //      │   │   └── x.txt
  //      │   ├── b
  //      │   │   ├── a
  //      │   │   │   └── ba.txt
  //      │   │   └── b.txt
  //      │   ├── c
  //      │   │   ├── c.txt
  //      │   │   └── x.txt
  //      │   ├── empty
  //      │   ├── link_to_a -> a
  //      │   ├── one.txt
  //      │   ├── readme.md
  //      │   ├── three.txt
  //      │   └── two.txt
  //      └── special
  //          ├── .*
  //          │   └── a
  //          │       └── a.txt
  //          └── **
  //              └── a
  //                  └── a.txt
  //
  override def beforeAll() = {
    testDir = File.newTemporaryDirectory("glob-tests")
    globTree = testDir / "globtree"

    mkdir(globTree)
    val a = mkdir(globTree / "a" )
    mkdir(globTree / "a" / "a2")
    touch(globTree / "a" / "a2" / "a2.txt")
    touch(globTree / "a" / "a2" / "x.txt")
    touch(globTree / "a" / "a.not")
    touch(globTree / "a" / "a.txt")
    touch(globTree / "a" / "x.txt")

    mkdir(globTree / "b" )
    mkdir(globTree / "b" / "a")
    touch(globTree / "b" / "a" / "ba.txt")
    touch(globTree / "b" / "b.txt")

    mkdir(globTree / "c" )
    touch(globTree / "c" / "c.txt")
    touch(globTree / "c" / "x.txt")

    mkdir(globTree / "empty" )

    if (isUnixOS) {
      ln_s(globTree / "link_to_a", a)
    }

    touch(globTree / "one.txt")
    touch(globTree / "two.txt")
    touch(globTree / "three.txt")
    touch(globTree / "readme.md")

    // Special target with path name components as wildcards
    specialTree = testDir / "special"

    // Windows does not support '*' in file names
    if (isUnixOS) {
      // regex
      mkdir(specialTree)
      regexWildcardPath = mkdir(specialTree / ".*")
      mkdir(specialTree / ".*" / "a")
      touch(specialTree / ".*" / "a" / "a.txt")

      // glob
      globWildcardPath = mkdir(specialTree / "**")
      mkdir(specialTree / "**" / "a")
      touch(specialTree / "**" / "a" / "a.txt")
    }

    ()
  }

  override def afterAll() = {
    val _ = rm(testDir)
  }

  /**
   * Helper in case something goes wrong...
   */
  private def debugPaths(files: Seq[File]): String = {
    files
      .sortBy(_.path)
      .map(files => s"PATH: ${files.toString}")
      .mkString(s"SIZE: ${files.size}\n", "\n", "\n")
  }

  /**
   * Verity if candidates are equal with references.
   * Does not accept empty sets, use assert(paths.isEmpty) for that.
   *
   * @param pathsIt candidates
   * @param refPaths references
   * @param baseDir basedir to for creating full path of references
   */
  private def verify(pathsIt: Files, refPaths: Seq[String], baseDir: File) = {
    val paths = pathsIt.toSeq
    val refs = refPaths
      .map(refPath => baseDir/refPath)
      .sortBy(_.path)

    withClue("Result: " + debugPaths(paths) + "Reference: " + debugPaths(refs)) {
      assert(paths.length === refPaths.length)
      assert(paths.nonEmpty)
      paths.sortBy(_.path).zip(refs).foreach({case (path, refPath) => assert(path === refPath)})
    }
  }

  "glob" should "match plain file (e.g. 'file.ext')" in {
    val refPaths = Seq(
      "one.txt"
    )
    val paths = globTree.glob("one.txt")
    verify(paths, refPaths, globTree)
  }
  it should "match path without glob (e.g. 'sub/dir/file.ext')" in {
    val refPaths = Seq(
      "a/a.txt"
    )
    val paths = globTree.glob("a/a.txt")
    verify(paths, refPaths, globTree)
  }

  it should "match file-glob (e.g. '*.ext')" in {
    val refPaths = Seq(
      "one.txt",
      "two.txt",
      "three.txt"
    )
    val paths = globTree.glob("*.txt")
    verify(paths, refPaths, globTree)
    assert(globTree.glob("*.txt", includePath = false)(File.PathMatcherSyntax.glob).isEmpty)
  }

  it should "match fixed sub dir and file-glob  (e.g. '**/subdir/*.ext')" in {
    // TODO: DOC: why top level 'a' is not matched
    val refPaths = List(
      "b/a/ba.txt"
    )
    val paths = globTree.glob("**/a/*.txt")
    verify(paths, refPaths, globTree)
  }

  it should "use parent dir for matching (e.g. plain 'subdir/*.ext')" in {
    // e.g. check that b nor c are matched, nor b/a
    val refPaths = Seq(
      "a/a.txt",
      "a/x.txt"
    )
    val paths = globTree.glob("a/*.txt")
    verify(paths, refPaths, globTree)
  }

  it should "match sub-directory glob with plain file (e.g. 'subdir/*/file.ext')" in {
    val refPaths = Seq(
      "a/x.txt",
      "c/x.txt"
    )
    val paths = testDir.glob("globtree/*/x.txt")
    verify(paths, refPaths, globTree)
  }

  it should "match sub-directory glob with file-glob (e.g. 'subdir/*/*.ext')" in {
    val refPaths = Seq(
      "a/a.txt",
      "a/x.txt",
      "c/c.txt",
      "c/x.txt",
      "b/b.txt"
    )
    val paths = testDir.glob("globtree/*/*.txt")
    verify(paths, refPaths, globTree)
  }

  it should "match deep sub-directory glob with plain file (e.g. 'subdir/**/file.ext')" in {
    val refPaths = Seq(
      "a/a2/x.txt",
      "a/x.txt",
      "c/x.txt"
    )
    val p1s = globTree.glob("**/x.txt")
    verify(p1s, refPaths, globTree)

    val p2s = testDir.glob("globtree/**/x.txt")
    verify(p2s, refPaths, globTree)
  }

  it should "match deep sub-directory glob with file-glob (e.g. 'subdir/**/*.ext')" in {
    val refPaths = Seq(
      "a/a.txt",
      "a/x.txt",
      "a/a2/x.txt",
      "a/a2/a2.txt",
      "c/x.txt",
      "c/c.txt",
      "b/b.txt",
      "b/a/ba.txt"
    )
    val p1s = globTree.glob("**/*.txt")
    verify(p1s, refPaths, globTree)

    val p2s = testDir.glob("globtree/**/*.txt")
    verify(p2s, refPaths, globTree)
  }

  it should "match deep file-glob (e.g. 'subdir/**.ext')" in {
    val refPaths = Seq(
      "one.txt",
      "two.txt",
      "three.txt",
      "a/a.txt",
      "a/x.txt",
      "a/a2/x.txt",
      "a/a2/a2.txt",
      "b/a/ba.txt",
      "b/b.txt",
      "c/x.txt",
      "c/c.txt"
    )
    val p1s = globTree.glob("**.txt")
    verify(p1s, refPaths, globTree)

    val p2s = testDir.glob("globtree/**.txt")
    verify(p2s, refPaths, globTree)
  }

  it should "match everything (e.g. 'subdir/**')" in {
    val refPaths = List(
      "a",
      "a/a.not",
      "a/a.txt",
      "a/a2",
      "a/a2/a2.txt",
      "a/a2/x.txt",
      "a/x.txt",
      "b",
      "b/a",
      "b/a/ba.txt",
      "b/b.txt",
      "c",
      "c/c.txt",
      "c/x.txt",
      "empty",
      "one.txt",
      "readme.md",
      "three.txt",
      "two.txt") ++
      when(isUnixOS)("link_to_a")

    val paths = testDir.glob("globtree/**")
    verify(paths, refPaths, globTree)
  }

  it should "work with links (e.g. 'link_to_a/**.txt')" in {
    assume(isUnixOS)
    val refPaths = Seq(
      "a/a.txt",
      "a/x.txt",
      "a/a2/x.txt",
      "a/a2/a2.txt"
    )

    // TODO: DOC: File behaviour, links are resolved (abs + normalized path)

    val p1s = globTree.glob("link_to_a/**.txt")(visitOptions = File.VisitOptions.follow)
    verify(p1s, refPaths, globTree)

    val p2s = globTree.glob("link_to_a/**.txt").toSeq
    assert(p2s.isEmpty)

    val p3s = testDir.glob("globtree/link_to_a/**.txt")(visitOptions = File.VisitOptions.follow)
    verify(p3s, refPaths, globTree)

    val p4s = testDir.glob("globtree/link_to_a/**.txt")
    assert(p4s.isEmpty)
  }

  it should "not use dir name as wildcard (e.g. dirname is **)" in {
    assume(isUnixOS)
    val d = globWildcardPath // "path" / "with" / "**"
    val paths = d.glob("*.txt")

    assert(paths.isEmpty)
  }

  "Regex" should "match all txt-files under sub-directory (e.g. '.*/.*\\\\.txt')" in {
    val refPaths = Seq(
      "a/a.txt",
      "a/x.txt",
      "a/a2/x.txt",
      "a/a2/a2.txt",
      "c/x.txt",
      "c/c.txt",
      "b/b.txt",
      "b/a/ba.txt"
    )
    val paths = globTree.glob(".*" + separator + ".*\\.txt")(File.PathMatcherSyntax.regex)

    verify(paths, refPaths, globTree)
  }

  it should "match the same if `Regex` is used" in {
    val pattern = (".*" + separator + ".*\\.txt").r

    val pathsGlob = globTree.glob(pattern.regex)(File.PathMatcherSyntax.regex)
    val pathsRegex = globTree.globRegex(pattern)

    verify(pathsRegex, pathsGlob.toSeq.map(_.toString), globTree)

  }

  it should "use parent dir for matching (e.g. plain 'subdir/*.ext' instead of '**/subdir/*.ext)" in {
    // e.g. check that b nor c are matched, nor b/a
    val refPaths = Seq(
      "a/a.txt",
      "a/x.txt",
      "a/a2/a2.txt",
      "a/a2/x.txt"
    )
    val paths = globTree.glob("a" + separator + ".*\\.txt")(File.PathMatcherSyntax.regex)

    verify(paths, refPaths, globTree)
    assert(globTree.glob("a/.*\\.txt", includePath = false)(File.PathMatcherSyntax.regex).isEmpty)
  }

  it should "not use dir name as wildcard (e.g. dirname is .*)" in {
    assume(isUnixOS)
    val d = regexWildcardPath // "path" / "with" / ".*"
    val paths = d.glob("a\\.txt")(File.PathMatcherSyntax.regex)
    assert(paths.isEmpty)
  }
}
