package com.bilicki.codejudge.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "sandbox.max-output-bytes=4096",
        "sandbox.cpus=1.0"
})
public class SandboxRunnerIntegrationTest {

    @Autowired
    private SandboxRunner sandboxRunner;

    @Autowired
    private SandboxProperties properties;

    @Autowired
    private DockerClient dockerClient;

    @AfterEach
    public void assertNoLeftoverContainers() {
        List<Container> leftovers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(properties.labelKey(), properties.labelValue()))
                .exec();

        assertThat(leftovers).as("All sandbox containers must be removed after session close").isEmpty();
    }

    @Test
    public void shouldCompileOnceAndRunMultipleTestCases() throws Exception {
        String source = """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        int a = sc.nextInt();
                        int b = sc.nextInt();
                        System.out.println(a + b);
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            CompileResult compile = session.compile(source);
            assertThat(compile.success()).isTrue();
            assertThat(compile.timedOut()).isFalse();

            RunTestResult run1 = session.runTest("2 3", 1);
            assertThat(run1.exitCode()).isZero();
            assertThat(run1.stdout().strip()).isEqualTo("5");
            assertThat(run1.timedOut()).isFalse();
            assertThat(run1.oomKilled()).isFalse();
            assertThat(run1.outputLimitExceeded()).isFalse();

            RunTestResult run2 = session.runTest("100 250", 1);
            assertThat(run2.exitCode()).isZero();
            assertThat(run2.stdout().strip()).isEqualTo("350");
        }
    }

    @Test
    public void shouldReportCompileErrorOnInvalidSyntax() throws Exception {
        String brokenSource = """
                public class Main {
                    public static void main(String[] args) {
                        int x = "not an int";
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            CompileResult compile = session.compile(brokenSource);

            assertThat(compile.success()).isFalse();
            assertThat(compile.timedOut()).isFalse();
            assertThat(compile.stderr()).contains("incompatible types");
        }
    }

    @Test
    public void shouldReportNonZeroExitCodeOnRuntimeException() throws Exception {
        String runtimeErrorSource = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(1 / 0);
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            assertThat(session.compile(runtimeErrorSource).success()).isTrue();

            RunTestResult result = session.runTest("", 1);
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains("ArithmeticException");
            assertThat(result.timedOut()).isFalse();
            assertThat(result.oomKilled()).isFalse();
        }
    }

    @Test
    public void shouldDetectTimeLimitExceededOnInfiniteLoop() throws Exception {
        String infiniteLoopSource = """
                public class Main {
                    public static void main(String[] args) {
                        while (true) {}
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            assertThat(session.compile(infiniteLoopSource).success()).isTrue();

            RunTestResult result = session.runTest("", 1);
            assertThat(result.timedOut()).isTrue();
            assertThat(result.oomKilled()).isFalse();
        }
    }

    @Test
    public void shouldDetectMemoryLimitExceededOnHeapBomb() throws Exception {
        String oomSource = """
                import java.util.ArrayList;
                import java.util.List;
                public class Main {
                    public static void main(String[] args) {
                        List<byte[]> list = new ArrayList<>();
                        while (true) {
                            list.add(new byte[1024 * 1024]);
                        }
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            assertThat(session.compile(oomSource).success()).isTrue();

            RunTestResult result = session.runTest("", 1);
            assertThat(result.oomKilled()).isTrue();
            assertThat(result.timedOut()).isFalse();
        }
    }

    @Test
    public void shouldDetectOutputLimitExceededOnStdoutBomb() throws Exception {
        String outputBombSource = """
                public class Main {
                    public static void main(String[] args) {
                        String line = "A".repeat(1024);
                        for (int i = 0; i < 200; i++) {
                            System.out.println(line);
                        }
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            assertThat(session.compile(outputBombSource).success()).isTrue();

            RunTestResult result = session.runTest("", 1);
            assertThat(result.outputLimitExceeded()).isTrue();
            assertThat(result.stdout().getBytes(StandardCharsets.UTF_8).length).isEqualTo(properties.maxOutputBytes());
        }
    }

    @Test
    public void shouldContainThreadBombViaPidsLimit() throws Exception {
        String threadBombSource = """
                public class Main {
                    public static void main(String[] args) {
                        for (int i = 0; i < 500; i++) {
                            new Thread(() -> {
                                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                            }).start();
                        }
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            assertThat(session.compile(threadBombSource).success()).isTrue();

            RunTestResult result = session.runTest("", 1);
            assertThat(result.exitCode()).isNotZero();
        }
    }

    @Test
    public void shouldBlockNetworkAndRootFilesystemWrites() throws Exception {
        String adversarialSource = """
                import java.io.FileWriter;
                import java.net.InetSocketAddress;
                import java.net.Socket;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        try {
                            new FileWriter("/file.txt").write("pwn");
                            System.out.println("FS_WRITABLE");
                            return;
                        } catch (Exception expected) {}

                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress("8.8.8.8", 53), 1000);
                            System.out.println("NET_REACHABLE");
                            return;
                        } catch (Exception expected) {}

                        System.out.println("SANDBOX_LOCKED_DOWN");
                    }
                }
                """;

        try (SandboxSession session = sandboxRunner.openSession()) {
            assertThat(session.compile(adversarialSource).success()).isTrue();

            RunTestResult result = session.runTest("", 1);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().strip()).isEqualTo("SANDBOX_LOCKED_DOWN");
        }
    }
}
