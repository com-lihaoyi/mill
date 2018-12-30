package mill.scalalib.backgroundwrapper;

public class BackgroundWrapper {
    public static void main(String[] args) throws Exception{
        String watched = args[0];
        String tombstone = args[1];
        String expected = args[2];
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try{
                        Thread.sleep(50);
                        String token = new String(
                            java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(watched))
                        );
                        if (!token.equals(expected)) {
                            new java.io.File(tombstone).createNewFile();
                            System.exit(0);
                        }
                    }catch(Exception e){
                        try {
                            new java.io.File(tombstone).createNewFile();
                        }catch(Exception e2){}
                        System.exit(0);
                    }

                }
            }
        });
        watcher.setDaemon(true);
        watcher.start();
        String realMain = args[3];
        String[] realArgs = new String[args.length - 4];
        for(int i = 0; i < args.length-4; i++){
            realArgs[i] = args[i+4];
        }
        Class.forName(realMain).getMethod("main", String[].class).invoke(null, (Object)realArgs);
    }
}
