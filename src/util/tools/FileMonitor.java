package util.tools;

import org.tinylog.Logger;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Function;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class FileMonitor {

    WatchService service;
    ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
    ArrayList<ReactionInfo> files = new ArrayList<>();
    static final int BUFFER_SIZE = 256;
    Path root;
    BlockingQueue<Datagram> dQueue;

    public FileMonitor(Path root, BlockingQueue<Datagram> dQueue){
        this.root=root;
        this.dQueue=dQueue;

        readFromXML();
    }
    /**
     * Check if the settings.xml contains an element for the monitor
     * @param xml The path to the settings.xml
     * @return True if it contains a monitor node
     */
    public static boolean inXML( Path xml ){
        return XMLtools.getAllElementsByTag(XMLtools.readXML( xml),"monitor").length==1;
    }

    /**
     * Read the monitor setup from settings.xml
     * @return True if read properly
     */
    public boolean readFromXML( ){
        var base = XMLtools.readXML(root.resolve("settings.xml"));
        var ele = XMLtools.getAllElementsByTag(base,"monitor");
        if( ele.length==0)
            return false;
        /* Check for simple file modification alerts */
        for( var file : XMLtools.getChildElements(ele[0],"file")){
            var p = XMLtools.getPathAttribute(file,"path",root);
            if( p.isEmpty())
                continue;
            String val = XMLtools.getChildValueByTag(file,"onmodify","");
            if( val.isBlank())
                continue;
            var mon = new ReactionInfo(p.get(),val);
            files.add( mon );
            watcher.submit(() -> watch(mon));
        }
        return true;
    }
    public void addSimpleWatch( Path p, Function<String,Integer> act){
        if(service == null) {
            try {
                service = FileSystems.getDefault().newWatchService();
                Files.createFile(p);
                var mon = new ReactionInfo(p,act,false);
                files.add( mon );
                watcher.submit(() -> watch(mon));
            } catch (IOException e) {
                Logger.error(e);
            }

        }
    }
    private void watch( ReactionInfo mon )  {
        int blank=0;
        if( !mon.exists() ){
            Logger.warn("No such file to monitor: "+mon.getFilename());
            return;
        }
        try {
            if(service == null)
                service = FileSystems.getDefault().newWatchService();

            var wk = mon.register(service);
            mon.updatePosition();
            service.take();
            for (var ev : wk.pollEvents()) {
                String mod = ev.context().toString();

                if (mod.equals(mon.getFilename())) {
                    if( mon.hasCmd() ) {
                        dQueue.add(Datagram.system(mon.getCmd()));
                        Logger.info("Executing '"+mon.getCmd()+"' because modified "+mon.getFilename());
                    }
                    if( mon.handlesReads() ) {
                        byte[] array = new byte[BUFFER_SIZE];
                        int read = 0;
                        try (FileInputStream stream = new FileInputStream(mon.file.toFile())) {
                            stream.getChannel().position(mon.position);
                            read = stream.read(array, 0, BUFFER_SIZE);
                        } catch (IOException r) {
                           Logger.error(r);
                        }
                        if(read > 0) {
                            String rec = new String(Arrays.copyOfRange(array,0,read));
                            wk.reset();
                            wk.cancel();
                            blank=mon.handleRead(rec);
                            break;
                        }else{
                            mon.handleIssue();
                        }
                    }else{
                        blank=mon.handleNoRead();
                    }
                }
            }
            // reset the key
            wk.reset();
        }catch(InterruptedException e){
            Logger.error(e);
        } catch (IOException e) {
            Logger.error(e);
        }
        watcher.schedule(()->watch(mon),Math.max(blank,1), TimeUnit.SECONDS);
    }

    /**
     * Simple class to contain all required info on what to do after an event
     */
    private class ReactionInfo {
        private Path file;
        private Function<String, Integer> action;
        private String cmd="";
        private long position=-1;
        private boolean issue=false;

        public ReactionInfo(Path file, Function<String, Integer> action, boolean alteration) throws IOException {
            this.file=file;
            this.action=action;
            if( alteration )
                position = Files.size(file);
        }
        public ReactionInfo(Path file, String cmd) {
            this.file=file;
            this.cmd=cmd;

            Logger.info("Monitoring '"+file+"', on modify: "+cmd);
        }
        public boolean updatePosition() throws IOException{
            if( position==-1)
                return false;
            long old=position;
            position = Files.size(file);
            return old!=position;
        }
        public WatchKey register( WatchService ws ) throws IOException {
            return file.getParent().register(ws, ENTRY_MODIFY);
        }
        public boolean handlesReads(){
            return position !=-1;
        }
        public int handleRead(String rec){
            position += rec.length();
            issue=false;
            return action.apply(rec);
        }
        public int handleNoRead(){
            return action.apply("");
        }
        public void handleIssue(){
            if( issue ) {
                position=0;
                issue=false;
            }else {
                issue = true;
            }
        }
        public String getFilename(){
            return file.getFileName().toString();
        }
        public boolean hasCmd(){
            return !cmd.isBlank();
        }
        public String getCmd(){
            return cmd;
        }
        public boolean exists(){
            return Files.exists(file);
        }
    }
}
