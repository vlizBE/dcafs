package util.taskblocks;

import worker.Datagram;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

public class LabelBlock extends AbstractBlock{
    BlockingQueue<Datagram> dQueue;
    String label="";
    String value="";
    ArrayList<String[]> pairs = new ArrayList<>();

    public LabelBlock(  BlockingQueue<Datagram> dQueue,String set ){
        this.dQueue=dQueue;
        pairs.add(new String[]{set.substring(0,set.indexOf(":")),set.substring(set.indexOf(":")+1)});
    }
    public static LabelBlock prepBlock( BlockingQueue<Datagram> dQueue, String set ){
        return new LabelBlock(dQueue,set);
    }
    @Override
    public boolean build() {
        return true;
    }
    public LabelBlock addSet( String set ){
        pairs.add(new String[]{set.substring(0,set.indexOf(":")),set.substring(set.indexOf(":")+1)});
        return this;
    }
    public LabelBlock addSet( String label,String value ){
        pairs.add(new String[]{label,value});
        return this;
    }
    @Override
    public boolean addNext(TaskBlock block) {
        return false;
    }
    @Override
    public boolean addData(String data){
        dQueue.add( Datagram.build(data.substring(data.indexOf(":")+1))
                                .label(data.substring(0,data.indexOf(":")))
                    );
        return true;
    }
    @Override
    public boolean start(TaskBlock starter) {
        for( var p : pairs )
            dQueue.add( Datagram.build(p[1]).label(p[0]) );
        doNext();
        return true;
    }

    @Override
    public void nextOk() {

    }

    @Override
    public void nextFailed() {

    }
}
