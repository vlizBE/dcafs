package com.stream.collector;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileCollector extends AbstractCollector{

    ScheduledExecutorService scheduler;

    Queue<String> dataBuffer = new ConcurrentLinkedQueue<String>();
    private int byteCount=0;
    private Path destPath;
    private String lineSeparator = System.lineSeparator();
    private ArrayList<String> headers= new ArrayList<>();
    private Charset charSet = StandardCharsets.UTF_8;
    private int batchSize = 10;

    /* Variables related to the rollover */
    private DateTimeFormatter format = null;
    private String oriFormat="";
    private ScheduledFuture<?> rollOverFuture;
    private TimeTools.RolloverUnit rollUnit = TimeTools.RolloverUnit.NONE;
    private int rollCount = 0;

    private LocalDateTime rolloverTimestamp;

    private String currentForm = "";
    private String workPath="";

    long lastData=-1;

    public FileCollector(String id, String timeoutPeriod, ScheduledExecutorService scheduler) {
        super(id);
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler=scheduler;
    }
    public FileCollector(String id){
        super(id);
    }

    /**
     * Read the elements and build the FileCollectors based on the content
     * @param fcEles The filecollector elements
     * @param scheduler A Scheduler used for timeouts, writes etc
     * @param workpath The current workpath
     * @return A list of the found filecollectors
     */
    public static List<FileCollector> createFromXml(Stream<Element> fcEles, ScheduledExecutorService scheduler, String workpath ) {
        var fcs = new ArrayList<FileCollector>();
        if( scheduler==null){
            Logger.error("Need a valid scheduler to use FileCollectors");
            return fcs;
        }
        for( Element fcEle : fcEles.collect(Collectors.toList()) ) {
            String id = XMLtools.getStringAttribute(fcEle, "id", "");
            if( id.isEmpty() )
                continue;
            var fc = new FileCollector(id);

            // Flush settings
            Element flush = XMLtools.getFirstChildByTag(fcEle,"flush");
            if( flush != null ){
                fc.setBatchsize( XMLtools.getIntAttribute(flush,"batchsize",Integer.MAX_VALUE));
                if( scheduler != null ) {
                    String timeout = XMLtools.getStringAttribute(flush, "period", "-1");
                    if (!timeout.equalsIgnoreCase("-1")) {
                        fc.setTimeOut(timeout, scheduler);
                    }
                }
            }
            // Source and destination
            fc.addSource( XMLtools.getStringAttribute(fcEle,"src",""));
            fc.setWorkPath(workpath);
            String path = XMLtools.getChildValueByTag(fcEle,"path","");
            if( path.isEmpty() ){
                Logger.error(id+"(fc) -> No valid destination given");
                continue;
            }
            Path dest = Path.of(path);
            if( !dest.isAbsolute()) {
                fc.setWorkPath(workpath);
            }
            fc.setPath(dest);

            //Headers
            for( var ele : XMLtools.getChildElements(fcEle,"header") ){
                fc.addHeaderLine(ele.getTextContent());
            }

            /* RollOver */
            Element roll = XMLtools.getFirstChildByTag(fcEle, "rollover");
            if( roll != null ){
                int rollCount = XMLtools.getIntAttribute(roll, "count", 1);
                String unit = XMLtools.getStringAttribute(roll, "unit", "").toLowerCase();
                String format = roll.getTextContent();

                TimeTools.RolloverUnit rollUnit = TimeTools.convertToRolloverUnit( unit );
                if( rollUnit !=null){
                    Logger.info(id+"(fc) -> Setting rollover: "+format+" "+rollCount+" "+rollUnit);
                    fc.setRollOver(format,rollCount,rollUnit);
                }else{
                    Logger.error(id+"(fc) -> Bad Rollover given" );
                    continue;
                }
            }

            // Changing defaults
            fc.setLineSeparator( Tools.fromEscapedStringToBytes( XMLtools.getStringAttribute(fcEle,"eol",System.lineSeparator())) );

            fcs.add(fc);
        }
        return fcs;
    }

    /**
     * Set the the full path (relative of absolute) to the file
     * @param path the path to the file
     */
    public void setPath( Path path ){
        this.destPath =path;
    }
    /**
     * Get the current path this database can be found at
     * @return The path to the database as a string
     */
    public Path getPath(){
        String path = workPath+destPath.toString();

        //without rollover
        if( currentForm.isEmpty() )
            return Path.of(path);

        //with rollover and on a specific position
        if( path.contains("{rollover}"))
            return Path.of(path.replace("{rollover}", currentForm));

        // with rollover but on default position
        return Path.of(path.replace(".", currentForm+'.'));
    }

    /**
     * Alter the work path used
     * @param workPath The new path
     */
    public void setWorkPath( String workPath ){
        this.workPath=workPath;
    }

    /**
     * Set the amount of messages in the batch before it's flushed to disk
     * @param batch The amount
     */
    public void setBatchsize( int batch ){
        this.batchSize=batch;
    }

    /**
     * Set a maximum age of data before a flush is initiated
     * @param timeoutPeriod The period (fe. 5m or 63s etc)
     * @param scheduler A scheduler to use for this
     */
    public void setTimeOut( String timeoutPeriod, ScheduledExecutorService scheduler ){
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler=scheduler;
        Logger.info(id+"(fc) -> Setting flush period to "+secondsTimeout+"s");
    }

    /**
     * Add a line that will we added first to a new file
     * @param header A line to add at the top, the standard line separator will be appended
     */
    public void addHeaderLine(String header){
        headers.add(header);
    }

    /**
     * Change the line separator, by default this is the system one
     * @param eol Alter the line separater/eol characters
     */
    public void setLineSeparator( String eol ){
        this.lineSeparator=eol;
    }

    @Override
    protected synchronized boolean addData(String data) {
        dataBuffer.add(data);
        byteCount += data.length();
        lastData = Instant.now().getEpochSecond();

        if( timeoutFuture==null || timeoutFuture.isDone() || timeoutFuture.isCancelled() ){
            timeoutFuture = scheduler.schedule(new TimeOut(), secondsTimeout, TimeUnit.SECONDS );
        }

        if( dataBuffer.size() > batchSize ){
            Logger.debug(id+ "(fc) -> Buffer matches batchsize");
            scheduler.submit(()->appendData(getPath()));
        }
        return true;
    }

    /**
     * Force the collector to flush the data, used in case of urgent flushing
     */
    public void flushNow(){
        scheduler.submit(()->appendData(getPath()));
    }
    @Override
    protected void timedOut() {
        Logger.debug(id+ "(fc) -> TimeOut expired");

        if( dataBuffer.isEmpty() ){
            // Timed out with empty buffer
        }else{
            long dif = Instant.now().getEpochSecond() - lastData;
           if( dif >= secondsTimeout-1 ) {
               scheduler.submit(() -> appendData(getPath()));
               timeoutFuture = scheduler.schedule(new TimeOut(), secondsTimeout, TimeUnit.SECONDS );
           }else{
               long next = secondsTimeout - dif;
               timeoutFuture = scheduler.schedule(new TimeOut(), next, TimeUnit.SECONDS );
           }
        }
    }

    /**
     * Write data to the chosen file
     * @param dest The path to write to
     */
    private void appendData( Path dest ){
       StringJoiner join;
        if( dest ==null) {
            Logger.error(id+"(fc) -> No valid destination path");
            return;
        }
        if(Files.notExists(dest) ){ // the file doesn't exist yet
            try { // So first create the dir structure
                Files.createDirectories(dest.toAbsolutePath().getParent());
            } catch (IOException e) {
                Logger.error(e);
            }
            join = new StringJoiner( lineSeparator );
            headers.forEach( join::add ); // Add the headers
        }else{
            join = new StringJoiner( lineSeparator,lineSeparator,"" );
        }
        String line;
        while((line=dataBuffer.poll()) !=null ) {
            join.add(line);
        }
        byteCount=0;
        try {
            Files.write(dest, join.toString().getBytes(charSet) , StandardOpenOption.CREATE, StandardOpenOption.APPEND );
            Logger.debug("Written "+join.toString().length()+" bytes to "+ dest.getFileName().toString());
        } catch (IOException e) {
            Logger.error(id + "(fc) -> Failed to write to "+ dest.toString());
        }
    }

    /* ***************************** RollOver stuff *************************************************************** */
    public void setRollOver(String dateFormat, int rollCount, TimeTools.RolloverUnit unit ) {

        if(  unit == TimeTools.RolloverUnit.NONE || unit == null) {
            Logger.warn(id+"(fc) -> Bad rollover given");
            return;
        }
        this.rollCount=rollCount;
        rollUnit=unit;
        oriFormat=dateFormat;

        format = DateTimeFormatter.ofPattern(dateFormat);
        rolloverTimestamp = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        Logger.info(id+"(fc) -> Init rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        rolloverTimestamp = TimeTools.applyTimestampRollover(true,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        updateFileName(rolloverTimestamp);
        rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        Logger.info(id+"(fc) -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));

        long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC),rolloverTimestamp).toMillis();
        if( next > 100) {
            rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            Logger.info(id+"(fc) -> Next rollover in "+TimeTools.convertPeriodtoString(rollOverFuture.getDelay(TimeUnit.SECONDS),TimeUnit.SECONDS));
        }else{
            Logger.error(id+"(fc) -> Bad rollover for "+rollCount+" counts and unit "+unit+" because next is "+next);
        }
        return;
    }
    /**
     * Update the filename of the database currently used
     * @return True if successful or not needed (if no rollover)
     */
    public boolean updateFileName(LocalDateTime ldt){
        if( format==null)
            return true;
        try{
            if( ldt!=null ){
                currentForm = ldt.format(format);
            }
        }catch( java.time.temporal.UnsupportedTemporalTypeException f ){
            Logger.error( getID() + " -> Format given is unsupported! Database creation cancelled.");
            return false;
        }
        Logger.info("Updated filename after rollover to "+getPath());
        return true;
    }
    private class DoRollOver implements Runnable {
        boolean renew=true;

        public DoRollOver( boolean renew ){
            this.renew=renew;
        }
        @Override
        public void run() {
            Logger.info(id+"(fc) -> Doing rollover");

            scheduler.submit(()->appendData(getPath())); // First flush the data

            if(renew)
                updateFileName(rolloverTimestamp); // first update the filename

            if( renew ) {
                Logger.info(id+"(fc) -> Current rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
                Logger.info(id+"(fc) -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC), rolloverTimestamp).toMillis();
                rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            }
        }
    }
}
