import mill._

import $ivy.`org.scalaj::scalaj-http:2.4.2`

def task0 = task {
  build.a.input()
  build.b.input()
  build.c.input()
}

object module extends Module {
  def task0 = task {
    println("task")
    build.a.input()
    build.b.input()
    build.c.input()
  }
}

def taskE = task {
  println("taskE")
  build.e.input()
}

def taskSymbols = task {
  println("taskSymbols")
  build.`-#!+→&%=~`.input()
}

def taskSymbolsInFile = task {
  println("taskSymbolsInFile")
  build.`-#+&%`.module.input()
}
