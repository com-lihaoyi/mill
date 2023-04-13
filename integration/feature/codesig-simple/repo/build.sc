import mill._

def helperFoo = { println("running helperFoo"); 1 }

def foo = T{ println("running foo"); helperFoo }

def helperBar = { println("running helperBar"); 20 }

def bar = T{ println("running bar"); helperBar }

def helperQux = { println("running helperQux"); 300 }

def qux = T{ println("running qux"); foo() + bar() + helperQux }
