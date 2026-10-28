package com.bilicki.codejudge.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(WorkerApplication.class, args);

		SandboxRunner sandboxRunner = new SandboxRunner();
		sandboxRunner.executeJavaCode("""
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
	}

}
