package dev.deskflow;

import java.nio.file.*;

public class ProjectProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("echo")) {
            System.out.print(args[1]);
            return;
        }
        if (args[0].equals("large")) {
            System.out.print("x".repeat(70000));
            return;
        }
        if (args[0].equals("environment")) {
            System.out.print(System.getenv("HOME") == null && System.getenv("APP_ENCRYPTION_KEY") == null);
            return;
        }
        if (args[0].equals("parent")) {
            String java = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            Process child = new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),ProjectProcessFixture.class.getName(),"child").start();
            Files.writeString(Path.of("child.pid"),Long.toString(child.pid()));
        }
        Thread.sleep(30000);
    }
}
