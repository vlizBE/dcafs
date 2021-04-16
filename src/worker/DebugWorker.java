package worker;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.database.DatabaseManager;
import util.tools.TimeTools;
import util.xml.XMLtools;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;

/**
 * This is a worker that simulates receiving data from devices. In order to do
 * that it reads the data from a previously made raw file, puts each line in the
 * correct format and writes it in the main processing queue (just like the read
 * threads do). Main difference is that the speeds at which data is inserted can
 * be regulated.
 * 
 * @author Michiel T'Jampens @ VLIZ
 */
public class DebugWorker implements Runnable {

	private BlockingQueue<Datagram> dQueue; // to simulate writing to main processing queue
	private BlockingQueue<String> sqlQueue;
	private boolean loop = false; // whether or not to loop the read file (read till end and then start over)
	private boolean goOn = true; // the thread is a endless loop, this gives a clean way of stopping it
	private boolean logRaw = false;
	private final ArrayList<Path> logs = new ArrayList<>(); // if more then one file needs to be read, the paths are
															// stored in here
	private boolean sleep = true; // True means the program will sleep when it should, so data will be send based
									// on the divider
	private SourceType srcType = SourceType.RAW;
	private final ArrayList<String[]> data = new ArrayList<>(); // buffer to hold the data read from the file

	Scanner sc;
	Path filesPath;

	boolean debugWS = false;
	boolean debugDB = false;
	boolean debugEmails = false;
	private String label="";
	DatabaseManager dbm;
	public enum SourceType { // different types of GPS fixes

		RAW, NMEA, GAPS, FILTER
	}

	public DebugWorker(BlockingQueue<Datagram> dQueue,
			DatabaseManager dbm) {
		this.dQueue = dQueue;
		this.dbm = dbm;
	}

	public void setDataQueue(BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
	}
	public static boolean inXML(Document xml) {
		return XMLtools.getFirstElementByTag(xml, "debug") != null;
	}

	public void readSettingsFromXML(Document xml) {

		Element dbg = XMLtools.getFirstElementByTag(xml, "debug");

		this.filesPath = XMLtools.getXMLparent(xml).resolve(XMLtools.getChildValueByTag(dbg, "inputfile", "")); // Raw files folder
		this.logRaw = XMLtools.getChildValueByTag(dbg, "rawlog", "no").equals("yes"); // Send WS messages or not
		Logger.info("Path to search:" + filesPath.toString());
		this.sleep = XMLtools.getChildValueByTag(dbg, "realtime", "no").equals("yes"); // Work at realtime or as fast as
																						// possible
		Logger.info("DebugWorker working"
				+ (sleep ? " in realtime" : " as fast as possible"));
		switch (XMLtools.getChildValueByTag(dbg, "sourcetype", "raw")) { // Which kind of raw files, das or nmea
		case "nmea":
			srcType = SourceType.NMEA;
			break;
		case "gaps":
			srcType = SourceType.GAPS;
			label="filter:ptsag";
			break;
		case "filter":
			srcType = SourceType.FILTER;
			label = XMLtools.getChildValueByTag(dbg,"label","");
			break;
		case "raw":
		default:
			srcType = SourceType.RAW;
			break;
		}
		debugDB = XMLtools.getChildValueByTag(dbg, "dbwrite", "no").equals("yes"); // Write to DB or not
		debugWS = XMLtools.getChildValueByTag(dbg, "wswrite", "no").equals("yes"); // Send WS messages or not
		debugEmails = XMLtools.getChildValueByTag(dbg, "emails", "no").equals("yes"); // Send emails or not


		Logger.info("Debug sourcetype " + srcType);
	}

	public void enableRawLogging() {
		this.logRaw = true;
	}

	public boolean doDB() {
		return debugDB;
	}

	public boolean doEmails() {
		return debugEmails;
	}

	public boolean doWebsocket() {
		return debugWS;
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
	public void setLooping(boolean loop) {
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


	@Override
	public synchronized void run() {
		Logger.info("DebugWorker Started");
		int readLines = 0;
		findFiles();
		
		if (loop) {
			// loop so add the first file to the end
			logs.add(logs.get(0));
		}
		if (logs.isEmpty()){
			Logger.warn("No files found!");
			return;
		}
		if( dQueue == null ){
			Logger.error("No valid dQueue");
			return;
		}
		Logger.info("Reading: " + logs.get(0).toString());
		try {
			sc = new Scanner(logs.remove(0).toFile(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Logger.error(e);
			return;
		}
		
		long calStart = -1;
		long start = System.currentTimeMillis();
		int full = 0;
		
		Logger.info("Started processing it...");
		while (goOn) {
			if (sc.hasNext()) {
				if (dQueue.size() > 2500 ) {
					full++;
					while (dQueue.size() > 500);
				}
				if( dbm.getTotalQueryCount() > 500){
					full++;

					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}

				}
				String r = sc.nextLine();
				readLines++;
				if( readLines%10000==0)
					Logger.info("Read lines: "+readLines);
				String[] line = r.split("\t");
				
				switch( srcType ){
					case RAW:
						if( line.length <4 )
							break;

						line[0] = line[0].replace("[", ""); // remove the [
						line[0] = line[0].replace("]", "");// and remove the ]

						LocalDateTime dt = TimeTools.parseDateTime(line[0], "yyyy-MM-dd HH:mm:ss.SSS");

						try {
							int prio = Integer.parseInt(line[1]);
							line[3] = r.substring(r.indexOf(line[2]) + line[2].length() + 1);
							String[] labid = line[2].split("\\|");
							String label = line[2].split("\\|")[0];
							if( !this.label.isEmpty())
								labid[0]=this.label;
							Datagram d = new Datagram(line[3], prio, labid[0], 0);
							d.setOriginID( labid.length==2?labid[1]:"");
							d.raw = line[3].getBytes();
							d.setTimestamp(dt.toInstant(ZoneOffset.UTC).toEpochMilli());
							//Logger.info("Adding to queue: "+d.getMessage());
							dQueue.add(d);

							if (logRaw) {
								Logger.tag("RAW").warn(prio + "\t" + line[2] + "\t" + line[3]);
							}							
						} catch (java.lang.OutOfMemoryError e) { // if an out of memory error occurs, output the buffer size
																	// to see which one caused it
							Logger.info("Out of Memory: Data size: " + data.size() + "\tDqueue size: " + dQueue.size());
						}						
					break;
					case NMEA:
						dQueue.add(new Datagram(r, 1, "nmea", 1));
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
							dQueue.add(new Datagram(r.substring(3), 1, label, 1));
						}else{
							dQueue.add(new Datagram(r, 1, label, 1));
						}
					break;
				}
			} else {
				Logger.info("End of file reached");
				sc.close();
				if ( logs.isEmpty()) {
					goOn=false;
					break;
				}
				try {
					Logger.info("Reading: " + logs.get(0).toString());
					if (loop) { // loop so add the first file to the end
						Path p = logs.get(0);
						logs.add(p);
					}
					sc = new Scanner(logs.remove(0).toFile());
				} catch (FileNotFoundException e) {
					goOn = false;
					Logger.error("Error Reading Raw, Stopping!");
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
