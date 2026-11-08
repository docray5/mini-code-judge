package com.bilicki.codejudge.worker.judge;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class OutputComparator {

    public boolean matches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return normalize(actual).equals(normalize(expected));
    }

    private String normalize(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").split("\n", -1))
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .stripTrailing();
    }
}
