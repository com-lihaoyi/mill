import $file.a.inputA
import $file.b.{inputB => inputBRenamed}
import $file.inputC
import $ivy.`org.scalaj::scalaj-http:2.4.2`
import $file.e.inputE
import $file.`-#!+→&%=~`.inputSymbols
import $file.`-#+&%`

def task = T {
  inputA.input()
  inputBRenamed.input()
  inputC.input()
}

object module extends Module {
  def task = T {
    println("task")
    inputA.input()
    inputBRenamed.input()
    inputC.input()
  }
}

def taskE = T {
  println("taskE")
  inputE.input()
}

def taskSymbols = T {
  println("taskSymbols")
  inputSymbols.input()
}

def taskSymbolsInFile = T {
  println("taskSymbolsInFile")
  `-#+&%`.module.input()
}
