import java.io.IOException;
import java.util.Arrays;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("Args: " + args.length + ", " + Arrays.toString(args));

        if (args.length == 0) {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + "/bin/java";
            ProcessBuilder pb = new ProcessBuilder(javaBin, "Main.java", "", "1", "2", "3");
            pb.inheritIO();
            pb.start().waitFor();
        }
    }
}
