package util.task;

import io.email.EmailSending;
import io.sms.SMSSending;
import io.stream.StreamManager;
import io.Writable;
import das.CommandPool;
import das.Commandable;
import util.data.DataProviding;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.StringJoiner;

public class TaskManagerPool implements Commandable {

    private String workPath;
    HashMap<String, TaskManager> tasklists = new HashMap<>();
    DataProviding dp;
    CommandPool cmdReq;
    StreamManager streamManager;
    EmailSending emailSender;
    SMSSending smsSender;

    static final String UNKNOWN_CMD = "unknown command";

    public TaskManagerPool(String workpath, DataProviding dp, CommandPool cmdReq){
        this.workPath=workpath;
        this.dp=dp;
        this.cmdReq=cmdReq;

    }
    public void setStreamPool(StreamManager streamManager){
        this.streamManager = streamManager;
    }
    public void setEmailSending(EmailSending emailSender ){
        this.emailSender=emailSender;
    }
    public void setSMSSending(SMSSending smsSending){
        this.smsSender=smsSending;
    }
    public void readFromXML() {
        var xml = XMLtools.readXML(Path.of(workPath,"settings.xml"));
        for(Element e: XMLtools.getAllElementsByTag(xml, "taskmanager") ){
            Logger.info("Found reference to TaskManager in xml.");
            var p = Path.of(e.getTextContent());
            if( !p.isAbsolute())
                p = Path.of(workPath).resolve(p);

            if (Files.exists(p)) {
                addTaskList(e.getAttribute("id"), p);
            } else {
                Logger.error("No such task xml: " + p);
            }
        }
    }
    public TaskManager addTaskList( String id, TaskManager tl){
        tl.setStreamPool(streamManager);
        tl.setCommandReq(cmdReq);
        tl.setWorkPath(workPath);
        tl.setEmailSending(emailSender);
        tl.setSMSSending(smsSender);

        tasklists.put(id,tl);
        return tl;
    }
    public Optional<TaskManager> getTaskList(String id ){
        return Optional.ofNullable(tasklists.get(id));
    }
    public TaskManager addTaskList( String id, Path scriptPath){
        var tm = new TaskManager(id,dp,cmdReq);
        tm.setScriptPath(scriptPath);
        return addTaskList(id,tm);
    }
    /**
     * Check the TaskManager for tasks with the given keyword and start those
     *
     * @param keyword The keyword to look for
     */
    public void startKeywordTask(String keyword) {
        Logger.info("Checking for tasklists with keyword " + keyword);
        tasklists.forEach( (k, v) -> v.startKeywordTask(keyword) );
    }

    /**
     * Change a state stored by the TaskManagers
     *
     * @param state The format needs to be identifier:state
     */
    public void changeManagersState(String state) {
        tasklists.values().forEach(v -> v.changeState(state));
    }

    /**
     * Reload the script of a given TaskManager
     *
     * @param id The id of the manager
     * @return Result of the attempt
     */
    public String reloadTasklist(String id) {
        if (id.endsWith(".xml")) {
            for (TaskManager t : tasklists.values()) {
                if (t.getXMLPath().toString().endsWith(id)) {
                    return t.reloadTasks() ? "Tasks loaded successfully." : "Tasks loading failed.";
                }
            }

            addTaskList(id.replace(".xml", ""), Path.of(workPath,"scripts", id));
            return "No TaskManager associated with the script, creating one.";
        }
        TaskManager tm = tasklists.get(id);
        if (tm == null)
            return "Unknown manager.";
        return tm.reloadTasks() ? "Tasks loaded successfully." : "Tasks loading failed.";
    }

    /**
     * Try to start the given taskset in all the tasklists
     * @param taskset The taskset to start
     */
    public void startTaskset( String taskset ){
        tasklists.values().forEach( tl -> tl.startTaskset(taskset));
    }

    /**
     * Reload all the tasklists
     */
    public void reloadAll(){
        for (TaskManager tl : tasklists.values())
            tl.reloadTasks();
    }
    /* ******************************************* C O M M A N D A B L E ******************************************* */
    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        String nl = html?"<br>":"\r\n";
        StringJoiner response = new StringJoiner(nl);
        String[] cmd = request[1].split(",");

        if( tasklists.isEmpty() && !cmd[0].equalsIgnoreCase("addblank") && !cmd[0].equalsIgnoreCase("load"))
            return "No TaskManagers active, only tm:addblank available.";

        TaskManager tl;

        switch( cmd[0] ){
            case "?":
                response.add( "tm:reloadall -> Reload all the taskmanagers")
                        .add( "tm:stopall -> Stop all the taskmanagers")
                        .add( "tm:managers -> Get a list of currently active TaskManagers")
                        .add( "tm:remove,x -> Remove the manager with id x")
                        .add( "tm:run,id:task(set) -> Run the given task(set) from taskmanager id, taskset has priority if both exist")
                        .add( "tm:addblank,id -> Add a new taskmanager, creates a file etc")
                        .add( "tm:x,y -> Send command y to manager x");
                return response.toString();
            case "addtaskset":
                if( cmd.length != 3)
                    return "Not enough parameters, need tm:addtaskset,id,tasksetid";
                tl = tasklists.get(cmd[1]);
                if( tl !=null ) {
                    if( tl.addBlankTaskset(cmd[2]) ){
                        return "Taskset added";
                    }
                    return "Failed to add taskset";
                }
                return "No such TaskManager "+cmd[1];
            case "addblank":
                if( cmd.length != 2)
                    return "Not enough parameters, need tm:addblank,id";

                // Add to the settings xml
                try {
                    Files.createDirectories(Path.of(workPath,"tmscripts"));
                } catch (IOException e) {
                    Logger.error(e);
                }

                var p = Path.of(workPath,"tmscripts",cmd[1]+".xml");
                if( Files.notExists(p)) {
                    XMLfab tmFab = XMLfab.withRoot(Path.of(workPath,"settings.xml"), "dcafs","settings");
                    tmFab.addChild("taskmanager","tmscripts"+ File.separator+cmd[1]+".xml").attr("id",cmd[1]).build();
                    tmFab.build();

                    // Create an empty file
                    XMLfab.withRoot(p, "tasklist")
                            .comment("Any id is case insensitive")
                            .comment("Reload the script using tm:reload," + cmd[1])
                            .comment("If something is considered default, it can be omitted")
                            .comment("There's no hard limit to the amount of tasks or tasksets")
                            .comment("Task debug info has a separate log file, check logs/taskmanager.log")
                            .addParent("tasksets", "Tasksets are sets of tasks")
                            .comment("Below is an example taskset")
                            .addChild("taskset").attr("run", "oneshot").attr("id", "example").attr("info", "Example taskset that says hey and bye")
                            .comment("run can be either oneshot (start all at once) or step (one by one), default is oneshot")
                            .down().addChild("task", "Hello World from " + cmd[1]).attr("output", "telnet:info")
                            .addChild("task", "Goodbye :(").attr("output", "telnet:error").attr("trigger", "delay:2s")
                            .up()
                            .addParent("tasks", "Tasks are single commands to execute")
                            .comment("Below is an example task, this will be called on startup or if the script is reloaded")
                            .addChild("task", "taskset:example").attr("output", "system").attr("trigger", "delay:1s")
                            .comment("This task will wait a second and then start the example taskset")
                            .comment("A task doesn't need an id but it's allowed to have one")
                            .comment("Possible outputs: stream:id , system (default), log:info, email:ref, manager, telnet:info/warn/error")
                            .comment("Possible triggers: delay, interval, while,")
                            .comment("For more extensive info and examples, check Reference Guide - Taskmanager in the manual")
                            .build();
                }else{
                    return "Already a file in the tmscripts folder with that name, load it with tm:load,"+cmd[1];
                }
                // Add it to das
                addTaskList(cmd[1], p);
                return "Tasklist added, use tm:reload,"+cmd[1]+" to run it.";
            case "load":
                if( cmd.length != 2)
                    return "Not enough parameters, missing id";
                if( tasklists.get(cmd[1])!=null)
                    return "Already a taskmanager with that id";
                if( Files.notExists( Path.of(workPath,"tmscripts",cmd[1]+".xml") ))
                    return "No such script in the default location";
                if( addTaskList(cmd[1], Path.of(workPath,"tmscripts",cmd[1]+".xml")).reloadTasks()) {
                    XMLfab.withRoot(Path.of(workPath,"settings.xml"), "dcafs","settings")
                            .addChild("taskmanager","tmscripts"+ File.separator+cmd[1]+".xml").attr("id",cmd[1]).build();
                    return "Loaded " + cmd[1];
                }
                return "Failed to load tasks from "+cmd[1];
            case "reload":
                if( cmd.length != 2)
                    return "Not enough parameters, missing id";
                tl = tasklists.get(cmd[1]);
                if( tl == null)
                    return "No such TaskManager: "+cmd[1];
                if( tl.reloadTasks() )
                    return "Tasks reloaded";
                return "Tasks failed to reload";
            case "reloadall":
                for(TaskManager tam : tasklists.values() )
                    tam.reloadTasks();
                return "Reloaded all TaskManagers.";
            case "stopall":
                for(TaskManager tam : tasklists.values() )
                    tam.stopAll("baseReqManager");
                return "Stopped all TaskManagers.";
            case "managers": case "list":
                response.add("Currently active TaskManagers:");
                tasklists.keySet().forEach(response::add);
                return response.toString();
            case "run":
                if( cmd.length != 2)
                    return "Not enough parameters, missing manager:taskset";
                String[] task = cmd[1].split(":");

                if( task[0].equalsIgnoreCase("*")){
                    int a=0;
                    for( var t : tasklists.values()){
                        if( t.hasTaskset(task[1])) {
                            a+=t.startTaskset(task[1]).isEmpty()?0:1;
                        }else{
                            a+=t.startTask(task[1])?1:0;
                        }
                    }
                    if(a==0)
                        return "Nothing started";
                    return "Started "+a+" task(set)s";
                }else{
                    tl = tasklists.get(task[0]);
                    if( tl == null)
                        return "No such taskmanager: "+task[0];
                    if( tl.hasTaskset(task[1])){
                        return tl.startTaskset(task[1]);
                    }else{
                        return tl.startTask(task[1])?"Task started":"No such task(set) "+task[1];
                    }
                }

            case "remove":
                if( tasklists.remove(cmd[1]) == null ){
                    return "Failed to remove the TaskManager, unknown key";
                }else{
                    return "Removed the TaskManager";
                }
            case "startkeyword":
                if( cmd.length != 2)
                    return "Not enough arguments tm:startkeyword,keyword";
                startKeywordTask(cmd[1]);
                return "Tried starting the keyword stuff";
            case "getpath":
                if( cmd.length != 2)
                    return "";
                tl = tasklists.get(cmd[1]);
                if( tl != null){
                    return tl.getXMLPath().toString();
                }else{
                    return "";
                }
            default:
                if( cmd.length==1)
                    return UNKNOWN_CMD+": "+ Arrays.toString(request);

                tl = tasklists.get(cmd[0]);
                if( tl != null ){
                    return tl.replyToCommand( request[1].substring(request[1].indexOf(",")+1), html);
                }else{
                    return "No such TaskManager: "+cmd[0];
                }
        }
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
