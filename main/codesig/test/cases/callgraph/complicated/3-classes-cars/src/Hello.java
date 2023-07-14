package hello;

// Taken from https://github.com/lihaoyi/Metascala/blob/76dfbfa18484b9ee39bd09453328ea1081fcab6b/src/test/java/metascala/features/classes/Inheritance.java


import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

public class Hello {

    public static String abstractClass(){
        Car toyota = new Toyota();
        return toyota.vroom();
    }

    public static String shadowedInheritedGet(){
        Car honda = new Honda();
        return honda.vroom();
    }

    public static String shadowedInheritedSet(){
        Car honda = new Honda();
        honda.rev();
        honda.cc++;
        ((Honda)honda).cc++;
        return honda.vroom();
    }

    public static String superMethod(){
        return new Toyota().superVStart();
    }
}

class Toyota extends Car{
    public Toyota(){
        this.cc = 10;
    }

    public String vStart(){
        return "vr";
    }
    public String superVStart(){
        return super.vStart();
    }
}
class Honda extends Car{
    public int cc = 5;
    public String vStart(){
        return "v"  + cc + "r" + ((Car)this).cc + "r" + super.cc;
    }
}

class Car{
    public int cc;
    public String vStart(){
        return "";
    }
    public void rev(){
        this.cc = this.cc + 1;
    }
    public String vroom(){
        String s = vStart();
        for(int i = 0; i < cc; i++){
            s = s + "o";
        }
        return s + "m";
    }
}
/* expected-direct-call-graph
{
    "hello.Car#vroom()java.lang.String": [
        "hello.Car#vStart()java.lang.String",
        "hello.Honda#vStart()java.lang.String",
        "hello.Toyota#vStart()java.lang.String"
    ],
    "hello.Hello.abstractClass()java.lang.String": [
        "hello.Car#vroom()java.lang.String",
        "hello.Toyota#<init>()void"
    ],
    "hello.Hello.shadowedInheritedGet()java.lang.String": [
        "hello.Car#vroom()java.lang.String",
        "hello.Honda#<init>()void"
    ],
    "hello.Hello.shadowedInheritedSet()java.lang.String": [
        "hello.Car#rev()void",
        "hello.Car#vroom()java.lang.String",
        "hello.Honda#<init>()void"
    ],
    "hello.Hello.superMethod()java.lang.String": [
        "hello.Toyota#<init>()void",
        "hello.Toyota#superVStart()java.lang.String"
    ],
    "hello.Honda#<init>()void": [
        "hello.Car#<init>()void"
    ],
    "hello.Toyota#<init>()void": [
        "hello.Car#<init>()void"
    ],
    "hello.Toyota#superVStart()java.lang.String": [
        "hello.Car#vStart()java.lang.String"
    ]
}
*/
