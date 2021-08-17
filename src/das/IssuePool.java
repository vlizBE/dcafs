package das;

import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.DataProviding;
import util.task.RtvalCheck;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class IssuePool implements Commandable{

    private final HashMap<String,Issue> issues = new HashMap<>();
    private final BlockingQueue<Datagram> dQueue;
    private final Path settingsPath;
    private final DataProviding dp;

    public IssuePool( BlockingQueue<Datagram> dQueue, Path settingsPath, DataProviding dp){
        this.dQueue=dQueue;
        this.settingsPath=settingsPath;
        this.dp=dp;
        readFromXML();
    }

    /**
     * Read the issues from the settings.xml,
     * @return True if any were read
     */
    public boolean readFromXML( ){
        XMLfab.getRootChildren(settingsPath,"dcafs","issues","*").forEach(
                issueEle ->
                {
                    String id = XMLtools.getStringAttribute(issueEle,"id","");
                    var issue = new Issue(XMLtools.getChildValueByTag(issueEle,"message",""));
                    String start = XMLtools.getChildValueByTag(issueEle,"test","");
                    start = XMLtools.getChildValueByTag(issueEle,"startif",start);
                    String stop = XMLtools.getChildValueByTag(issueEle,"stopif","");
                    issue.setTests( start,stop );

                    for( Element cmdEle : XMLtools.getChildElements(issueEle,"cmd")){
                        String cmd = cmdEle.getTextContent();
                        cmd = cmd.replace("{message}",issue.getMessage());
                        switch( cmdEle.getAttribute("when") ){
                            case "start": issue.atStart(cmd); break;
                            case "stop": issue.atStop(cmd); break;
                            default:
                                Logger.error("Unknown when used: "+cmdEle.getAttribute("when"));
                        }
                    }
                    issues.put(id,issue);
                }
        );
        return !issues.isEmpty();
    }
    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        var cmds = request[1].split(",");
        String nl = html?"<br>":"\r\n";
        Issue issue;
        StringJoiner join;
        switch( cmds[0] ){
            case "?":
                join = new StringJoiner(nl);
                join.add("issue:? -> Show this message")
                        .add("issue:addblank -> Add a blank issue node with some example issues")
                        .add("issue:start -> Start an issue")
                        .add("issue:stop -> Stop an active issue")
                        .add("issue:add,id,message -> Add a new issue")
                        .add("issue:listactive -> Return a list of active issues")
                        .add("issue:listresolved -> Return a list of currently inactive issues")
                        .add("issue:resetall -> Reset the issue counters")
                        .add("issue:listall -> List all id/message pairs of the issues");
                return join.toString();
            case "add":
                if( cmds.length!=3)
                    return "Not enough parameters: issue:add,issueid,message";
                addIssue(cmds[1],cmds[2]);
                XMLfab.withRoot(settingsPath,"dcafs").digRoot("issues")
                        .addParentToRoot("issue").attr("id",cmds[1]).content(cmds[2]).build();
                return "Issue added";
            case "addblank":
                XMLfab.withRoot(settingsPath,"dcafs").digRoot("issues")
                        .addParentToRoot("issue","Issue without commands").attr("id","issueid")
                        .content("Message/info on the issue")
                        .addParentToRoot("issue","Issue with commands").attr("id","issue2")
                        .addChild("message","Message/info on the issue")
                        .addChild("cmd","cmd to run on start").attr("when","start")
                        .addChild("cmd","cmd to run on stop").attr("when","stop").build();
                return "Blank added to xml";
            case "trip":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:trip,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null) {
                    issue.increment();
                    return "Increased count of "+cmds[1];
                }
                return "No such issue: "+cmds[1];
            case "start":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:start,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null)
                    return issue.start()?"Issue started "+cmds[1]:"Issue already active";
                return "No such issue: "+cmds[1];
            case "stop":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:stop,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null){
                    return issue.stop()?"Issue stopped "+cmds[1]:"Issue not active";
                }
                return "No such issue: "+cmds[1];
            case "test":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:test,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null){
                   if(issue.doTest() )
                       return "Test run";
                   return "No proper test found";
                }
                return "Invalid issue or no test";
            case "resetall":
                issues.values().forEach(Issue::clear);
                return "Issues reset";
            case "listactive":
                join = new StringJoiner(nl,html?"<b>Active Issues</b><br>":"Active Issues\r\n","");
                join.setEmptyValue("None yet.");
                issues.values().stream().filter( is -> is.active)
                        .forEach( is->join.add(is.message+" --> total time "+ TimeTools.convertPeriodtoString(is.getTotalActiveTime(), TimeUnit.SECONDS)));
                return join.toString();
            case "listresolved":
                join = new StringJoiner(nl,(html?"<b>Resolved Issues</b><br>":"Resolved Issues\r\n"),"");
                join.setEmptyValue("None Yet");
                issues.values().stream().filter( is -> !is.active && is.totalCycles!=0 )
                        .forEach( is->join.add(is.message+" --> "
                                + (is.totalCycles==1?"once, ":is.totalCycles+" occurrences, ")+"total time "
                                + TimeTools.convertPeriodtoString(is.totalActiveTime, TimeUnit.SECONDS)));
                return join.toString();
            case "listall":
                return getReport(html,false);
        }
        return "unknown command: "+request[0]+":"+request[1];
    }
    public String getReport( boolean html, boolean clear ){
        String nl = html?"<br>":"\r\n";
        var join = new StringJoiner(nl,html?"<b>Active Issues</b><br>":"Active Issues\r\n","");
        join.setEmptyValue("None yet.");
        issues.values().stream().filter( is -> is.active)
                .forEach( is->join.add(is.message+" --> total time "+ TimeTools.convertPeriodtoString(is.getTotalActiveTime(), TimeUnit.SECONDS)));


        var join2 = new StringJoiner(nl,(html?"<b>Resolved Issues</b><br>":"Resolved Issues\r\n"),"");
        join2.setEmptyValue("None Yet");
        issues.values().stream().filter( is -> !is.active && is.totalCycles!=0 )
                .forEach( is->join2.add(is.message+" --> "
                        + (is.totalCycles==1?"once, ":is.totalCycles+" occurrences, ")+"total time "
                        + TimeTools.convertPeriodtoString(is.totalActiveTime, TimeUnit.SECONDS)));

        if( clear ){
            issues.values().forEach( Issue::clear );
        }
        return (html?"<b>Issues</b><br>":"Issues\r\n")+join+nl+join2;
    }
    /**
     * Resets/Clears the issues of which the id starts with the given text
     * @param startswith The text the id should start with
     */
    public void resetIssues(String startswith){
        issues.entrySet().stream().filter( ent -> ent.getKey().startsWith(startswith)).forEach( ent -> ent.getValue().clear() );
    }

    /**
     * Add the issue if it's new and start it
     * @param id The id of the issue
     * @param message The message explaining it
     */
    public void addIfNewAndStart( String id, String message){
        var is = issues.get(id);
        if( is==null)
            addIssue(id,message);
        issues.get(id).start();
    }

    /**
     * Add the issue if it doesn't exist and stop it if it does exist and is active
     * @param id The id pf the issue
     * @param message The message explaining it
     */
    public void addIfNewAndStop( String id, String message){
        var is = issues.get(id);
        if( is==null) {
            addIssue(id, message);
        }else{
            is.stop();
        }
    }

    /**
     * Add the issue if new and increment the issue count with one (same as toggle on and off)
     * @param id The id of the issue
     * @param message The message explaining it
     */
    public void addIfNewAndIncrement( String id, String message){
        var is = issues.get(id);
        if( is==null)
            addIssue(id,message);
        issues.get(id).increment();
    }

    /**
     * Get the amount of times the issue was triggered
     * @param id The id to look for
     * @return The amount of time it was triggered or 0 if not found
     */
    public int getIssueTriggerCount(String id){
        var is = issues.get(id);
        if( is!=null)
            return is.totalCycles;
        return 0;
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
    public void addIssue(String id, String message){
        issues.put(id,new Issue(message));
    }
    public boolean isActive( String id ){
        var is = issues.get(id);
        if( is!=null)
            return is.active;
        return false;
    }
    public ArrayList<String> getActives(){
        return issues.entrySet().stream().filter(ent -> ent.getValue().isActive()).map(Map.Entry::getKey).collect(Collectors.toCollection(ArrayList::new));
    }
    public class Issue{

        LocalDateTime lastStartTime;
        LocalDateTime lastEndTime;
        long totalActiveTime=0;
        int totalCycles=0;
        private boolean active = false;
        String message;
        ArrayList<String> startCmds;
        ArrayList<String> stopCmds;

        RtvalCheck activate;
        RtvalCheck resolve;

        /* Creation */
        public Issue( String message ){
            this.message=message;
        }

        public void setTests(String activateTest, String resolveTest){
            if( activateTest.isEmpty())
                return;

            activate = new RtvalCheck(activateTest);
            if( !resolveTest.isEmpty()){
                resolve = new RtvalCheck(resolveTest);
            }
        }

        public boolean doTest( ){
            if( activate==null) {
                Logger.error("Tried to check an issue '"+message+ "' without proper function");
                return false;
            }
            if( resolve!=null){ // meaning both and activate test and a resolve test
                if( active ){
                    if( resolve.test(dp,getActives()) )
                        stop();
                }else{
                    if( activate.test(dp,getActives()))
                        start();
                }
            }else{ //meaning only an activated test
                if( activate.test(dp,getActives()) ){
                    start();
                }else{
                    stop();
                }
            }
            return true;
        }
        public Issue atStart( String cmd){
            if( cmd==null)
                return this;
            if( startCmds==null)
                startCmds = new ArrayList<>();
            if( !startCmds.contains(cmd))
                startCmds.add(cmd);
            return this;
        }
        public Issue atStop( String cmd){
            if( cmd==null)
                return this;
            if( stopCmds==null)
                stopCmds = new ArrayList<>();
            if( !stopCmds.contains(cmd))
                stopCmds.add(cmd);
            return this;
        }

        /* Usage */
        public boolean start(){
            return start(LocalDateTime.now(ZoneOffset.UTC));
        }
        public boolean start( LocalDateTime dt ){
            if( active )
                return false;
            active = true;
            if( totalCycles < 10 || (totalCycles<100&&totalCycles%10==0) || (totalCycles > 100 && totalCycles%50==0) ){ // Show warning every time if first 10 times, every 10 times till 100 every 50 times afterwards
                Logger.warn( "Issue raised: "+message +" ("+totalCycles+")");
            }
            lastStartTime = dt;
            lastEndTime=null;
            if( startCmds!=null)
                startCmds.forEach( c -> dQueue.add(Datagram.system(c)));
            return true;
        }
        public void increment(){
            lastStartTime=LocalDateTime.now(ZoneOffset.UTC);
            totalCycles++;
        }
        public long secondsSinceStart(){
            return Duration.between(lastStartTime, LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
        }
        public long getTotalActiveTime(){
            return totalActiveTime+(active?secondsSinceStart():0);
        }
        /* De Activate */
        public boolean stop( ){
            return stop(LocalDateTime.now(ZoneOffset.UTC));
        }
        public boolean stop( LocalDateTime dt ){
            if( !active )
                return false;
            active = false;
            totalCycles++;
            lastEndTime = dt;
            totalActiveTime += Duration.between(lastStartTime, lastEndTime).getSeconds();
            if( stopCmds != null)
                stopCmds.forEach( c -> dQueue.add(Datagram.system(c)));
            if( totalCycles < 10 || (totalCycles<100&&totalCycles%10==0) || (totalCycles > 100 && totalCycles%50==0) ){ // Show warning every time if first 10 times, every 10 times till 100 every 50 times afterwards
                Logger.info( "Issue resolved: "+message +" ("+totalCycles+")");
            }
            return true;
        }
        public void clear(){
            totalCycles=0;
            totalActiveTime=0;
        }
        /* Status */
        public boolean isActive(){
            return active;
        }
        public LocalDateTime getStart(){
            return lastStartTime;
        }
        public LocalDateTime getEnd(){
            return lastEndTime;
        }
        public String getMessage(){
            return message;
        }
    }
}
