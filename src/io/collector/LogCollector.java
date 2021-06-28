package io.collector;

import org.tinylog.Logger;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Simple collector that uses tinylog to log data to a file according to the tag provided (and setup done in tinylog
 * properties file) and info level
 *
 * Work in progress: still trying to figure out to have the properties file outside the jar...
 */
public class LogCollector extends AbstractCollector{

    String tag; // The tag that is recognized by the tinylog.properties

    /**
     * Create a LogCollector with an indefinite life time
     * @param id A (preferably) unique identifier
     * @param tag The tag that is recognized by the tinylog.properties
     */
    public LogCollector(String id, String tag) {
        super(id);
        this.tag=tag;
    }

    /**
     * Create a LogCollector with a limited life time
     * @param id A (preferably) unique identifier
     * @param tag The tag that is recognized by the tinylog.properties
     * @param timeoutPeriod How long the log will be filled eg. 5s,5m5s, 10m, 1h etc
     * @param scheduler A Service to submit the timeout task to
     */
    public LogCollector(String id, String tag, String timeoutPeriod, ScheduledExecutorService scheduler) {
        super(id, timeoutPeriod, scheduler);
        this.tag=tag;
    }

    @Override
    protected boolean addData(String data) {
        Logger.tag(tag).info(data);
        return true;
    }

    @Override
    protected void timedOut() {
        valid = false;
    }
}
