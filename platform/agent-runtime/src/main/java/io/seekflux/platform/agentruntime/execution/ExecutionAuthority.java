package io.seekflux.platform.agentruntime.execution;

public interface ExecutionAuthority extends AutoCloseable {

    boolean renew(long ttlMillis);

    @Override
    void close();
}
