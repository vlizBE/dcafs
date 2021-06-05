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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileCollector extends AbstractCollector{

    ScheduledExecutorService scheduler;
    Queue<String> dataBuffer = new ConcurrentLinkedQueue<String>();
    private int byteCount=0;
    private Path destination;
    private String lineSeparator = System.lineSeparator();
    private ArrayList<String> headers= new ArrayList<>();
    private Charset charSet = StandardCharsets.UTF_8;
    private int batchSize = 10;

    public FileCollector(String id, String timeoutPeriod, ScheduledExecutorService scheduler) {
        super(id);
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler=scheduler;
    }
    public FileCollector(String id){
        super(id);
    }
    public static List<FileCollector> createFromXml(Stream<Element> fcEles, ScheduledExecutorService scheduler ) {
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
            String path = XMLtools.getChildValueByTag(fcEle,"destination","");
            if( path.isEmpty() ){
                Logger.error(id+"(fc) -> No valid destination given");
                continue;
            }
            fc.setPath(Path.of(path));

            //Headers
            for( var ele : XMLtools.getChildElements(fcEle,"header") ){
                fc.addHeaderLine(ele.getTextContent());
            }

            // Changing defaults
            fc.setLineSeparator( Tools.fromEscapedStringToBytes( XMLtools.getStringAttribute(fcEle,"eol",System.lineSeparator())) );

            fcs.add(fc);
        }
        return fcs;
    }
    public void setPath( Path destination ){
        this.destination=destination;
    }
    public void setBatchsize( int batch ){
        this.batchSize=batch;
    }
    public void setTimeOut( String timeoutPeriod, ScheduledExecutorService scheduler ){
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler=scheduler;
        Logger.info(id+"(fc) -> Setting flush period to "+secondsTimeout+"s");
    }
    public void addHeaderLine(String header){
        headers.add(header);
    }
    public void setLineSeparator( String eol ){
        this.lineSeparator=eol;
    }

    @Override
    protected synchronized boolean addData(String data) {
        dataBuffer.add(data);
        byteCount += data.length();
        if( timeoutFuture==null || timeoutFuture.isDone() || timeoutFuture.isCancelled() ){
            timeoutFuture = scheduler.schedule(new TimeOut(), secondsTimeout, TimeUnit.SECONDS );
        }

        if( dataBuffer.size() > batchSize ){
            scheduler.submit(()->appendData());
        }
        return true;
    }

    @Override
    protected void timedOut() {
        Logger.info("TimeOut expired");

        if( dataBuffer.isEmpty() ){
            // Timed out with empty buffer
        }else{
            // Got data in buffer
           scheduler.submit( ()->appendData() );
           // cover edge case in which data is added during write...
           timeoutFuture = scheduler.schedule(new TimeOut(), secondsTimeout, TimeUnit.SECONDS );
        }
    }

    private void appendData(){
        Logger.info("Appending data?");
        StringJoiner join;
        if( destination==null) {
            Logger.error(id+" -> No valid destination path");
            return;
        }
        if(Files.notExists(destination) ){ // the file doesn't exist yet
            try { // So first create the dir structure
                Files.createDirectories(destination.toAbsolutePath().getParent());
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
            Files.write( destination, join.toString().getBytes(charSet) , StandardOpenOption.CREATE, StandardOpenOption.APPEND );
            Logger.info("Written "+join.toString().length()+" bytes to "+destination.getFileName().toString());
        } catch (IOException e) {
            Logger.error(id + " -> Failed to write to "+destination.toString());
        }
    }
}
