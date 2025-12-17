package hellostatic

import groovy.transform.CompileStatic

@CompileStatic
class HelloStatic {

    static void main(String[] args) {
        def x = new Some("Hello World")
        x.doStuff()
    }
}

@CompileStatic
class Some {
    
    private String toPrint;

    Some(String toPrint){
        this.toPrint = toPrint
    }

    void doStuff(){
        println(toPrint)
    }
}