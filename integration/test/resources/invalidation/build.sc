import $file.a.inputA
import $file.b.inputB
import $file.inputC
import $ivy.`org.scalaj::scalaj-http:2.4.2`
import $file.e.inputE

def task = T {
  inputA.input()
  inputB.input()
  inputC.input()
}

object module extends Module {
  def task = T {
    println("task")
    inputA.input()
    inputB.input()
    inputC.input()
  }
}

def taskE = T {
  println("taskE")
  inputE.input()
}
