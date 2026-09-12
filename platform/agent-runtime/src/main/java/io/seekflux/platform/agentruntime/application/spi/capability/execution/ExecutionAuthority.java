package io.seekflux.platform.agentruntime.application.spi.capability.execution;

public interface ExecutionAuthority extends AutoCloseable {

    long fencingToken();

    boolean renew(long ttlMillis);

    @Override
    void close();
}
