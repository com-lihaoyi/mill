class Foo {

    static void main(String[] args) {
        if(args == null || args.length != 1){
            println "Specify exactly one parameter for greeting"
            System.exit(1)
        }

        println "Hello ${args[0]}"
    }
}
