package com.mhi3.updater.util;

import com.mhi3.updater.model.VersionInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionTransformService {
    private static final Pattern INPUT_PATTERN = Pattern.compile("P?(\\d{4})", Pattern.CASE_INSENSITIVE);

    public VersionInfo derive(String input) {
        if (input == null) throw new IllegalArgumentException("Version cannot be null");
        Matcher matcher = INPUT_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Version must match P#### or #### format");
        }
        String mu = matcher.group(1);
        String normalized = "P" + mu;
        String wildcard = "P" + mu.substring(0, 3) + "*";
        return new VersionInfo(input.trim(), normalized, mu, wildcard);
    }
}
