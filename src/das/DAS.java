package das;

import io.Writable;
import io.collector.CollectorPool;
import io.email.Email;
import io.email.EmailSending;
import io.email.EmailWorker;
import io.hardware.gpio.InterruptPins;
import io.hardware.i2c.I2CWorker;
import io.matrix.MatrixClient;
import io.mqtt.MqttPool;
import io.stream.StreamManager;
import io.forward.ForwardPool;
import util.gis.Waypoints;
import util.tools.FileMonitor;
import io.stream.tcp.TcpServer;
import io.telnet.TelnetCodes;
import io.telnet.TelnetServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import org.tinylog.provider.ProviderRegistry;
import util.data.RealtimeValues;
import util.database.*;
import util.task.TaskManagerPool;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

public class DAS implements Commandable{

    private static final String version = "1.2.6";

    private final Path settingsPath;
    private String workPath;

    private final LocalDateTime bootupTimestamp = LocalDateTime.now(); // Store timestamp at boot up to calculate uptime

    /* Workers */
    private EmailWorker emailWorker;
    private LabelWorker labelWorker;
    private DebugWorker debugWorker;
    private I2CWorker i2cWorker;

    /* */
    private StreamManager streampool;
    private TcpServer trans;
    private TelnetServer telnet;

    private RealtimeValues rtvals;
    private CommandPool commandPool;
    private Waypoints waypoints; // waypoints

    /* Managers & Pools */
    private DatabaseManager dbManager;
    private MqttPool mqttPool;
    private TaskManagerPool taskManagerPool;
    private CollectorPool collectorPool;

    private boolean debug = false;
    private boolean log = false;
    private boolean bootOK = false; // Flag to show if booting went ok
    String sdReason = "Unwanted shutdown."; // Reason for shutdown of das, default is unwanted

    private final BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();
    boolean rebootOnShutDown = false;
    private InterruptPins isrs;

    private MatrixClient matrixClient;
    private FileMonitor fileMonitor;

    private Instant lastCheck;

    /* Threading */
    private final EventLoopGroup nettyGroup = new NioEventLoopGroup(); // Single group so telnet,trans and streampool can share it

    public DAS() {

        var classPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        classPath = classPath.replace("%20"," ");
        System.out.println("Checking for workpath at : "+classPath);

        if( classPath.startsWith("/") && SystemUtils.IS_OS_WINDOWS ) // for some reason this gets prepended
            classPath=classPath.substring(1);

        Path p=Path.of(classPath);

        if (classPath.endsWith("classes/")) { //meaning from ide
            p = p.getParent(); // get parent to get out of the classes
        }

        workPath = p.getParent().toString();
        if( workPath.matches(".*lib$")) { // Meaning used as a lib
            workPath = Path.of(workPath).getParent().toString();
        }else if( workPath.contains("repository")){
            workPath = Path.of("").toAbsolutePath().toString();
        }
        System.out.println("Workpath lib: "+workPath);
        if( System.getProperty("tinylog.directory") == null ) { // don't overwrite this
            // Re set the paths for the file writers to use the same path as the rest of the program
            System.setProperty("tinylog.directory", workPath); // Set work path as system property
        }
        settingsPath = Path.of(workPath, "settings.xml");

        if (Files.notExists(settingsPath)) {
            Logger.warn("No Settings.xml file found, creating new one. Searched path: "
                    + settingsPath.toFile().getAbsolutePath());
            createXML();
        }
        Logger.info("Used settingspath: "+settingsPath);

        var docOpt = XMLtools.readXML(settingsPath);

        if( docOpt.isEmpty()){
            Logger.error("Issue in current settings.xml, aborting: " + settingsPath.toString());
            addTelnetServer();
            return;
        }
        var settingsDoc= docOpt.get();

        XMLtools.getFirstElementByTag(settingsDoc, "settings").ifPresent( ele ->
                {
                    debug = XMLtools.getChildStringValueByTag(ele, "mode", "normal").equals("debug");
                    log = XMLtools.getChildStringValueByTag(ele, "mode", "normal").equals("log");
                    System.setProperty("tinylog.directory", XMLtools.getChildStringValueByTag(ele,"tinylog",workPath) );
        });

        if (debug) {
            Logger.info("Program booting in DEBUG mode");
        } else {
            Logger.info("Program booting in NORMAL mode");
        }

        /* RealtimeValues */
        rtvals = new RealtimeValues( settingsPath, dQueue );
        rtvals.setText("dcafs_version",version);

        /* Database manager */
        dbManager = new DatabaseManager(workPath,rtvals);

        /* CommandPool */
        commandPool = new CommandPool( workPath, dQueue );
        addCommandable(rtvals.getIssuePool(),"issue","issues");
        addCommandable("flags;fv;reals;real;rv;texts;tv;int;integer",rtvals);
        addCommandable(rtvals,"rtval","rtvals");
        addCommandable("stop",rtvals);
        addCommandable(dbManager,"dbm","myd");
        addCommandable(this,"st");

        /* TransServer */
        addTransServer(-1);

        /* MQTT worker */
        addMqttPool();

        /* Label Worker */
        addLabelWorker();

        /* StreamManager */
        addStreamPool();

        /* EmailWorker */
        if (XMLtools.hasElementByTag(settingsDoc, "email") ) {
            addEmailWorker();
        }
        /* DebugWorker */
        if (DebugWorker.inXML(settingsDoc)) {
            addDebugWorker();
        }

        /* I2C */
        addI2CWorker();

        /* Forwards */
        ForwardPool forwardPool = new ForwardPool(dQueue, settingsPath, rtvals, nettyGroup);
        addCommandable(forwardPool,"filter","ff","filters");
        addCommandable(forwardPool,"math","mf","maths");
        addCommandable(forwardPool,"editor","ef","editors");
        addCommandable(forwardPool,"paths","path","pf","paths");
        addCommandable(forwardPool, "");

        /* Waypoints */
        waypoints = new Waypoints(settingsPath,nettyGroup,rtvals,dQueue);
        addCommandable("wpts",waypoints);

        /* Collectors */
        collectorPool = new CollectorPool(settingsPath.getParent(),dQueue,nettyGroup,rtvals);
        addCommandable(collectorPool,"fc");
        addCommandable(collectorPool,"mc");

        /* File monitor */
        if( XMLdigger.goIn(settingsPath,"dcafs").goDown("monitor").isValid() ) {
            fileMonitor = new FileMonitor(settingsPath.getParent(), dQueue);
            addCommandable(fileMonitor,"fm","fms");
        }
        /* GPIO's */
        if( XMLdigger.goIn(settingsPath,"dcafs").goDown("gpio").isValid() ){
            Logger.info("Reading interrupt gpio's from settings.xml");
            isrs = new InterruptPins(dQueue,settingsPath);
        }else{
            Logger.info("No gpio's defined in settings.xml");
        }

        /* Matrix */
        if( XMLdigger.goIn(settingsPath,"dcafs").goDown("matrix").isValid() ){
            Logger.info("Reading Matrix info from settings.xml");
            matrixClient = new MatrixClient( dQueue, rtvals, settingsPath );
            addCommandable("matrix",matrixClient);
        }else{
            Logger.info("No matrix settings");
        }

        /* TaskManagerPool */
        addTaskManager();
        nettyGroup.schedule(this::checkClock,1,TimeUnit.MINUTES);
        bootOK = true;

        /* Telnet */
        addTelnetServer();

        attachShutDownHook();
    }
    public String getVersion(){return version;}
    public Path getWorkPath(){
        return Path.of(workPath);
    }
    public Path getSettingsPath(){
        return settingsPath;
    }
    /**
     * Check if running in debug mode
     * 
     * @return True if running in debug
     */
    public boolean inDebug() {
        return debug;
    }

    public String getUptime() {
        return TimeTools.convertPeriodtoString(Duration.between(bootupTimestamp, LocalDateTime.now()).getSeconds(),
                TimeUnit.SECONDS);
    }
    /**
     Compares the stored timestamp with the current one
     */
    private void checkClock(){
        if( Duration.between(lastCheck, Instant.now()).toSeconds() > 65) { // Checks every minute, so shouldn't be much more than that
            var error = "System time change detected, last check (max 60s ago) " + TimeTools.LONGDATE_FORMATTER.format(lastCheck) + " is now " + TimeTools.formatLongUTCNow();
            Logger.error(error);
            if( emailWorker !=null )
                emailWorker.sendEmail( Email.toAdminAbout("System clock").subject("System clock suddenly changed!").content(error));
            dbManager.recheckRollOver();// The rollover is affected by sudden changes
        }
        lastCheck = Instant.now();
    }
    /* ************************************  X M L *****************************************************/
    private void createXML() {
       XMLfab.withRoot(settingsPath, "dcafs")
                .addParentToRoot("settings")
                    .addChild("mode","normal")
                .addParentToRoot("streams")
                    .comment("Defining the various streams that need to be read")
                .build();
    }
    /* **************************************  C O M M A N D P O O L ********************************************/
    /**
     * Add a commandable to the CommandPool, this is the same as adding commands to dcafs
     * @param id The unique start command (so whatever is in front of the : )
     * @param cmd The commandable to add
     */
    public void addCommandable( String id, Commandable cmd ){
        commandPool.addCommandable(id,cmd);
    }
    public void addCommandable( Commandable cmd, String... id  ){
        commandPool.addCommandable(String.join(";",id),cmd);
    }
    /* ***************************************  T A S K M A N A G E R ********************************************/
    /**
     * Create a Taskmanager to handle tasklist scripts
     */
    private void addTaskManager() {

        taskManagerPool = new TaskManagerPool(workPath, rtvals, commandPool);

        if (streampool != null)
            taskManagerPool.setStreamPool(streampool);
        if (emailWorker != null)
            taskManagerPool.setEmailSending(emailWorker.getSender());
        taskManagerPool.readFromXML();
        addCommandable("tm", taskManagerPool);
    }
    public TaskManagerPool getTaskManagerPool(){
        return taskManagerPool;
    }
    /* ******************************************  S T R E A M P O O L ***********************************************/
    /**
     * Adds the streampool
     */
    private void addStreamPool() {

        streampool = new StreamManager(dQueue, rtvals.getIssuePool(), nettyGroup,rtvals);
        addCommandable(streampool,"ss","streams","");
        addCommandable(streampool,"s_","h_");
        addCommandable(streampool,"rios","raw","stream","store");
        addCommandable(streampool,"");

        if (debug) {
            streampool.enableDebug();
        }else{
            streampool.readSettingsFromXML(settingsPath);
            streampool.getStreamIDs().forEach( id->addCommandable(streampool,id));
        }
    }
    public Optional<Writable> getStreamWritable( String id ){
        var opt = streampool.getStream(id);
        return opt.map(baseStream -> (Writable) baseStream);
    }
    public StreamManager getStreampool(){
        return streampool;
    }
    /* ***************************************** D B M  ******************************************************** */
    public DatabaseManager getDatabaseManager(){
        return dbManager;
    }

    /* *************************************  L A B E L W O R K E R **********************************************/
    /**
     * Adds the BaseWorker
     */
    private void addLabelWorker() {
        if (this.labelWorker == null)
            labelWorker = new LabelWorker(settingsPath,dQueue,rtvals,dbManager);
        labelWorker.setCommandReq(commandPool);
        labelWorker.setDebugging(debug);
        labelWorker.setMqttWriter(mqttPool);

        addCommandable(labelWorker,"gens");
    }
    public void addDatagramProcessor( DatagramProcessing rtvals ){
        labelWorker.addDatagramProcessing(rtvals);
    }
    public BlockingQueue<Datagram> getDataQueue() {
        addLabelWorker();
        return dQueue;
    }
    /* ***************************************** M Q T T ******************************************************** */
    private void addMqttPool(){
        mqttPool = new MqttPool(settingsPath,rtvals,dQueue);
        addCommandable("mqtt", mqttPool);
    }
    /* *****************************************  T R A N S S E R V E R ***************************************** */
    /**
     * Adds the TransServer listening on the given port
     * 
     * @param port The port the server will be listening on
     */
    private void addTransServer(int port) {

        Logger.info("Adding TransServer");
        trans = new TcpServer(settingsPath, nettyGroup);
        trans.setServerPort(port);
        trans.setDataQueue(dQueue);

        addCommandable("ts",trans);
        addCommandable("trans",trans);
    }

    /* **********************************  E M A I L W O R K E R *********************************************/
    /**
     * Adds an EmailWorker
     */
    private void addEmailWorker() {
        Logger.info("Adding EmailWorker");
        addLabelWorker();
        emailWorker = new EmailWorker(settingsPath, dQueue);
        addCommandable("email",emailWorker);
        commandPool.setEmailSender(emailWorker);
    }
    public EmailSending getEmailSender(){
        return emailWorker;
    }
    /* *************************************  D E B U G W O R K E R ***********************************************/
    /**
     * Creates the DebugWorker
     */
    private void addDebugWorker() {
        Logger.info("Adding DebugWorker");
        addLabelWorker();

        debugWorker = new DebugWorker(labelWorker.getQueue(), dbManager, XMLtools.readXML(settingsPath).get());

        if (this.inDebug() && emailWorker != null) 
            emailWorker.setSending(debugWorker.doEmails());            
    }

    /* ***************************************  T E L N E T S E R V E R ******************************************/
    /**
     * Create the telnet server
     */
    private void addTelnetServer() {

        if( bootOK) {
            telnet = new TelnetServer(this.getDataQueue(), settingsPath, nettyGroup);
            addCommandable(telnet, "telnet", "nb");
        }else{
            telnet = new TelnetServer(null, settingsPath, nettyGroup);
        }
    }

    /* ********************************   B U S ************************************************/
    /**
     * Create the I2CWorker
     */
    private void addI2CWorker() {
        if( i2cWorker!=null)
            return;

        if (SystemUtils.IS_OS_WINDOWS) {
            Logger.info("No native I2C busses on windows... ignoring I2C");
            return;
        }
        Logger.info("Adding I2CWorker.");
        i2cWorker = new I2CWorker(settingsPath, dQueue);
        addCommandable("i2c",i2cWorker);
        addCommandable("stop",i2cWorker);
    }
    /* ******************************** R E A L T I M E  D A T A  ******************************************* */
    // Note: these are used when dcafs is used as a library
    public IssuePool getIssuePool(){ return rtvals.getIssuePool();}
    public Waypoints getWaypoints(){
        return waypoints;
    }
    public RealtimeValues getRealtimeValues(){
        return rtvals;
    }
    /* ******************************** * S H U T D O W N S T U F F ***************************************** */
    /**
     * Attach a hook to the shutdown process, so we're sure that all queue's etc. get
     * processed first
     */
    private void attachShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread("shutdownHook") {
            @Override
            public void run() {
                Logger.info("Dcafs shutting down!");
                telnet.replyToCommand(new String[]{"telnet","broadcast,error,Dcafs shutting down!"},null,false);

                if( matrixClient!=null)
                    matrixClient.broadcast("Shutting down!");

                // Run shutdown tasks
                taskManagerPool.startTaskset("shutdown");

                // SQLite & SQLDB
                Logger.info("Flushing database buffers");
                dbManager.flushAll();

                // Collectors
                collectorPool.flushAll();

                // Try to send email...
                if (emailWorker != null) {
                    Logger.info("Informing admin");
                    String r = commandPool.getShutdownReason();
                    sdReason = r.isEmpty()?sdReason:r;
                    emailWorker.sendEmail( Email.toAdminAbout(telnet.getTitle() + " shutting down.").content("Reason: " + sdReason) );
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

    /* ******************************* T H R E A D I N G *****************************************/
    /**
     * Start all the threads
     */
    public void startAll() {

        if (labelWorker != null) {
            Logger.info("Starting LabelWorker...");
            new Thread(labelWorker, "LabelWorker").start();// Start the thread
        }
        if (debug && debugWorker == null) {
            Logger.info("Debug mode but no debug worker created...");
        } else if (debugWorker != null) {
            if (debug || log) {
                Logger.info("Starting DebugWorker...");
                debugWorker.start();// Start the thread
            } else {
                Logger.info("Not in debug mode, not starting debug worker...");
            }
        }
        if (trans != null && trans.isActive()) {
            trans.run(); // Start the server
        }
        if (telnet != null) {
            telnet.run(); // Start the server
        }

        // TaskManager
        if (taskManagerPool != null) {
            String errors = taskManagerPool.reloadAll();
            if( !errors.isEmpty()){
                telnet.addMessage("Errors during TaskManager parsing:\r\n"+errors);
            }
        }
        // Matrix
        if( matrixClient != null ){
            Logger.info("Trying to login to matrix");
            matrixClient.login();
        }

        Logger.debug("Finished");
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
        final String TEXT_NB = html?"":TelnetCodes.TEXT_REGULAR;
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
        if( i2cWorker !=null && i2cWorker.getDeviceCount()!=0){
            if (html) {
                b.append("<br><b>Devices</b><br>");
            } else {
                b.append(TEXT_YELLOW).append(TEXT_CYAN).append("\r\n").append("Devices").append("\r\n").append(UNDERLINE_OFF).append(TEXT_YELLOW);
            }
            for( String s : i2cWorker.getStatus("\r\n").split("\r\n") ){
                if (s.startsWith("!!") || s.endsWith("false")) {
                    b.append(TEXT_RED).append(s).append(TEXT_YELLOW).append(UNDERLINE_OFF);
                } else {
                    b.append(s);
                }
                b.append("\r\n");
            }
        }
        if (mqttPool !=null && !mqttPool.getMqttWorkerIDs().isEmpty()) {
            if (html) {
                b.append("<br><b>MQTT</b><br>");
            } else {
                b.append(TEXT_YELLOW).append(TEXT_CYAN).append("\r\n").append("MQTT").append("\r\n").append(UNDERLINE_OFF).append(TEXT_YELLOW);
            }
            b.append(mqttPool.getMqttBrokersInfo()).append("\r\n");
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
        if( html ){
            return b.toString().replace("\r\n","<br>");
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
        join.add("Data buffer: " + dQueue.size() + " in receive buffer and "+ labelWorker.getWaitingQueueSize()+" waiting...");

        if (emailWorker != null)
            join.add("Email backlog: " + emailWorker.getRetryQueueSize() );
        return join.toString();
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        return switch( request[0]){
            case "st" -> getStatus(html);
            default -> "Unknown command";
        };
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }

    public static void main(String[] args) {

        DAS das = new DAS();

        if( das.telnet == null ){
            das.addTelnetServer();
        }
        das.startAll();

        Logger.info("Dcafs "+version+" boot finished!");
    }
}