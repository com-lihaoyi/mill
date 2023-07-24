package hello;

// Taken from https://github.com/lihaoyi/Metascala/blob/76dfbfa18484b9ee39bd09453328ea1081fcab6b/src/test/java/metascala/features/classes/Inheritance.java

public class Hello {
    public static String implement(int n){
        Baas b = new Sheep();
        return b.baa(n);
    }
}

class Sheep implements Baas{
    public String baa(int n){
        String s = "b";
        for(int i = 0; i < n; i++) s = s + "a";
        return s;
    }
}
interface Baas{
    public String baa(int n);
}

// Baas a single-abstract-method interface and so is approximated as
// being invoked during <init>

/* expected-direct-call-graph
{
    "hello.Hello.implement(int)java.lang.String": [
        "hello.Sheep#<init>()void"
    ],
    "hello.Sheep#<init>()void": [
        "hello.Sheep#baa(int)java.lang.String"
    ]
}
*/
