package com.bilicki.codejudge.worker.sandbox;

public interface SandboxSession extends AutoCloseable {
    CompileResult compile(String javaSourceCode);
    RunTestResult runTest(String stdin, int runTestTimeoutMs);

    @Override
    void close();
}
