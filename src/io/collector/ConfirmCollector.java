package io.collector;

import io.Writable;
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
        maxAttempts=attempts;
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
            confirms.get(0).doAttempt(false);
        }else{
            if( reply.isEmpty()){
                logInfo("Added '" + message + "' without reply to the queue for "+target.getID());
            }else {
                logInfo("Added '" + message + "' with reply '" + reply + "' to the queue for "+target.getID());
            }
        }
    }

    /**
     * Add multiple confirms to this object with the same reply
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
     * Get a list of all the confirm's stored in this object
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
     * @param reply The received message
     * @return True if ok, false if all confirms were processed
     */
    protected boolean addData( String reply ){
        if( confirms.isEmpty() || confirms.get(0).reply.isEmpty() )
            return false;

        Logger.debug("Comparing '"+confirms.get(0).reply+"' to received '"+reply+"'");

        if( reply.equalsIgnoreCase(confirms.get(0).reply)){ // matches
            logInfo("Received '"+reply+"' as reply for '"+confirms.get(0).reply+"' next up '"+confirms.get(0).msg+"'");
            confirms.remove(0);
            if( !confirms.isEmpty() ){
                confirms.get(0).doAttempt(false);
            }else{
                Logger.tag("TASK").info(id+" -> All confirms received");
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
            confirms.remove(0); // remove the top one that was send
            if( !confirms.isEmpty() ) { // If that didn't empty the list
                confirms.get(0).doAttempt(true); // try sending the top one
                if( confirms.size()==1) // send the last one and no reply needed
                    listeners.forEach( rw -> rw.collectorFinished("confirm:"+id,"", true) );
            }
        }else {
            if( !confirms.get(0).doAttempt(true) ){
                logError("Max amount of attempts done, stopping and clearing buffer ("+confirms.size()+" items), failed: "+confirms.get(0).reply);
                confirms.clear();
                listeners.forEach( rw -> rw.collectorFinished("confirm:"+id,"", false) );
            }
        }
    }
    private void logError( String error ){
        if( id.contains("_")) {
            Logger.tag("TASK").error(id+" -> "+error);
        }else{
            Logger.error(id+" -> "+error);
        }
    }
    private void logInfo( String message ){
        if( id.contains("_")) {
            Logger.tag("TASK").info(id+" -> "+message);
        }else{
            Logger.info(id+" -> "+message);
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
        public boolean doAttempt( boolean timeout){
            if( timeoutFuture != null )
                timeoutFuture.cancel(true);

            if( maxAttempts <= attempts ) {
                Logger.tag("TASK").info(id+ " -> All attempts done ("+attempts+" of "+maxAttempts+"), giving up.");
                return false;
            }
            Logger.tag("TASK").info(id+ " -> Sending '"+confirms.get(0).msg+"' to "+target.getID()+" for attempt "+attempts);
            target.writeLine(msg);
            if( confirms.size()>1 || !timeout)
                withTimeOut(timeoutSeconds+"s",scheduler);
            if(!reply.isEmpty())// Can't fail, so no attempts counted
                attempts++;
            return true;
        }
    }
    
}
