package hello

class Hello {

    static String getHelloString() {
        return "Hello, world!"
    }

    static String sayHello(String name){
        return "Hello, $name"
    }

    static void main(String[] args) {
        println(getHelloString())
    }
}