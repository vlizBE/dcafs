package util.taskblocks;

public class ControlBlock extends AbstractBlock{

    TaskBlock target;
    enum ACTIONS {START,STOP};
    ACTIONS TODO;

    public ControlBlock( TaskBlock target, String action ){
        this.target=target;
        if( target==null)
            valid=false;
        switch(action){
            case "stop":  TODO = ACTIONS.STOP; break;
            case "start": TODO = ACTIONS.START; break;
            default: valid=false;
        }
    }
    public static ControlBlock prepBlock( TaskBlock target, String action){
        return new ControlBlock(target,action);
    }
    @Override
    public boolean build() {
        return true;
    }

    @Override
    public boolean start(TaskBlock starter) {
        switch(TODO){
            case STOP : target.stop(); return true;
            case START : target.start(this); return true;
        }
        return false;
    }
    public String toString(){
        switch(TODO){
            case STOP :  return "CB: Stop the block if running: "+target.toString();
            case START : return "CB: Start the block: " +target.toString();
        }
        return "CB: Nothing to do";
    }
    @Override
    public void nextOk() {

    }

    @Override
    public void nextFailed() {

    }
}
