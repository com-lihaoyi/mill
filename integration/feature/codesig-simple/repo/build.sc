import mill._


def constant = T{ println("running constant"); 1 }

def transitiveHelperFoo() = { println("running transitiveHelperFoo"); 20 }

def helperFoo = { println("running helperFoo"); 300 + transitiveHelperFoo() }

def foo = T{ println("running foo"); helperFoo + constant() }

val helperBar = { println("running helperBar"); 4000 }

def bar = T{ println("running bar"); helperBar + foo() }

lazy val helperQux = { println("running helperQux"); 50000}

def qux = T{ println("running qux"); helperQux  + bar() }

class HelperBaz{
  def help() = { println("running HelperBaz.help"); 600000 }
}

def baz = T{ println("running baz");  qux() + new HelperBaz().help() }