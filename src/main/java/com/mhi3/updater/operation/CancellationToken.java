package com.mhi3.updater.operation;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();

    CancellationToken NONE = () -> false;
}
