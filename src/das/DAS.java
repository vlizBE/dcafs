package das;

import io.collector.CollectorPool;
import io.email.Email;
import io.email.EmailSending;
import io.email.EmailWorker;
import io.hardware.gpio.InterruptPins;
import io.hardware.i2c.I2CWorker;
import io.matrix.MatrixClient;
import io.mqtt.MqttPool;
import io.sms.DigiWorker;
import io.stream.StreamManager;
import io.forward.ForwardPool;
import io.stream.tcp.TcpServer;
import io.telnet.TelnetCodes;
import io.telnet.TelnetServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import org.tinylog.provider.ProviderRegistry;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.DeadThreadListener;
import util.data.DataProviding;
import util.data.RealtimeValues;
import util.database.*;
import util.task.TaskManager;
import util.task.TaskManagerPool;
import util.tools.TinyWrapErr;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.*;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class DAS implements DeadThreadListener {

    private static final String version = "1.0.0";

    private Path settingsPath = Path.of("settings.xml");
    private String workPath=Path.of("").toString();

    private Document settingsDoc;

    private final LocalDateTime bootupTimestamp = LocalDateTime.now(); // Store timestamp at boot up to calculate uptime

    /* Workers */
    private EmailWorker emailWorker;
    private LabelWorker labelWorker;
    private DigiWorker digiWorker;
    private DebugWorker debugWorker;
    private I2CWorker i2cWorker;

    /* */
    private StreamManager streampool;
    private TcpServer trans;
    private TelnetServer telnet;

    private RealtimeValues rtvals;
    private CommandPool commandPool;

    /* Managers & Pools */
    private DatabaseManager dbManager;
    private MqttPool mqttPool;
    private TaskManagerPool taskManagerPool;
    private CollectorPool collectorPool;

    private boolean debug = false;
    private boolean log = false;
    private boolean bootOK = false; // Flag to show if booting went ok
    String sdReason = "Unwanted shutdown."; // Reason for shutdown of das, default is unwanted

    BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();
    boolean rebootOnShutDown = false;
    private InterruptPins isrs;

    private MatrixClient matrixClient;

    /* Threading */
    EventLoopGroup nettyGroup = new NioEventLoopGroup(); // Single group so telnet,trans and streampool can share it

    public DAS() {

        try {
            Path p = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            System.out.println("Path found: "+ p);
            if (!p.toString().endsWith(".jar")) { //meaning from ide
                p = p.getParent();
            }else{
                TinyWrapErr.install();
                System.setProperty("tinylog.stream","out");
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
        } catch (URISyntaxException e) {
            Logger.error(e);
        }

        if (Files.notExists(settingsPath)) {
            Logger.warn("No Settings.xml file found, creating new one. Searched path: "
                    + settingsPath.toFile().getAbsolutePath());
            createXML();
        }
        Logger.info("Used settingspath: "+settingsPath);

        settingsDoc = XMLtools.readXML(settingsPath);

        if (settingsDoc == null) {
            Logger.error("Issue in current settings.xml, aborting: " + settingsPath.toString());
            addTelnetServer();
        } else {


            Element settings = XMLtools.getFirstElementByTag(settingsDoc, "settings");

            if (settings != null) {
                debug = XMLtools.getChildValueByTag(settings, "mode", "normal").equals("debug");
                log = XMLtools.getChildValueByTag(settings, "mode", "normal").equals("log");

                System.setProperty("tinylog.directory", XMLtools.getChildValueByTag(settings,"tinylog",workPath) );

                if (debug) {
                    Logger.info("Program booting in DEBUG mode");
                } else {
                    Logger.info("Program booting in NORMAL mode");
                }
            }
            /* RealtimeValues */
            rtvals = new RealtimeValues( settingsPath, dQueue );

            /* Database manager */
            dbManager = new DatabaseManager(workPath,rtvals);

            /* CommandPool */
            commandPool = new CommandPool( workPath, dQueue );
            addCommandable(rtvals.getIssuePool(),"issue","issues");
            addCommandable("flags;fv;doubles;double;dv;texts;tv",rtvals);
            addCommandable(rtvals,"rtval","rtvals");
            addCommandable( "wpts",rtvals.enableWaypoints(nettyGroup) );
            addCommandable(dbManager,"dbm","myd");

            /* TransServer */
            addTransServer(-1);

            /* MQTT worker */
            addMqttPool();

            /* Base Worker */
            addLabelWorker();

            /* ValMaps */
            loadValMaps(true);

            /* StreamManager */
            addStreamPool();

            /* EmailWorker */
            if (XMLtools.hasElementByTag(settingsDoc, "email") ) {
                addEmailWorker();
            }
            /* DigiWorker */
            if (XMLtools.hasElementByTag(settingsDoc, "digi") ) {
                addDigiWorker();
            }
            /* DebugWorker */
            if (DebugWorker.inXML(settingsDoc)) {
                addDebugWorker();
            }

            /* I2C */
            addI2CWorker();

            /* Telnet */
            addTelnetServer();

            /* TaskManagerPool */
            addTaskManager();

            /* Forwards */
            ForwardPool forwardPool = new ForwardPool(dQueue, settingsPath, rtvals, nettyGroup);
            addCommandable(forwardPool,"filter","ff","filters");
            addCommandable(forwardPool,"math","mf","maths");
            addCommandable(forwardPool,"editor","ef","editors");
            addCommandable(forwardPool,"paths","path","pf","paths");
            addCommandable(forwardPool, "");

            /* Collectors */
            collectorPool = new CollectorPool(settingsPath,dQueue,nettyGroup,rtvals);
            addCommandable(collectorPool,"fc");
            addCommandable(collectorPool,"mc");

            /* GPIO's */
            if( XMLfab.hasRoot(settingsPath,"dcafs","gpio") ){
                Logger.info("Reading interrupt gpio's from settings.xml");
                isrs = new InterruptPins(dQueue,settingsPath);
            }else{
                Logger.info("No gpios defined in settings.xml");
            }

            /* Matrix */
            if( XMLfab.hasRoot(settingsPath,"dcafs","settings","matrix") ){
                Logger.info("Reading Matrix info from settings.xml");
                matrixClient = new MatrixClient( dQueue, rtvals, settingsPath );
                addCommandable("matrix",matrixClient);
            }else{
                Logger.info("No matrix settings");
            }

            bootOK = true;
        }
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
    public Path getSettingsPath(){
        return settingsPath;
    }
    public DatabaseManager getDatabaseManager(){return dbManager;}

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

    public String getUptime() {
        return TimeTools.convertPeriodtoString(Duration.between(bootupTimestamp, LocalDateTime.now()).getSeconds(),
                TimeUnit.SECONDS);
    }

    /* ************************************  X M L *****************************************************/
    public void createXML() {
       
       XMLfab.withRoot(settingsPath, "dcafs")
                .addParentToRoot("settings")
                    .addChild("mode","normal")
                .addParentToRoot("streams")
                    .comment("Defining the various streams that need to be read")
                .build();
    }

    /* **************************************  C O M M A N D R E Q  ********************************************/
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
    public CommandPool getCommandPool(){
        return commandPool;
    }

    /**
     * Adds a check to do to see if it's allowed to shut down now
     * @param sdp
     */
    public void addShutdownPreventing( ShutdownPreventing sdp){
        commandPool.addShutdownPreventing(sdp);
    }
    /* **************************************  R E A L T I M E V A L U E S ********************************************/
    public DataProviding getDataProvider() {
        return rtvals;
    }

    /* ***************************************  T A S K M A N A G E R ********************************************/
    /**
     * Create a Taskmanager to handle tasklist scripts
     */
    public void addTaskManager() {

        taskManagerPool = new TaskManagerPool(workPath, rtvals, commandPool);

        if (streampool != null)
            taskManagerPool.setStreamPool(streampool);
        if (emailWorker != null)
            taskManagerPool.setEmailSending(emailWorker.getSender());
        if (digiWorker != null) {
            taskManagerPool.setSMSSending(digiWorker);
        }
        taskManagerPool.readFromXML();
        addCommandable("tm", taskManagerPool);
    }
    public Optional<TaskManager> getTaskManager( String id){
        return taskManagerPool.getTaskList(id);
    }

    /* ******************************************  S T R E A M P O O L ***********************************************/
    /**
     * Adds the streampool
     */
    public void addStreamPool() {

        streampool = new StreamManager(dQueue, rtvals.getIssuePool(), nettyGroup);
        addCommandable(streampool,"ss","streams","");
        addCommandable(streampool,"s_","h_");
        addCommandable(streampool,"rios","raw","stream");

        if (debug) {
            streampool.enableDebug();
        }else{
            streampool.readSettingsFromXML(settingsPath);
        }
    }

    public StreamManager getStreamPool() {
        if (streampool == null) {
            Logger.warn("No Streampool defined");
            return null;
        }
        return streampool;
    }

    /* *************************************  L A B E L W O R K E R **********************************************/
    /**
     * Adds the BaseWorker
     */
    public void addLabelWorker() {
        if (this.labelWorker == null)
            labelWorker = new LabelWorker(settingsPath,dQueue,rtvals,dbManager);
        labelWorker.setCommandReq(commandPool);
        labelWorker.setDebugging(debug);
        labelWorker.setMqttWriter(mqttPool);
        labelWorker.setEventListener(this);

        addCommandable(labelWorker,"gens");
    }
    public LabelWorker getLabelWorker() {
        return labelWorker;
    }

    public BlockingQueue<Datagram> getDataQueue() {
        addLabelWorker();
        return dQueue;
    }

    public void loadValMaps(boolean clear){
        if( clear ){
            settingsDoc = XMLtools.readXML(settingsPath);
            labelWorker.clearValMaps();
        }
        XMLfab.getRootChildren(settingsDoc, "dcafs","valmaps","valmap")
                .forEach( ele ->  labelWorker.addValMap( ValMap.readFromXML(ele) ) );

        // Find the path ones?
        XMLfab.getRootChildren(settingsPath, "dcafs","paths","path")
                .forEach( ele -> {
                        String imp = ele.getAttribute("import");

                        int a=1;
                        if( !imp.isEmpty() ){ //meaning imported
                            String file = Path.of(imp).getFileName().toString();
                            file = file.substring(0,file.length()-4);//remove the .xml

                            for( Element vm : XMLfab.getRootChildren(Path.of(imp), "dcafs","paths","path","valmap").collect(Collectors.toList())){
                                if( !vm.hasAttribute("id")){ //if it hasn't got an id, give it one
                                    vm.setAttribute("id",file+"_vm"+a);
                                    a++;
                                }
                                if( !vm.hasAttribute("delimiter") ) //if it hasn't got an id, give it one
                                    vm.setAttribute("delimiter",vm.getAttribute("delimiter"));
                                labelWorker.addValMap( ValMap.readFromXML(vm) );
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
                            labelWorker.addValMap( ValMap.readFromXML(vm) );
                        }
                    }
            );
    }
    /* ***************************************** M Q T T ******************************************************** */
    public void addMqttPool(){
        mqttPool = new MqttPool(settingsPath,rtvals,dQueue);
        addCommandable("mqtt", mqttPool);
    }
    /* *****************************************  T R A N S S E R V E R ***************************************** */
    /**
     * Adds the TransServer listening on the given port
     * 
     * @param port The port the server will be listening on
     */
    public void addTransServer(int port) {

        Logger.info("Adding TransServer");
        trans = new TcpServer(settingsPath, nettyGroup);
        trans.setServerPort(port);
        trans.setDataQueue(dQueue);

        addCommandable("ts",trans);
    }

    /* **********************************  E M A I L W O R K E R *********************************************/
    /**
     * Adds an EmailWorker
     */
    public void addEmailWorker() {
        Logger.info("Adding EmailWorker");
        addLabelWorker();
        emailWorker = new EmailWorker(settingsDoc, dQueue);
        emailWorker.setEventListener(this);
        commandPool.setEmailWorker(emailWorker);
    }
    public Optional<EmailSending> getEmailSender(){
        if(emailWorker!=null)
            return Optional.ofNullable(emailWorker.getSender());
        return Optional.empty();
    }

    /* *****************************************  D I G I W O R K E R **************************************************/
    /**
     * Adds a digiworker, this is a worker talking to a Digi 4g modem via telnet
     */
    public void addDigiWorker() {
        Logger.info("Adding DigiWorker");
        digiWorker = new DigiWorker(settingsDoc);
        digiWorker.setEventListener(this);
        addCommandable("sms",digiWorker);
        commandPool.setSMSSending(digiWorker);
    }
    /* *************************************  D E B U G W O R K E R ***********************************************/
    /**
     * Creates the DebugWorker
     */
    public void addDebugWorker() {
        Logger.info("Adding DebugWorker");
        addLabelWorker();

        debugWorker = new DebugWorker(labelWorker.getQueue(), dbManager, settingsDoc);

        if (this.inDebug() && emailWorker != null) 
            emailWorker.setSending(debugWorker.doEmails());            
    }

    /* ***************************************  T E L N E T S E R V E R ******************************************/
    /**
     * Create the telnet server
     */
    public void addTelnetServer() {

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
    public void addI2CWorker() {
        if( i2cWorker!=null)
            return;

        Logger.info("Adding I2CWorker.");
        if (SystemUtils.IS_OS_WINDOWS) {
            Logger.warn("No native I2C busses on windows... ignoring I2C");
            return;
        }

        i2cWorker = new I2CWorker(settingsPath, dQueue);
        addCommandable("i2c",i2cWorker);
    }

    /* ******************************** * S H U T D O W N S T U F F ***************************************** */
    /**
     * Set the reason for shutting down
     * 
     * @param reason The reason DAS is shutting down
     */
    public void setShutdownReason(String reason) {
        this.sdReason = reason;
        if (reason.startsWith("upgrade")) {
            this.rebootOnShutDown = true;
        }
    }

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

    /* ******************************  * T H R E A D I N G *****************************************/
    /**
     * Start all the threads
     */
    public void startAll() {

        if (labelWorker != null) {
            Logger.info("Starting BaseWorker...");
            new Thread(labelWorker, "BaseWorker").start();// Start the thread
        }
        if (digiWorker != null) {
            Logger.info("Starting DigiWorker...");
            new Thread(digiWorker, "DigiWorker").start();// Start the thread
        }
        if (debug && debugWorker == null) {
            Logger.info("Debug mode but no debugworker created...");
        } else if (debugWorker != null) {
            if (debug || log) {
                Logger.info("Starting DebugWorker...");
                debugWorker.start();// Start the thread
            } else {
                Logger.info("Not in debug mode, not starting debugworker...");
            }
        }
        if (trans != null && trans.isActive()) {
            trans.run(); // Start the server
        }
        if (telnet != null) {
            telnet.run(); // Start the server
        }

        // TaskManager
        if (taskManagerPool != null)
            taskManagerPool.reloadAll();

        // Matrix
        if( matrixClient != null ){
            Logger.info("Trying to login to matrix");
            matrixClient.login();
        }

        Logger.debug("Finished");
    }

    public void haltWorkers() {
        if (labelWorker != null)
            labelWorker.stopWorker();
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
        join.add("Data buffer: " + this.dQueue.size() + " in receive buffer and "+ labelWorker.getWaitingQueueSize()+" waiting...");

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
        if (mqttPool !=null) {
            join.add("\r\n----MQTT----");
            join.add(mqttPool.getMqttBrokersInfo());
        }
        return join.toString();
    }

    @Override
    public void notifyCancelled(String thread) {

        Logger.error("Thread: " + thread + " stopped for some reason.");
        rtvals.getIssuePool().addIfNewAndIncrement("threaddied:" + thread, thread + " died and got restarted");

        switch (thread) {
            case "BaseWorker": // done
                int retries = rtvals.getIssuePool().getIssueTriggerCount("thread died:" + thread);
                if (labelWorker != null && retries < 50) {
                    Logger.error("BaseWorker not alive, trying to restart...");
                    new Thread(labelWorker, "BaseWorker").start();// Start the thread
                } else {
                    Logger.error("BaseWorker died 50 times, giving up reviving.");
                    rtvals.getIssuePool().addIfNewAndIncrement("fatal:" + thread, thread + " permanently dead.");
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
    public void test(){

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