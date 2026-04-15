package com.mhi3.updater;

import com.mhi3.updater.checksum.ChecksumCalculator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ChecksumCalculatorTest {
    @Test
    void computesMultipleAlgorithms() throws Exception {
        Path temp = Files.createTempFile("sum-test", ".txt");
        Files.writeString(temp, "hello");
        ChecksumCalculator c = new ChecksumCalculator();
        assertFalse(c.calculate(temp, "SHA1").isBlank());
        assertFalse(c.calculate(temp, "SHA-256").isBlank());
        assertFalse(c.calculate(temp, "MD5").isBlank());
        assertFalse(c.calculate(temp, "CRC32").isBlank());
    }
}
