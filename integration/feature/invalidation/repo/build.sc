import mill._

import $file.inputC
import $ivy.`org.scalaj::scalaj-http:2.4.2`
import build.`-#!+→&%=~`.inputSymbols

def task = T {
  build.a.input()
  build.b.input()
  inputC.input()
}

object module extends Module {
  def task = T {
    println("task")
    build.a.input()
    inputBRenamed.input()
    inputC.input()
  }
}

def taskE = T {
  println("taskE")
  build.e.input()
}

def taskSymbols = T {
  println("taskSymbols")
  inputSymbols.input()
}

def taskSymbolsInFile = T {
  println("taskSymbolsInFile")
  `-#+&%`.module.input()
}
