package com.bilicki.codejudge.worker;

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

			sandboxSession.runTest("10", 3);
		} catch (Exception e) {
			System.out.println(e);
		}
	}

}
