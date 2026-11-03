package com.bilicki.codejudge.worker.sandbox;

import org.springframework.stereotype.Component;

@Component
public interface SandboxRunner {
    SandboxSession openSession();
}
