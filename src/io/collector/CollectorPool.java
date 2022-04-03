package io.collector;

import das.Commandable;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.DataProviding;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public class CollectorPool implements Commandable, CollectorFuture {

    static final String UNKNOWN_CMD = "unknown command";

    private final Map<String, FileCollector> fileCollectors = new HashMap<>();
    private final HashMap<String, MathCollector> mathCollectors = new HashMap<>(); // Math collectors

    private final Path workPath;
    private final BlockingQueue<Datagram> dQueue;
    private final EventLoopGroup nettyGroup;
    private final DataProviding dp;

    public CollectorPool(Path workpath, BlockingQueue<Datagram> dQueue, EventLoopGroup nettyGroup, DataProviding dp ){
        this.workPath=workpath;
        this.dQueue=dQueue;
        this.nettyGroup=nettyGroup;
        this.dp=dp;

        // Load the collectors
        loadMathCollectors();
        loadFileCollectors();
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        return switch( request[0] ) {
            case "fc" -> doFileCollector(request, html);
            case "mc" -> "No commands yet";
            default -> "Wrong commandable...";
        };
    }
    public void flushAll(){
        fileCollectors.values().forEach(FileCollector::flushNow);
    }
    /* **************************** MATH COLLECTOR ********************************************** */
    private void loadMathCollectors(){
        mathCollectors.clear();
        var settingsDoc = XMLtools.readXML(workPath.resolve("settings.xml"));
        MathCollector.createFromXml( XMLfab.getRootChildren(settingsDoc,"dcafs","maths","*") ).forEach(
                mc ->
                {
                    mc.addListener(this);
                    mathCollectors.put( mc.getID(),mc);
                    dQueue.add( Datagram.system(mc.getSource()).writable(mc) ); // request the data
                }
        );
    }
    public void addMathCollector( MathCollector mc ){
        mc.addListener(this);
        mathCollectors.put( mc.getID(),mc);
    }
    @Override
    public void collectorFinished(String id, String message, Object result) {
        String[] ids = id.split(":");
        if(ids[0].equalsIgnoreCase("math")){
            dp.setDouble(message,(double)result);
        }
    }
    /* *************************************** F I L E C O L L E C T O R ************************************ */
    private void loadFileCollectors(){
        fileCollectors.clear();
        FileCollector.createFromXml( XMLfab.getRootChildren(workPath.resolve("settings.xml"),"dcafs","collectors","file"), nettyGroup,dQueue, workPath.toString() ).forEach(
            fc ->
            {
                Logger.info("Created "+fc.getID());
                fileCollectors.put(fc.getID().substring(3),fc); // remove the fc: from the front
                dQueue.add( Datagram.system(fc.getSource()).writable(fc) ); // request the data
            }
        );
    }

    public Optional<FileCollector> getFileCollector(String id){
        return Optional.ofNullable(fileCollectors.get(id));
    }
    public String getFileCollectorsList( String eol ){
        StringJoiner join = new StringJoiner(eol);
        join.setEmptyValue("None yet");
        fileCollectors.forEach((key, value) -> join.add(key + " -> " + value.toString()));
        return join.toString();
    }
    public FileCollector addFileCollector( String id ){
        var fc = new FileCollector(id,"1m",nettyGroup,dQueue);
        fileCollectors.put(id, fc);
        return fc;
    }
    public String doFileCollector( String[] request, boolean html ) {
        String[] cmds = request[1].split(",");
        StringJoiner join = new StringJoiner(html?"<br":"\r\n");

        Optional<FileCollector>  fco = Optional.ofNullable(fileCollectors.get(cmds[1]));
        var settingsPath = workPath.resolve("settings.xml");
        switch( cmds[0] ) {
            case "?":
                join.add(TelnetCodes.TEXT_MAGENTA+"The FileCollectors store data from sources in files with custom headers and optional rollover");
                join.add(TelnetCodes.TEXT_GREEN+"Create/Alter the FileCollector"+TelnetCodes.TEXT_YELLOW)
                        .add("   fc:addnew,id,src,path -> Create a blank filecollector with given id, source and path")
                        .add("   fc:alter,id,param:value -> Alter some elements, options: eol, path, sizelimit, src")
                        .add("   fc:reload -> Reload the file collectors");
                join.add(TelnetCodes.TEXT_GREEN+"Add optional parts"+TelnetCodes.TEXT_YELLOW)
                        .add("   fc:addrollover,id,count,unit,format,zip? -> Add rollover (unit options:min,hour,day,week,month,year")
                        .add("   fc:addcmd,id,trigger:cmd -> Add a triggered command, triggers: maxsize,idle,rollover")
                        .add("   fc:addheader,id,headerline -> Adds the header to the given fc")
                        .add("   fc:addsizelimit,id,size,zip? -> Adds a limit of the given size with optional zipping");
                join.add(TelnetCodes.TEXT_GREEN+"Get info"+TelnetCodes.TEXT_YELLOW)
                        .add("   fc:list -> Get a list of all active File Collectors")
                        .add("   fc:? -> Show this message");
                return join.toString();
            case "addnew":
                if( cmds.length<4)
                    return "Not enough arguments given: fc:addnew,id,src,path";
                FileCollector.addBlankToXML(XMLfab.withRoot(workPath.resolve("settings.xml"),"dcafs"),cmds[1],cmds[2],cmds[3]);
                var fc = addFileCollector(cmds[1]);
                fc.addSource(cmds[2]);
                fc.setPath( Path.of(cmds[3]), settingsPath.toString() );

                return "FileCollector "+cmds[1]+" created and added to xml.";
            case "list":
                return getFileCollectorsList(html?"<br":"\r\n");
            case "addrollover":
                if( cmds.length<6)
                    return "Not enough arguments given: fc:addrollover,id,count,unit,format,zip?";

                if( fco.isEmpty() )
                    return "No such fc: "+cmds[1];

                if( fco.get().setRollOver(cmds[4], NumberUtils.toInt(cmds[2]), TimeTools.convertToRolloverUnit(cmds[3]), Tools.parseBool(cmds[5],false)) ) {
                    XMLfab.withRoot( settingsPath, "dcafs", "collectors")
                            .selectOrAddChildAsParent("file", "id", cmds[1])
                            .alterChild("rollover",cmds[4]).attr("count",cmds[2]).attr("unit",cmds[3]).attr("zip",cmds[5]).build();
                    return "Rollover added";
                }
                return "Failed to add rollover";
            case "addheader":
                if( cmds.length<3)
                    return "Not enough arguments given: fc:addheader,id,header";

                if( fco.isEmpty() )
                    return "No such fc: "+cmds[1];
                fco.get().flushNow();
                fco.get().addHeaderLine(cmds[2]);
                XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                        .selectOrAddChildAsParent("file", "id", cmds[1])
                        .addChild("header", cmds[2]).build();
                return "Header line added to "+cmds[1];
            case "addcmd":
                if( cmds.length<3)
                    return "Not enough arguments given: fc:adcmd,id,trigger:cmd";

                if( fco.isEmpty() )
                    return "No such fc: "+cmds[1];

                String[] cmd = cmds[2].split(":");
                if( fco.get().addTriggerCommand(cmd[0],cmd[1]) ) {
                    XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                            .selectOrAddChildAsParent("file", "id", cmds[1])
                            .addChild("cmd", cmd[1]).attr("trigger", cmd[0]).build();
                    return "Triggered command added to "+cmds[1];
                }
                return "Failed to add command, unknown trigger?";
            case "addsizelimit":
                if( cmds.length<4)
                    return "Not enough arguments given: fc:addsizelimit,id,size,zip?";

                if( fco.isEmpty() )
                    return "No such fc: "+cmds[1];

                fco.get().setMaxFileSize(cmds[2],Tools.parseBool(cmds[3],false));
                XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                        .selectOrAddChildAsParent("file", "id", cmds[1])
                        .addChild("sizelimit", cmds[2]).attr("zip", cmds[3]).build();
                return "Size limit added to "+cmds[1];
            case "alter":
                if( cmds.length<3)
                    return "Not enough arguments given: fc:alter,id,param:value";
                int a = cmds[2].indexOf(":");
                if( a == -1)
                    return "No valid param:value pair";

                String[] alter = {cmds[2].substring(0,a),cmds[2].substring(a+1)};

                if( fco.isEmpty() )
                    return "No such fc: "+cmds[1];

                var fab = XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                        .selectOrAddChildAsParent("file", "id", cmds[1]);

                switch(alter[0]){
                    case "path":
                        fco.get().setPath(Path.of(alter[1]),workPath.toString());
                        fab.alterChild("path",alter[1]).build();
                        return "Altered the path";
                    case "sizelimit":
                        fco.get().setMaxFileSize(alter[1]);
                        fab.alterChild("sizelimit",alter[1]).build();
                        return "Altered the size limit to "+alter[1];
                    case "eol":
                        fco.get().setLineSeparator( Tools.fromEscapedStringToBytes(alter[1]));
                        fab.attr("eol",alter[1]).build();
                        return "Altered the eol string to "+alter[1];
                    case "charset":
                        fab.attr("charset",alter[1]).build();
                        return "Altered the charset to "+alter[1];
                    case "src":
                        fco.get().addSource(alter[1]);
                        fab.attr("src",alter[1]).build();
                        return "Source altered to "+alter[1];
                    default:
                        return "No such param "+alter[0];
                }
            case "reload":
                if( cmds.length==1) {
                    loadFileCollectors();
                    return "Reloaded all filecollectors";
                }

                if( fco.isEmpty() )
                    return "No such fc: "+cmds[1];

                var opt = XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                        .getChild("file", "id", cmds[1]);
                if( opt.isPresent() ) {
                    fco.get().flushNow();
                    fco.get().readFromXML(opt.get(), workPath.toString() );
                }

        }
        return UNKNOWN_CMD+": "+request[0]+":"+request[1];
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
