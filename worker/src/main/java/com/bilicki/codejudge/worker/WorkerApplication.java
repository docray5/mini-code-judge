package com.bilicki.codejudge.worker;

import com.bilicki.codejudge.worker.sandbox.RunTestResult;
import com.bilicki.codejudge.worker.sandbox.SandboxRunner;
import com.bilicki.codejudge.worker.sandbox.SandboxSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@ConfigurationPropertiesScan
@SpringBootApplication
public class WorkerApplication {

	public static void main(String[] args) {
		// temporary so that everything gets managed by spring boot

		ConfigurableApplicationContext context = SpringApplication.run(WorkerApplication.class, args);

		// Test Success
		SandboxRunner sandboxRunner = context.getBean(SandboxRunner.class);
		try (SandboxSession sandboxSession = sandboxRunner.openSession()) {

			sandboxSession.compile("""
					import java.util.Scanner;
					
					public class Main {
					
					    public static void printFibonacciSeries(int count) {
					        int firstTerm = 0;
					        int secondTerm = 1;
					
					        System.out.print("Fibonacci Series of " + count + " numbers: ");
					
					        for (int i = 1; i <= count; i++) {
					            System.out.print(firstTerm + " ");
					
					            int nextTerm = firstTerm + secondTerm;
					            firstTerm = secondTerm;
					            secondTerm = nextTerm;
					        }
					        System.out.println();
					    }
					
					    public static void main(String[] args) {
					    	Scanner scanner = new Scanner(System.in);
					        int numberOfTerms = scanner.nextInt();
					
					        printFibonacciSeries(numberOfTerms);
					    }
					}
					
					""");


			RunTestResult result = sandboxSession.runTest("10", 3);
			System.out.println("Fibonacci test duration:");
			System.out.println(result);
		} catch (Exception e) {
			System.out.println(e);
		}

		// Test OOM
		try (SandboxSession sandboxSession = sandboxRunner.openSession()) {

			sandboxSession.compile("""
			import java.util.ArrayList;
			import java.util.List;
			public class Main {
				public static void main(String[] args) {
					List<byte[]> list = new ArrayList<>();
					while (true) {
						list.add(new byte[100 * 1024]);
					}
				}
			}""");

			RunTestResult result = sandboxSession.runTest("", 3);
			System.out.println("oom exception test duration: " + result.durationMs());
			System.out.println(result);
		} catch (Exception e) {
			System.out.println(e);
		}

		// Timed out exception
		try (SandboxSession sandboxSession = sandboxRunner.openSession()) {

			sandboxSession.compile("""
			import java.util.concurrent.TimeUnit;
			
			public class Main {
				public static void main(String[] args) {
					try {
						TimeUnit.SECONDS.sleep(10);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}""");

			RunTestResult result = sandboxSession.runTest("", 3);
			System.out.println("timed out exception test:");
			System.out.println(result);
		} catch (Exception e) {
			System.out.println(e);
		}

		// output exceeded
		try (SandboxSession sandboxSession = sandboxRunner.openSession()) {

			sandboxSession.compile("""
			public class Main {
				public static void main(String[] args) {
					for (int i = 0; i < 10000; i++) {
						System.out.println("Some very long text.");
					}
				}
			}""");

			RunTestResult result = sandboxSession.runTest("", 3);
			System.out.println("output exceeded test duration:");
			System.out.println(result);
		} catch (Exception e) {
			System.out.println(e);
		}

	}

}
