package io.stream.file;

import io.Writable;
import org.tinylog.Logger;
import util.tools.FileTools;
import worker.Datagram;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class FileStream implements Writable {

    Path file;
    WatchService service;
    long position = 0;
    ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
    boolean issue=false;
    WatchKey wk;
    BlockingQueue<Datagram> dQueue;
    String label="system";

    public FileStream(Path p, BlockingQueue<Datagram> dQueue){
        this(p,null,dQueue);
    }
    public FileStream(Path p, WatchService ws, BlockingQueue<Datagram> dQueue ){
        this.file=p;
        this.dQueue=dQueue;
        try {
            if( Files.notExists(file)){
                Files.createFile(file);
            }
            position = Files.size(file);
            if( ws == null) {
                service = FileSystems.getDefault().newWatchService();
            }else{
                service=ws;
            }
            watcher.submit(()->watch());
        } catch (IOException e) {
            Logger.error(e);
        }
    }
    private void watch()  {
        try {
            wk = file.getParent().register(service, ENTRY_MODIFY);
            service.take();
            for (var ev : wk.pollEvents()) {
                String mod = ev.context().toString();
                if (mod.equals(file.getFileName().toString())) {
                    byte[] array = new byte[256];
                    int read=0;
                    try (FileInputStream stream = new FileInputStream(file.toFile())) {
                        stream.getChannel().position(position);
                        read = stream.read(array,0,256);
                    }catch( IOException r){
                        r.printStackTrace();
                    }
                    if(read > 0) {
                        String rec = new String(Arrays.copyOfRange(array,0,read));
                        position += read;
                        wk.reset();
                        wk.cancel();
                        dQueue.add(Datagram.build(rec.strip()).label(label).writable(this));
                        issue=false;
                        break;
                    }else{
                        if( issue ) {
                            position=0;
                            issue=false;
                        }else {
                            issue = true;
                        }
                    }
                }
            }
            // reset the key
            wk.reset();
        }catch(InterruptedException | IOException e){
            Logger.error(e);
        }
        watcher.schedule(()->watch(),5, TimeUnit.SECONDS);
    }
    @Override
    public boolean writeString(String data) {
        return writeLine(data);
    }

    @Override
    public boolean writeLine(String data) {
        String write=System.lineSeparator()+data+System.lineSeparator();
        position+=write.length();
        FileTools.appendToTxtFile(file,write);
        return true;
    }

    @Override
    public boolean writeBytes(byte[] data) {
        FileTools.appendToTxtFile(file,new String(data));
        position+=data.length;
        return true;
    }

    @Override
    public String getID() {
        return "file:"+file.getFileName().toString();
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }

    @Override
    public Writable getWritable() {
        return this;
    }
}
