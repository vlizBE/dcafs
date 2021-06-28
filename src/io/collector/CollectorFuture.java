package io.collector;

/**
 * Allows a class to monitor the result of a collector
 */
public interface CollectorFuture {
    void collectorFinished( String id, String message, Object result);
}
