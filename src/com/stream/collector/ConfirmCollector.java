package com.stream.collector;

import com.stream.Writable;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The purpose of this class is to handle sending data to a stream and wait for a specific
 * response from it. Retries will be made and a good/bad result is reported to the registered
 * WritableFutures.
 *
 * It's considered a collector because data arriving at it doesn't go any further.
 */
public class ConfirmCollector extends AbstractCollector {

    ArrayList<Confirm> confirms = new ArrayList<>();
    Writable target;
    ScheduledExecutorService scheduler;
    int maxAttempts=5;
    int timeoutSeconds=3;

    /* Constructors */
    public ConfirmCollector(String id, Writable target, ScheduledExecutorService scheduler ){
        super(id);
        this.target=target;
        this.scheduler=scheduler;
    }
    public ConfirmCollector(String id, int tries, int timeoutSeconds, Writable target, ScheduledExecutorService scheduler){
        this(id,target,scheduler);
        setAttempts(tries, timeoutSeconds);
    }

    /**
     * Set the attempts that should be made for the confirms
     * @param attempts How many times the message is send
     * @param secs How much seconds is waited for a retry
     */
    public void setAttempts( int attempts, int secs){
        timeoutSeconds=secs;
        this.maxAttempts=attempts;
    }

    /*  Class specific stuff */

    /**
     * Add a confirm, if the reply contains ** this will be replaced with the message
     * @param message The message to send to the stream
     * @param reply The reply that should be received
     */
    public void addConfirm( String message, String reply ){
        confirms.add( new Confirm(message,reply.replace("**", message)) );
        if( confirms.size()==1){
            logInfo(id+" -> Sending '"+confirms.get(0).msg+"'");
            confirms.get(0).doAttempt();
        }else{
            logInfo(id+" -> Added '"+message+"' with reply '"+reply+"' to the queue");
        }
    }

    /**
     * Add mutliple confirms to this object with the same reply
     * @param messages The messages that should get the reply
     * @param reply the expected reply to the messages
     */
    public void addConfirm( String[] messages, String reply ){
        for( String msg : messages )
            addConfirm(msg, reply);
    }

    /**
     * Check if this has no more confirms left
     * @return True if none left
     */
    public boolean isEmpty(){
        return confirms.isEmpty();
    }
    /**
     * Get a list of all the confirms stored in this object
     * @return The list
     */
    public String getStored(){
        StringJoiner join = new StringJoiner("\r\n");
        int a=0;
        for(Confirm c : confirms ){
            join.add( a+") "+c.msg +" -> "+c.reply);
            a++;
        }
        return join.toString();
    }

    /* *********************** Implementation of the abstract methods *********************** */
    /**
     * Process received data
     * @param msg The received message
     * @return True if ok, false if all confirms were processed
     */
    protected boolean addData( String msg ){
        if( confirms.isEmpty())
            return false;

        Logger.info("Comparing '"+confirms.get(0).reply+"' to received '"+msg+"'");
        
        if( msg.equalsIgnoreCase(confirms.get(0).reply)){ // matches
            var con = confirms.remove(0);
            if( !confirms.isEmpty() ){
                logInfo("Received '"+msg+"' as reply for '"+con.msg+"' next up '"+confirms.get(0).msg+"'");
                confirms.get(0).doAttempt();
            }else{
                logInfo("Confirm ended successfully for " + con.msg);
                if( timeoutFuture!=null) {
                    timeoutFuture.cancel(true);
                }else{
                    logInfo("Timeout future is null...?");
                }
                listeners.forEach( l -> l.collectorFinished("confirm:"+id,"confirm",true));
            }
        }
        return true;
    }

    /**
     * Upon timedOut and no confirms left, ignore it.
     * If there are left, execute it.
     */
    protected void timedOut(){
        if( confirms.isEmpty() ) // if all confirms were processed, ignore it
            return;

        if( confirms.get(0).reply.isEmpty() ){ // Meaning no reply requested (so just delayed sending)
            confirms.remove(0); // remove the top one that doesn't need reply
            if( !confirms.isEmpty() ) { // If that didn't empty the list
                logInfo("Next to send '"+confirms.get(0).msg+"'");
                confirms.get(0).doAttempt(); // try sending the top one
            }
        }else if( !confirms.get(0).doAttempt() ){
            logError("Max amount of attempts done, stopping and clearing buffer (\"+confirms.size()+\" items)\"");
            confirms.clear();
            listeners.forEach( rw -> rw.collectorFinished("confirm:"+id,/*"noconfirm"*/"", false) );
        }
    }
    private void logError( String error ){
        if( id.contains("_")) {
            Logger.tag("task").error(id+" -> "+error);
        }else{
            Logger.error(id+" -> "+error);
        }
    }
    private void logInfo( String error ){
        if( id.contains("_")) {
            Logger.tag("task").info(id+" -> "+error);
        }else{
            Logger.info(id+" -> "+error);
        }
    }
    /* *********************** Override of Writable ************************************************/
    @Override
    public boolean isConnectionValid() {
        return !confirms.isEmpty();
    }

    private class Confirm{
        String msg;
        String reply;
        int attempts=0;

        public Confirm(String msg,String reply){
            this.reply=reply;
            this.msg=msg;
        }
        public boolean doAttempt(){
            if( timeoutFuture != null )
                timeoutFuture.cancel(true);

            if( maxAttempts <= attempts ) {
                Logger.info(id+ " -> All attempts done ("+attempts+"), giving up.");
                return false;
            }
            target.writeLine(msg);
            withTimeOut(timeoutSeconds+"s",scheduler);
            
            attempts++;
            return true;
        }

    }
    
}
