package io.collector;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

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
import java.util.StringJoiner;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileCollector extends AbstractCollector{

    ScheduledExecutorService scheduler;

   // Queue<String> dataBuffer = new ConcurrentLinkedQueue<String>();
    BlockingQueue<String> dataBuffer = new LinkedBlockingQueue<>();
    private BlockingQueue<Datagram> dQueue;                        // Queue to send commands

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
    private boolean zippedRoll=false;

    private String currentForm = "";
    //private String workPath="";

    long lastData=-1;
    long firstData=-1;

    /* Triggers */
    enum TRIGGERS {IDLE, ROLLOVER, MAXSIZE };
    ArrayList<TriggeredCommand> trigCmds = new ArrayList<>();

    /* Size limit */
    long maxBytes=-1;
    boolean zipMaxBytes=false;
    private boolean headerChanged=false;

    private Future<?> flushFuture;

    public FileCollector(String id, String timeoutPeriod, ScheduledExecutorService scheduler,BlockingQueue<Datagram> dQueue) {
        super(id);
        this.dQueue=dQueue;
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler=scheduler;
    }
    public FileCollector(String id,BlockingQueue<Datagram> dQueue ){
        super(id);
        this.dQueue=dQueue;
    }
    @Override
    public String getID(){ return "fc:"+id;}
    public String toString(){
        return "Writing to "+getPath()+" buffer containing "+byteCount+"bytes";
    }
    public void setScheduler( ScheduledExecutorService scheduler ){
        this.scheduler=scheduler;
    }
    /**
     * Read the elements and build the FileCollectors based on the content
     * @param fcEles The filecollector elements
     * @param scheduler A Scheduler used for timeouts, writes etc
     * @param workpath The current workpath
     * @return A list of the found filecollectors
     */
    public static List<FileCollector> createFromXml(Stream<Element> fcEles, ScheduledExecutorService scheduler, BlockingQueue<Datagram> dQueue, String workpath ) {
        var fcs = new ArrayList<FileCollector>();
        if( scheduler==null){
            Logger.error("Need a valid scheduler to use FileCollectors");
            return fcs;
        }
        for( Element fcEle : fcEles.collect(Collectors.toList()) ) {
            String id = XMLtools.getStringAttribute(fcEle, "id", "");
            if( id.isEmpty() )
                continue;
            var fc = new FileCollector(id,dQueue);
            fc.setScheduler(scheduler);
            fc.readFromXML(fcEle,workpath);
            fcs.add(fc);
        }
        return fcs;
    }
    public void readFromXML( Element fcEle, String workpath ){
        /* Flush settings */
        Element flush = XMLtools.getFirstChildByTag(fcEle,"flush");
        if( flush != null ){
            setBatchsize( XMLtools.getIntAttribute(flush,"batchsize",Integer.MAX_VALUE));
            if( scheduler != null ) {
                String timeout = XMLtools.getStringAttribute(flush, "age", "-1");
                if (!timeout.equalsIgnoreCase("-1")) {
                    setTimeOut(timeout, scheduler);
                }
            }
        }
        /* Source and destination */
        addSource( XMLtools.getStringAttribute(fcEle,"src",""));
        String path = XMLtools.getChildValueByTag(fcEle,"path","");
        if( path.isEmpty() ){
            Logger.error(id+"(fc) -> No valid destination given");
            return;
        }

        setPath(path,workpath);

        /* Headers */
        headers.clear();
        for( var ele : XMLtools.getChildElements(fcEle,"header") ){
            addHeaderLine(ele.getTextContent());
        }

        /* RollOver */
        Element roll = XMLtools.getFirstChildByTag(fcEle, "rollover");
        if( roll != null ){
            int rollCount = XMLtools.getIntAttribute(roll, "count", 1);
            String unit = XMLtools.getStringAttribute(roll, "unit", "none").toLowerCase();
            boolean zip = XMLtools.getBooleanAttribute(roll,"zip",false);
            String format = roll.getTextContent();

            TimeTools.RolloverUnit rollUnit = TimeTools.convertToRolloverUnit( unit );
            if( rollUnit !=null){
                Logger.info(id+"(fc) -> Setting rollover: "+format+" "+rollCount+" "+rollUnit);
                setRollOver(format,rollCount,rollUnit,zip);
            }else{
                Logger.error(id+"(fc) -> Bad Rollover given" );
                return;
            }
        }
        /* Size limit */
        trigCmds.clear();
        Element sizeEle = XMLtools.getFirstChildByTag(fcEle, "sizelimit");
        if( sizeEle != null ){
            boolean zip = XMLtools.getBooleanAttribute(fcEle,"zip",false);
            String size = sizeEle.getTextContent();
            if( size!=null){
                setMaxFileSize(size.toLowerCase(),zip);
            }
        }
        /* Triggered */
        for( var ele : XMLtools.getChildElements(fcEle,"cmd") ){
            String cmd = ele.getTextContent();
            addTriggerCommand(XMLtools.getStringAttribute(ele,"trigger","none").toLowerCase(),cmd);
        }

        /* Changing defaults */
        setLineSeparator( Tools.fromEscapedStringToBytes( XMLtools.getStringAttribute(fcEle,"eol",System.lineSeparator())) );

        /* Headers change ?*/
        if( Files.exists(getPath()) ) {
            var curHead = FileTools.readLines(getPath(), 1, headers.size());
            headerChanged = false;
            if (curHead.size() == headers.size()) {
                for (int a = 0; a < headers.size(); a++) {
                    if (!headers.get(a).equals(curHead.get(a))) {
                        headerChanged = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Add a blank node in the position the fab is pointing to
     * @param fab XMLfab pointing to where the collectors parent should be
     * @param id The id for the filecollector
     * @param source The source of data
     * @param path The path of the file
     */
    public static void addBlankToXML(XMLfab fab, String id, String source, String path ){
        fab.selectOrAddChildAsParent("collectors")
                .addChild("file").attr("id",id).attr("src",source)
                    .down()
                    .addChild("path",path)
                    .addChild("flush").attr("batchsize","-1").attr("age","1m")
                .build();
    }

    /**
     * Set the maximum size th file can become, and whether or not to zip it after reaching this point
     * @param size The size of the file, allows kb,mb,gb extension (fe. 15mb)
     * @param zip If true, this file gets zipped
     */
    public void setMaxFileSize( String size,boolean zip ){
        long multiplier=1;
        size=size.replace("b","");
        if( size.endsWith("k"))
            multiplier=1024;
        if( size.endsWith("m"))
            multiplier=1024*1024;
        if( size.endsWith("g"))
            multiplier=1024*1024*1024;

        maxBytes=NumberUtils.toLong(size.substring(0,size.length()-1))*multiplier;
        zipMaxBytes=zip;
        Logger.info(id+"(fc) -> Maximum size set to "+maxBytes);
    }

    /**
     * Change the max file size but keep the zip option as it was
     * @param size The size of the file, allows kb,mb,gb extension (fe. 15mb)
     */
    public void setMaxFileSize( String size ){
        setMaxFileSize(size,zipMaxBytes);
    }

    /**
     * Add a triggered command to the collector
     * @param trigger The trigger, current options: rollover, idle, maxsize
     * @param cmd The command to execute if triggered
     * @return True if added successfully
     */
    public boolean addTriggerCommand( String trigger, String cmd ){
        if(cmd==null)
            return false;

        switch(trigger){
            case "rollover": addTriggerCommand(TRIGGERS.ROLLOVER,cmd);break;
            case "idle": addTriggerCommand(TRIGGERS.IDLE,cmd);break;
            case "maxsize": addTriggerCommand(TRIGGERS.MAXSIZE,cmd);break;
            default:return false;
        }
        return true;
    }
    /**
     * Add a triggered command to the collector
     * @param trigger The trigger, using the enum
     * @param cmd The command to execute if triggered
     * @return True if added successfully
     */
    public void addTriggerCommand( TRIGGERS trigger, String cmd ){
        trigCmds.add( new TriggeredCommand(trigger,cmd) );
    }
    /**
     * Set the the full path (relative of absolute) to the file
     * @param path the path to the file
     */
    public void setPath( Path path ){
        this.destPath=path;
    }
    public void setPath( String path, String workPath ){
       setPath(Path.of(path),workPath);
    }
    public void setPath( Path path, String workPath ){
        if( !path.isAbsolute()) {
            destPath = Path.of(workPath).resolve(path);
        }else{
            destPath=path;
        }
        Logger.info(id+"(fc) -> Path set to "+destPath);
    }
    /**
     * Get the current path this file can be found at
     * @return The path to the file as a string
     */
    public Path getPath(){
        String path = destPath.toString();

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
        headerChanged=true;
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
        if( dataBuffer.isEmpty())
            firstData=Instant.now().getEpochSecond();

        dataBuffer.add(data);
        byteCount += data.length();
        lastData = Instant.now().getEpochSecond();

        if( timeoutFuture==null || timeoutFuture.isDone() || timeoutFuture.isCancelled() ){
            timeoutFuture = scheduler.schedule(new TimeOut(), secondsTimeout, TimeUnit.SECONDS );
        }

        if( dataBuffer.size() > batchSize && batchSize !=-1){
            Logger.debug(id+ "(fc) -> Buffer matches batchsize");
            flushNow();
        }
        return true;
    }

    /**
     * Force the collector to flush the data, used in case of urgent flushing
     * @return
     */
    public void flushNow(){
        if( flushFuture==null||flushFuture.isCancelled() || flushFuture.isDone()) {
            flushFuture = scheduler.submit(() -> appendData(getPath()));
        }
    }
    @Override
    protected void timedOut() {
        Logger.debug(id+ "(fc) -> TimeOut expired");

        if( dataBuffer.isEmpty() ){
            // Timed out with empty buffer
            trigCmds.stream().filter( tc -> tc.trigger==TRIGGERS.IDLE)
                             .forEach( tc->dQueue.add( Datagram.system(tc.cmd.replace("{path}",getPath().toString())).writable(this)) );
        }else{
            long dif = Instant.now().getEpochSecond() - lastData; // if there's a batchsize, that is primary

            if( batchSize==-1 )
                dif = Instant.now().getEpochSecond() - firstData; // if there's no batchsize

            if( dif >= secondsTimeout-1 ) {
                flushNow();
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

        if( dest ==null) {
            Logger.error(id+"(fc) -> No valid destination path");
            return;
        }
        if( Files.notExists(dest) ){
            try { // So first create the dir structure
                Files.createDirectories(dest.toAbsolutePath().getParent());
            } catch (IOException e) {
                Logger.error(e);
            }
        }

        StringJoiner join;
        if( !headers.isEmpty() && (Files.notExists(dest) || headerChanged) ){ // the file doesn't exist yet
            join = new StringJoiner( lineSeparator );
            headers.forEach( hdr -> join.add(hdr.replace("{file}",dest.getFileName().toString()))); // Add the headers
        }else{
            join = new StringJoiner( lineSeparator,"",lineSeparator );
        }
        String line;
        int cnt=dataBuffer.size()*4; // At maximum write 4 times the buffer
        while((line=dataBuffer.poll()) != null && cnt !=0) {
            join.add(line);
            cnt--;
        }
        byteCount=0;
        try {
            if(headerChanged && Files.exists(dest)) {
                Path renamed=null;
                for( int a=1;a<1000;a++){
                    renamed = Path.of(dest.toString().replace(".", "."+a+"."));
                    // Check if the desired name or zipped version already is available
                    if( Files.notExists(renamed) )
                        break;
                }
                Files.move(dest, dest.resolveSibling(renamed));
            }
            headerChanged=false;

            Files.write(dest, join.toString().getBytes(charSet), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Logger.debug("Written " + join.toString().length() + " bytes to " + dest.getFileName().toString());

            if( maxBytes!=-1 ){

                if( Files.size(dest) >= maxBytes  ){
                    Path renamed=null;
                    for( int a=1;a<1000;a++){
                        renamed = Path.of(dest.toString().replace(".", "."+a+"."));
                        // Check if the desired name or zipped version already is available
                        if( Files.notExists(renamed) && Files.notExists(Path.of(renamed+".zip")) )
                            break;
                    }
                    if( renamed !=null) {
                        Logger.debug("Renamed to "+renamed.toString());
                        Files.move(dest, dest.resolveSibling(renamed)); // rename the file
                        String path ;
                        if (zipMaxBytes) { // if wanted, zip it
                            FileTools.zipFile(renamed);
                            Files.deleteIfExists(renamed);
                            path = renamed+".zip";
                        }else{
                            path = renamed.toString();
                        }

                        // run the triggered commands
                        trigCmds.stream().filter( tc -> tc.trigger==TRIGGERS.MAXSIZE)
                                .forEach(tc->dQueue.add(Datagram.system(tc.cmd.replace("{path}",path)).writable(this)));
                    }else{
                        Logger.error("Couldn't create another file "+dest.toString());
                    }
                }
            }

        } catch (IOException e) {
            Logger.error(id + "(fc) -> Failed to write to "+ dest+" because "+e);
        }
    }
    /* ***************************** Overrides  ******************************************************************* */
    @Override
    public void addSource( String source ){
        if( !this.source.isEmpty() ){
            dQueue.add( Datagram.system("stop:"+this.source).writable(this) );
        }
        this.source=source;
        dQueue.add( Datagram.system(source).writable(this) ); // request the data
    }
    /* ***************************** RollOver stuff *************************************************************** */
    public boolean setRollOver(String dateFormat, int rollCount, TimeTools.RolloverUnit unit, boolean zip ) {

        if(  unit == TimeTools.RolloverUnit.NONE || unit == null) {
            Logger.warn(id+"(fc) -> Bad rollover given");
            return false;
        }
        this.rollCount=rollCount;
        rollUnit=unit;
        oriFormat=dateFormat;
        zippedRoll=zip;

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
        return true;
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
        boolean renew;

        public DoRollOver( boolean renew ){
            this.renew=renew;
        }
        @Override
        public void run() {
            Logger.info(id+"(fc) -> Doing rollover");

            Path old = getPath();
            flushNow();

            if(renew)
                updateFileName(rolloverTimestamp); // first update the filename

            if( renew ) {
                Logger.info(id+"(fc) -> Current rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
                Logger.info(id+"(fc) -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC), rolloverTimestamp).toMillis();
                rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            }

            try {
                String path;
                if( zippedRoll ){
                    var res = flushFuture.get(5,TimeUnit.SECONDS); // Writing should be done in 5 seconds...
                    if( res==null) { // if zipping and append is finished
                        Path zip = FileTools.zipFile(old);
                        if (zip != null) {
                            Files.deleteIfExists(old);
                            Logger.info(id + "(fc) -> Zipped " + old.toAbsolutePath());
                            path = zip.toString();
                        } else {
                            Logger.error(id + "(fc) -> Failed to zip " + old.toString());
                            path=old.toString();
                        }
                    }else{
                        path=old.toString();
                    }
                }else{
                    Logger.info("Not zipping");
                    path=old.toString();
                }
                // Triggered commands
                trigCmds.stream().filter( tc -> tc.trigger==TRIGGERS.ROLLOVER)
                        .forEach(tc->dQueue.add(Datagram.system(tc.cmd.replace("{path}",path)).writable(FileCollector.this)));

            } catch (InterruptedException | ExecutionException | IOException | TimeoutException e) {
                Logger.error(e);
            }
        }
    }
    private class TriggeredCommand {
        TRIGGERS trigger;
        String cmd;

        public TriggeredCommand(TRIGGERS trigger, String cmd){
            this.trigger=trigger;
            this.cmd=cmd;
        }
    }
}
