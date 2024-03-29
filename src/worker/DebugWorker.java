package worker;

import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import util.database.DatabaseManager;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.concurrent.*;

import io.Readable;
/**
 * This is a worker that simulates receiving data from devices. In order to do
 * that it reads the data from a previously made raw file, puts each line in the
 * correct format and writes it in the main processing queue (just like the read
 * threads do). Main difference is that the speeds at which data is inserted can
 * be regulated.
 * 
 * @author Michiel T'Jampens @ VLIZ
 */
public class DebugWorker implements Readable {

	private BlockingQueue<Datagram> dQueue; // to simulate writing to main processing queue
	private BlockingQueue<String> sqlQueue;
	private int loop = 1; // whether or not to loop the read file (read till end and then start over)
	private boolean goOn = true; // the thread is a endless loop, this gives a clean way of stopping it
	private boolean logRaw = false;
	private final ArrayList<Path> logs = new ArrayList<>(); // if more then one file needs to be read, the paths are
															// stored in here
	private boolean sleep = true; // True means the program will sleep when it should, so data will be send based
									// on the divider
	private SourceType srcType = SourceType.RAW;

	BufferedReader br;
	Path filesPath;

	boolean debugWS = false;
	boolean debugDB = false;
	boolean debugEmails = false;
	private String label="";
	DatabaseManager dbm;

	HashMap<String,ArrayList<Writable>> targets = new HashMap<>();

	long interval =0;
	int steps=1;

	ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	ScheduledFuture<?> stepFuture;
	int filePos=0;

	@Override
	public String getID() {
		return "debugworker";
	}

	@Override
	public boolean addTarget(Writable target, String src) {
		var t = targets.get(src);
		if( t==null) {
			t = new ArrayList<Writable>();
			targets.put(src,t);
		}
		if( !t.contains(target)) {
			t.add(target);
			return true;
		}
		return false;
	}

	@Override
	public boolean isInvalid() {
		return false;
	}

	public enum SourceType { // different types of GPS fixes
		RAW, NMEA, GAPS, FILTER,RANDOM ;
	}

	public DebugWorker(BlockingQueue<Datagram> dQueue,
			DatabaseManager dbm, Document xml) {
		this.dQueue = dQueue;
		dQueue.add( Datagram.build().label("readable").readable(this) ); // register debugworker as a readable in the baseworker
		this.dbm = dbm;
		readSettingsFromXML(xml);

		prepareWork();
	}
	public void start(){
		if( interval==0){
			executor.execute(new Rush());
		}else{
			Logger.info("Starting stepped at interval: "+interval);
			stepFuture = executor.scheduleAtFixedRate( new Stepper(),0,interval, TimeUnit.MILLISECONDS);
		}
	}
	public static boolean inXML(Document xml) {
		return XMLtools.getFirstElementByTag(xml, "debug").isPresent();
	}

	public static void addBlank( XMLfab fab){
		fab.alterChild("debug").down()
				.addChild("inputfile","rework")
				.addChild("rawlog","no")
				.addChild("sourcetype","raw")
				.addChild("dbwrite","no")
				.addChild("emails","no")
				.addChild("loop","1")
				.addChild("interval","0s")
				.addChild("steps","1")
				.build();
	}
	public void readSettingsFromXML(Document xml) {

		var dbgOpt = XMLtools.getFirstElementByTag(xml, "debug");

		if(dbgOpt.isEmpty())
			return;

		var dbg=dbgOpt.get();
		var pathOpt = XMLtools.getXMLparent(xml);
		if( pathOpt.isEmpty())
			return;
		this.filesPath = pathOpt.get().resolve(XMLtools.getChildStringValueByTag(dbg, "inputfile", "")); // Raw files folder
		this.logRaw = XMLtools.getChildStringValueByTag(dbg, "rawlog", "no").equals("yes"); // Send WS messages or not
		Logger.info("Path to search:" + filesPath.toString());
		this.sleep = XMLtools.getChildStringValueByTag(dbg, "realtime", "no").equals("yes"); // Work at realtime or as fast as
																						// possible
		Logger.info("DebugWorker working"
				+ (sleep ? " in realtime" : " as fast as possible"));
		switch (XMLtools.getChildStringValueByTag(dbg, "sourcetype", "raw")) { // Which kind of raw files, das or nmea
		case "nmea":
			srcType = SourceType.NMEA;
			break;
		case "gaps":
			srcType = SourceType.GAPS;
			label="filter:ptsag";
			break;
		case "filter":
			srcType = SourceType.FILTER;
			label = XMLtools.getChildStringValueByTag(dbg,"label","");
			break;
		case "raw":
			srcType = SourceType.RAW;
			break;
		default:
			srcType = SourceType.RANDOM;
			break;
		}
		debugDB = XMLtools.getChildStringValueByTag(dbg, "dbwrite", "no").equals("yes"); // Write to DB or not
		debugWS = XMLtools.getChildStringValueByTag(dbg, "wswrite", "no").equals("yes"); // Send WS messages or not
		debugEmails = XMLtools.getChildStringValueByTag(dbg, "emails", "no").equals("yes"); // Send emails or not
		loop = XMLtools.getChildIntValueByTag(dbg,"loop",1);
		interval = TimeTools.parsePeriodStringToMillis(XMLtools.getChildStringValueByTag(dbg,"interval","0s"));
		steps = XMLtools.getChildIntValueByTag(dbg,"step",1);
		Logger.info("Debug sourcetype " + srcType);
	}

	public boolean doEmails() {
		return debugEmails;
	}

	private boolean prepareWork(){
		findFiles();

		if (logs.isEmpty()){
			Logger.warn("No files found!");
			return false;
		}
		if( dQueue == null ){
			Logger.error("No valid dQueue");
			return false;
		}
		Logger.info("Reading: " + logs.get(0).toString());
		try {
			br = new BufferedReader(
					new InputStreamReader(new FileInputStream(logs.get(0).toFile()), StandardCharsets.UTF_8));
			filePos=1;
		} catch (IOException e) {
			Logger.error(e);
			return false;
		}

		return true;
	}
	public void findFiles() {
		if (Files.isDirectory(filesPath)) { // If the settings.xml gave a path instead of a filename
			Logger.info("Specified directory");
			String extension = "*.log";
			switch (srcType) {
			case GAPS:
				extension = "*.dat";
				break;
			case NMEA:
				extension = "*.txt";
				break;
			case RAW:
				extension = "*.log";
				break;
			case FILTER:
				extension = "*.log";
				break;
			case RANDOM:
				extension = "*.*";
				break;
			default:
				break;
			}
			Logger.info("DebugWorker.findFiles\tLooking for files with extension: " + extension);
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(filesPath, extension)) {
				for (Path d : ds) {// Iterate over the paths in the directory and print filenames
					Logger.info("DebugWorker.findFiles\tFound:" + d.toString());
					logs.add(d); // add the path to the buffer
				}
			} catch (IOException e) {
				Logger.error(e);
			}

		} else {
			Logger.info("Single file debug");
			if (Files.exists(filesPath)) {
				Logger.info( "Path: " + filesPath.normalize());
				logs.add(filesPath.normalize());
			} else {
				Logger.info("File doesn't exist: " + filesPath.toString());
			}
		}
	}

	public void setSourceType(SourceType type) {
		this.srcType = type;
	}

	/**
	 * Enable/Disable of looping the raw file
	 * 
	 * @param loop True means the same file get read over and over and over and
	 *             over...
	 */
	public void setLoopingCount(int loop) {
		this.loop = loop;
	}

	/**
	 * Enable/disable sleeping (thread waiting to add more lines to the processing
	 * queue
	 * 
	 * @param sleep True means the thread will sleep based on the divider
	 */
	public void doSleep(boolean sleep) {
		this.sleep = sleep;
	}

	public class Stepper implements Runnable{
		@Override
		public synchronized void run() {
			try {
				for( int a=0;a<steps;a++) {
					String r = br.readLine();
					if( r == null || br==null ){
						Logger.info("End of file reached, reading next one");
						try {
							if( filePos == logs.size() ){
								loop--;
								if( loop == 0){ // stop
									stepFuture.cancel(true);
								}
								filePos=0;
							}
							Logger.info("Reading "+logs.get(filePos));
							br = new BufferedReader(
									new InputStreamReader(new FileInputStream(logs.get(filePos).toFile()), StandardCharsets.UTF_8));
							filePos++;
							r = br.readLine();
						} catch (IOException e) {
							Logger.error(e);
							stepFuture.cancel(true);
							return;
						}
					}
					if (r != null) {
						final String send;
						String origin="";
						switch (srcType) {
							case RAW:
								var d = rawLineToDatagram(r);
								if (d != null) {
									dQueue.add(d);
									origin = d.getOriginID();
									String[] line = r.split("\t");
									StringJoiner join = new StringJoiner("\t");
									if( line.length>3){
										for( int x=3;x<line.length;x++)
											join.add(line[x]);
										line[3]=join.toString();
									}
									if (logRaw) {
										Logger.tag("RAW").warn(d.getPriority() + "\t" + line[2] + "\t" + line[3]);
									}
									send = line[3];
								} else {
									send = "";
								}
								break;
							case NMEA:
								dQueue.add( Datagram.build(r).label("nmea").timeOffset(1));
								send = r;
								break;
							default:
								send = r;
								break;
						}

						if (!send.isEmpty() && !targets.isEmpty()) {
							var t = targets.get(origin);
							if( t==null)
								t=targets.get("*");

							if( t != null ){
								try {
									t.forEach( wr -> wr.writeLine(send));
									t.removeIf( wr -> !wr.isConnectionValid() );
								}catch(Exception e){
									Logger.error(e);
								}
							}
						}
					}else{
						Logger.warn("End of debug file reached? "+logs.get(filePos--));
					}
				}
			} catch (IOException e) {
				Logger.error(e);
			}
		}
	}
	public Datagram rawLineToDatagram(String line){
		String[] split = line.split("\t");
		if( split.length <4 )
			return null;

		split[0] = split[0].replace("[", ""); // remove the [
		split[0] = split[0].replace("]", "");// and remove the ]

		LocalDateTime dt = TimeTools.parseDateTime(split[0], "yyyy-MM-dd HH:mm:ss.SSS");

		try {
			int prio = Integer.parseInt(split[1]);
			split[3] = line.substring(line.indexOf(split[2]) + split[2].length() + 1);
			String[] labid = split[2].split("\\|");
			if( !label.isEmpty())
				labid[0]=label;

			return Datagram.build(split[3])
					       .label(labid[0])
					       .priority(prio)
					       .origin(labid.length==2?labid[1]:"debugworker")
					       .timestamp(dt.toInstant(ZoneOffset.UTC).toEpochMilli());
		} catch (java.lang.OutOfMemoryError e) { // if an out of memory error occurs, output the buffer size
			// to see which one caused it
			Logger.info("Out of Memory: Dqueue size: " + dQueue.size());
		}
		return null;
	}
	public class Rush implements Runnable{

		@Override
		public synchronized void run() {
			Logger.info("DebugWorker Started");

			int readLines = 0;


			Logger.info("Reading: " + logs.get(0).toString());
			try {
				br = new BufferedReader(
						new InputStreamReader(new FileInputStream(logs.get(0).toFile()), StandardCharsets.UTF_8));
				filePos++;
			} catch (IOException e) {
				Logger.error(e);
				return;
			}

			long calStart = -1;
			long start = System.currentTimeMillis();
			int full = 0;
			int maxQueries = Math.max(dbm.getTotalMaxCount()*5,5000);
			Logger.info("Started processing it... (with "+maxQueries+" query buffer limit)");

			while (goOn) {
				String r=null;
				try {
					r=br.readLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
				if (r!=null) {
					if (dQueue.size() > 2500 ) {
						full++;
						while (dQueue.size() > 500);
					}
					if( dbm.getTotalQueryCount() > maxQueries){
						full++;

						try {
							Thread.sleep(1);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}

					readLines++;
					String[] line = r.split("\t");

					switch( srcType ){
						case RAW:
								var d = rawLineToDatagram(r);
								if( d !=null ){
									dQueue.add(d);
									if (logRaw) {
										Logger.tag("RAW").warn(d.getPriority() + "\t" + line[2] + "\t" + line[3]);
									}
								}
							break;
						case NMEA:
							dQueue.add( Datagram.build(r).label("nmea").timeOffset(1));
							break;
						case GAPS:case FILTER:
							if( r.startsWith("<<") ) {
								if (r.contains("$HEHDT,") && sleep) {// Sleep a second between messages
									try {
										wait(1000);
									} catch (InterruptedException e1) {
										Logger.error(e1);
										Thread.currentThread().interrupt();
									}
								}
								dQueue.add( Datagram.build(r.substring(3)).label(label).timeOffset(1));
							}else{
								dQueue.add( Datagram.build(r).label(label).timeOffset(1));
							}
							break;
					}
				} else {
					Logger.info("End of file reached");
					try {
						br.close();
						String suffix="";
						if( filePos==logs.size()){
							if( loop != 0 ){
								filePos=0;
							}
							if(loop==0){
								goOn=false;
								continue;
							}
							if( loop > 0){
								loop--;
								Logger.info("Another loop, after this "+loop+" to go.");
							}else{
								Logger.info("Next run of endless loop");
							}
						}

						Logger.info("Reading: " + logs.get(filePos).toString());
						br = new BufferedReader(
								new InputStreamReader(new FileInputStream(logs.get(filePos).toFile()), StandardCharsets.UTF_8));
						filePos++;
					} catch (IOException e) {
						Logger.error(e);
						return;
					}
				}
			}

			// just some calculations to give some performance feedback
			long end = System.currentTimeMillis() - start;

			if (end != 0) {
				BigDecimal d = new BigDecimal(end);
				BigDecimal l = new BigDecimal(String.valueOf(readLines));
				BigDecimal e = l.divide(d, 3, RoundingMode.HALF_UP);
				e = e.setScale(0,RoundingMode.HALF_UP);

				Logger.info("Stopped after reading "+readLines+" lines in "+d+"ms or "+e+"k messages/sec and "+full+" buffer waits.");
				//System.exit(0);
			}
		}
	}

}
