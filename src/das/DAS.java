package das;

import com.email.EmailSending;
import com.email.EmailWorker;
import com.hardware.i2c.I2CWorker;
import com.mqtt.MqttWorker;
import com.sms.DigiWorker;
import com.stream.StreamPool;
import com.stream.Writable;
import com.stream.collector.MathCollector;
import com.stream.tcp.TcpServer;
import com.telnet.TelnetCodes;
import com.telnet.TelnetServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import org.tinylog.configuration.Configuration;
import org.tinylog.provider.ProviderRegistry;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.DeadThreadListener;
import util.database.*;
import util.gis.Waypoints;
import util.task.TaskManager;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.*;

import javax.swing.text.html.Option;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

public class DAS implements DeadThreadListener {

    private static final String version = "0.9.4";

    // Last date that changes were made
    Path settingsFile = Path.of("settings.xml");
    String workPath=Path.of("").toString();

    private Document xml;

    LocalDateTime bootup = LocalDateTime.now(); // Store timestamp at boot up to calculate uptime

    // Workers
    private EmailWorker emailWorker;
    private BaseWorker dataWorker;
    private DigiWorker digiWorker;
    private DebugWorker debugWorker;

    // IO
    StreamPool streampool;
    TcpServer trans;

    // Storage
    RealtimeValues rtvals;
    BaseReq baseReq;

    // Telnet
    TelnetServer telnet;

    // Other
    HashMap<String, TaskManager> taskManagers = new HashMap<>();
    Map<String, MqttWorker> mqttWorkers = new HashMap<>();

    IssueCollector issues;

    // Hardware
    I2CWorker i2cWorker;

    private boolean debug = false;
    private boolean log = false;
    private boolean bootOK = false; // Flag to show if booting went ok
    String sdReason = "Unwanted shutdown."; // Reason for shutdown of das, default is unwanted

    BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();
    boolean rebootOnShutDown = false;

    /* Threading */
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);// scheduler for the request data action
    EventLoopGroup nettyGroup = new NioEventLoopGroup();

    /* Database */
    private final DatabaseManager dbManager = new DatabaseManager();

    public DAS() {

        try {
            Path p = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!p.toString().endsWith(".jar")) { //meaning from ide
                p = p.getParent();
            }
            workPath = p.getParent().toString();

            if( workPath.matches(".*[lib]")) { // Meaning used as a lib
                workPath = Path.of(workPath).getParent().toString();
            }

            // Re set the paths for the file writers to use the same path as the rest of the program
            // Do note that you can't do a get first...
            Path logs = Path.of(workPath).resolve("logs");
            Configuration.set("writer2.file", logs.resolve("info.log").toString());
            Configuration.set("writer3.file", logs+File.separator+"errors_{date:yyMMdd}.log");
            Configuration.set("writer6.file", logs.resolve("taskmanager.log").toString());

            Path raw = Path.of(workPath).resolve("raw");
            Configuration.set("writer4.file", raw+File.separator+"{date:yyyy-MM}/{date:yyyy-MM-dd}_RAW_{count}.log");
            Configuration.set("writer5.file", raw+File.separator+"{date:yyyy-MM}/SQL_queries.log");

            settingsFile = Path.of(workPath, "settings.xml");
        } catch (URISyntaxException e) {
            Logger.error(e);
        }


        if (Files.notExists(settingsFile)) {
            Logger.warn("No Settings.xml file found, creating new one. Searched path: "
                    + settingsFile.toFile().getAbsolutePath());
            this.createXML();
        } else {
            Logger.info("Settings.xml found at " + settingsFile.toFile().getAbsolutePath());
        }

        xml = XMLtools.readXML(settingsFile);

        if (xml == null) {
            Logger.error("No Settings.xml file found, aborting. Searched path: " + settingsFile.toString());
        } else {
            bootOK = true;

            Element settings = XMLtools.getFirstElementByTag(xml, "settings");

            if (settings != null) {
                debug = XMLtools.getChildValueByTag(settings, "mode", "normal").equals("debug");
                log = XMLtools.getChildValueByTag(settings, "mode", "normal").equals("log");
                if (debug) {
                    Logger.info("Program booting in DEBUG mode");
                } else {
                    Logger.info("Program booting in NORMAL mode");
                }
            }

            issues = new IssueCollector();

            if( issues.hasAlarms())
                taskManagers.put("alarms",issues.alarms); // Make that manager available through general interface

            rtvals = new RealtimeValues(issues);
            readDatabasesFromXML();

            baseReq = new BaseReq(rtvals, issues, workPath);
            baseReq.setSQLitesManager(dbManager);

            /* TransServer */
            if (TcpServer.inXML(xml)) {
                this.addTransServer();
            }
            /* Base Worker */
            addBaseWorker();

            /* Generics */
            loadGenerics(true);

            /* ValMaps */
            loadValMaps(true);

            /* StreamPool */
            addStreamPool();

            /* EmailWorker */
            if (XMLtools.hasElementByTag(xml, "email") ) {
                this.addEmailWorker();
            }
            /* DigiWorker */
            if (XMLtools.hasElementByTag(xml, "digi") ) {
                this.addDigiWorker();
            }
            /* DebugWorker */
            if (DebugWorker.inXML(xml)) {
                this.addDebugWorker();
            }

            /* MQTT worker */
            Element mqtt;
            if ((mqtt = XMLtools.getFirstElementByTag(xml, "mqtt")) != null) {
                for (Element broker : XMLtools.getChildElements(mqtt, "broker")) {
                    String id = XMLtools.getStringAttribute(broker, "id", "general");
                    Logger.info("Adding MQTT broker called " + id);
                    mqttWorkers.put(id, new MqttWorker(broker, dQueue));
                    rtvals.addMQTTworker(id, mqttWorkers.get(id));
                }
            }
            /* I2C */
            if (XMLtools.hasElementByTag(xml, "i2c") ) {
                this.addI2CWorker();
            }

            /* Telnet */
            this.addTelnetServer();

            /* TaskManager */
            loadTaskManagersFromXML(xml);

            /* Waypoints */
            if (Waypoints.inXML(xml)) {
                rtvals.getWaypoints().loadWaypointsFromXML(xml, true);
            } else {
                rtvals.getWaypoints().setXML(xml);
            }

            /* Math Collectors */
            MathCollector.createFromXml( XMLfab.getRootChildren(xml,"das","maths","*") ).forEach(
                mc ->
                {
                    rtvals.addMathCollector(mc);
                    dQueue.add( new Datagram(mc,"system",mc.getSource()) ); // request the data
                }
            );

        }
        baseReq.setDAS(this);
        this.attachShutDownHook();
    }
    public DAS(boolean start) {
        this();
        if( start )
            startAll();
    }
    public String getWorkPath(){
        return workPath;
    }
    public Optional<EmailSending> getEmailSender(){
        if(emailWorker!=null)
            return Optional.ofNullable(emailWorker.getSender());
        return Optional.empty();
    }
    /**
     * Check if the boot up was successful
     * 
     * @return True if boot went fine
     */
    public boolean isOk() {
        return bootOK;
    }

    /**
     * Check if running in debug mode
     * 
     * @return True if running in debug
     */
    public boolean inDebug() {
        return debug;
    }

    public String getDASVersion() {
        return version;
    }

    /**
     * Get the timestamp DAS was started
     * 
     * @return Boot timestamp
     */
    public LocalDateTime getBootupDateTime() {
        return bootup;
    }

    /**
     * Get the scheduler created by das, used for the database manager, has two
     * threads
     * 
     * @return
     */
    public ScheduledExecutorService getScheduler() {
        return this.scheduler;
    }

    public String getUptime() {
        return TimeTools.convertPeriodtoString(Duration.between(bootup, LocalDateTime.now()).getSeconds(),
                TimeUnit.SECONDS);
    }

    /* ************************************  X M L *****************************************************/
    public void createXML() {
       
       XMLfab.withRoot(settingsFile, "das") 
                .addParent("settings")
                    .addChild("mode","normal")
                .addParent("streams")
                    .comment("Defining the various streams that need to be read")
                .build();
    }

    public Document getXMLdoc() {        
        return XMLtools.readXML(settingsFile);
    }

    /* **************************************  R E A L T I M E V A L U E S ********************************************/
    /**
     * Change the current RealtimeValues for the extended version
     * 
     * @param altered The extended version of the RealtimeValues
     */
    public void alterRealtimeValues(RealtimeValues altered) {

        this.rtvals.copySetup(altered);
        this.rtvals = altered;

        baseReq.setRealtimeValues(rtvals);
        rtvals.setIssueCollector(issues);
        dataWorker.setRealtimeValues(rtvals);
    }

    public RealtimeValues getRealtimeValues() {
        return rtvals;
    }

    /* *****************************************  M Q T T **********************************************/
    /**
     * Get The @see MQTTWorker based on the given id
     * 
     * @param id The id of the MQTT worker requested
     * @return The worder requested or null if not found
     */
    public Optional<MqttWorker> getMqttWorker(String id) {        
        return Optional.ofNullable( mqttWorkers.get(id) );
    }

    /**
     * Get a list of all the MQTTWorker id's
     * 
     * @return List of all the id's
     */
    public Set<String> getMqttWorkerIDs() {
        return mqttWorkers.keySet();
    }

    /**
     * Get a descriptive listing of the current brokers/workers and their
     * subscriptions
     * 
     * @return The earlier mentioned descriptive listing
     */
    public String getMqttBrokersInfo() {
        StringJoiner join = new StringJoiner("\r\n", "id -> broker -> online?", "");
        join.add("");
        mqttWorkers.forEach((id, worker) -> join
                .add(id + " -> " + worker.getBroker() + " -> " + (worker.isConnected() ? "online" : "offline"))
                .add(worker.getSubscriptions("\r\n")));
        return join.toString();
    }

    /**
     * Adds a subscription to a certain MQTTWorker
     * 
     * @param id    The id of the worker to add it to
     * @param label The label associated wit the data, this will be given to @see
     *              BaseWorker when data is recevied
     * @param topic The topic to subscribe to
     * @return True if a subscription was successfully added
     */
    public boolean addMQTTSubscription(String id, String label, String topic) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;
        return worker.addSubscription(topic, label);
    }

    /**
     * Remove a subscription from a certain MQTTWorker
     * 
     * @param id    The id of the worker
     * @param topic The topic to remove
     * @return True if it was removed, false if it wasn't either because not found
     *         or no such worker
     */
    public boolean removeMQTTSubscription(String id, String topic) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;
        return worker.removeSubscription(topic);
    }

    /**
     * Update the settings in the xml for a certain MQTTWorker based on id
     * 
     * @param id The worker of which the settings need to be altered
     * @return True if updated
     */
    public boolean updateMQTTsettings(String id) {
        Element mqtt;

        if ((mqtt = XMLtools.getFirstElementByTag(xml, "mqtt")) == null)
            return false; // No valid node, nothing to update

        for (Element broker : XMLtools.getChildElements(mqtt, "broker")) {
            if (XMLtools.getStringAttribute(broker, "id", "general").equals(id)) {
                MqttWorker worker = mqttWorkers.get(id);
                if (worker != null && worker.updateXMLsettings(xml, broker))
                    return XMLtools.updateXML(xml);                
            }
        }
        return false;
    }

    /**
     * Reload the settings for a certain MQTTWorker from the settings.xml
     * 
     * @param id The worker for which the settings need to be reloaded
     * @return True if this was succesful
     */
    public boolean reloadMQTTsettings(String id) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;

        Element mqtt;
        xml = XMLtools.readXML(settingsFile);// make sure the xml is up to date
        if ((mqtt = XMLtools.getFirstElementByTag(xml, "mqtt")) != null) {
            for (Element broker : XMLtools.getChildElements(mqtt, "broker")) {
                if (XMLtools.getStringAttribute(broker, "id", "general").equals(id)) {
                    worker.readSettings(broker);
                    return true;
                }
            }
        }

        return false;
    }

    /* ***************************************  T A S K M A N A G E R ********************************************/
    /**
     * Create a Taskmanager to handle a given script
     * 
     * @param id   The id with which the TaskManager will be referenced
     * @param path The path to the script
     * @return The created TaskManager
     */
    public TaskManager addTaskManager(String id, Path path) {

        TaskManager tm = new TaskManager(id, rtvals, baseReq);
        tm.setXMLPath(path);

        Logger.info("Reading scripts for " + id + " at " + path.toString());

        if (streampool != null)
            tm.setStreamPool(streampool);
        if (emailWorker != null)
            tm.setEmailSending(emailWorker.getSender());
        if (digiWorker != null) {
            tm.setSMSQueue(digiWorker.getQueue());
        }
        taskManagers.put(id, tm);
        return tm;
    }

    public void loadTaskManagersFromXML(Document xml) {
        for(Element e: XMLtools.getAllElementsByTag(xml, "taskmanager") ){
            Logger.info("Found reference to TaskManager in xml.");
            var p = Path.of(e.getTextContent());
            if( !p.isAbsolute())
                p = Path.of(workPath).resolve(p);

            if (Files.exists(p)) {
                addTaskManager(e.getAttribute("id"), p);
            } else {
                Logger.error("No such task xml: " + p);
            }
        }
    }

    /**
     * Check the TaskManager for tasks with the given keyword and start those
     * 
     * @param keyword The keyword to look for
     */
    public void startKeywordTask(String keyword) {
        Logger.info("Checking for task with keyword " + keyword);
        taskManagers.forEach( (k, v) -> v.startKeywordTask(keyword) );
    }

    /**
     * Start a taskset from a specific TaskManager
     * 
     * @param manager   The manager
     * @param shortName The name of the taskset
     * @return Descriptive result
     */
    public String startTaskset(String manager, String shortName) {        
        TaskManager tm = taskManagers.get(manager);
        if (tm == null)
            return "Unknown manager: " + manager;
        return tm.startTaskset(shortName);
    }

    /**
     * Check if the interval tasks are still allowed to run
     */
    public void recheckIntervalTasks() {
        taskManagers.values().forEach(TaskManager::recheckIntervalTasks);
    }

    /**
     * Change a state stored by the TaskManagers
     * 
     * @param state The format needs to be identifier:state
     */
    public void changeManagersState(String state) {
        taskManagers.values().forEach(v -> v.changeState(state));
    }

    /**
     * Get a listing of all the states of all the TaskManagers
     * 
     * @return Descriptive listing of the current states.
     */
    public String getTaskManagerStates() {
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue("No TaskManagers defined yet.");

        taskManagers.forEach((id, manager) -> join.add(id).add(manager.getStatesListing()));
        return join.toString();
    }

    /**
     * Get a descriptive listing of all the tasks of a certain manager
     * 
     * @param id      The id of the manager
     * @param newline Which character to use as newline
     * @return Descriptive listing of all the tasks managed or Unknown manager if id
     *         wasn't recognize
     */
    public String getTasksListing(String id, String newline) {
        TaskManager tm = taskManagers.get(id);
        return tm==null?"Unknown manager":tm.getTaskListing(newline);
    }

    /**
     * Get a descriptive listing of all the tasksets of a certain manager
     * 
     * @param id      The id of the manager
     * @param newline Which character to use as newline
     * @return Descriptive listing of all the tasksets managed or Unknown manager if
     *         id wasn't recognize
     */
    public String getTaskSetsListing(String id, String newline) {
        TaskManager tm = taskManagers.get(id);
        return tm==null?"Unknown manager":tm.getTaskSetListing(newline);
    }

    /**
     * Reload the script of a given TaskManager
     * 
     * @param id The id of the manager
     * @return Result of the attempt
     */
    public String reloadTaskmanager(String id) {
        if (id.endsWith(".xml")) {
            for (TaskManager t : taskManagers.values()) {
                if (t.getXMLPath().toString().endsWith(id)) {
                    return t.reloadTasks() ? "Tasks loaded succesfully." : "Tasks loading failed.";
                }
            }

            this.addTaskManager(id.replace(".xml", ""), Path.of(workPath,"scripts", id));
            return "No TaskManager associated with the script, creating one.";
        }
        TaskManager tm = taskManagers.get(id);
        if (tm == null)
            return "Unknown manager.";
        return tm.reloadTasks() ? "Tasks loaded successfully." : "Tasks loading failed.";
    }

    /* ******************************************  S T R E A M P O O L ***********************************************/
    /**
     * Adds the streampool
     */
    public void addStreamPool() {

        streampool = new StreamPool(dQueue, issues, nettyGroup);
        baseReq.setStreamPool(streampool);

        if (debug) {
            Logger.info("Connecting to streams once because in debug.");
            streampool.enableDebug();
        }
        streampool.readSettingsFromXML(xml);
    }

    public StreamPool getStreamPool() {
        if (streampool == null) {
            Logger.warn("No Streampool defined");
            return null;
        }
        return streampool;
    }

    /* *************************************  B A S E W O R K E R **********************************************/
    /**
     * Adds the BaseWorker
     */
    public void addBaseWorker() {
        if (this.dataWorker == null)
            dataWorker = new BaseWorker(dQueue);
        dataWorker.setReqData(baseReq);
        dataWorker.setRealtimeValues(rtvals);
        dataWorker.setDebugging(debug);
        dataWorker.setEventListener(this);
    }

    public void alterBaseWorker(BaseWorker altered) {
        Logger.info("Using alternate BaseWorker");
        if ( dataWorker != null)
            dataWorker.stopWorker();
        altered.setQueue(dQueue);
        dataWorker = altered;
        dataWorker.setReqData(baseReq);
        dataWorker.setRealtimeValues(rtvals);
        dataWorker.setDebugging(debug);
        dataWorker.setEventListener(this);
        loadGenerics(true);
    }

    public BlockingQueue<Datagram> getDataQueue() {
        addBaseWorker();
        return dQueue;
    }

    public BaseWorker getBaseWorker() {
        return dataWorker;
    }

    public void loadGenerics(boolean clear) {
        if (clear) {
            xml = XMLtools.readXML(settingsFile);
            dataWorker.clearGenerics();
        }        
        XMLfab.getRootChildren( xml, "das","generics","generic")
                .forEach( ele ->  dataWorker.addGeneric( Generic.readFromXML(ele) ) );
    }
    public void loadValMaps(boolean clear){
        if( clear ){
            xml = XMLtools.readXML(settingsFile);
            dataWorker.clearValMaps();
        }
        XMLfab.getRootChildren( xml, "das","valmaps","valmap")
                .forEach( ele ->  dataWorker.addValMap( ValMap.readFromXML(ele) ) );
    }
    /* *****************************************  T R A N S S E R V E R ******************************************/
    /**
     * Adds the TransServer listening on the given port
     * 
     * @param port The port the server will be listening on
     */
    public void addTcpServer(int port) {

        if (trans != null) {
            trans.setServerPort(port);
            return;
        }
        Logger.info("Adding TransServer");
        trans = new TcpServer( settingsFile, nettyGroup);
        trans.setServerPort(port);
        trans.setDataQueue(dQueue);
        baseReq.setTcpServer(trans);
    }

    /**
     * Add Transserver with default port
     */
    public void addTransServer() {
        addTcpServer(-1);
    }

    /**
     * Check if there's already a server defined
     * 
     * @return True if there's one active
     */
    public boolean hasTransServer() {
        return trans != null;
    }

    /**
     * Start the TransServer with the current settings
     */
    public void startTransServer() {
        if (this.trans != null) {
            trans.run(); // Start the server
        }
    }
    /* **********************************  E M A I L W O R K E R *********************************************/
    /**
     * Adds an EmailWorker
     */
    public void addEmailWorker() {
        Logger.info("Adding EmailWorker");
        addBaseWorker();
        emailWorker = new EmailWorker(xml, dQueue);
        emailWorker.setEventListener(this);
        baseReq.setEmailWorker(emailWorker);
    }
    public EmailWorker getEmailWorker() {
        return emailWorker;
    }

    /* *****************************************  D I G I W O R K E R **************************************************/
    /**
     * Adds a digiworker, this is a worker talking to a Digi 4g modem via telnet
     */
    public void addDigiWorker() {
        Logger.info("Adding DigiWorker");
        this.digiWorker = new DigiWorker(xml);
        this.digiWorker.setEventListener(this);
    }

    public BlockingQueue<String[]> getSMSQueue() {
        if (digiWorker == null)
            return null;
        return this.digiWorker.getQueue();
    }

    /**
     * Send an SMS using the digi worker
     * 
     * @param to      The cellphone number or a keyword
     * @param message The message to send
     * @return True if successfully added to the queue
     */
    public boolean sendSMS(String to, String message) {
        if (digiWorker == null) {
            Logger.warn("Not sending SMS because no worker active");
            return false;
        }
        digiWorker.getQueue().add(new String[] { to, message });
        Logger.info("Added SMS: '" + message + "' to " + to);
        return true;
    }

    /* ************************************  D A T A B A S E   M A N A G E R ***************************************/

    public SQLiteDB addSQLiteDB(SQLiteDB db) {
        if( db !=null ){
            dbManager.addSQLiteDB(db.getID(), db);
            rtvals.addDB(db.getID(), db);
        }else{
            Logger.error("Tried to add a null sqlite or empty id (id:"+db.getID()+")");
        }
        return db;
    }
    public Optional<SQLDB> addSQLDB(String id, SQLDB db) {
        if( db !=null && !id.isEmpty() ){
            dbManager.addSQLDB(id, db);
            rtvals.addDB(id, db);
            return Optional.of(db);
        }else{
            Logger.error("Tried to add a null database or empty id (id:"+id+")");
        }

        return Optional.empty();
    }
    public boolean addInfluxDB(String id, Influx db) {
        if( db !=null && !id.isEmpty() ){
            dbManager.addInfluxDB(id, db);
            rtvals.setInfluxDB(db);
            return true;
        }else{
            Logger.error("Tried to add a null influxdb or empty id (id:"+id+")");
        }

        return false;
    }
    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }
    public Database reloadDatabase( String id ){
        Element root = XMLtools.getFirstElementByTag(getXMLdoc(), "databases");
        
        Optional<Element> sqlite = XMLtools.getChildElements(root, "sqlite").stream()
                        .filter( db -> db.getAttribute("id").equals(id)).findFirst();

        if( sqlite.isPresent() )
            return addSQLiteDB(SQLiteDB.readFromXML(sqlite.get(),workPath));

        return XMLtools.getChildElements(root, "server").stream()
                        .filter( db -> db.getAttribute("id").equals(id))
                        .findFirst()
                        .map( db -> {
                            var sqldb =SQLDB.readFromXML(db);
                            if( sqldb!=null) {
                                return addSQLDB(id, sqldb).get();
                            }else{
                                return null;
                            }
                        }).orElse(null);

    }
    private void readDatabasesFromXML() {
        Element root = XMLtools.getFirstElementByTag(xml, "databases");
        XMLtools.getChildElements(root, "sqlite").stream()
                    .filter( db -> !db.getAttribute("id").isEmpty() ) // Can't do anything without id
                    .forEach( db -> addSQLiteDB( SQLiteDB.readFromXML(db,workPath) ) );
        
        XMLtools.getChildElements(root, "server").stream()
            .filter( db -> !db.getAttribute("id").isEmpty() && !db.getAttribute("type").isEmpty() ) // Can't do anything without id/type
            .forEach( db ->
            {
                if( db.getAttribute("type").equalsIgnoreCase("influxdb")){
                    addInfluxDB( db.getAttribute("id"), Influx.readFromXML(db) );
                }else {
                    addSQLDB(db.getAttribute("id"), SQLDB.readFromXML(db));
                }
            } );
    }

    public Database getDatabase(String id) {
        return dbManager.getDatabase(id);
    }

    /* *************************************  D E B U G W O R K E R ***********************************************/
    /**
     * Creates the DebugWorker
     */
    public void addDebugWorker() {
        Logger.info("Adding DebugWorker");
        addBaseWorker();

        debugWorker = new DebugWorker(dataWorker.getQueue(), dbManager, xml);

        if (this.inDebug() && emailWorker != null) 
            emailWorker.setSending(debugWorker.doEmails());            
    }

    /* *************************************  I S S U E C O L L E C T I O N **********************************/
    /**
     * Alter the current IssueCollect with an extended one
     * 
     * @param alter     The newly extended IssueCollector
     * @param useMainDB Whether or not to use the main database for storing
     */
    public void alterIssueCollection(IssueCollector alter, boolean useMainDB) {

        if (alter == null) {
            Logger.error("Invalid IssueCollector given");
            return;
        }
        this.issues = alter;

        if (streampool != null) {
            streampool.setIssueCollector(issues);
        } else {
            Logger.error("No valid streampool");
        }
        baseReq.setIssues(this.issues);
        rtvals.setIssueCollector(issues);
    }
    public IssueCollector getIssueCollector(){
        return issues;
    }
    /* ************************************ * R E Q D A T A ****************************************************/
    /**
     * Replace the BaseReq with the extended one
     * 
     * @param altered The extended BaseReq
     */
    public void alterBaseReq(BaseReq altered) {
        if (altered.rtvals == null)
            altered.rtvals = baseReq.rtvals;
        baseReq = altered;
        if (altered.issues == null) // don't overwrite it already provided
            baseReq.setIssues(this.issues);
        baseReq.setDAS(this);

        this.dataWorker.setReqData(this.baseReq);
        if (trans != null)
            baseReq.setTcpServer(trans);
        if (dbManager != null)
            baseReq.setSQLitesManager(dbManager);
        if (emailWorker != null)
            baseReq.setEmailWorker(emailWorker);
        if (streampool != null)
            baseReq.setStreamPool(streampool);
        for (TaskManager tm : taskManagers.values())
            tm.setBaseReq(baseReq);
    }

    /* ***************************************  T E L N E T S E R V E R ******************************************/
    /**
     * Create the telnetserver
     */
    public void addTelnetServer() {
        telnet = new TelnetServer(this.getDataQueue(), xml, nettyGroup);
    }

    /**
     * Check if there's already a telnet server
     * 
     * @return True if there isn't
     */
    public boolean hasTelnetServer() {
        return telnet != null;
    }

    /* ********************************   B U S ************************************************/
    /**
     * Create the I2CWorker
     */
    public void addI2CWorker() {
        if( i2cWorker!=null)
            return;

        Logger.info("Adding I2CWorker.");
        if (SystemUtils.IS_OS_WINDOWS) {
            Logger.warn("No native I2C busses on windows... ignoring I2C");
            return;
        }

        i2cWorker = new I2CWorker(xml, dQueue);
    }
    public Optional<I2CWorker> getI2CWorker() {
        return Optional.ofNullable(i2cWorker);
    }

    public String getI2CDevices(boolean fullCmds) {
        if (i2cWorker == null)
            return "No I2CWorker active.";

        return i2cWorker.getDeviceList(fullCmds);
    }

    public String reloadI2CCommands() {
        if (i2cWorker == null) {
            addI2CWorker();
            return "No I2CWorker active.";
        }
        return i2cWorker.reloadCommands();
    }

    public boolean runI2Ccommand(String device, String command) {
        if (i2cWorker != null) {
            return i2cWorker.addWork(device, command);
        } else {
            Logger.warn("Can't execute i2c commands, worker not initialized.");
            return false;
        }
    }
    public boolean addI2CDataRequest( String id, Writable wr){
        return i2cWorker.addTarget(id, wr);
    }
    public String getI2CListeners(){
        return i2cWorker.getListeners();
    }
    public String detectI2Cdevices(int controller) {
        return I2CWorker.detectI2Cdevices(controller);
    }

    /* ******************************** * S H U T D O W N S T U F F ******************************************/
    /**
     * Set the reason for shutting down
     * 
     * @param reason The reason DAS is going to shutdown
     */
    public void setShutdownReason(String reason) {
        this.sdReason = reason;
        if (reason.startsWith("upgrade")) {
            this.rebootOnShutDown = true;
        }
    }

    /**
     * Attach a hook to the shutdown process so we're sure that all queue's etc get
     * processed first
     */
    private void attachShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread("shutdownHook") {
            @Override
            public void run() {
                Logger.info("DAS Shutting down");

                // Run shutdown tasks
                for( var tm : taskManagers.values() ){
                    tm.startTaskset("shutdown");
                }

                // SQLite & SQLDB
                Logger.info("Flushing database buffers");
                dbManager.flushAll();

                // Try to send email...
                if (emailWorker != null) {
                    Logger.info("Informing admin");
                    emailWorker.sendEmail("admin", telnet.getTitle() + " shutting down.", "Reason: " + sdReason);
                }
                try {
                    Logger.info("Giving things two seconds to finish up.");
                    sleep(2000);
                } catch (InterruptedException e) {
                    Logger.error(e);
                    Thread.currentThread().interrupt();
                }

                // disconnecting tcp ports
                if (streampool != null)
                    streampool.disconnectAll();

                Logger.info("All processes terminated!");
                try {
                    ProviderRegistry.getLoggingProvider().shutdown();
                } catch (InterruptedException e) {
                    Logger.error(e);
                    Thread.currentThread().interrupt();
                }

                if (rebootOnShutDown) {
                    try {
                        Runtime rt = Runtime.getRuntime();
                        if (SystemUtils.IS_OS_LINUX) { // if linux
                            rt.exec("reboot now");
                        } else if (SystemUtils.IS_OS_WINDOWS) {
                            Logger.warn("Windows not supported yet for reboot");
                        }
                    } catch (java.io.IOException err) {
                        Logger.error(err);
                    }
                }
            }
        });
        Logger.info("Shut Down Hook Attached.");
    }

    /* ******************************  * T H R E A D I N G *****************************************/
    /**
     * Start all the threads
     */
    public void startAll() {

        this.baseReq.getMethodMapping();

        if (this.dataWorker != null) {
            Logger.info("Starting BaseWorker...");
            new Thread(dataWorker, "BaseWorker").start();// Start the thread
        }
        if (this.digiWorker != null) {
            Logger.info("Starting DigiWorker...");
            new Thread(digiWorker, "DigiWorker").start();// Start the thread
        }
        if (debug && this.debugWorker == null) {
            Logger.info("Debug mode but no debugworker created...");
        } else if (this.debugWorker != null) {
            if (debug || log) {
                Logger.info("Starting DebugWorker...");
                debugWorker.start();// Start the thread
            } else {
                Logger.info("Not in debug mode, not starting debugworker...");
            }
        }
        if (this.trans != null) {
            trans.run(); // Start the server
        }
        if (this.telnet != null) {
            telnet.run(); // Start the server
        }
        if (this.i2cWorker != null) {
            Logger.info("Starting I2CWorker...");
            new Thread(i2cWorker, "i2cWorker").start();// Start the thread
        }

        // TaskManager
        for (TaskManager tm : this.taskManagers.values())
            tm.reloadTasks();

        Logger.debug("Finished");
    }

    public void haltWorkers() {
        if (dataWorker != null)
            dataWorker.stopWorker();
    }

    /* **************************** * S T A T U S S T U F F *********************************************************/
    /**
     * Request a status message regarding the streams, databases, buffers etc
     * 
     * @param html Whether the status needs to be given in telnet or html
     * @return A status message
     */
    public String getStatus(boolean html) {
        final String TEXT_GREEN = html?"":TelnetCodes.TEXT_GREEN;
        final String TEXT_CYAN = html?"":TelnetCodes.TEXT_CYAN;
        final String UNDERLINE_OFF = html?"":TelnetCodes.UNDERLINE_OFF;
        final String TEXT_YELLOW = html?"":TelnetCodes.TEXT_YELLOW;
        final String TEXT_RED = html?"":TelnetCodes.TEXT_RED;
        final String TEXT_NB = html?"":TelnetCodes.TEXT_NOTBRIGHT;
        final String TEXT_BRIGHT = html?"":TelnetCodes.TEXT_BRIGHT;

        StringBuilder b = new StringBuilder();

        double totalMem = (double)Runtime.getRuntime().totalMemory();
        double usedMem = totalMem-Runtime.getRuntime().freeMemory();

        totalMem = Tools.roundDouble(totalMem/(1024.0*1024.0),1);
        usedMem = Tools.roundDouble(usedMem/(1024.0*1024.0),1);

        if (html) {
            b.append("<b><u>DCAFS Status at ").append(TimeTools.formatNow("HH:mm:ss")).append(".</b></u><br><br>");
        } else {
            b.append(TEXT_GREEN).append("DCAFS Status at ").append(TimeTools.formatNow("HH:mm:ss")).append("\r\n\r\n")
                    .append(UNDERLINE_OFF);
        }
        b.append(TEXT_YELLOW).append("DCAFS Version: ").append(TEXT_GREEN).append(version).append(" (jvm:").append(System.getProperty("java.version")).append(")\r\n");
        b.append(TEXT_YELLOW).append("Uptime: ").append(TEXT_GREEN).append(getUptime()).append("\r\n");
        b.append(TEXT_YELLOW).append("Memory: ").append(TEXT_GREEN).append(usedMem).append("/").append(totalMem).append("MB\r\n");
        b.append(TEXT_YELLOW).append("Current mode: ").append(debug ? TEXT_RED + "debug" : TEXT_GREEN + "normal").append("\r\n");
        b.append(TEXT_YELLOW).append("IP: ").append(TEXT_GREEN).append(Tools.getLocalIP());
        b.append(UNDERLINE_OFF).append("\r\n");

        b.append(TEXT_YELLOW).append(rtvals.getStatus(html));

        if (html) {
            b.append("<br><b>Streams</b><br>");
        } else {
            b.append(TEXT_YELLOW).append(TEXT_CYAN).append("\r\n").append("Streams").append("\r\n").append(UNDERLINE_OFF).append(TEXT_YELLOW);
        }
        if (streampool != null) {
            if (streampool.getStreamCount() == 0) {
                b.append("No streams defined (yet)").append("\r\n");
            } else {
                for (String s : streampool.getStatus().split("\r\n")) {
                    if (s.startsWith("!!")) {
                        b.append(TEXT_RED).append(s).append(TEXT_YELLOW).append(UNDERLINE_OFF);
                    } else {
                        b.append(s);
                    }
                    b.append("\r\n");
                }
            }
        }
        if (!mqttWorkers.isEmpty()) {
            if (html) {
                b.append("<br><b>MQTT</b><br>");
            } else {
                b.append(TEXT_YELLOW).append(TEXT_CYAN).append("\r\n").append("MQTT").append("\r\n").append(UNDERLINE_OFF).append(TEXT_YELLOW);
            }
            b.append(this.getMqttBrokersInfo());
        }

        try {
            if (html) {
                b.append("<br><b>Buffers</b><br>");
            } else {
                b.append(TelnetCodes.TEXT_CYAN).append("\r\nBuffers\r\n").append(TelnetCodes.TEXT_YELLOW)
                        .append(TelnetCodes.UNDERLINE_OFF);
            }
            b.append(getQueueSizes());
        } catch (java.lang.NullPointerException e) {
            Logger.error("Error reading buffers " + e.getMessage());
        }

            if (html) {
                b.append("<br><b>Databases</b><br>");
            } else {
                b.append(TelnetCodes.TEXT_CYAN)
                        .append("\r\nDatabases\r\n")
                        .append(TelnetCodes.TEXT_YELLOW).append(TelnetCodes.UNDERLINE_OFF);
            }
        if (dbManager.hasDatabases()) {
            for( String l : dbManager.getStatus().split("\r\n") ){
                if (l.endsWith("(NC)"))
                    l = TEXT_NB + l + TEXT_BRIGHT;
                b.append(l.replace(workPath+File.separator,"")).append("\r\n");
            }
        }else{
            b.append("None yet\r\n");
        }
        return b.toString().replace("false", TEXT_RED + "false" + TEXT_GREEN);
    }

    /**
     * Get a status update of the various queues, mostly to verify that they are
     * empty
     * 
     * @return The status update showing the amount of items in the queues
     */
    public String getQueueSizes() {
        StringJoiner join = new StringJoiner("\r\n", "", "\r\n");
        join.add("Data buffer: " + this.dQueue.size() + " in receive buffer and "+dataWorker.getWaitingQueueSize()+" waiting...");

        if (emailWorker != null)
            join.add("Email backlog: " + emailWorker.getRetryQueueSize() );
        return join.toString();
    }

    /**
     * Get the settings in string format
     * 
     * @return The settings in string format
     */
    public String getSettings() {

        StringJoiner join = new StringJoiner("\r\n", "\r\n", "\r\n");

        if (streampool != null) {
            join.add("----Serial & TCP & UDP PORTS----");
            join.add(streampool.getSettings());
        }
        if (emailWorker != null) {
            join.add("\r\n----Email----");
            join.add(emailWorker.getSettings());
            join.add(emailWorker.getEmailBook());
        }
        if (digiWorker != null) {
            join.add("\r\n----SMS----");
            join.add(digiWorker.getServerInfo());
            join.add(digiWorker.getSMSBook());
        }
        if (!mqttWorkers.isEmpty()) {
            join.add("\r\n----MQTT----");
            join.add(this.getMqttBrokersInfo());
        }
        return join.toString();
    }

    @Override
    public void notifyCancelled(String thread) {

        Logger.error("Thread: " + thread + " stopped for some reason.");
        issues.triggerIssue("thread died:" + thread, thread + " died and got restarted", LocalDateTime.now());

        switch (thread) {
            case "BaseWorker": // done
                int retries = issues.getIssueTriggerCount("thread died:" + thread);
                if (dataWorker != null && retries < 50) {
                    Logger.error("BaseWorker not alive, trying to restart...");
                    new Thread(dataWorker, "BaseWorker").start();// Start the thread
                } else {
                    Logger.error("BaseWorker died 50 times, giving up reviving.");
                    issues.triggerIssue("fatal:" + thread, thread + " permanently dead.", LocalDateTime.now());
                }
                break;
            case "DigiWorker": // done
                if (digiWorker != null) {
                    Logger.error("DigiWorker not alive, trying to restart...");
                    new Thread(digiWorker, "DigiWorker").start();// Start the thread
                }
                break;
            default:
                Logger.error("Unknown thread");
                break;
        }
    }
    public static void main(String[] args) {

        DAS das = new DAS();

        if( das.telnet == null ){
            das.addTelnetServer();
        }
        das.startAll();   

        Logger.info("Dcafs boot finished!");
    }
}
