package mill.script.asm;

public class TemplateMainClass {
    public static void main(String[] args) {
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = "TEMPLATE_METHOD_NAME";
        System.arraycopy(args, 0, newArgs, 1, args.length);
        TemplateMainClass.main(newArgs);
    }
}
