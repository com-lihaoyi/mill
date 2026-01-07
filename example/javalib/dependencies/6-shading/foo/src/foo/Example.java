package foo;

// Note: We import the shaded package name, not the original
import shaded.gson.Gson;
import shaded.gson.GsonBuilder;

public class Example {
    public static void main(String[] args) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Person person = new Person("Alice", 30);
        String json = gson.toJson(person);
        System.out.println("Serialized with shaded Gson:");
        System.out.println(json);
    }

    static class Person {
        String name;
        int age;

        Person(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }
}
