package compileroptions

class HelloCompilerOptions {

    static String getHelloString() {
        return "Hello, Java 11 Preview!"
    }

    static void main(String[] args) {
        println(getHelloString())
    }
}