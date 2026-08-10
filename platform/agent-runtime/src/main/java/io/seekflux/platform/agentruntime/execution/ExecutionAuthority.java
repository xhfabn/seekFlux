package io.seekflux.platform.agentruntime.execution;

public interface ExecutionAuthority extends AutoCloseable {

    long fencingToken();

    boolean renew(long ttlMillis);

    @Override
    void close();
}
