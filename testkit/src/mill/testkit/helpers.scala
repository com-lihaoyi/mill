package mill.testkit

import pprint.{TPrint, TPrintColors}
import utest.TestValue

def asTestValue[A](a: sourcecode.Text[A])(using typeName: TPrint[A]): TestValue =
  TestValue.Single(a.source, Some(typeName.render(using TPrintColors.BlackWhite).plainText), a.value)

def asTestValue[A](name: String, a: A)(using typeName: TPrint[A]): TestValue =
  TestValue.Single(name, Some(typeName.render(using TPrintColors.BlackWhite).plainText), a)

/** Adds the provided clues to the thrown [[utest.AssertionError]]. */
def withTestClues[A](clues: TestValue*)(f: => A): A = {
  try f
  catch {
    case e: utest.AssertionError =>
      val newException = e.copy(captured = clues ++ e.captured)
      newException.setStackTrace(e.getStackTrace)
      throw newException
  }
}
