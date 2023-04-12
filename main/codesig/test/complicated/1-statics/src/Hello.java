package hello;
// Taken from https://github.com/lihaoyi/Metascala/blob/76dfbfa18484b9ee39bd09453328ea1081fcab6b/src/test/java/metascala/features/methods/Statics.java

public class Hello {
    public static int helloWorld(int n){
        return timesTwo(n);
    }

    public static int timesTwo(int n){
        return n * 2;
    }

    public static int helloWorld2(int a, int b){
        return timesTwo2(a, b);
    }

    public static int timesTwo2(int a, int b){
        return (a - b) * 2;
    }

    public static int tailFactorial(int n){
        if (n == 1){
            return 1;
        }else{
            return n * tailFactorial(n-1);
        }
    }
    public static int fibonacci(int n){
        if (n == 1 || n == 0){
            return 1;
        }else{
            return fibonacci(n-1) + fibonacci(n-2);
        }
    }
    public static int indirectFibonacciA(int n){
        if (n == 1 || n == 0){
            return 1;
        }else{
            return indirectFibonacciB(n-1) + indirectFibonacciB(n-2);
        }
    }

    public static int indirectFibonacciB(int n){
        return indirectFibonacciA(n);
    }

    public static int call(int x) {
        return x+1;
    }
    public static int callAtPhiBoundary(int i){

        int size = (i < 0) ? 1  : call(i);
        return size;
    }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello.callAtPhiBoundary(I)I": [
        "hello.Hello.call(I)I"
    ],
    "hello.Hello.helloWorld(I)I": [
        "hello.Hello.timesTwo(I)I"
    ],
    "hello.Hello.helloWorld2(II)I": [
        "hello.Hello.timesTwo2(II)I"
    ],
    "hello.Hello.indirectFibonacciA(I)I": [
        "hello.Hello.indirectFibonacciB(I)I"
    ],
    "hello.Hello.indirectFibonacciB(I)I": [
        "hello.Hello.indirectFibonacciA(I)I"
    ]
}
*/
