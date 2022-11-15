package worker;

import das.Commandable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.ArrayUtils;
import org.w3c.dom.Element;
import io.Readable;
import io.Writable;
import das.CommandPool;
import io.mqtt.MqttWriting;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.RealVal;
import util.data.RealtimeValues;
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
public class LabelWorker implements Runnable, Commandable {

	static final String UNKNOWN_CMD = "unknown command";

	private final Map<String, Generic> generics = new HashMap<>();
	private final Map<String, ValMap> mappers = new HashMap<>();
	private final Map<String, Readable> readables = new HashMap<>();

	private final ArrayList<DatagramProcessing> dgProc = new ArrayList<>();

	private BlockingQueue<Datagram> dQueue;      // The queue holding raw data for processing
	private final RealtimeValues rtvals;
	private final QueryWriting queryWriting;
	private MqttWriting mqtt;
	private final Path settingsPath;
	private boolean goOn=true;
	protected CommandPool reqData;

	protected boolean debugMode = false;

	private final AtomicInteger procCount = new AtomicInteger(0);
	private long procTime = Instant.now().toEpochMilli();

	ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
			Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>());

	ScheduledExecutorService debug = Executors.newSingleThreadScheduledExecutor();

	// Debug help
	int readCount=0;
	int oldReadCount=0;
	String lastOrigin="";

	Writable spy;
	String spyingOn ="";
	/* ***************************** C O N S T R U C T O R **************************************/

	/**
	 * Default constructor that gets a queue to use
	 *
	 * @param dQueue The queue to use
	 */
	public LabelWorker(Path settingsPath, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals, QueryWriting queryWriting) {
		this.settingsPath=settingsPath;
		this.dQueue = dQueue;
		this.rtvals=rtvals;
		this.queryWriting=queryWriting;

		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
		debug.scheduleAtFixedRate(this::selfCheck,5,30,TimeUnit.MINUTES);

		loadGenerics();
		loadValMaps(true);
	}
	public void setMqttWriter( MqttWriting mqtt){
		this.mqtt=mqtt;
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
	 * Set or remove debugmode flag
	 * @param deb New state for the debugmode
	 */
	public void setDebugging(boolean deb) {
		this.debugMode = deb;
	}
	public void addDatagramProcessing( DatagramProcessing dgp){
		dgProc.add(dgp);
	}
	/* ****************************************** V A L M A P S *************************************************** */
	private void addValMap(ValMap map) {
		mappers.put(map.getID(), map);
		Logger.info("Added generic " + map.getID());
	}
	public void loadValMaps(boolean clear){
		var settingsDoc = XMLtools.readXML(settingsPath);
		if( clear ){
			mappers.clear();
		}
		XMLfab.getRootChildren(settingsDoc, "dcafs","valmaps","valmap")
				.forEach( ele ->  addValMap( ValMap.readFromXML(ele) ) );

		// Find the path ones?
		XMLfab.getRootChildren(settingsPath, "dcafs","paths","path")
				.forEach( ele -> {
							String imp = ele.getAttribute("import");

							int a=1;
							if( !imp.isEmpty() ){ //meaning imported
								var importPath = Path.of(imp);
								if( !importPath.isAbsolute())
									importPath = settingsPath.getParent().resolve(importPath);
								String file = importPath.getFileName().toString();
								file = file.substring(0,file.length()-4);//remove the .xml

								for( Element vm : XMLfab.getRootChildren(importPath, "dcafs", "paths", "path", "valmap").toList()){
									if( !vm.hasAttribute("id")){ //if it hasn't got an id, give it one
										vm.setAttribute("id",file+"_vm"+a);
										a++;
									}
									if( !vm.hasAttribute("delimiter") ) //if it hasn't got an id, give it one
										vm.setAttribute("delimiter",vm.getAttribute("delimiter"));
									addValMap( ValMap.readFromXML(vm) );
								}
							}
							String delimiter = XMLtools.getStringAttribute(ele,"delimiter","");
							for( Element vm : XMLtools.getChildElements(ele,"valmap")){
								if( !vm.hasAttribute("id")){ //if it hasn't got an id, give it one
									vm.setAttribute("id",ele.getAttribute("id")+"_vm"+a);
									a++;
								}
								if( !vm.hasAttribute("delimiter") && !delimiter.isEmpty()) //if it hasn't got an id, give it one
									vm.setAttribute("delimiter",delimiter);
								addValMap( ValMap.readFromXML(vm) );
							}
						}
				);
	}
	/* *************************** GENERICS **********************************************/
	public void addGeneric(Generic gen) {
		if( gen == null)
			return;
		if( generics.containsKey(gen.getID())){
			Logger.error("Tried to add generic with same id twice: "+gen.getID());
		}else {
			generics.put(gen.getID(), gen);
			Logger.info("Added generic " + gen.getID());
		}
	}
	public String getGenericInfo() {
		StringJoiner join = new StringJoiner("\r\n", "Generics:\r\n", "");
		join.setEmptyValue("None yet");
		for (Generic gen : generics.values()) {
			join.add(gen.toString());
		}
		return join.toString();
	}
	private List<Generic> getGenerics( String id ){
		return generics.entrySet().stream().filter( set -> set.getKey().equalsIgnoreCase(id) ||set.getKey().matches(id))
				.map(Entry::getValue).collect(Collectors.toList());
	}
	/**
	 * Load the generics
	 */
	public void loadGenerics() {
		generics.clear();

		XMLfab.getRootChildren(settingsPath, "dcafs","generics","generic")
				.forEach( ele ->  {
					var gen = Generic.readFromXML(ele,settingsPath);
					if( !gen.getID().isEmpty()) {
						if( generics.containsKey(gen.getID())){
							Logger.error("Tried to add generic with duplicate ID: "+gen.getID());
						}else {
							addGeneric(gen);
						}
					}else{
						Logger.error("Tried to read generic without id!");
					}
				} );
		// Find the path ones?
		XMLfab.getRootChildren(settingsPath, "dcafs","paths","path")
				.forEach(this::readGenericElementInPath);
		Logger.info("Finished loading generics.");
	}

	/**
	 * Check the element of a path for presence of generics
	 * @param pathElement The path element to check
	 */
	private void readGenericElementInPath(Element pathElement){
		String imp = pathElement.getAttribute("import");
		String setId="";

		int a=1;
		if( !imp.isEmpty() ){ //meaning imported
			var importPath = Path.of(imp);
			if(  !importPath.isAbsolute() ) {
				Logger.info("Import path: "+importPath+" isn't absolute, altering");
				importPath = settingsPath.getParent().resolve(importPath);
				Logger.info("Altered to "+importPath);
			}
			var sub = XMLfab.getRootChildren(importPath, "dcafs","path").findFirst();
			if( sub.isPresent() ){
				setId = XMLtools.getStringAttribute(sub.get(),"id",pathElement.getAttribute("id"));
			}else{
				Logger.error("No such xml file: "+importPath+", aborting.");
				return;
			}
			var gens = XMLfab.getRootChildren(importPath, "dcafs", "path", "generic").toList();
			var stores = XMLfab.getRootChildren(importPath, "dcafs", "path", "store").toList();

			for( Element gen : gens){
				a = checkGen( gen,importPath,setId,a);
			}
			for( Element gen : stores){
				a = checkGen( gen,importPath,setId,a);
			}
		}
		String delimiter = XMLtools.getStringAttribute(pathElement,"delimiter","");
		for( Element gen : XMLtools.getChildElements(pathElement,"generic")){
			if( !gen.hasAttribute("id")){ //if it hasn't got an id, give it one
				gen.setAttribute("id",pathElement.getAttribute("id")+"_gen"+a);
				a++;
			}
			if( !gen.hasAttribute("delimiter") && !delimiter.isEmpty()) //if it hasn't got an id, give it one
				gen.setAttribute("delimiter",delimiter);
			if( !gen.hasAttribute("group"))
				gen.setAttribute("group",setId);
			addGeneric( Generic.readFromXML(gen,settingsPath) );
		}
	}
	private int checkGen( Element gen, Path importPath, String setId, int cnt){
		if( !gen.hasAttribute("id")){ //if it hasn't got an id, give it one
			gen.setAttribute("id",setId+"_gen"+cnt);
			cnt++;
		}
		String delim = ((Element)gen.getParentNode()).getAttribute("delimiter");
		if( !gen.hasAttribute("delimiter") ) //if it hasn't got an id, give it one
			gen.setAttribute("delimiter",delim);
		if( !gen.hasAttribute("group"))
			gen.setAttribute("group",setId);
		addGeneric( Generic.readFromXML(gen,importPath) );
		return cnt;
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

	/* ******************************* D E F A U L T   S T U F F **************************************** */
	private void storeInRealVal(String param, String data, String origin ){
		try{
			String[] ids = param.split(",");

			String group = ids.length==1?"":ids[0];
			String name = ids.length==1?ids[0]:ids[1];
			String id = group+"_"+name;

			var val = NumberUtils.toDouble(data,Double.NaN);
			if( Double.isNaN(val) && NumberUtils.isCreatable(data)){
				val = NumberUtils.createInteger(data);
			}

			if( !Double.isNaN(val) ){
				if( rtvals.hasReal(id)){
					rtvals.updateReal(id,val);
				}else{
					rtvals.addRealVal( RealVal.newVal(group,name).value(val),true);
				}
			}else{
				Logger.warn("Tried to convert "+data+" from "+origin+" to a double, but got NaN");
			}
		}catch( NumberFormatException e ){
			Logger.warn("Tried to convert "+data+" from "+origin+" to a double, but got numberformatexception");
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
		d.origin("telnet");

		if( d.getData().equalsIgnoreCase("spy:off")||d.getData().equalsIgnoreCase("spy:stop")){
			spy.writeLine("Stopped spying...");
			spy=null;
			return;
		}else if( d.getData().startsWith("spy:")){
			spyingOn =d.getData().split(":")[1];
			spy=d.getWritable();
			spy.writeLine("Started spying on "+ spyingOn);
			return;
		}

		if (!d.getData().equals("status")) {
			String from = " for ";

			if (dt != null) {
				from += dt.getID();
			}
			if (!d.getData().isBlank())
				Logger.info("Executing telnet command [" + d.getData() + "]" + from);
		}

		String response = reqData.createResponse( d, false);
		if( spy!=null && d.getWritable()!=spy && (d.getWritable().getID().equalsIgnoreCase(spyingOn)|| spyingOn.equalsIgnoreCase("all"))){
			spy.writeLine(TelnetCodes.TEXT_ORANGE+"Cmd: "+d.getData()+TelnetCodes.TEXT_YELLOW);
			spy.writeLine(response);
		}
		String[] split = d.getLabel().split(":");
		if (dt != null) {
			if( d.getData().startsWith("telnet:write")){
				dt.writeString(TelnetCodes.PREV_LINE+TelnetCodes.CLEAR_LINE+response);
			}else{
				dt.writeLine(response);
				dt.writeString((split.length >= 2 ? "<" + split[1] : "") + ">");
			}
		} else {
			Logger.info(response);
			Logger.info((split.length >= 2 ? "<" + split[1] : "") + ">");
		}
		procCount.incrementAndGet();
	}
	public void stop(){
		goOn=false;
	}
	/* ************************************** RUNNABLES ******************************************************/
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
						case "generic": executor.execute( () -> processGeneric(d)); break;
						case "double": case "real":  executor.execute(() -> storeInRealVal(readID,d.getData(),d.getOriginID())); break;
						case "valmap":  executor.execute( () -> processValmap(d)); break;
						case "text":    executor.execute( () -> rtvals.setText(readID, d.data)); break;
						case "read":    executor.execute( ()-> checkRead(d.getOriginID(),d.getWritable(),readID) );break;
						case "telnet":  executor.execute( ()-> checkTelnet(d)); break;
						case "log":
							switch (d.label.split(":")[1]) {
								case "info" -> Logger.info(d.getData());
								case "warn" -> Logger.warn(d.getData());
								case "error" -> Logger.error(d.getData());
							}
							break;
						default:
							boolean processed=false;
							for( DatagramProcessing dgp : dgProc ){
								if( dgp.processDatagram(d)) {
									processed=true;
									break;
								}
							}
							if( ! processed)
								Logger.error("Unknown label: "+label);
							break;
					}
				}else {
					switch (label) {
						case "system": case "cmd": case "matrix":
							executor.execute(() -> reqData.createResponse( d, false));
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
							break;
						default:
							boolean processed=false;
							for( DatagramProcessing dgp : dgProc ){
								if( dgp.processDatagram(d)) {
									processed=true;
									break;
								}
							}
							if( ! processed)
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
	}
	public void selfCheck(){
		Logger.info("Read count now "+readCount+", old one "+oldReadCount+ " last message processed from "+lastOrigin+ " buffersize "+dQueue.size());
		Logger.info("Executioner: "+ executor.getCompletedTaskCount()+" completed, "+ executor.getTaskCount()+" submitted, "
				+ executor.getActiveCount()+"/"+ executor.getCorePoolSize()+"("+executor.getMaximumPoolSize()+")"+" active threads, "+ executor.getQueue().size()+" waiting to run");

		oldReadCount = readCount;
		readCount=0;
		lastOrigin="";
	}
	public void processValmap(Datagram d) {
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
					map.apply(mes, rtvals);
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

	private void processGeneric(Datagram d){
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
								Object[] data = gen.apply( mes, doubles, rtvals, queryWriting,mqtt );
								if (!gen.getTable().isEmpty() && gen.writesInDB()) {
									if (gen.isTableMatch()) {
										for( String id : gen.getDBID() )
											queryWriting.addDirectInsert( id, gen.getTable(), data);
									} else {
										for( String id : gen.getDBID() ){
											if (!queryWriting.buildInsert(id, gen.getTable(), gen.macro)) {
												Logger.error("Failed to write record for " + gen.getTable()+ " in "+id);
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
			Logger.error("Caught an exception when processing "+d.getData()+" from "+d.getOriginID()+" message: "+e.getMessage());
			Logger.error(e);
		}
		procCount.incrementAndGet();
	}

	/* *************************************** C O M M A N D A B L E ********************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {
		if ("gens".equals(request[0])) {
			return replyToGenCmds(request, wr, html);
		}
		return "unknown command: "+request[0]+":"+request[1];
	}
	public String replyToGenCmds(String[] request, Writable wr, boolean html ){

		StringJoiner join = new StringJoiner(html?"<br":"\r\n");
		String[] cmds = request[1].split(",");

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		switch(cmds[0]){
			case "?":
				join.add("")
						.add(TelnetCodes.TEXT_RED+"Purpose"+reg)
						.add("  Generics (gens) are used to take delimited data and store it as rtvals or in a database.");
				join.add(ora+"Notes"+reg)
						.add("  - ...");
				join.add("").add(cyan+"Creation"+reg)
						.add(green+"  gens:fromtable,dbid,dbtable,gen id[,delimiter] "+reg+"-> Create a generic according to a table, delim is optional, def is ','")
						.add(green+"  gens:fromdb,dbid,delimiter "+reg+"-> Create a generic with chosen delimiter for each table if there's no such generic yet")
						.add(green+"  gens:addgen,id,group,format,delimiter "+reg+"-> Create a blank generic with the given id, group and format")
						.add("      The format consists of a letter, followed by a number and then a word and this is repeated with , as delimiter")
						.add("      The letter is the type of value, the number the index in the array of received data and the word is the name/id of the value")
						.add("      So for example: i2temp,r5offset  -> integer on index 2 with name temp, real on 5 with name offset")
						.add("      Options for the letter:")
						.add("       r/d = a real/double number" )
						.add("       i = an integer number")
						.add("       t = a piece of text")
						.add("       m = macro, this value can be used as part as the rtval")
						.add("       eg. 1234,temp,19.2,hum,55 ( with 1234 = serial number")
						.add("           -> serial number,title,temperature reading,title,humidity reading")
						.add("           -> m0,r2temp,i4hum");
				join.add("").add(cyan+"Other"+reg);
				join.add(green+"  gens:? "+reg+"-> Show this info")
						.add(green+"  gens:reload "+reg+"-> Reloads all generics")
						.add(green+"  gens:alter,id,param:value "+reg+"-> Change a parameter of the specified generic")
						.add(green+"  gens:list "+reg+"-> Lists all generics");

				return join.toString();
			case "reload":
				loadGenerics();
				return getGenericInfo();
			case "fromtable":
				if(cmds.length < 4 )
					return "To few parameters, gens:fromtable,dbid,table,gen id,delimiter";
				var db = queryWriting.getDatabase(cmds[1]);
				if( db.isEmpty())
					return "No such database found "+cmds[1];
				if( db.get().buildGenericFromTable(XMLfab.withRoot(settingsPath, "dcafs","generics"),cmds[2],cmds[3],cmds.length>4?cmds[4]:",") ){
					loadGenerics();
					return "Generic written";
				}else{
					return "Failed to write to xml";
				}
			case "fromdb":
				if(cmds.length < 3 )
					return "To few parameters, gens:fromdb,dbid,delimiter";
				var dbs = queryWriting.getDatabase(cmds[1]);
				if( dbs.isEmpty())
					return "No such database found "+cmds[1];

				if( dbs.get().buildGenericsFromTables(XMLfab.withRoot(settingsPath, "dcafs","generics"),false,cmds[2]) >0 ){
					loadGenerics();
					return "Generic(s) written";
				}else{
					return "No generics written";
				}
			case "addblank": case "addgen": case "add":
				if( cmds.length < 4 )
					return "Not enough arguments, must be gens:"+cmds[0]+",id,group,format,delimiter";

				var delimiter=request[1].endsWith(",")?",":cmds[cmds.length-1];
				int forms=cmds.length-1;
				if( delimiter.length()>3) {
					delimiter = ",";
					forms++;
				}
				if( generics.containsKey(cmds[1]))
					return "This id is already used, try another.";
				if( cmds[2].contains("_"))
					return "Group name can't contain '_' (underscore), try again?";

				if( Generic.addBlankToXML(XMLfab.withRoot(settingsPath, "dcafs","generics"), cmds[1],cmds[2],
					ArrayUtils.subarray(cmds,3,forms),delimiter)){
					loadGenerics();
					var ps = cmds[2].split(":");
					switch( ps[0]){
						case "raw":
							ps[0]="stream";
						case "filter": case "editor": case "math": case "stream": case "path":
							dQueue.add(Datagram.system(ps[0]+"s:alter,"+ps[1]+",label:generic:"+cmds[1]).writable(wr));
					}
					return "Generic added";
				}
				return "Failed to write generic";
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
									if( !cmds[i+2].equalsIgnoreCase(".")) {
										var gr = f.getAttribute("group");
										gr=(gr.isEmpty()?"":gr+"_");
										if( !rtvals.hasReal(gr+cmds[i + 2])){
											rtvals.renameReal( gr+f.getTextContent(),gr+cmds[i + 2],false );
											f.setTextContent(cmds[i + 2]);
										}else{
											return "Failed to rename to already existing one";
										}
									}
								}
								i++;
							}
							fab.get().build();
							rtvals.storeValsInXml(true);
							loadGenerics();
							return "Names set, generics reloaded";
						case "delim":
							attr="delimiter";
						case "group": // if
						case "db":
						case "delimiter":
						case "id":
							fab.get().attr(attr,val).build();
							break;
						case "src":
							if( !val.contains(":"))
								return "Bad source format, needs to be type:id";
							var ps = val.split(":");
							switch( ps[0]){
								case "raw":
									ps[0]="stream";
								case "filter": case "editor": case "math": case "stream": case "path":
									dQueue.add(Datagram.system(ps[0]+"s:alter,"+ps[1]+",label:generic:"+cmds[1]).writable(wr));
									return "Tried to alter src";
							}
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
	@Override
	public boolean removeWritable(Writable wr) {
		return false;
	}
}
