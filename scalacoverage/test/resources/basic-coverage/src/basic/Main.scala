package basic

object Main {
  def runMethod(branch: Int): Int = {
    if (branch == 0) { println("The value was 0"); 0 }
    else if (branch == 1) { println("The value was 1"); 1 }
    else { println(s"The value was $branch"); branch + 1 }
  }
  def notTestedMethod(): Unit = {
    throw new IllegalStateException("Not tested")
  }
}