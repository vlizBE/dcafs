package util.taskblocks;

import das.CommandPool;
import io.Writable;
import io.email.Email;
import io.email.EmailSending;
import io.stream.StreamManager;
import io.stream.tcp.TcpServer;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.DataProviding;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.*;

public class BlockPool {

    HashMap<String,MetaBlock> startBlocks = new HashMap<>();
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    CommandPool cp;
    DataProviding dp;
    StreamManager ss;
    TcpServer ts;
    EmailSending es;

    public BlockPool( CommandPool cp, DataProviding dp, StreamManager ss ){
        this.cp=cp;
        this.dp=dp;
        this.ss=ss;
    }
    public void setTransServer(TcpServer ts){
        this.ts=ts;
    }
    public void addEmailSending(EmailSending es){
        this.es=es;
    }
    public Optional<MetaBlock> getStartBlock(String id, boolean createIfNew){
        if( startBlocks.get(id)==null && createIfNew){
            var v = new MetaBlock(id,"");
            startBlocks.put(id,v);
            return Optional.of(v);
        }
        return Optional.ofNullable(startBlocks.get(id));
    }
    public boolean runStartBlock( String id){
        return getStartBlock(id,false).map( tb->tb.start(null) ).orElse(false);
    }
    public void readFromXML( Path script){

        var fab=XMLfab.withRoot(script,"tasklist");
        // Go through the sets
        fab.selectChildAsParent("tasksets");

        for( var ts : fab.getChildren("taskset")){
            String tsId = XMLtools.getStringAttribute(ts,"id","");
            String info = XMLtools.getStringAttribute(ts,"info","");
            String req = XMLtools.getStringAttribute(ts,"req","");

            BlockTree tree = BlockTree.trunk( getStartBlock(tsId,true).get() );
            tree.getMetaBlock().info(info);

            startBlocks.put( tsId,tree.getMetaBlock());

            if( !req.isEmpty()){ // add the req step if any
                tree.addTwig( CheckBlock.prepBlock(dp,req));
            }

            for( var t : XMLtools.getChildElements(ts)){
                readTask(t,tree);
            }
        }
        fab.selectChildAsParent("tasks");

        for( var t : fab.getChildren("task")){
            String tid = XMLtools.getStringAttribute(t,"id","");
            BlockTree tree;
            if( tid.isEmpty() ){
                tree = BlockTree.trunk( getStartBlock("init",true).get() );
            }else{
                tree = BlockTree.trunk( new MetaBlock(tid,"Lose task") );
            }
            readTask(t,tree);
        }
        for( var b : startBlocks.values()){
            Logger.info( b.getTreeInfo());
        }
    }
    public void readTask( Element t, BlockTree tree){
        var trigger = XMLtools.getStringAttribute(t,"trigger","");
        if( !trigger.isEmpty()){
            tree.branchOut( TriggerBlock.prepBlock(scheduler,trigger));
        }

        // Read and process the state attribute
        var state = XMLtools.getStringAttribute(t,"state","");
        state=state.replace("always",""); // remove the old default
        if( !state.isEmpty() && state.contains(":")){
            var stat = state.split(":");
            tree.branchOut( CheckBlock.prepBlock(dp, "{t:"+stat[0]+"} equals "+stat[1]));
        }

        // Read and process the req attribute
        var req = XMLtools.getStringAttribute(t,"req","");
        if( !req.isEmpty()){
            tree.branchOut( CheckBlock.prepBlock(dp,req));
        }

        // Read and process the output attribute
        var output = XMLtools.getStringAttribute(t,"output","").split(":");
        var data = t.getTextContent();
        var values = data.split(";");

        switch(output[0]){
            case "":case "system":
                tree.addTwig( CmdBlock.prepBlock(cp,t.getTextContent()));
                break;
            case "email":
                String attachment = XMLtools.getStringAttribute(t,"attachment","");
                tree.branchOut( CmdBlock.prepBlock(cp,values[1]));
                var email = Email.to(output[1]).subject(values[0]).attachment(attachment).content(values[1]);
                tree.addTwig( EmailBlock.prepBlock(es,email));
                tree.branchIn();
                break;
            case "stream":
                var bsOpt = ss.getStream(output[1]);
                if( bsOpt.isPresent() && bsOpt.get() instanceof Writable ) {
                    var bl = WritableBlock.prepBlock( bsOpt.get(), t.getTextContent());
                    var reply = XMLtools.getStringAttribute(t,"reply","");
                    if( !reply.isEmpty()) {
                        bl.addReply(reply, scheduler);
                    }
                    tree.addTwig(bl);
                }else{
                    Logger.error("No such Base stream: "+output[1]);
                    return;
                }
                break;
            case "trans":
                if( ts!=null ){
                    var h = ts.getClientWritable(output[1]);
                    if( h.isPresent()){
                        tree.addTwig(WritableBlock.prepBlock( h.get(), t.getTextContent()));
                    }else{
                        Logger.error("No such client connected: "+output[1]);
                    }
                }else{
                    Logger.error("No TCP server defined");
                    return;
                }
                break;
            case "manager":
                var text = t.getTextContent().split(":");
                switch( text[0]){
                    case "taskset":
                        tree.addTwig( getStartBlock(text[1],true).get() );
                        break;
                    case "stop":
                        var b = getStartBlock(text[1],false);
                        if( b.isPresent() ){
                            tree.addTwig( ControlBlock.prepBlock(b.get(),"stop"));
                        }else{
                            var mb=new MetaBlock(text[1],"");
                            startBlocks.put(text[1],mb);
                            tree.addTwig( ControlBlock.prepBlock(mb,"stop"));
                        }
                        break;
                }
                break;
        }

        if( !trigger.isEmpty()){
            tree.branchIn();
        }
        if( !req.isEmpty()){
            tree.branchIn();
        }
        if( !state.isEmpty()){
            tree.branchIn();
        }
    }
}
