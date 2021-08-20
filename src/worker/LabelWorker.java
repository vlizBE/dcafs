package worker;

import das.Commandable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.ArrayUtils;
import org.w3c.dom.Element;
import util.data.DataProviding;
import io.Readable;
import io.Writable;
import das.CommandPool;
import io.mqtt.MqttWriting;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.DeadThreadListener;
import util.database.QueryWriting;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * This class retrieves @see worker.Datagram s from a @see BlockingQueue. 
 * Next the content of @see Datagram is investigated and processed. 
 *
 * @author Michiel TJampens @vliz
 */
public class LabelWorker implements Runnable, Labeller, Commandable {

	static final String UNKNOWN_CMD = "unknown command";

	Map<String, Generic> generics = new HashMap<>();
	Map<String, ValMap> mappers = new HashMap<>();
	Map<String, Readable> readables = new HashMap<>();

	private BlockingQueue<Datagram> dQueue;      // The queue holding raw data for processing
	private DataProviding dp;
	private final QueryWriting queryWriting;
	private MqttWriting mqtt;
	private Path settingsPath;

	private boolean goOn = true; // General process boolean, clean way of stopping thread

	protected CommandPool reqData;

	protected boolean debugMode = false;

	private final AtomicInteger procCount = new AtomicInteger(0);
	private long procTime = Instant.now().toEpochMilli();
	long waitingSince;

	protected DeadThreadListener listener;

	ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
			Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>());

	ScheduledExecutorService debug = Executors.newSingleThreadScheduledExecutor();

	// Debug help
	int readCount=0;
	int oldReadCount=0;
	String lastOrigin="";
	/* ***************************** C O N S T R U C T O R **************************************/

	/**
	 * Default constructor that gets a queue to use
	 *
	 * @param dQueue The queue to use
	 */
	public LabelWorker(Path settingsPath, BlockingQueue<Datagram> dQueue, DataProviding dp, QueryWriting queryWriting) {
		this.settingsPath=settingsPath;
		this.dQueue = dQueue;
		this.dp=dp;
		this.queryWriting=queryWriting;

		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
		debug.scheduleAtFixedRate(new SelfCheck(),5,30,TimeUnit.MINUTES);

		loadGenerics();
	}
	@Override
	public void addDatagram(Datagram d){
		dQueue.add(d);
	}

	public void setMqttWriter( MqttWriting mqtt){
		this.mqtt=mqtt;
	}
	/**
	 * Add a listener to be notified of the event the thread fails.
	 *
	 * @param listener the listener
	 */
	public void setEventListener(DeadThreadListener listener) {
		this.listener = listener;
	}

	/**
	 * Set the BaseReq for this worker to use
	 *
	 * @param commandPool The default BaseReq or extended one
	 */
	public void setCommandReq(CommandPool commandPool) {
		this.reqData = commandPool;
	}

	/**
	 * Set the DataProvider for the worker to use
	 *
	 * @param dp The implementation of the DataProviding interface
	 */
	public void setDataProviding(DataProviding dp) {
		this.dp = dp;
	}

	/**
	 * Set or remove debugmode flag
	 * @param deb New state for the debugmode
	 */
	public void setDebugging(boolean deb) {
		this.debugMode = deb;
	}

	public synchronized int getProcCount(int seconds) {
		double a = procCount.get();
		long passed = (Instant.now().toEpochMilli() - procTime) / 1000; //seconds since last check

		a /= (double) passed;
		a *= seconds;
		procCount.set(0);    // Clear the count
		procTime = Instant.now().toEpochMilli(); // Overwrite for next check
		return (int) Math.rint(a);
	}

	protected long dataAge() {
		return (Instant.now().toEpochMilli() - waitingSince) / 1000;
	}

	/**
	 * Stop the worker thread
	 */
	public void stopWorker() {
		goOn = false;
	}

	/* ****************************************** V A L M A P S *************************************************** */
	public ValMap addValMap(ValMap map) {
		mappers.put(map.getID(), map);
		Logger.info("Added generic " + map.getID());
		return map;
	}

	public void clearValMaps() {
		mappers.clear();
	}

	public ValMap addValMap(String id) {
		ValMap map = new ValMap("");
		mappers.put(id, map);
		return map;
	}

	public String getValMapsInfo() {
		StringJoiner join = new StringJoiner("\r\n", "Valmaps:\r\n", "\r\n");
		for (ValMap map : mappers.values()) {
			join.add(map.toString() + "\r\n");
		}
		return join.toString();
	}

	/* *************************** GENERICS **********************************************/
	public Generic addGeneric(Generic gen) {
		generics.put(gen.getID(), gen);
		Logger.info("Added generic " + gen.getID());
		return gen;
	}

	public void clearGenerics() {
		generics.clear();
	}

	public String getGenericInfo() {
		StringJoiner join = new StringJoiner("\r\n", "Generics:\r\n", "\r\n");
		for (Generic gen : generics.values()) {
			join.add(gen.toString() + "\r\n");
		}
		return join.toString();
	}

	/* ******************************** Q U E U E S **********************************************/
	public int getWaitingQueueSize(){
		return executor.getQueue().size();
	}
	/**
	 * Get the queue for adding work for this worker
	 *
	 * @return The qeueu
	 */
	public BlockingQueue<Datagram> getQueue() {
		return dQueue;
	}

	/**
	 * Set the queue for adding work for this worker
	 *
	 * @param d The queue
	 */
	public void setQueue(BlockingQueue<Datagram> d) {
		this.dQueue = d;
	}

	/* *******************************************************************************************/
	@Override
	public void run() {

		if (this.reqData == null) {
			Logger.error("Not starting without proper BaseReq");
			return;
		}
		while (goOn) {
			try {
				Datagram d = dQueue.take();
				readCount++;

				lastOrigin=d.getOriginID();
				String label = d.getLabel();

				if (label == null) {
					Logger.error("Invalid label received along with message :" + d.getData());
					continue;
				}

				if( d.label.contains(":") ){
					String readID = label.substring(label.indexOf(":")+1);
					switch(d.label.split(":")[0]){
						case "generic": executor.execute(new ProcessGeneric(d)); break;
						case "double":  executor.execute(() -> storeInDoubleVal(readID,d.getData(),d.getOriginID())); break;
						case "valmap":  executor.execute(new ProcessValmap(d)); break;
						case "text":    executor.execute(() -> dp.setText(readID, d.data)); break;
						case "read":    executor.execute( ()-> checkRead(d.getOriginID(),d.getWritable(),readID) );break;
						case "telnet":  executor.execute(()-> checkTelnet(d)); break;
						case "log":
							switch(d.label.split(":")[1]){
								case "info":Logger.info(d.getData()); break;
								case "warn":Logger.warn(d.getData()); break;
								case "error":Logger.error(d.getData()); break;
							}
							break;
						default:
							Logger.error("Unknown label: "+label);
							break;
					}
				}else {
					switch (label) {
						case "system":
							executor.execute(() -> reqData.createResponse(d.getData(), d.getWritable(), false));
							break;
						case "void":
							break;
						case "readable":
							if(  d.getReadable()!=null)
								readables.put(d.getReadable().getID(),d.getReadable());
							readables.entrySet().removeIf( entry -> entry.getValue().isInvalid()); // cleanup
							break;
						case "test":
							executor.execute(() -> Logger.info(d.getOriginID() + "|" + label + " -> " + d.getData()));
							break;
						case "email":
							executor.execute(() -> reqData.emailResponse(d));
							break;
						case "telnet":
							executor.execute(() -> checkTelnet(d) );
						default:
							Logger.error("Unknown label: "+label);
							break;
					}
				}
				int proc = procCount.get();
				if (proc >= 500000) {
					procCount.set(0);
					long millis = Instant.now().toEpochMilli() - procTime;
					procTime = Instant.now().toEpochMilli();
					Logger.info("Processed " + (proc / 1000) + "k lines in " + TimeTools.convertPeriodtoString(millis, TimeUnit.MILLISECONDS));
				}
			} catch( RejectedExecutionException e){
				Logger.error(e.getMessage());
			} catch (Exception e) {
				Logger.error(e);
			}
		}
		listener.notifyCancelled("BaseWorker");
	}

	/* ******************************* D E F A U L T   S T U F F **************************************** */
	private void storeInDoubleVal(String param, String data, String origin ){
		try{
			var val = NumberUtils.toDouble(data,Double.NaN);
			if( Double.isNaN(val) && NumberUtils.isCreatable(data)){
				val = NumberUtils.createInteger(data);
			}
			if( !Double.isNaN(val) ){
				dp.setDouble(param,val);
			}else{
				Logger.warn("Tried to convert "+data+" from "+origin+" to a double...");
			}
		}catch( NumberFormatException e ){
			Logger.warn("Tried to convert "+data+" from "+origin+" to a double...");
		}
	}
	private void checkRead( String from, Writable wr, String id){
		if(wr!=null){
			var ids = id.split(",");
			var read = readables.get(ids[0]);
			if( read != null && !read.isInvalid()){

				read.addTarget(wr,ids.length==2?ids[1]:"*");
				Logger.info("Added "+wr.getID()+ " to target list of "+read.getID());
			}else{
				Logger.error(wr.getID()+" asked for data from "+id+" but doesn't exists (anymore)");
			}
			readables.entrySet().removeIf( entry -> entry.getValue().isInvalid());
		}else{
			Logger.error("No valid writable in the datagram from "+from);
		}
	}
	public void checkTelnet(Datagram d) {
		Writable dt = d.getWritable();
		if (!d.getData().equals("status")) {
			String from = " for ";

			if (dt != null) {
				from += dt.getID();
			}
			if (!d.getData().isBlank())
				Logger.info("Executing telnet command [" + d.getData() + "]" + from);
		}
		String response = reqData.createResponse(d.getData(), dt, false);
		String[] split = d.getLabel().split(":");
		if (dt != null) {
			if (!d.isSilent()) {
				dt.writeLine(response);
				dt.writeString((split.length >= 2 ? "<" + split[1] : "") + ">");
			}
		} else {
			Logger.info(response);
			Logger.info((split.length >= 2 ? "<" + split[1] : "") + ">");
		}
		procCount.incrementAndGet();
	}

	/* ************************************** RUNNABLES ******************************************************/
	public class SelfCheck implements Runnable {
		public void run() {
			Logger.info("Read count now "+readCount+", old one "+oldReadCount+ " last message processed from "+lastOrigin+ " buffersize "+dQueue.size());
			Logger.info("Executioner: "+ executor.getCompletedTaskCount()+" completed, "+ executor.getTaskCount()+" submitted, "
					+ executor.getActiveCount()+"/"+ executor.getCorePoolSize()+"("+executor.getMaximumPoolSize()+")"+" active threads, "+ executor.getQueue().size()+" waiting to run");

			oldReadCount = readCount;
			readCount=0;
			lastOrigin="";
		}
	}
	public class ProcessValmap implements Runnable {
		Datagram d;

		public ProcessValmap(Datagram d) {
			this.d = d;
		}

		public void run() {
			try {
				String valMapIDs = d.label.split(":")[1];
				String mes = d.getData();

				if (mes.isBlank()) {
					Logger.warn(valMapIDs + " -> Ignoring blank line");
					return;
				}
				for (String valmapID : valMapIDs.split(",")) {
					var map = mappers.get(valmapID);
					if (map != null) {
						map.apply(mes, dp);
					}else{
						Logger.error("ValMap requested but unknown id: " + valmapID + " -> Message: " + d.getData());
					}
				}
			} catch (ArrayIndexOutOfBoundsException l) {
				Logger.error("Generic requested (" + d.label + ") but no valid id given.");
			} catch( Exception e){
				Logger.error(e);
			}
			procCount.incrementAndGet();
		}
	}
	private List<Generic> getGenerics( String id ){
		return generics.entrySet().stream().filter( set -> set.getKey().equalsIgnoreCase(id) ||set.getKey().matches(id))
				.map(Entry::getValue).collect(Collectors.toList());
	}
	public class ProcessGeneric implements Runnable{
		Datagram d;

		public ProcessGeneric(Datagram d){
			this.d=d;
		}

		public void run(){
			try{

				String mes = d.getData();
				if( mes.isBlank() ){
					Logger.warn( d.getOriginID() + " -> Ignoring blank line" );
					return;
				}

				var genericIDs = d.label.split(":")[1].split(",");
				for( String genericID : genericIDs ){
					var generics = getGenerics(genericID);
					if( generics==null || generics.isEmpty() ) {
						Logger.error("No such generic " + genericID + " for " + d.getOriginID());
						continue;
					}
					Double[] doubles = (Double[]) d.getPayload();

					generics.stream().forEach(
							gen -> {
								if ( mes.startsWith(gen.getStartsWith()) ) {
									Object[] data = gen.apply( mes, doubles, dp, queryWriting,mqtt );
									if (!gen.getTable().isEmpty() && gen.writesInDB()) {
										if (gen.isTableMatch()) {
											for( String id : gen.getDBID() )
												queryWriting.doDirectInsert( id, gen.getTable(), data);
										} else {
											for( String id : gen.getDBID() ){
												if (!queryWriting.buildInsert(id, gen.getTable(), dp, gen.macro)) {
													Logger.error("Failed to write record for " + gen.getTable());
												}
											}
										}
									}
								}
							}
					);
				}
			}catch( ArrayIndexOutOfBoundsException e ){
				Logger.error("Generic requested ("+d.label+") but no valid id given.");
			} catch( Exception e){
				Logger.error("Caught an exception when processing "+d.getData()+" from "+d.getOriginID());
				Logger.error(e);
			}
			procCount.incrementAndGet();
		}
	}

	/* *************************************** C O M M A N D A B L E ********************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {
		switch(request[0]) {
			case "gens": return doGENericS(request,wr,html);

		}
		return "unknown command: "+request[0]+":"+request[1];
	}
	public String doGENericS( String[] request, Writable wr, boolean html ){

		StringJoiner join = new StringJoiner(html?"<br":"\r\n");
		String[] cmds = request[1].split(",");

		switch(cmds[0]){
			case "?":
				join.add("")
						.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
						.add("  Generics (gens) are used to take delimited data and store it as rtvals or in a database.");
				join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
						.add("  - ...");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Create a Generic"+TelnetCodes.TEXT_YELLOW)
						.add("  gens:fromtable,dbid,dbtable,gen id[,delimiter] -> Create a generic according to a table, delim is optional, def is ','")
						.add("  gens:fromdb,dbid,delimiter -> Create a generic with chosen delimiter for each table if there's no such generic yet")
						.add("  gens:addblank,id,format,delimiter -> Create a blank generic with the given id and format")
						.add("      The format consists of a letter, followed by a number and then a word and this is repeated with , as delimiter")
						.add("      The letter is the type of value, the number the index in the array of received data and the word is the name/id of the value")
						.add("      So for example: i2temp,r5offset  -> integer on index 2 with name temp, real on 5 with name offset")
						.add("      Options for the letter:")
						.add("       r = a real number" )
						.add("       i = an integer number")
						.add("       t = a piece of text")
						.add("       m = macro, this value can be used as part as the rtval")
						.add("       s = skip, this won't show up in the xml but will increase the index counter")
						.add("       eg. 1234,temp,19.2,hum,55 ( with 1234 = serial number")
						.add("           -> serial number,title,temperature reading,title,humidity reading")
						.add("           -> msrsi -> macro,skip,real,skip,integer");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW);
				join.add("  gens:? -> Show this info")
						.add("  gens:reload -> Reloads all generics")
						.add("  gens:alter,id,param:value -> Change a parameter of the specified generic")
						.add("  gens:list -> Lists all generics");

				return join.toString();
			case "reload":
				loadGenerics();
				return getGenericInfo();
			case "fromtable":
				if(cmds.length < 4 )
					return "To few parameters, gens:fromtable,dbid,table,gen id,delimiter";
				var db = queryWriting.getDatabase(cmds[1]);
				if( db ==null)
					return "No such database found "+cmds[1];
				if( db.buildGenericFromTable(XMLfab.withRoot(settingsPath, "dcafs","generics"),cmds[2],cmds[3],cmds.length>4?cmds[4]:",") ){
					return "Generic written";
				}else{
					return "Failed to write to xml";
				}
			case "fromdb":
				if(cmds.length < 3 )
					return "To few parameters, gens:fromdb,dbid,delimiter";
				var dbs = queryWriting.getDatabase(cmds[1]);
				if( dbs ==null)
					return "No such database found "+cmds[1];

				if( dbs.buildGenericFromTables(XMLfab.withRoot(settingsPath, "dcafs","generics"),false,cmds.length>2?cmds[2]:",") >0 ){
					return "Generic(s) written";
				}else{
					return "No generics written";
				}
			case "addblank":
				if( cmds.length < 3 )
					return "Not enough arguments, must be generics:addblank,id,format,delimiter";

				var delimiter=request[1].endsWith(",")?",":cmds[cmds.length-1];
				var format = String.join(",", ArrayUtils.subarray(cmds,2,cmds.length>3?cmds.length-1:cmds.length));
				return Generic.addBlankToXML(XMLfab.withRoot(settingsPath, "dcafs","generics"), cmds[1], format,delimiter);
			case "alter":
				if( cmds.length < 3 )
					return "Not enough arguments, must be generics:alter,id,param:value";
				var gen = generics.get(cmds[1]);
				if( gen == null )
					return "No such generic "+cmds[1];
				int in = cmds[2].indexOf(":");
				if( in==-1)
					return "Missing valid param:value pair";
				var fab = XMLfab.withRoot(settingsPath,"generics")
						.selectChildAsParent("generic","id",cmds[1]);
				if( fab.isPresent() ){
					String attr = cmds[2].substring(0,in);
					var val = cmds[2].substring(in+1);
					switch( attr ){
						case "names":
							cmds[2]=val;
							int i=0;
							for( var f : fab.get().getChildren("*")){
								if( cmds.length-2>i){
									if( !cmds[i+2].equalsIgnoreCase("."))
										f.setTextContent(cmds[i+2]);
								}
								i++;
							}
							if( fab.get().build()!=null) {
								loadGenerics();
								return "Names set, generics reloaded";
							}
						case "dbid":
						case "delimiter":
						case "table":
						case "id":
							fab.get().attr(attr,val).build();
							break;
						default: return "No such attr "+attr;
					}
					loadGenerics();
					return "Added/changed attribute and reloaded";
				}
				return "No such node found";

			case "list":
				return getGenericInfo();
			default:
				return UNKNOWN_CMD+": "+cmds[0];
		}
	}

	/**
	 * Load the generics
	 */
	public void loadGenerics() {

		generics.clear();

		XMLfab.getRootChildren(settingsPath, "dcafs","generics","generic")
				.forEach( ele ->  addGeneric( Generic.readFromXML(ele) ) );
		// Find the path ones?
		XMLfab.getRootChildren(settingsPath, "dcafs","datapaths","path")
				.forEach( ele -> {
							String imp = ele.getAttribute("import");

							int a=1;
							if( !imp.isEmpty() ){ //meaning imported
								String file = Path.of(imp).getFileName().toString();
								file = file.substring(0,file.length()-4);//remove the .xml

								for( Element gen : XMLfab.getRootChildren(Path.of(imp), "dcafs","path","generic").collect(Collectors.toList())){
									if( !gen.hasAttribute("id")){ //if it hasn't got an id, give it one
										gen.setAttribute("id",file+"_gen"+a);
										a++;
									}
									String delim = ((Element)gen.getParentNode()).getAttribute("delimiter");
									if( !gen.hasAttribute("delimiter") ) //if it hasn't got an id, give it one
										gen.setAttribute("delimiter",delim);
									addGeneric( Generic.readFromXML(gen) );
								}
							}
							String delimiter = XMLtools.getStringAttribute(ele,"delimiter","");
							for( Element gen : XMLtools.getChildElements(ele,"generic")){
								if( !gen.hasAttribute("id")){ //if it hasn't got an id, give it one
									gen.setAttribute("id",ele.getAttribute("id")+"_gen"+a);
									a++;
								}
								if( !gen.hasAttribute("delimiter") && !delimiter.isEmpty()) //if it hasn't got an id, give it one
									gen.setAttribute("delimiter",delimiter);
								addGeneric( Generic.readFromXML(gen) );
							}
						}
				);
	}
	@Override
	public boolean removeWritable(Writable wr) {
		return false;
	}
}
