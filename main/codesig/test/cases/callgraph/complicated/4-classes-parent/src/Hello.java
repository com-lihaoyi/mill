package hello;

// Taken from https://github.com/lihaoyi/Metascala/blob/76dfbfa18484b9ee39bd09453328ea1081fcab6b/src/test/java/metascala/features/classes/Inheritance.java


import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

public class Hello {

    public static int staticInheritance(){
        int a = Parent.x;
        Child1.x = 100;
        return a + Child1.x + Child2.x;
    }
}
interface ParentInterface{
    public static int x = 30;
}
class Parent{
    public static int x = 10;
}
class Child1 extends Parent{
    public static int get(){
        return x;
    }
}
class Cowc{}
class Child2 extends Cowc implements ParentInterface{
    public static int get(){
        return x;
    }
}
/* expected-direct-call-graph
{
    "hello.Child1#<init>()void": [
        "hello.Parent#<init>()void"
    ],
    "hello.Child2#<init>()void": [
        "hello.Cowc#<init>()void"
    ]
}
*/
