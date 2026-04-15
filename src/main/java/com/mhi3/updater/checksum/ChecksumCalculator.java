package com.mhi3.updater.checksum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;

public class ChecksumCalculator {
    public String calculate(Path file, String algorithm) throws IOException {
        byte[] data = Files.readAllBytes(file);
        return switch (algorithm.toUpperCase()) {
            case "SHA1", "SHA-1" -> digest(data, "SHA-1");
            case "SHA256", "SHA-256" -> digest(data, "SHA-256");
            case "MD5" -> digest(data, "MD5");
            case "CRC32" -> crc32(data);
            default -> throw new IllegalArgumentException("Unsupported checksum algorithm: " + algorithm);
        };
    }

    private String digest(byte[] bytes, String algo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Algorithm unavailable: " + algo, e);
        }
    }

    private String crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return Long.toHexString(crc32.getValue());
    }
}
