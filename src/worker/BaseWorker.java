package worker;

import com.stream.Writable;
import das.BaseReq;
import das.RealtimeValues;
import org.tinylog.Logger;
import util.DeadThreadListener;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * This class retrieves @see worker.Datagram s from a @see BlockingQueue. 
 * Next the content of @see Datagram is investigated and processed. 
 *
 * @author Michiel TJampens @vliz
 */
public class BaseWorker implements Runnable {
	   
	Map<String, Method> nmeaMetMap = null;
	Map<String, Method> regMetMap = null;
	
	Map<String,Long[]> lastUsage = new HashMap<>();
	Map<String,Generic> generics = new HashMap<>();
	Map<String,ValMap> mappers = new HashMap<>();
	Map<String,Writable> writables = new HashMap<>();

	protected BlockingQueue<Datagram> dQueue  = new LinkedBlockingQueue<>();	  // The queue holding raw data for processing
	private boolean goOn = true; // General process boolean, clean way of stopping thread
	
	protected BaseReq reqData;
		
	protected boolean debugMode = false;
		
	private final AtomicInteger procCount = new AtomicInteger(0);
	private long procTime = Instant.now().toEpochMilli();
	protected boolean printData = false;
	long waitingSince;
	protected RealtimeValues rtvals;

	protected DeadThreadListener listener;

	public enum FAILreason { // Used by the nmea processing to return the result
		NONE, PRIORITY, LENGTH, SYNTAX, INVALID, TODO, IGNORED, DISABLED, EMPTY
	}

	/* ***************************** C O N S T R U C T O R **************************************/

	/**
	 * Default constructor that gets a queue to use
	 * @param dQueue The queue to use
	 */
	public BaseWorker( BlockingQueue<Datagram> dQueue ) {
		this();
		this.dQueue = dQueue;
	}
	/**
	 * Constructor to use its own queue
	 */
	public BaseWorker( ) {
		getMethodMapping( this.getClass() );
	}
	/**
	 * Add a listener to be notified of the event the thread fails.
	 * 
	 * @param listener the listener
	 */
	public void setEventListener( DeadThreadListener listener ){
		this.listener=listener;
	}
	/**
	 * Set the BaseReq for this worker to use
	 * @param baseReq The default BaseReq or extended one
	 */
	public void setReqData( BaseReq baseReq ){
		this.reqData=baseReq;
	}
	/**
	 * Set the realtimevalues for the worker to use
	 * @param rtvals The default RealtimeValues or extended one
	 */
	public void setRealtimeValues( RealtimeValues rtvals){
		this.rtvals=rtvals;
	}


	/* *************************** VALMAPS **********************************************/
	public ValMap addValMap( ValMap map ){
		mappers.put(map.getID(), map);
		Logger.info("Added generic "+map.getID());
		return map;
	}

	public void clearValMaps(){
		mappers.clear();
	}
	public ValMap addValMap( String id ){
		ValMap map = new ValMap("");
		mappers.put(id, map);
		return map;
	}
	public String getValMapsInfo(){
		StringJoiner join = new StringJoiner("\r\n","Valmaps:\r\n","\r\n");
		for( ValMap map : mappers.values() ){
			join.add(map.toString()+"\r\n");
		}
		return join.toString();
	}

	/* *************************** GENERICS **********************************************/
	public Generic addGeneric( Generic gen ){
		generics.put(gen.getID(), gen);
		Logger.info("Added generic "+gen.getID());
		return gen;
	}
	public void clearGenerics(){
		mappers.clear();
	}
	public Generic addGeneric( String id ){
		Generic gen = new Generic("");
		generics.put(id, gen);
		return gen;
	}
	public String getGenericInfo(){
		StringJoiner join = new StringJoiner("\r\n","Generics:\r\n","\r\n");
		for( Generic gen : generics.values() ){
			join.add(gen.toString()+"\r\n");
		}
		return join.toString();
	}

	/* ******************************** Q U E U E S **********************************************/
	/**
	 * Get the queue for adding work for this worker
	 * @return The qeueu
	 */
	public BlockingQueue<Datagram> getQueue(){
		return dQueue;
	}
	/**
	 * Set the queue for adding work for this worker
	 * @param d The queue
	 */
	public void setQueue(BlockingQueue<Datagram> d){
		this.dQueue=d;
	}
	/**
	 * Clear this workers queue
	 */
	public void clearQueue() {
		dQueue.clear();
	}
	/* ******************************** I N V O K E **********************************************/
	/**
	 * Get all the relevant methods available in this (extended) class
	 * @param baseworker THe class to look in
	 */
	public void getMethodMapping(Class<?> baseworker) {

	    if( nmeaMetMap == null) {
	    	nmeaMetMap = new HashMap<>();
	    	regMetMap = new HashMap<>();
			
			ArrayList<Method> methods = new ArrayList<>();

			methods.addAll(Arrays.asList(baseworker.getDeclaredMethods()));

			if( baseworker.getSuperclass() == BaseWorker.class){
				methods.addAll(Arrays.asList(baseworker.getSuperclass().getDeclaredMethods()));
			}
	        for(Method method : methods) {
	        	String com = method.getName();
	        	if(com.contains("NMEA")){
	        		com = com.substring(com.indexOf("NMEA")+4);
	        		if( com.equals("time")){
	        			nmeaMetMap.put("me_", method);
	        		}else{	      								
	        			nmeaMetMap.put(com, method);	        			
					}				
	        	}else if(com.length()>=4 && com.startsWith("do")){
	        		com = com.substring(2); //Remove the 'do'	
					regMetMap.put(com.toLowerCase(), method);
				}
			}   
			Logger.info( "Found "+nmeaMetMap.size() +" doNMEA and "+regMetMap.size()+" other ones."); 
	    }
	}
	/* *******************************************************************************************/
	/**
	 * The main processing method for this class
	 */
	@Override
	public void run() {
		Logger.info("BaseWorker Started");
		goOn = true;
		long time=0;
		waitingSince=0;
		Datagram d = null;
		try {
			while (goOn) { // Continuously repeat the following code, unless
						   // anything makes goOn false. This provides a clean way of interrupting the thread
				waitingSince = Instant.now().toEpochMilli();
			
				d = dQueue.take(); // Request a Datagram from the queue, blocks until one is received	
				time -= Instant.now().toEpochMilli()-waitingSince;
				if( printData )
					Logger.debug("REC: "+d.label+" -> "+d.getMessage());
				if( debugMode ){
					if( procCount.get()==1){
						time = Instant.now().toEpochMilli();
					}else if( procCount.get() == 100000){
						Logger.info("Processed 100k in "+(Instant.now().toEpochMilli()-time)+"ms");
						time = System.currentTimeMillis();
						procCount.set(1);
					}
				}
				if( d.label==null ){
					Logger.error( "Bad datagram received, invalid label! "+d.getMessage()+" title:"+d.getOriginID());
					continue;
				}
				if( d.label.equals("nmea")){
					boolean ok = MathUtils.doNMEAChecksum(d.getMessage());
					if (ok) {// Either checksum was ok or test was skipped
						String[] split = d.getMessage().substring(0,d.getMessage().length()-3).split(",");// remove the checksum and split the nmea string in its components
						FAILreason fail = FAILreason.IGNORED;
						
					    	try {
					    		String nmea = split[0].substring(3).toLowerCase();
					    		if( nmeaMetMap.get(nmea) != null ){
									/* Keep track of method usage *********************************************/
									Long[] times = lastUsage.get(nmea);
									if( times == null){
										times = new Long[2];
										times[1]=Instant.now().toEpochMilli();
									}
									times[0]=times[1];
									times[1]=Instant.now().toEpochMilli();
									
									lastUsage.put(nmea,times);
									/* *************************************************************************/
					    			fail = (FAILreason) nmeaMetMap.get(nmea).invoke(this,split,d.priority);
					    		}else{
					    			Logger.error( "Can't process the message: "+d.getMessage());					    			
					    		}
							} catch (IllegalAccessException | IllegalArgumentException e) {
								Logger.error( "Something wrong in the processing method for: " + d.getMessage()+" -> "+e.getMessage() );
							} catch( java.lang.NullPointerException np){
								Logger.error( "Nullpointer when processing: " + d.getMessage() );
							}catch (InvocationTargetException e) {
								Throwable originalException = e.getTargetException();
								Logger.error( "'"+originalException+"' at "+originalException.getStackTrace()[0].toString()+" when processing: "+d.getMessage());
							}
																
						switch (fail) {
							case NONE: break; // Most common result
							case SYNTAX:// The process failed because of a syntax error, eg. the string doesn't contain the right amount of parts															
								Logger.warn( "Bad NMEA String Syntax: "+d.getMessage() );
								break;
							case LENGTH:// The string was longer or shorter then expected
								Logger.warn( "Bad NMEA String Length: "+d.getMessage() + " (length:"+split.length+") from "+d.getOriginID());
								break;
							case PRIORITY:
								/* The datagram didn't have the right priority to use it (highest priority = 1, 
								   if no messages of 1 are received  it becomes 2 etc)
								   Logger.debug("SKIPPED (priority): " +  d.getMessage())*/
								break;
							case INVALID:
								Logger.warn( "Invalid NMEA String: "+d.getMessage(), true);
								break;
							case TODO:// A NMEA string was received that hasn't got
										// any processing code yet.
										Logger.warn( "TODO: "+ d.priority+"\t"+ d.getMessage()+"\t From:"+d.getOriginID());
								break;
							case IGNORED: // For some reason the string was ignored
							case DISABLED:
							case EMPTY: // Nothing to report
							break;
						}
					} else {
						if( d.getMessage().length() > 250 )
							d.setMessage(d.getMessage().substring(0,250));
						Logger.warn( "NMEA message from "+d.getOriginID() +" failed checksum "+d.getMessage().replace("\r","<cr>").replace("\n","<lf>") );
					}
				}else{
					String what = d.label.toLowerCase().split(":")[0];
					/* Keep track of method usage *********************************************/
					Long[] times = lastUsage.get(what);
					if( times == null){
						times = new Long[2];
						times[1]=Instant.now().toEpochMilli();
					}
					times[0]=times[1];
					times[1]=Instant.now().toEpochMilli();
					lastUsage.put(what,times);
					/* *************************************************************************/
					Method m = regMetMap.get( what );
					if( m != null ){
						try {
							m.invoke( this,d );
							if( debugMode )
								Logger.debug("Processing:"+d.getMessage()+" for "+d.label+" from "+d.getOriginID());
						} catch (IllegalAccessException | IllegalArgumentException e) {
							Logger.warn( "Invoke Failed:'" + d.label + "'>" + d.getMessage() + "<");
							Logger.error(e);
						}catch (InvocationTargetException e) {
							Throwable originalException = e.getTargetException();
							Logger.error( "'"+originalException+"' at "+originalException.getStackTrace()[0].toString()+" when processing: "+d.getMessage());
						}			
					}else{
						Logger.warn( "Not defined:" +d.getOriginID() +"|"+ d.label + " >" + d.getMessage() + "< raw: "+Tools.fromBytesToHexString(d.raw));
					}
				}
				// Debug information
				procCount.incrementAndGet();
			}
			Logger.error( "Worker stopped!"); // notify that for some reason the worker stopped working
		} catch (InterruptedException ex) {		
			Logger.error( "Main worker thread interrupted while processing: "+d.getMessage()+ " Label: "+d.label);
			Logger.error( ex );
			// Restore interrupted state...
			Thread.currentThread().interrupt();
		} catch( java.lang.ArrayIndexOutOfBoundsException f){
			Logger.error( "Main worker thread interrupted because out ArrayIndexOut"+f.toString() +" while processing: "+d.getMessage()+ " Label: "+d.label);			
		} finally{
			if( listener != null)
				listener.notifyCancelled("BaseWorker");
		}
	} 
	 public String createResponse(String question, Writable trans){
    	if( reqData==null)
    		return "No response, DataReq still null";
    	return reqData.createResponse( question, trans, false );
    }
	public void setDebugging( boolean deb ){
		this.debugMode=deb;
	}
	public synchronized int getProcCount(int seconds) {
		double a = procCount.get();
		long passed = (Instant.now().toEpochMilli()-procTime)/1000; //seconds since last check
		
		a /= (double) passed; 
		a*=seconds;
		procCount.set(0);	// Clear the count
		procTime = Instant.now().toEpochMilli(); // Overwrite for next check
		return (int)Math.rint(a);
	}
	protected long dataAge(){
		return (Instant.now().toEpochMilli() - waitingSince)/1000;
	}
	public String getMethodCallAge(String newline){
		StringJoiner b = new StringJoiner(newline);
		ArrayList<String> items = new ArrayList<>();
		for( Entry<String,Long[]> la : lastUsage.entrySet() ){

			Long[] times = la.getValue();
			long time = System.currentTimeMillis() - times[1];
			double freq = (double)(times[1]-times[0]);
			freq = Tools.roundDouble( 1000/freq, freq > 900?0:1);
			items.add( la.getKey() +" -> Age/Interval:"+ TimeTools.convertPeriodtoString(time, TimeUnit.MILLISECONDS) + "/"+freq+"Hz");
		}
		Collections.sort(items);
		items.forEach(b::add);		
		return b.toString();
	}
	/**
	 * Stop the worker thread
	 */
	public void stopWorker() {
		goOn = false;
	}

	/* ******************************* D E F A U L T   S T U F F *****************************************/
	public void doSYSTEM( Datagram d ){
		if( reqData==null){
			Logger.error("Tried to issue a system command ("+d.getMessage()+") without defined BaseReq");
			return;
		}
		createResponse( d.getMessage(), d.getWritable() );
	}
	public void doTELNET( Datagram d ){
		Writable dt = d.getWritable();
		if( !d.getMessage().equals("status")){
			String from = " for ";
			
			if( dt!=null){
				from += dt.getID();
			}
			if( !d.getMessage().isBlank() )
				Logger.info( "Executing telnet command ["+d.getMessage()+"]"+from);
		}
		String response = createResponse( d.getMessage(), dt );
		String[] split = d.label.split(":");
		if( dt != null){
			if( !d.silent ){
				dt.writeLine( response );
				dt.writeString( (split.length>=2?"<"+split[1]:"")+">" );
			}
		}else{
			Logger.info(response); 
			Logger.info( (split.length>=2?"<"+split[1]:"")+">" );
		}
	}
	public void doEMAIL( Datagram d ) {
		if( reqData!=null) {
			Logger.info( "Executing email command ["+d.getMessage()+"], origin: " + d.getOriginID() );
    		reqData.emailResponse( d );
		}else {
			Logger.error( "ReqData null?");
		}
	}
	public void doTEST( Datagram d ){
		Logger.info("TEST received:"+d.getMessage()+" from "+d.getOriginID());
	}

	public void doVOID( Datagram d ){
		// This is a label so that nothing is done with the data
	}
	public void doVALMAP( Datagram d ){
		try{
			String valmapIDs = d.label.split(":")[1];
			String mes = d.getMessage();

			if( mes.isBlank() ){
				Logger.warn( valmapIDs + " -> Ignoring blank line" );
				return;
			}
			for( String valmapID : valmapIDs.split(",") ){
				var map = mappers.get(valmapID);
				if( map != null ){
					map.apply( mes, rtvals);
				}else{
					if( map==null){
						Logger.error("Valmap requested but unknown id: "+valmapID+ " -> Message: "+d.getMessage());
					}
				}
			}
		}catch( ArrayIndexOutOfBoundsException l ){
			Logger.error("Generic requested ("+d.label+") but no valid id given.");
		}
	}
	public synchronized void doGENERIC( Datagram d ){

		try{
			String mes = d.getMessage();
			if( mes.isBlank() ){
				Logger.warn( d.getOriginID() + " -> Ignoring blank line" );
				return;
			}
			var genericIDs = d.label.split(":")[1].split(",");
			for( String genericID : genericIDs ){
				getGenerics(genericID).stream().forEach(
						gen -> {
							if ( mes.startsWith(gen.getStartsWith()) ) {
								Object[] data = gen.apply(mes, rtvals);
								if (!gen.getTable().isEmpty() && gen.writesInDB()) {
									if (gen.isTableMatch()) {
										rtvals.provideRecord( gen.getDBID(), gen.getTable(), data);
									} else {
										rtvals.writeRecord( gen.getDBID(), gen.getTable(), gen.macro);
									}
								}
							} else {
								if (gen == null) {
									Logger.error("Generic requested but unknown id: " + genericID + " -> Message: " + d.getMessage());
								}
							}
						}
				);
			}
			
		}catch( ArrayIndexOutOfBoundsException l ){
			Logger.error("Generic requested ("+d.label+") but no valid id given.");
		}
	}
	private List<Generic> getGenerics( String id ){
		return generics.entrySet().stream().filter( set -> set.getKey().equalsIgnoreCase(id) || set.getKey().matches(id)).map( x -> x.getValue()).collect(Collectors.toList());
	}

	public void doFILTER( Datagram d ){
		String[] filter = d.label.split(":");

		for( var f : filter[1].split(",")) {
			var wr = writables.get(filter[0]+":"+f);
			if (wr != null) {
				wr.writeLine(d.getMessage());
			} else {
				var filterOpt = reqData.getFilter(f);
				if (filterOpt.isPresent()) {
					writables.put(d.label, filterOpt.get().getWritable());
					filterOpt.get().getWritable().writeLine(d.getMessage());
				}
			}
		}
	}
}
