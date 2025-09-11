package jointcompile

import jointCompile.JavaPrinter

class GroovyGreeter{

    private final String toGreet;
    private final JavaPrinter printer;

    GroovyGreeter(String toGreet){
        this.toGreet = toGreet
        this.printer = new JavaPrinter()
    }


    void greet(){
        printer.print("Hello $toGreet");
    }

}