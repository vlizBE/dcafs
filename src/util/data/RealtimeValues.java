package util.data;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.collector.CollectorFuture;
import io.collector.MathCollector;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.gis.Waypoints;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A storage class
 *
 */
public class RealtimeValues implements CollectorFuture, DataProviding, Commandable {

	/* Data stores */
	private final ConcurrentHashMap<String, DoubleVal> doubleVals = new ConcurrentHashMap<>(); // doubles
	private final ConcurrentHashMap<String, IntegerVal> integerVals = new ConcurrentHashMap<>(); // doubles
	private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>(); // strings
	private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>(); // booleans
	private final HashMap<String, MathCollector> mathCollectors = new HashMap<>(); // Math collectors
	private Waypoints waypoints; // waypoints
	private final IssuePool issuePool;

	/* Data update requests */
	private final HashMap<String, List<Writable>> textRequest = new HashMap<>();

	/* General settings */
	private final Path settingsPath;

	/* Patterns */
	private final Pattern words = Pattern.compile("[a-zA-Z]+[_:0-9]*[a-zA-Z0-9]+\\d*"); // find references to doublevals etc

	/* Other */
	private final BlockingQueue<Datagram> dQueue; // Used to issue triggered cmd's
	private ArrayList<String> errorLog = new ArrayList<>();

	boolean readingXML=false;

	public RealtimeValues( Path settingsPath,BlockingQueue<Datagram> dQueue ){
		this.settingsPath=settingsPath;
		this.dQueue=dQueue;

		readFromXML( XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals") );

		issuePool = new IssuePool(dQueue, settingsPath,this);
	}

	/* ************************************ X M L ****************************************************************** */
	/**
	 * Read the rtvals node in the settings.xml
	 */
	public void readFromXML( XMLfab fab ){

		double defDouble = XMLtools.getDoubleAttribute(fab.getCurrentElement(),"realdefault",Double.NaN);
		String defText = XMLtools.getStringAttribute(fab.getCurrentElement(),"textdefault","");
		boolean defFlag = XMLtools.getBooleanAttribute(fab.getCurrentElement(),"flagdefault",false);
		int defInteger = XMLtools.getIntAttribute(fab.getCurrentElement(),"integerdefault",-999);

		readingXML=true;
		fab.getChildren("*").forEach( // The tag * is special and acts as wildcard
				rtval -> {
					String id = XMLtools.getStringAttribute(rtval,"id",""); // get the id of the node
					if( id.isEmpty()) // Can't do anything without an id
						return;
					if( rtval.getTagName().equals("group")){ // If the node is a group node
						id += "_"; // The id we got is the group id, so add an underscore for the full id with name
						for( var groupie : XMLtools.getChildElements(rtval)){ // Get the nodes inside the group node
							// Both id and name are valid attributes for the node name that forms the full id
							// First check if id is used
							var gid = XMLtools.getStringAttribute(groupie,"id",""); // get the node id
							// then check if it has an attribute name and use this instead if found
							gid = id + XMLtools.getStringAttribute(groupie,"name",gid); //
							processRtvalElement(groupie, gid, defDouble, defText, defFlag,defInteger);
						}
					}else { // If it isn't a group node
						// then the id is the full id
						processRtvalElement(rtval, id, defDouble, defText, defFlag,defInteger);
					}
				}
		);
		readingXML=false;
	}

	/**
	 * Process a Val node
	 * @param rtval The node to process
	 * @param id The id of the Val
	 * @param defDouble The default double value
	 * @param defText The default text value
	 * @param defFlag The default boolean value
	 */
	private void processRtvalElement(Element rtval, String id, double defDouble, String defText, boolean defFlag, int defInteger ){
		switch( rtval.getTagName() ){
			case "double": case "real":
				// Both attributes fractiondigits and scale are valid, so check for both
				int scale = XMLtools.getIntAttribute(rtval,"fractiondigits",-1);
				if( scale == -1)
					scale = XMLtools.getIntAttribute(rtval,"scale",-1);

				if( !hasDouble(id)) // If it doesn't exist yet
					setDouble(id,defDouble,true); // create it
				var dv = getDoubleVal(id).get();//
				dv.reset(); // reset needed if this is called because of reload
				dv.name(XMLtools.getChildValueByTag(rtval,"name",dv.name()))
						.group(XMLtools.getChildValueByTag(rtval,"group",dv.group()))
						.unit(XMLtools.getStringAttribute(rtval,"unit",""))
						.fractionDigits(scale)
						.defValue(XMLtools.getDoubleAttribute(rtval,"default",defDouble));

					String options = XMLtools.getStringAttribute(rtval,"options","");
					for( var opt : options.split(",")){
						var arg = opt.split(":");
						switch( arg[0]){
							case "minmax":  dv.keepMinMax(); break;
							case "time":    dv.keepTime();   break;
							case "scale":	dv.fractionDigits( NumberUtils.toInt(arg[1],-1)); break;
							case "order":   dv.order( NumberUtils.toInt(arg[1],-1)); break;
							case "history": dv.enableHistory( NumberUtils.toInt(arg[1],-1)); break;
						}
					}
				if( !XMLtools.getChildElements(rtval,"cmd").isEmpty() )
					dv.enableTriggeredCmds(dQueue);
				for( Element trigCmd : XMLtools.getChildElements(rtval,"cmd")){
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					dv.addTriggeredCmd(trig,cmd);
				}
				break;
			case "integer":case "int":
				if( !hasInteger(id)) // If it doesn't exist yet
					setInteger(id,defInteger,true); // create it
				var iv = getIntegerVal(id).get();//
				iv.reset(); // reset needed if this is called because of reload
				iv.name(XMLtools.getChildValueByTag(rtval,"name",iv.name()))
						.group(XMLtools.getChildValueByTag(rtval,"group",iv.group()))
						.unit(XMLtools.getStringAttribute(rtval,"unit",""))
						.defValue(XMLtools.getIntAttribute(rtval,"default",defInteger));

				String opts = XMLtools.getStringAttribute(rtval,"options","");
				for( var opt : opts.split(",")){
					var arg = opt.split(":");
					switch( arg[0]){
						case "minmax":  iv.keepMinMax(); break;
						case "time":    iv.keepTime();   break;
						case "order":   iv.order( NumberUtils.toInt(arg[1],-1)); break;
						case "history": iv.enableHistory( NumberUtils.toInt(arg[1],-1)); break;
					}
				}
				if( !XMLtools.getChildElements(rtval,"cmd").isEmpty() )
					iv.enableTriggeredCmds(dQueue);
				for( Element trigCmd : XMLtools.getChildElements(rtval,"cmd")){
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					iv.addTriggeredCmd(trig,cmd);
				}
				break;
			case "text":
				setText(id,XMLtools.getStringAttribute(rtval,"default",defText));
				break;
			case "flag":
				var fv = getOrAddFlagVal(id);
				fv.reset(); // reset is needed if this is called because of reload
				fv.name(XMLtools.getChildValueByTag(rtval,"name",fv.name()))
						.group(XMLtools.getChildValueByTag(rtval,"group",fv.group()))
						.defState(XMLtools.getBooleanAttribute(rtval,"default",defFlag));
				if( XMLtools.getBooleanAttribute(rtval,"keeptime",false) )
					fv.keepTime();
				if( !XMLtools.getChildElements(rtval,"cmd").isEmpty() )
					fv.enableTriggeredCmds(dQueue);
				for( Element trigCmd : XMLtools.getChildElements(rtval,"cmd")){
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					fv.addTriggeredCmd(trig,cmd);
				}
				break;
		}
	}
	/* ************************************* P A R S I N G ********************************************************* */
	/**
	 * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps.
	 * It assumes all words are double, but also allows for d:id,t:id and f:id or D: or F: if it should create them
	 * @param line The line to parse
	 * @param error The line to return on an error or 'ignore' if errors should be ignored
	 * @return The (possibly) altered line
	 */
	public String simpleParseRT( String line,String error ){

		var found = words.matcher(line).results().map(MatchResult::group).collect(Collectors.toList());

		for( var word : found ){
			if( word.contains(":")){ // Check if the word contains a : with means it's {d:id} etc
				var id = word.split(":")[1];
				switch( word.charAt(0) ) {
					case 'd': case 'r':
						if( !hasDouble(id)) {
							Logger.error("No such double "+id+", extracted from "+line);
							if( !error.equalsIgnoreCase("ignore"))
								return error;
						}
					case 'D': case 'R':
						line = line.replace(word,""+getOrAddDoubleVal(id).value());
						break;
					case 'i':
						if( !hasInteger(id)) {
							Logger.error("No such integer "+id+", extracted from "+line);
							if( !error.equalsIgnoreCase("ignore"))
								return error;
						}
					case 'I':
						line = line.replace(word,""+getOrAddIntegerVal(id).value());
						break;
					case 'f':
						if( !hasFlag(id)) {
							Logger.error("No such flag "+id+ ", extracted from "+line);
							if( !error.equalsIgnoreCase("ignore"))
								return error;
						}
					case 'F':
						if( !hasFlag(id))
							setFlagState(id,false);
						line = line.replace(word, isFlagUp(id) ? "1" : "0");
						break;
					case 't': case 'T':
						var te = texts.get(id);
						if( te == null && word.charAt(0)=='T') {
							texts.put(id, "");
							te="";
						}
						if( te !=null ) {
							line = line.replace(word, te);
						}else{
							Logger.error("No such text "+id+", extracted from "+line);
							if( !error.equalsIgnoreCase("ignore"))
								return error;
						}
						break;
				}
			}else { // If it doesn't contain : it could be anything...
				var d = getDouble(word, Double.NaN); // First check if it's a double
				if (!Double.isNaN(d)) {
					line = line.replace(word, "" + d);
 				} else { // if not
					var i = getInteger(word,Integer.MAX_VALUE);
					if( i != Integer.MAX_VALUE){
						line = line.replace(word, "" + i);
					}else {
						var t = getText(word, ""); // check if it's a text
						if (!t.isEmpty()) {
							line = line.replace(word, t);
						} else if (hasFlag(word)) { // if it isn't a text, check if it's a flag
							line = line.replace(word, isFlagUp(word) ? "1" : "0");
						} else if (error.equalsIgnoreCase("create")) { // if still not found and error is set to create
							// add it as a double
							getOrAddDoubleVal(word).updateValue(0);
							Logger.warn("Created doubleval " + word + " with value 0");
							line = line.replace(word, "0"); // default of a double is 0
						} else if (!error.equalsIgnoreCase("ignore")) { // if it's not ignore
							Logger.error("Couldn't process " + word + " found in " + line); // log it and abort
							return error;
						}
					}
				}
			}
		}
		return line;
	}

	/**
	 * Stricter version to parse a realtime line, must contain the references within { }
	 * Options are:
	 * - DoubleVal: {d:id} and {double:id} if it should already exist, or {D:id} if it should be created if new (value is 0.0)
	 * - FlagVal: {f:id} or {b:id} and {flag:id} if it should already exist, or {F:id} if it should be created if new (state will be false)
	 * This also checks for {utc}/{utclong},{utcshort} to insert current timestamp
	 * @param line The original line to parse/alter
	 * @param error Value to put if the reference isn't found
	 * @return The (possibly) altered line
	 */
	public String parseRTline( String line, String error ){

		if( !line.contains("{"))
			return line;

		var pairs = Tools.parseKeyValue(line,true);
		for( var p : pairs ){
			if(p.length==2) {
				switch (p[0]) {
					case "d": case "D": case "r": case "R":
					case "double": case "real": {
						var d = p[0].equals("D")||p[0].equals("R")?getOrAddDoubleVal(p[1]).value():getDouble(p[1], Double.NaN);
						if (!Double.isNaN(d) || !error.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", Double.isNaN(d) ? error : "" + d);
						break;
					}
					case "i": case "I":
					case "int": case "integer": {
						var i = p[0].equals("I")?getOrAddIntegerVal(p[1]).intValue():getInteger(p[1], Integer.MAX_VALUE);
						if (i != Integer.MAX_VALUE)
							line = line.replace("{" + p[0] + ":" + p[1] + "}",  "" + i);
						break;
					}
					case "t":
					case "text":
						String t = getText(p[1], error);
						if (!t.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", t);
						break;
					case "f": case "F": case "b":
					case "flag": {
						var d = p[0].equalsIgnoreCase("F")?Optional.of(getOrAddFlagVal(p[1])):getFlagVal(p[1]);
						var r = d.map(FlagVal::toString).orElse(error);
						if (!r.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", r);
						break;
					}
				}
			}else{
				switch(p[0]){
					case "utc": line = line.replace("{utc}", TimeTools.formatLongUTCNow());break;
					case "utclong": line = line.replace("{utclong}", TimeTools.formatLongUTCNow());
					case "utcshort": line = line.replace("{utcshort}", TimeTools.formatShortUTCNow());
				}
			}
		}
		if( line.toLowerCase().matches(".*[{][drfi]:.*") && !pairs.isEmpty()){
			Logger.warn("Found a {*:*}, might mean parsing a section of "+line+" failed");
		}
		return line;
	}

	/**
	 * Checks the exp for any mentions of the numerical rtvals and if found adds these to the nums arraylist and replaces
	 * the reference with i followed by the index in nums+offset. {D:id} and {F:id} will add the rtvals if they don't
	 * exist yet.
	 *
	 * fe. {d:temp}+30, nums still empty and offset 1: will add the DoubleVal temp to nums and alter exp to i1 + 30
	 * @param exp The expression to check
	 * @param nums The Arraylist to hold the numerical values
	 * @param offset The index offset to apply
	 * @return The altered expression
	 */
	public String buildNumericalMem( String exp, ArrayList<NumericVal> nums, int offset){
		if( nums==null)
			nums = new ArrayList<>();

		// Find all the double/flag pairs
		var pairs = Tools.parseKeyValue(exp,true); // Add those of the format {d:id}
		//pairs.addAll( Tools.parseKeyValueNoBrackets(exp) ); // Add those of the format d:id

		for( var p : pairs ) {
			boolean ok=false;
			if (p.length == 2) {
				for( int pos=0;pos<nums.size();pos++ ){ // go through the known doubleVals
					var d = nums.get(pos);
					if( d.id().equalsIgnoreCase(p[1])) { // If a match is found
						exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + (offset + pos));
						exp = exp.replace(p[0] + ":" + p[1], "i" + (offset + pos));
						ok=true;
						break;
					}
				}
				if( ok )
					continue;
				int index;
				switch(p[0]){
					case "d": case "double": case "D": case "r": case "R": case "real":
						var d = p[0].equalsIgnoreCase("D")||p[0].equalsIgnoreCase("R")?Optional.of(getOrAddDoubleVal(p[1])):getDoubleVal(p[1]);
						if( d.isPresent() ){
							index = nums.indexOf(d.get());
							if(index==-1){
								nums.add( d.get() );
								index = nums.size()-1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						}else{
							Logger.error("Couldn't find a doubleval/real with id "+p[1]);
							errorLog.add("Couldn't find a doubleval/real with id "+p[1]);
							return "";
						}
						break;
					case "int": case "i": case "I": case "INT":
						var ii = p[0].startsWith("I:")?Optional.of(getOrAddIntegerVal(p[1])):getIntegerVal(p[1]);
						if( ii.isPresent() ){
							index = nums.indexOf(ii.get());
							if(index==-1){
								nums.add( ii.get() );
								index = nums.size()-1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						}else{
							Logger.error("Couldn't find a integer with id "+p[1]);
							errorLog.add("Couldn't find a integer with id "+p[1]);
							return "";
						}
						break;
					case "f": case "flag": case "b": case "F":
						var f = p[0].equalsIgnoreCase("F")?Optional.of(getOrAddFlagVal(p[1])):getFlagVal(p[1]);
						if( f.isPresent() ){
							index = nums.indexOf(f.get());
							if(index==-1){
								nums.add( f.get() );
								index = nums.size()-1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						}else{
							Logger.error("Couldn't find a FlagVal with id "+p[1]);
							errorLog.add("Couldn't find a FlagVal  with id "+p[1]);
							return "";
						}
						break;
					case "is": // issues
						var i = issuePool.getIssue(p[1]);
						if( i.isPresent() ){
							index = nums.indexOf(i.get());
							if(index==-1){
								nums.add( i.get() );
								index = nums.size()-1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						}else{
							Logger.error("Couldn't find an Issue with id "+p[1]);
							errorLog.add("Couldn't find a Issue with id "+p[1]);
							return "";
						}
						break;
					default:
						Logger.error("Operation containing unknown pair: "+p[0]+":"+p[1]);
						errorLog.add("Operation containing unknown pair: "+p[0]+":"+p[1]);
						return "";
				}
			}else{
				Logger.error( "Pair containing odd amount of elements: "+String.join(":",p));
				errorLog.add("Pair containing odd amount of elements: "+String.join(":",p));
			}
		}
		// Figure out the rest?
		var found = words.matcher(exp).results().map(MatchResult::group).collect(Collectors.toList());
		for( String fl : found){
			if( fl.matches("^i\\d+") )
				continue;
			int index;
			if( fl.startsWith("flag:")){
				var f = getFlagVal(fl.substring(5));
				if( f.isPresent() ){
					index = nums.indexOf(f.get());
					if(index==-1){
						nums.add( f.get() );
						index = nums.size()-1;
					}
					index += offset;
					exp = exp.replace(fl, "i" + index);
				}else{
					Logger.error("Couldn't find a FlagVal with id "+fl);
					errorLog.add("Couldn't find a FlagVal  with id "+fl);
					return "";
				}
			}else{
				var d = getDoubleVal(fl);
				if( d.isPresent() ){
					index = nums.indexOf(d.get());
					if(index==-1){
						nums.add( d.get() );
						index = nums.size()-1;
					}
					index += offset;
					exp = exp.replace(fl, "i" + index);
				}else{
					var f = getFlagVal(fl);
					if( f.isPresent() ){
						index = nums.indexOf(f.get());
						if(index==-1){
							nums.add( f.get() );
							index = nums.size()-1;
						}
						index += offset;
						exp = exp.replace(fl, "i" + index);
					}else{
						Logger.error("Couldn't find a doubleval with id "+fl);
						errorLog.add("Couldn't find a DoubleVal  with id "+fl);
						return "";
					}
					return exp;
				}
			}
		}
		nums.trimToSize();
		return exp;
	}
	public String getErrrorLog(){
		var j = String.join("\r\n",errorLog);
		errorLog.clear();;
		return j;
	}
	/**
	 * Look for a numerical val (i.e. DoubleVal, IntegerVal or FlagVal) with the given id
	 * @param id The id to look for
	 * @return An optional Numericalval that's empty if nothing was found
	 */
	public Optional<NumericVal> getNumericVal( String id){
		if( id.startsWith("{")){ // First check if the id is contained inside a { }
			id = id.substring(1,id.length()-2);
			var pair = id.split(":");
			switch(pair[0].toLowerCase()) {
				case "i": case "int": case "integer":
					return Optional.ofNullable(integerVals.get(id));
				case "d":case "double": case "r": case "real":
					return Optional.ofNullable(doubleVals.get(id));
				case "f": case "flag": case "b":
					return Optional.ofNullable(flagVals.get(id));
			}
			return Optional.empty();
		} // If it isn't inside { } just check if a match is found in the DoubleVals and then the FlagVals
		return getDoubleVal(id).map( d -> Optional.of((NumericVal)d)).orElse(Optional.ofNullable((flagVals.get(id))));
	}
	/* ************************************ D O U B L E V A L ***************************************************** */

	/**
	 * Retrieve a DoubleVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested DoubleVal or null if not found
	 */
	public Optional<DoubleVal> getDoubleVal( String id ){
		if( doubleVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing doubleval "+id);
		return Optional.ofNullable(doubleVals.get(id));
	}

	/**
	 * Retrieves the id or adds it if it doesn't exist yet
	 * @param id The group_name or just name of the val
	 * @return The object if found or made or null if something went wrong
	 */
	public DoubleVal getOrAddDoubleVal( String id ){
		if( id.isEmpty())
			return null;

		var val = doubleVals.get(id);
		if( val==null){
			val = DoubleVal.newVal(id);
			doubleVals.put(id,val);
			if( !readingXML ){
				var fab = XMLfab.withRoot(settingsPath, "dcafs","settings","rtvals");

				if( fab.hasChild("real","id",id).isEmpty()){
					if( val.group().isEmpty()) {
						if( fab.hasChild("real","id",id).isEmpty()) {
							Logger.info("doubleval new, adding to xml :"+id);
							fab.addChild("real").attr("id", id).attr("unit", val.unit()).build();
						}
					}else{
						if( fab.hasChild("group","id",val.group()).isEmpty() ){
							fab.addChild("group").attr("id",val.group()).down();
							if( fab.hasChild("real","id",val.name()).isEmpty() )
								fab.addChild("real").attr("name", val.name()).attr("unit", val.unit()).build();
						}else{
							String name = val.name();
							String unit = val.unit();
							fab.selectChildAsParent("group","id",val.group())
									.ifPresent( f->{
										if( f.hasChild("real","name",name).isEmpty()) {
											Logger.info("doubleval new, adding to xml :"+id);
											fab.addChild("real").attr("name", name).attr("unit", unit).build();
										}
									});
						}
					}
				}
			}
		}
		return val;
	}
	public boolean renameDouble( String from, String to, boolean alterXml){
		if( hasDouble(to) ){
			return false;
		}
		var dv = getDoubleVal(from);
		if( dv.isPresent() ){
			// Alter the DoubleVal
			doubleVals.remove(from); // Remove it from the list
			var name = to;
			if( name.contains("_") )
				name = name.substring(name.indexOf("_")+1);
			String newName = name;
			String oriName = dv.get().name();
			dv.get().name(name);
			doubleVals.put(to,dv.get()); // Add it renamed

			// Correct the XML
			if( alterXml ) {
				if (name.equalsIgnoreCase(to)) { // so didn't contain a group
					XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("real", "id", from)
							.ifPresent(fab -> fab.attr("id", to).build());
				} else { // If it did contain a group
					var grOpt = XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("group", "id", from.substring(0, from.indexOf("_")));
					if (grOpt.isPresent()) { // If the group tag is already present alter it there
						grOpt.get().selectChildAsParent("real", "name", oriName).ifPresent(f -> f.attr("name", newName).build());
					} else { // If not
						XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("real", "id", from)
								.ifPresent(fab -> fab.attr("id", to).build());
					}
				}
			}
		}
		return true;
	}
	public boolean hasDouble( String id){
		return doubleVals.containsKey(id);
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * @param id The parameter name
	 * @param value The value of the parameter
	 * @param createIfNew Whether to create a new object if none was found
	 * @return True if it was created
	 */
	private boolean setDouble(String id, double value, boolean createIfNew) {

		if( id.isEmpty()) {
			Logger.error("Empty id given");
			return false;
		}
		DoubleVal d;
		if( createIfNew ) {
			d = getOrAddDoubleVal(id);
		}else{
			d = doubleVals.get(id);
		}

		if( d==null ) {
			Logger.error("No such double "+id+" yet, create it first");
			return false;
		}
		d.updateValue(value);
		return true;
	}
	public boolean setDouble(String id, double value){
		return setDouble(id,value,true);
	}
	public boolean updateDouble(String id, double value) {
		if( id.isEmpty())
			return false;
		return !setDouble(id,value,false);
	}

	/**
	 * Alter all the values of the doubles in the given group
	 * @param group The group to alter
	 * @param value The value to set
	 * @return The amount of doubles updated
	 */
	public int updateDoubleGroup(String group, double value){
		var set = doubleVals.values().stream().filter( dv -> dv.group().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(dv->dv.updateValue(value));
		return set.size();
	}
	/**
	 * Get the value of a double
	 *
	 * @param id The id to get the value of
	 * @param bad The value to return of the id wasn't found
	 * @return The value found or the bad value
	 */
	public double getDouble(String id, double bad) {
		return getDouble(id,bad,false);
	}
	public double getDouble(String id, double defVal, boolean createIfNew) {

		DoubleVal d = doubleVals.get(id.toLowerCase());
		if (d == null) {
			if( createIfNew ){
				Logger.warn("ID "+id+" doesn't exist, creating it with value "+defVal);
				setDouble(id,defVal,true);
			}else{
				Logger.debug("No such id: " + id);
			}
			return defVal;
		}
		if (Double.isNaN(d.value())) {
			Logger.error("ID: " + id + " is NaN.");
			return defVal;
		}
		return d.value();
	}
	/* ************************************ I N T E G E R V A L ***************************************************** */

	/**
	 * Retrieve a IntegerVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested IntegerVal or empty optional if not found
	 */
	public Optional<IntegerVal> getIntegerVal( String id ){
		if( integerVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing integerval "+id);
		return Optional.ofNullable(integerVals.get(id));
	}

	/**
	 * Retrieves the id or adds it if it doesn't exist yet
	 * @param id The group_name or just name of the val
	 * @return The object if found or made or null if something went wrong
	 */
	public IntegerVal getOrAddIntegerVal( String id ){
		if( id.isEmpty())
			return null;

		var val = integerVals.get(id);
		if( val==null){
			val = IntegerVal.newVal(id);
			integerVals.put(id,val);
			if( !readingXML ){
				var fab = XMLfab.withRoot(settingsPath, "dcafs","settings","rtvals");

				if( fab.hasChild("int","id",id).isEmpty()){
					if( val.group().isEmpty()) {
						if( fab.hasChild("int","id",id).isEmpty()) {
							Logger.info("New integer, adding to xml :"+id);
							fab.addChild("int").attr("id", id).attr("unit", val.unit()).build();
						}
					}else{
						if( fab.hasChild("group","id",val.group()).isEmpty() ){
							fab.addChild("group").attr("id",val.group()).down();
							if( fab.hasChild("int","id",val.name()).isEmpty() )
								fab.addChild("int").attr("name", val.name()).attr("unit", val.unit()).build();
						}else{
							String name = val.name();
							String unit = val.unit();
							fab.selectChildAsParent("group","id",val.group())
									.ifPresent( f->{
										if( f.hasChild("int","name",name).isEmpty()) {
											Logger.info("New integer, adding to xml :"+id);
											fab.addChild("int").attr("name", name).attr("unit", unit).build();
										}
									});
						}
					}
				}
			}
		}
		return val;
	}
	public boolean renameInteger( String from, String to, boolean alterXml){
		if( hasInteger(to) ){
			return false;
		}
		var iv = getIntegerVal(from);
		if( iv.isPresent() ){
			// Alter the DoubleVal
			integerVals.remove(from); // Remove it from the list
			var name = to;
			if( name.contains("_") )
				name = name.substring(name.indexOf("_")+1);
			String newName = name;
			String oriName = iv.get().name();
			iv.get().name(name);
			integerVals.put(to,iv.get()); // Add it renamed

			// Correct the XML
			if( alterXml ) {
				if (name.equalsIgnoreCase(to)) { // so didn't contain a group
					XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("int", "id", from)
							.ifPresent(fab -> fab.attr("id", to).build());
				} else { // If it did contain a group
					var grOpt = XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("group", "id", from.substring(0, from.indexOf("_")));
					if (grOpt.isPresent()) { // If the group tag is already present alter it there
						grOpt.get().selectChildAsParent("int", "name", oriName).ifPresent(f -> f.attr("name", newName).build());
					} else { // If not
						XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("int", "id", from)
								.ifPresent(fab -> fab.attr("id", to).build());
					}
				}
			}
		}
		return true;
	}
	public boolean hasInteger( String id){
		return integerVals.containsKey(id);
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * @param id The parameter name
	 * @param value The value of the parameter
	 * @param createIfNew Whether to create a new object if none was found
	 * @return True if it was created
	 */
	private boolean setInteger(String id, int value, boolean createIfNew) {

		if( id.isEmpty()) {
			Logger.error("Empty id given");
			return false;
		}
		IntegerVal d;
		if( createIfNew ) {
			d = getOrAddIntegerVal(id);
		}else{
			d = integerVals.get(id);
		}

		if( d==null ) {
			Logger.error("No such double "+id+" yet, create it first");
			return false;
		}
		d.updateValue(value);
		return true;
	}
	public boolean setInteger(String id, int value){
		return setInteger(id,value,true);
	}
	public boolean updateInteger(String id, int value) {
		if( id.isEmpty())
			return false;
		return !setInteger(id,value,false);
	}

	/**
	 * Alter all the values of the ints in the given group
	 * @param group The group to alter
	 * @param value The value to set
	 * @return The amount of ints updated
	 */
	public int updateIntegerGroup(String group, int value){
		var set = integerVals.values().stream().filter( iv -> iv.group().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(iv->iv.updateValue(value));
		return set.size();
	}
	/**
	 * Get the value of an integer
	 *
	 * @param id The id to get the value of
	 * @param bad The value to return of the id wasn't found
	 * @return The value found or the bad value
	 */
	public int getInteger(String id, int bad) {
		return getInteger(id,bad,false);
	}
	public int getInteger(String id, int defVal, boolean createIfNew) {

		IntegerVal i = integerVals.get(id.toLowerCase());
		if (i == null) {
			if( createIfNew ){
				Logger.warn("ID "+id+" doesn't exist, creating it with value "+defVal);
				setDouble(id,defVal,true);
			}else{
				Logger.debug("No such id: " + id);
			}
			return defVal;
		}
		return i.intValue();
	}
	/* *********************************** T E X T S  ************************************************************* */
	public boolean hasText(String id){
		return texts.containsKey(id);
	}
	public boolean setText(String parameter, String value) {


		if( parameter.isEmpty()) {
			Logger.error("Empty param given");
			return false;
		}
		Logger.debug("Setting "+parameter+" to "+value);

		boolean created = texts.put(parameter, value)==null;

		if( !textRequest.isEmpty()){
			var res = textRequest.get(parameter);
			if( res != null)
				res.forEach( wr -> wr.writeLine(parameter + " : " + value));
		}
		return created;
	}
	public boolean updateText( String id, String value){
		if( texts.containsKey(id)) {
			texts.put(id, value);
			return true;
		}
		return false;
	}
	public String getText(String parameter, String def) {
		String result = texts.get(parameter);
		return result == null ? def : result;
	}

	/* ************************************ F L A G S ************************************************************* */
	public FlagVal getOrAddFlagVal( String id ){
		if( id.isEmpty())
			return null;

		var val = flagVals.get(id);

		if( val==null){
			val = FlagVal.newVal(id);
			flagVals.put(id,val);

			if( !readingXML ) { // Only add to xml if not reading from xml
				var fab = XMLfab.withRoot(settingsPath, "dcafs", "settings", "rtvals");

				if (fab.hasChild("flag", "id", id).isPresent())
					return val;

				if (val.group().isEmpty()) {
					fab.alterChild("flag", "id", id).build();
				} else {
					if (fab.hasChild("group", "id", val.group()).isEmpty()) {
						fab.addChild("group").attr("id", val.group()).down();
					} else {
						fab.selectChildAsParent("group", "id", val.group());
					}
					fab.alterChild("flag").attr("name", val.name()).build();
				}
			}
		}
		return val;
	}
	public Optional<FlagVal> getFlagVal( String flag){
		return Optional.ofNullable(flagVals.get(flag));
	}
	public boolean hasFlag( String flag){
		return flagVals.get(flag)!=null;
	}
	public boolean isFlagUp( String flag ){
		var f = flagVals.get(flag);
		if( f==null) {
			Logger.warn("No such flag: " + flag);
			return false;
		}
		return f.isUp();
	}
	public boolean isFlagDown( String flag ){
		var f = flagVals.get(flag);
		if( f==null) {
			Logger.warn("No such flag: " + flag);
			return false;
		}
		return f.isDown();
	}

	/**
	 * Raises a flag
	 * @param flags The flags/bits to set
	 * @return True if this is a new flag/bit
	 */
	public boolean raiseFlag( String... flags ){
		int cnt = flagVals.size();
		for( var f : flags) {
			setFlagState(f,true);
		}
		return cnt!= flagVals.size();
	}
	/**
	 * Lowers a flag or clears a bool.
	 * @param flag The flags/bits to clear
	 * @return True if this is a new flag/bit
	 */
	public boolean lowerFlag( String... flag ){
		int cnt = flagVals.size();
		for( var f : flag){
			setFlagState(f,false);
		}
		return cnt!= flagVals.size();
	}
	/**
	 * Set the state of the flag
	 * @param id The flag id
	 * @param state The new state for the flag
	 * @return True if the state was changed, false if a new flag was made
	 */
	public boolean setFlagState( String id, boolean state){

		int size = flagVals.size();
		getOrAddFlagVal(id).setState(state);
		return size==flagVals.size();
	}
	public ArrayList<String> listFlags(){
		return flagVals.entrySet().stream().map(ent -> ent.getKey()+" : "+ent.getValue()).collect(Collectors.toCollection(ArrayList::new));
	}
	/* ********************************* O V E R V I E W *********************************************************** */

	/**
	 * Store the rtvals in the settings.xml
	 * @return The result
	 */
	public boolean storeValsInXml(boolean clearFirst){
		XMLfab fab = XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals");
		if( clearFirst ) // If it needs to cleared first, remove child nodes
			fab.clearChildren();
		var vals = doubleVals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Entry::getValue).collect(Collectors.toList());
		for( var dv : vals ){
			dv.storeInXml(fab);
		}
		var keys = texts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Entry::getKey).collect(Collectors.toList());
		for( var dt : keys ){
			fab.selectOrAddChildAsParent("text","id",dt).up();
		}
		var flags = flagVals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Entry::getValue).collect(Collectors.toList());
		for( var fv : flags ){
			fv.storeInXml(fab);
		}
		fab.build();
		return true;
	}
	/* ******************************************************************************************************/
	/**
	 * Get the current timestamp in db approved format, this should be overridden if
	 * a gps is present
	 * 
	 * @return The timestamp in a sql valid format yyyy-MM-dd HH:mm:ss.SSS
	 */
	public synchronized String getTimeStamp() {
		return LocalDateTime.now(ZoneOffset.UTC).format(TimeTools.LONGDATE_FORMATTER_UTC);
	}

	/* ******************************************************************************************************/

	public int addRequest(Writable writable, String type, String req) {

		switch (type) {
			case "rtval": case "double": case "real":
				var list = doubleVals.entrySet().stream()
							.filter(e -> e.getKey().matches(req)) // matches the req
							.map( e->e.getValue()) // Only care about the values
							.collect(Collectors.toList());
				list.forEach( dv -> dv.addTarget(writable));
				return list.size();
			case "text":
				var t = textRequest.get(req);
				if( t == null) {
					textRequest.put(req, new ArrayList<>());
					Logger.info("Created new request for: " + req);
				}else{
					Logger.info("Appended existing request to: " + t + "," + req);
				}
				if( !textRequest.get(req).contains(writable)) {
					textRequest.get(req).add(writable);
					return 1;
				}
				break;
			default:
				Logger.warn("Requested unknown type: "+type);
				break;
		}
		return 0;
	}
	/* **************************** MATH COLLECTOR ********************************************** */
	public void addMathCollector( MathCollector mc ){
		mc.addListener(this);
		mathCollectors.put( mc.getID(),mc);
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		String[] ids = id.split(":");
		if(ids[0].equalsIgnoreCase("math")){
			setDouble(message,(double)result,false);
		}
	}
	/* ************************** C O M M A N D A B L E ***************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {

		switch( request[0] ){
			case "doubles": case "dv":
				return replyToDoublesCmd(request,html);
			case "texts": case "tv":
				return replyToTextsCmd(request,html);
			case "flags": case "fv":
				return replyToFlagsCmd(request,html);
			case "rtval": case "double": case "real":
				int s = addRequest(wr,request[0],request[1]);
				return s!=0?"Request added to "+s+" doublevals":"Request failed";
			case "rtvals": case "rvs":
				return replyToRtvalsCmd(request,wr,html);
			default:
				return "unknown command "+request[0]+":"+request[1];
		}
	}
	public boolean removeWritable(Writable writable ) {
		doubleVals.values().forEach( dv -> dv.removeTarget(writable));
		textRequest.forEach( (key, list) -> list.remove(writable));
		return true;
	}
	public String replyToTextsCmd( String[] request,  boolean html ){

		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		if( cmds.length>3){
			for( int a=3;a<cmds.length;a++){
				cmds[2]+=","+cmds[3];
			}
		}
		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None yet");
		switch( cmds[0] ){
			/* Info */
			case "?":
				join.add(ora+"Note: both tv and texts are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  tv:new,id,value"+reg+" -> Create a new text (or update) with the given id/value")
						.add( green+"  tv:update,id,value"+reg+" -> Update an existing text, do nothing if not found")
						.add("").add( cyan+" Get info"+reg)
						.add( green+"  tv:?"+reg+" -> Show this message")
						.add( green+"  tv:list"+reg+" -> Get a listing of currently stored texts")
						.add( green+"  tv:reqs"+reg+" -> Get a listing of the requests active");
				return join.toString();
			case "list":
				return String.join(html?"<br>":"\r\n",getRtvalsList(html,false,false,true));
			case "reqs":
				textRequest.forEach((rq, list) -> join.add(rq +" -> "+list.size()+" requesters"));
				return join.toString();
			/* Create or alter */
			case "new": case "create":
				if( setText(cmds[1],cmds[2]) )
					return cmds[1]+" stored with value "+cmds[2];
				return cmds[1]+" updated with value "+cmds[2];
			case "update":
				if( updateText(cmds[1],cmds[2]) )
					return cmds[1]+" updated with value "+cmds[2];
				return "No such text found: "+cmds[1];
			default:
				if( updateText( cmds[0],cmds[1] ) ){
					return cmds[0]+" updated with value "+cmds[1];
				}
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	public String replyToFlagsCmd( String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var cmds = request[1].split(",");
		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None yet");
		var fab = XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals");
		switch( cmds[0] ){
			case "?":
				join.add(ora+"Note: both fv and flags are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  fv:raise,id"+reg+" or "+green+"flags:set,id"+reg+" -> Raises the flag/Sets the bit, created if new")
						.add( green+"  fv:lower,id"+reg+" or "+green+"flags:clear,id"+reg+" -> Lowers the flag/Clears the bit, created if new")
						.add( green+"  fv:toggle,id"+reg+" -> Toggles the flag/bit, not created if new")
						.add( green+"  fv:addcmd,id,when:cmd"+reg+" -> Add a cmd with the given trigger to id, current triggers:raised,lowered")
						.add("").add( cyan+" Get info"+reg)
						.add( green+"  fv:list"+reg+" -> Give a listing of all current flags and their state");
				return join.toString();
			case "list":
				return getRtvalsList(html,false,true,false);
			case "new":
				if( cmds.length <2)
					return "Not enough arguments, need flags:new,id<,state> or fv:new,id<,state>";
				setFlagState(cmds[1],Tools.parseBool( cmds.length==3?cmds[2]:"false",false));
				fab.alterChild("flag","id",cmds[1]).attr("default",cmds.length==3?cmds[2]:"false").build();
				return "Flag created/updated "+cmds[1];
			case "raise": case "set":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:raise,id or flags:set,id";
				return raiseFlag(cmds[1])?"New flag raised":"Flag raised";
			case "lower": case "clear":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:lower,id or flags:clear,id";
				return lowerFlag(cmds[1])?"New flag raised":"Flag raised";
			case "toggle":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:toggle,id";

				if( !hasFlag(cmds[1]) )
					return "No such flag";

				if( isFlagUp(cmds[1])) {
					lowerFlag(cmds[1]);
					return "flag lowered";
				}
				raiseFlag(cmds[1]);
				return "Flag raised";
			case "addcmd":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:addcmd,id,when:cmd";
				var fv = flagVals.get(cmds[1]);
				if( fv==null)
					return "No such double: "+cmds[1];
				String cmd = request[1].substring(request[1].indexOf(":")+1);
				String when = cmds[2].substring(0,cmds[2].indexOf(":"));
				if( !fv.hasTriggeredCmds())
					fv.enableTriggeredCmds(dQueue);
				fv.addTriggeredCmd(when, cmd);
				XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals")
						.selectChildAsParent("flag","id",cmds[1])
						.ifPresent( f -> f.addChild("cmd",cmd).attr("when",when).build());
				return "Cmd added";
		}
		return "unknown command "+request[0]+":"+request[1];
	}
	public String replyToDoublesCmd(String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		double result;
		var fab = XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals");
		var join = new StringJoiner(html?"<br>":"\r\n");
		switch( cmds[0] ){
			case "?":
				join.add(ora+"Note: both dv and doubles are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  dv:new,id,value"+reg+" -> Create a new double (or update) with the given id/value")
						.add( green+"  dv:update,id,value"+reg+" -> Update an existing double, do nothing if not found")
						.add( green+"  dv:addcmd,id,when:cmd"+reg+" -> Add a cmd with the given trigger to id")
						.add( green+"  dv:alter,id,param:value"+reg+" -> Alter some of the params of id, currently scale and unit are possible")
						.add( green+"  dv:updategroup,groupid,value"+reg+" -> Update all DoubleVals that belong to the groupid with the given value")
						.add("").add( cyan+" Get info"+reg)
				  	    .add( green+"  dv:?"+reg+" -> Show this message" )
						.add( green+"  dv:list"+reg+" -> Get a listing of currently stored texts")
						.add( green+"  dv:reqs"+reg+" -> Get a listing of all the requests currently active");
				return join.toString();
			case "list": return getRtvalsList(html,true,false,false);
			case "new": case "create":
				result=0;
				if( cmds.length==3 ) {
					result = processExpression(cmds[2], true);
					if (Double.isNaN(result))
						return "Failed to process expression";
				}
				getOrAddDoubleVal(cmds[1]).updateValue(result);
				return "DoubleVal set to "+result;
			case "alter":
				if( cmds.length<3)
					return "Not enough arguments: doubles:alter,id,param:value";
				var vals = cmds[2].split(":");
				if( vals.length==1)
					return "Incorrect param:value pair: "+cmds[2];
				return getDoubleVal(cmds[1]).map( dv -> {
					if( vals[0].equals("scale")) {
						dv.fractionDigits(NumberUtils.toInt(vals[1]));
						fab.alterChild("real","id",cmds[1]).attr("scale",dv.scale()).build();
						return "Scaling for " +cmds[1]+" set to " + dv.scale() + " digits";
					}else if( vals[0].equals("unit")) {
						fab.alterChild("real","id",cmds[1]).attr("unit",vals[1]).build();
						return "Unit for "+cmds[1]+" set to "+vals[1];
					}else{
						return "Unknown param: "+vals[0];
					}
				}).orElse("No such DoubleVal");
			case "update":
				if( !hasDouble(cmds[1]) )
					return "No such id "+cmds[1];
				result = processExpression(cmds[2],false);
				if( Double.isNaN(result) )
					return "Unknown id(s) in the expression "+cmds[2];
				updateDouble(cmds[1],result);
				return cmds[1]+" updated to "+result;
			case "updategroup":
				int up = updateDoubleGroup(cmds[1], NumberUtils.createDouble(cmds[2]));
				if( up == 0)
					return "No double's updated";
				return "Updated "+up+" doubles";
			case "addcmd":
				if( cmds.length < 3)
					return "Not enough arguments, dv:addcmd,id,when:cmd";
				var dv = doubleVals.get(cmds[1]);
				if( dv==null)
					return "No such double: "+cmds[1];
				String cmd = request[1].substring(request[1].indexOf(":")+1);
				String when = cmds[2].substring(0,cmds[2].indexOf(":"));
				if( !dv.hasTriggeredCmds())
					dv.enableTriggeredCmds(dQueue);
				dv.addTriggeredCmd(when,cmd);
				XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals")
						.selectChildAsParent("real","id",cmds[1])
						.ifPresent( f -> f.addChild("cmd",cmd).attr("when",when).build());
				return "Cmd added";
			case "reqs":
				join.setEmptyValue("None yet");
				doubleVals.forEach((id, d) -> join.add(id +" -> "+d.getTargets()));
				return join.toString();
			default:
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	private double processExpression( String exp, boolean create ){
		double result=Double.NaN;

		if( create ) {
			exp = exp.replace("d:","D:");
			exp = exp.replace("double:","D:");
			exp = exp.replace("f:","F:");
			exp = exp.replace("flag:","F:");
		}

		exp = parseRTline(exp,"");
		exp=exp.replace("true","1");
		exp=exp.replace("false","0");

		exp = simpleParseRT(exp,create?"create":"");
		if( exp.isEmpty())
			return result;

		var parts = MathUtils.extractParts(exp);
		if( parts.size()==1 ){
			if( !NumberUtils.isCreatable(exp)) {
				if( hasDouble(exp) || create ) {
					result = getDouble(exp, 0, create);
				}else{
					return Double.NaN;
				}
			}else{
				result = NumberUtils.createDouble(exp);
			}
		}else if (parts.size()==3){
			if( !NumberUtils.isCreatable(parts.get(0))) {
				if( hasDouble(parts.get(0)) || create ) {
					parts.set(0, "" + getDouble(parts.get(0), 0, create));
				}else{
					return Double.NaN;
				}
			}
			if( !NumberUtils.isCreatable(parts.get(2))) {
				if( hasDouble(parts.get(2)) || create ) {
					parts.set(2, "" + getDouble(parts.get(2), 0, create));
				}else{
					return Double.NaN;
				}
			}
			result = Objects.requireNonNull(MathUtils.decodeDoublesOp(parts.get(0), parts.get(2), parts.get(1), 0)).apply(new Double[]{});
		}else{
			try {
				result = MathUtils.simpleCalculation(exp, Double.NaN, false);
			}catch(IndexOutOfBoundsException e){
				Logger.error("Index out of bounds while processing "+exp);
				return Double.NaN;
			}
		}
		return result;
	}
	public String replyToRtvalsCmd( String[] request, Writable wr, boolean html ){

		if( request[1].isEmpty())
			return getRtvalsList(html,true,true,true);

		String[] cmds = request[1].split(",");

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None Yet");

		if( cmds.length==1 ){
			switch( cmds[0]){
				case "?":
					join.add( cyan+" Interact with XML"+reg)
							.add(green+"  rtvals:store"+reg+" -> Store all rtvals to XML")
							.add(green+"  rtvals:reload"+reg+" -> Reload all rtvals from XML")
							.add("").add( cyan+" Get info"+reg)
							.add(green+"  rtvals:?"+reg+" -> Get this message")
							.add(green+"  rtvals"+reg+" -> Get a listing of all rtvals")
							.add(green+"  rtvals:groups"+reg+" -> Get a listing of all the available groups")
							.add(green+"  rtvals:group,groupid"+reg+" -> Get a listing of all rtvals belonging to the group")
							.add(green+"  rtvals:name,valname"+reg+" -> Get a listing of all rtvals with the given valname (independent of group)")
							.add(green+"  rtvals:resetgroup,groupid"+reg+" -> Reset the integers and real/double rtvals in the given group");
					return join.toString();
				case "store": return  storeValsInXml(false)?"Written in xml":"Failed to write to xml";
				case "reload":
					readFromXML( XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals") );
					return "Reloaded rtvals";
			}
		}else if(cmds.length==2){
			switch(cmds[0]){
				case "group":  return getRTValsGroupList(cmds[1],true,true,true,html);
				case "groups":
					String groups = String.join(html?"<br>":"\r\n",getGroups());
					return groups.isEmpty()?"No groups yet":groups;
				case "name"	:  return getAllIDsList(cmds[1],html);
				case "resetgroup":
					int a = updateIntegerGroup(cmds[1],-999);
					a += updateDoubleGroup(cmds[1],Double.NaN);
					return "Reset "+a+" vals.";
			}
		}
		return "unknown command: "+request[0]+":"+request[1];
	}
	/**
	 * Get a listing of all stored variables that belong to a certain group
	 * @param group The group they should belong to
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getRTValsGroupList(String group, boolean showDoubles, boolean showFlags, boolean showTexts, boolean html) {
		String eol = html ? "<br>" : "\r\n";
		String title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_YELLOW;
		String space = html ? "  " : "  ";

		StringJoiner join = new StringJoiner(eol, title + eol, "");
		join.setEmptyValue("None yet");
		if (showDoubles){
			doubleVals.values().stream().filter(dv -> dv.group().equalsIgnoreCase(group))
					.sorted((dv1, dv2) -> {
						if (dv1.order() != dv2.order()) {
							return Integer.compare(dv1.order(), dv2.order());
						} else {
							return dv1.name().compareTo(dv2.name());
						}
					})
					.map(dv -> space + dv.name() + " : " + dv) //Change it to strings
					.forEach(join::add); // Then add the sorted strings
		}
		if( showTexts ) {
			texts.entrySet().stream().filter(ent -> ent.getKey().startsWith(group + "_"))
					.map(ent -> space + ent.getKey().split("_")[1] + " : " + ent.getValue())
					.sorted().forEach(join::add);
		}
		if( showFlags ) {
			flagVals.values().stream().filter(fv -> fv.group().equalsIgnoreCase(group))
					.map(v -> space + v.name() + " : " + v) //Change it to strings
					.sorted().forEach(join::add); // Then add the sorted the strings
		}
		return join.toString();
	}
	/**
	 * Get a listing of all stored variables that have the given name
	 * @param name The name of the variable or the string the name starts with if ending it with *
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getAllIDsList(String name, boolean html) {
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Name: "+name+"</b>":TelnetCodes.TEXT_CYAN+"Name: "+name+TelnetCodes.TEXT_YELLOW;
		String space = html?"  ":"  ";

		StringJoiner join = new StringJoiner(eol,title+eol,"");
		join.setEmptyValue("No matches found");

		String regex;
		if( name.contains("*")&& name.contains(".*")) {
			regex = name.replace("*", ".*");
		}else{
			regex=name;
		}
		doubleVals.values().stream().filter( dv -> dv.name().matches(regex))
				.forEach(dv -> join.add(space+(dv.group().isEmpty()?"":dv.group()+" -> ")+dv.name()+" : "+dv));
		texts.entrySet().stream().filter(ent -> ent.getKey().matches(regex))
				.forEach( ent -> join.add( space+ent.getKey().replace("_","->")+" : "+ent.getValue()) );
		flagVals.values().stream().filter(fv -> fv.name().matches(regex))
				.forEach(fv -> join.add(space+(fv.group().isEmpty()?"":fv.group()+" -> ")+fv.name()+" : "+fv));
		return join.toString();
	}

	/**
	 * Get the full listing of all doubles,flags and text, so both grouped and ungrouped
	 * @param html If true will use html newline etc
	 * @return The listing
	 */
	public String getRtvalsList(boolean html, boolean showDoubles, boolean showFlags, boolean showTexts){
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Grouped</b>":TelnetCodes.TEXT_CYAN+"Grouped"+TelnetCodes.TEXT_YELLOW;
		String space = html?"  ":"  ";
		StringJoiner join = new StringJoiner(eol,getGroups().isEmpty()?"":title+eol,"");
		join.setEmptyValue("None yet");

		// Find & add the groups
		for( var group : getGroups() ){
			var res = getRTValsGroupList(group,showDoubles,showFlags,showTexts,html);
			if( !res.isEmpty() && !res.equalsIgnoreCase("none yet"))
				join.add(res).add("");
		}

		// Add the not grouped ones
		boolean ngDoubles = doubleVals.values().stream().anyMatch( dv -> dv.group().isEmpty())&&showDoubles;
		boolean ngTexts = texts.keySet().stream().anyMatch(k -> k.contains("_"))&&showTexts;
		boolean ngFlags = flagVals.values().stream().anyMatch( fv -> fv.group().isEmpty())&&showFlags;

		if( ngDoubles || ngTexts || ngFlags) {
			join.add("");
			join.add(html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_YELLOW);

			if (ngDoubles) {
				join.add(html ? "<b>Doubles</b>" : TelnetCodes.TEXT_BLUE + "Doubles" + TelnetCodes.TEXT_YELLOW);
				doubleVals.values().stream().filter(dv -> dv.group().isEmpty())
						.map(dv->space + dv.name() + " : " + dv).sorted().forEach(join::add);
			}
			if (ngTexts) {
				join.add("");
				join.add(html ? "<b>Texts</b>" : TelnetCodes.TEXT_BLUE + "Texts" + TelnetCodes.TEXT_YELLOW);
				texts.entrySet().stream().filter(e -> !e.getKey().contains("_"))
						.map( e -> space + e.getKey() + " : " + e.getValue()).sorted().forEach(join::add);
			}
			if (ngFlags) {
				join.add("");
				join.add(html ? "<b>Flags</b>" : TelnetCodes.TEXT_BLUE + "Flags" + TelnetCodes.TEXT_YELLOW);
				flagVals.values().stream().filter(fv -> fv.group().isEmpty())
						.map(fv->space + fv.name() + " : " + fv).sorted().forEach(join::add);
			}
		}
		return join.toString();
	}

	/**
	 * Get a list of all the groups that exist in the rtvals
	 * @return The list of the groups
	 */
	public List<String> getGroups(){
		var groups = doubleVals.values().stream()
				.map(DoubleVal::group)
				.filter(group -> !group.isEmpty()).distinct().collect(Collectors.toList());
		texts.keySet().stream()
						.filter( k -> k.contains("_"))
						.map( k -> k.split("_")[0] )
						.distinct()
						.filter( g -> !groups.contains(g))
						.forEach(groups::add);
		flagVals.values().stream()
						.map(FlagVal::group)
						.filter(group -> !group.isEmpty() )
						.distinct()
						.filter( g -> !groups.contains(g))
						.forEach(groups::add);

		Collections.sort(groups);
		return groups;
	}
	/* ******************************** I S S U E P O O L ********************************************************** */

	/**
	 * Get a list of the id's of the active issues
	 * @return A list of the active issues id's
	 */
	public ArrayList<String> getActiveIssues(){
		return issuePool.getActives();
	}

	/**
	 * Get the IssuePool object
	 * @return The IssuePool object
	 */
	public IssuePool getIssuePool(){
		return issuePool;
	}

	/* ******************************** W A Y P O I N T S *********************************************************** */
	/**
	 * Enable tracking/storing waypoints
	 * @param scheduler Scheduler to use for the tracking
	 * @return The Waypoints object
	 */
	public Waypoints enableWaypoints(ScheduledExecutorService scheduler){
		waypoints = new Waypoints(settingsPath,scheduler,this,dQueue);
		return waypoints;
	}
	/**
	 * Get the waypoints object in an optional, which is empty if it doesn't exist yet
	 * @return The optional that could contain the waypoints object
	 */
	public Optional<Waypoints> getWaypoints(){
		return Optional.ofNullable(waypoints);
	}
}