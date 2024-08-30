import mill._

import $ivy.`org.scalaj::scalaj-http:2.4.2`

def task = T {
  a.input()
  b.input()
  c.input()
}

object module extends Module {
  def task = T {
    println("task")
    a.input()
    b.input()
    c.input()
  }
}

def taskE = T {
  println("taskE")
  e.input()
}

def taskSymbols = T {
  println("taskSymbols")
  `-#!+→&%=~`.input()
}

def taskSymbolsInFile = T {
  println("taskSymbolsInFile")
  `-#+&%`.input()
}
