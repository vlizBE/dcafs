package io.matrix;

import das.Commandable;
import io.Writable;
import io.forward.MathForward;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.DataProviding;
import util.math.MathFab;
import util.tools.FileTools;
import util.tools.Tools;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MatrixClient implements Writable, Commandable {

    private static String root = "_matrix/";
    public static String client = root+"client/v3/";
    public static String media = root+"media/v3/";

    public static String login = client+"login";
    public static String logout = client+"logout";
    public static String logout_all = client+"logout/all";
    public static String whoami = client+"account/whoami";
    public static String presence = client+"presence/";
    public static String rooms = client+"rooms/";
    public static String knock = client+"knock/";
    public static String sync = client+"sync";
    public static String user  = client+"user/";
    public static String upload = media+"upload/";
    public static String keys = client+"keys/";

    public static String push = client+"pushers";
    public static String addPush =push+"/set";

    String userName;
    String pw;
    String server;

    ExecutorService executorService = Executors.newFixedThreadPool(2);
    String accessToken = "";
    String deviceID = "";
    String userID;
    HashMap<String,String> roomPresence = new HashMap<>();
    HashMap<String,String> files = new HashMap<>();
    String since = "";
    HttpClient httpClient;

    BlockingQueue<Datagram> dQueue;
    MathForward math;
    boolean downloadAll=true;
    Path dlFolder=Path.of("downloads");
    DataProviding dp;


    public MatrixClient(BlockingQueue<Datagram> dQueue, DataProviding dp, Element matrixEle){
        this.dQueue=dQueue;
        math = new MathForward(null,dp);
        readFromXML(matrixEle);
    }

    /**
     * Reads the settings from an xml element
     * @param matrixEle The element containing the matrix info
     */
    public void readFromXML(Element matrixEle ){
        String u = XMLtools.getStringAttribute(matrixEle,"user","");
        if( u.isEmpty()) {
            Logger.error("Invalid matrix user");
            return;
        }
        if( u.contains(":")){ // If the format is @xxx:yyyy.zzz
            userID=u;
            userName=u.substring(1,u.indexOf(":"));
            server = "http://"+u.substring(u.indexOf(":")+1);
        }else{
            userName=u;
            server = XMLtools.getStringAttribute(matrixEle,"host","");
            userID="@"+u+":"+server.substring(7,server.length()-1);
        }
        server += server.endsWith("/")?"":"/";
        pw = XMLtools.getStringAttribute(matrixEle,"pass","");
        for( var rm : XMLtools.getChildElements(matrixEle,"room") )
            roomPresence.put( rm.getAttribute("id"),rm.getTextContent() );
    }
    public void login(){

        var json = new JSONObject().put("type","m.login.password")
                .put("identifier",new JSONObject().put("type","m.id.user").put("user",userName))
                .put("password",pw);

        httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();

        asyncPOST( login, json, res -> {
                                    if( res.statusCode()==200 ) {
                                        JSONObject j = new JSONObject(res.body());
                                        accessToken = j.getString("access_token");
                                        deviceID = j.getString("device_id");

                                        setupFilter();
                                        sync(true);
                                        for( var room : roomPresence.values())
                                            joinRoom(room);
                                        return true;
                                    }
                                    processError(res);
                                    return false;
                                }
                    );
    }

    public void hasFilter(){
        asyncGET(user+userID+"/filter/1",
                            res -> {
                                    var body=new JSONObject(res.body());
                                    if( res.statusCode()!=200){
                                        Logger.warn("matrix -> No such filter yet.");
                                        if( body.getString("error").equalsIgnoreCase("No such filter")) {
                                            setupFilter();
                                        }
                                        return false;
                                    }else{
                                        Logger.info("matrix -> Active filter:"+res.body());
                                        return true;
                                    }
                                });
    }

    private void setupFilter(){

        Optional<Path> filterOpt = FileTools.getPathToResource(this.getClass(),"filter.json");
        JSONObject js = new JSONObject(new JSONTokener(FileTools.readTxtFileToString(filterOpt.get().toString())));
        asyncPOST( user+userID+"/filter",js, res -> {
                                if( res.statusCode() != 200 ) {
                                    Logger.info("matrix -> Filters applied");
                                    return true;
                                }
                                processError(res);
                                return true;
                            });
    }


    public void keyClaim(){

        JSONObject js = new JSONObject()
                .put("one_time_keys",new JSONObject().put(userID,new JSONObject().put(deviceID,"signed_curve25519")))
                .put("timeout",10000);

        asyncPOST( keys+"claim",js,
                            res -> {
                                if( res.statusCode()==200 ){
                                    Logger.info("matrix -> Key Claimed");
                                    return true;
                                }
                                processError(res);
                                return false;
                            }
                    );
    }


    public void sync( boolean first){
        try {
            String url = server+sync +"?access_token="+accessToken+"&timeout=10000&filter=1&set_presence=online";
            var request = HttpRequest.newBuilder(new URI(url+(since.isEmpty()?"":("&since="+since))));

            httpClient.sendAsync( request.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        var body = new JSONObject(res.body());
                        if( res.statusCode()==200 ){
                            since = body.getString("next_batch");
                            if( !first ) {
                                try {
                                    var b = body.getJSONObject("device_one_time_keys_count");
                                    if (b != null) {
                                        if (b.getInt("signed_curve25519") == 0) {
                                            //  keyClaim();
                                        }
                                    }
                                    getRoomEvents(body);
                                } catch (org.json.JSONException e) {
                                    System.err.println(e);
                                }
                            }
                            executorService.execute( ()->sync(false));
                            return true;
                        }
                        processError(res);
                        executorService.execute( ()->sync(false));
                        return false;
                    });
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    public void requestRoomInvite( String room ){
        Logger.info("Requesting invite to "+room);
        asyncPOST( knock+room,new JSONObject().put("reason","i want to"),
                res -> {
                    var body = new JSONObject(res.body());
                    if( res.statusCode()==200 ){
                        System.out.println(body);
                        return true;
                    }
                    processError(res);
                    return false;
        });
    }
    /**
     * Join a specific room (by room id not alias) and make it the active one
     * @param room
     */
    public void joinRoom( String room ){
        asyncPOST( rooms+room+"/join",new JSONObject().put("reason","Feel like it"),
                res -> {
                    var body = new JSONObject(res.body());
                    if( res.statusCode()==200 ){
                        // Joined the room
                        Logger.info("matrix -> Joined the room! " + body.getString("room_id"));
                        sendMessage(room, "Have no fear, "+userName+" is here! :)");
                        return true;
                    }
                    processError(res);
                    return false;
                });
    }

    public void getRoomEvents( JSONObject js){

        //System.out.println(js);
        var join = getJSONSubObject(js,"rooms","join");
        if( join.isEmpty())
            return;

        js = join.get();

        // Get room id
        String originRoom = js.keys().next();

        // Get events
        var events = getJSONArray(js,originRoom,"timeline","events");

        if( events.isEmpty())
            return; // Return if no events

        for( var event :events ){
            //System.out.println("type:"+event.getString("type"));
            String eventID = event.getString("event_id");
            String from = event.getString("sender");
            confirmRead( originRoom, eventID); // Confirm received

            if( from.equalsIgnoreCase(userID)){
                System.out.println("Ignored own event"); // confirm?
                System.out.println(event);
                continue;
            }
            switch( event.getString("type")){
                case "m.room.redaction":
                    break;
                case "m.room.message":
                    var content = event.getJSONObject("content");
                    String body = content.getString("body");
                    switch( content.getString("msgtype")){
                        case "m.image": case "m.file":
                            files.put(body,content.getString("url"));
                            Logger.info("Received link to "+body+" at "+files.get(body));
                            if( downloadAll )
                                downloadFile(body,null);
                            break;
                        case "m.text":
                          //  Logger.info("Received message in "+originRoom+" from "+from+": "+body);
                            if( body.startsWith("das") || body.startsWith(userName)){ // check if message for us
                                body = body.replaceAll("("+userName+"|das):?","").trim();
                                var d = Datagram.build(body).label("matrix").origin(originRoom +"|"+ from).writable(this);
                                dQueue.add(d);
                            }else if( body.matches(".+=[0-9]*$")){
                                var sp = body.split("=");
                                double d = NumberUtils.toDouble(sp[1].trim(),Double.NaN);
                                if( Double.isNaN(d)){
                                    sendMessage(originRoom, "Invalid number given, can't parse "+sp[1]);
                                }else{
                                    math.addNumericalRef(sp[0].trim(), d);
                                    sendMessage( originRoom, "Stored "+sp[1]+" as "+sp[0] );
                                }
                            }else if(body.startsWith("solve ")|| body.matches(".+=[a-zA-Z?]+?")) {

                                var split = body.split("=");
                                var op = split[0];
                                if( op.startsWith("*"))
                                    op.substring(2);
                                op = op.replace("solve ","").trim();

                                var ori=op;
                                op = Tools.alterMatches(op,"^[^{]+","[{]?[a-zA-Z:]+","{d:matrix_","}");
                                var dbl = math.solveOp( op );
                                if( Double.isNaN(dbl)){
                                    sendMessage(originRoom,"Failed to process: "+ori);
                                    continue;
                                }

                                var res=""+dbl;
                                if( res.endsWith(".0"))
                                    res=res.substring(0,res.indexOf("."));
                                if( split.length==1 || split[1].equalsIgnoreCase("?")){
                                    if( res.length()==1 ){
                                        sendMessage( originRoom,"No offense but... *"+userName+" raises "+res+" fingers. *");
                                    }else{
                                        sendMessage( originRoom, ori +" = "+res );
                                    }
                                }else{
                                    math.addNumericalRef(split[1],dbl);
                                    sendMessage( originRoom, "Stored "+res +" as "+split[1] );
                                }
                            }else{
                                Logger.info(from +" said "+body+" to someone/everyone");
                            }
                            break;
                        default:
                            Logger.info("Event of type:"+event.getString("type"));
                            break;
                    }
                    break;
            }
        }
    }

    /**
     * Send a confirmation of receival of the given event
     * @param room The room the event occurred in
     * @param eventID The id of the event
     */
    public void confirmRead(String room, String eventID){
        asyncPOST( rooms+room+"/receipt/m.read/"+eventID,new JSONObject(),
                res -> {
                    if(res.statusCode()==200){
                        return true;
                    }
                    processError(res);
                    return false;
                });
    }

    /**
     * Upload a file to the repository (doesn't work yet)
     * @param room The room to use
     * @param path The path to the file
     */
    public void sendFile( String room, Path path){

        try{
            String url=server+media + "upload";
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            url+="&Content-Type=m.file";
            url+="&filename="+path.getFileName().toString();

            var request = HttpRequest.newBuilder(new URI(url))
                    .POST(HttpRequest.BodyPublishers.ofFile(path))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        if( res.statusCode()==200){
                            System.out.println(res.body());
                            String mxc = new JSONObject(res.body()).getString("content_uri"); // Got a link... now post it?
                            shareFile(room,mxc,path.getFileName().toString());
                            return true;
                        }
                        processError(res);
                        return false;
                    } );
        } catch (URISyntaxException | FileNotFoundException e) {
            e.printStackTrace();
        }
    }
    public boolean downloadFile( String id, Writable wr ){
        String mxc=files.get(id);
        if( mxc.isEmpty())
            return false;

        try{
            String url = server+media+"download"+mxc.substring(5);
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .build();
            var p = dlFolder.resolve(id);
            Files.createDirectories(p.getParent());
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(p))
                    .thenApply( res -> {
                        if( res.statusCode()==200){
                            if( wr!=null)
                                wr.writeLine("File received");
                        }else{
                            Logger.error(res);
                            if( wr!=null)
                                wr.writeLine("File download failed with code: "+res.statusCode());
                        }
                        return 0;
                    } );
        } catch (URISyntaxException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }
    public void shareFile( String room, String mxc, String filename ){

        var j = new JSONObject()
                .put("body",filename)
                .put("url",mxc)
                .put("mimetype","text/plain")
                .put("msgtype", "m.file");

        try {
            String url = server+rooms+room+"/send/m.room.message/"+ Instant.now().toEpochMilli()+"?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .PUT(HttpRequest.BodyPublishers.ofString( j.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        System.out.println(res.toString());

                        if( res.statusCode()==200 ){
                            Logger.info("matrix -> Link send! ");
                        }else{
                            processError(res);
                        }
                        return 0;
                    });

        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    public void sendMessage( String room, String message ){

        String nohtml = message.replace("<br>","\r\n");
        nohtml = nohtml.replaceAll("<.?b>|<.?u>",""); // Alter bold

        if(message.toLowerCase().startsWith("unknown command"))
            message = "Either you made a typo or i lost that cmd... ;)";

        var j = new JSONObject().put("body",message).put("msgtype", "m.text");

            j = new JSONObject().put("body",nohtml)
                                .put("msgtype", "m.text")
                                .put("formatted_body", message)
                                .put("format","org.matrix.custom.html");

        try {
            String url = server+rooms+room+"/send/m.room.message/"+ Instant.now().toEpochMilli()+"?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .PUT(HttpRequest.BodyPublishers.ofString( j.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        System.out.println(res.toString());

                        if( res.statusCode()==200 ){
                            Logger.info("matrix -> Message send! ");
                        }else{
                            processError(res);
                        }
                        return 0;
                    });

        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    /* ******** Helper methods ****** */
    private void processError( HttpResponse<String> res ){
        if( res.statusCode()==200)
            return;
        var body = new JSONObject(res.body());
        String error = body.getString("error");

        if( res.statusCode()==403) {
            Logger.error("matrix -> " + body.getString("error"));
            switch (error) {
                case "You are not invited to this room.":
                    //requestRoomInvite(room); break;
                case "You don't have permission to knock":
                    break;
            }
        }else if( res.statusCode()==500){
            Logger.warn("matrix -> "+res.body());
        }else{
            Logger.warn("matrix -> "+res.body());
        }
    }
    private void asyncGET( String url, CompletionEvent onCompletion){
        try{
            url=server+url;
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> onCompletion.onCompletion(res) );
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    private void asyncPUT( String url, JSONObject data, CompletionEvent onCompletion){
        try{
            url=server+url;
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> onCompletion.onCompletion(res) );
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    private void asyncPOST( String url, JSONObject data, CompletionEvent onCompletion){
        try{
            url=server+url;
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> onCompletion.onCompletion(res) );
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    private Optional<JSONObject> getJSONSubObject(JSONObject obj, String... keys){
        for( int a=0;a<keys.length;a++){
            if( !obj.has(keys[a]))
                return Optional.empty();
            obj=obj.getJSONObject(keys[a]);
        }
        return Optional.of(obj);
    }
    private ArrayList<JSONObject> getJSONArray(JSONObject obj, String... keys){
        ArrayList<JSONObject> events = new ArrayList<>();
        for( int a=0;a<keys.length-1;a++){
            if( !obj.has(keys[a]))
                return events;
            obj=obj.getJSONObject(keys[a]);
        }

        if( obj.has(keys[keys.length-1])){
            var ar = obj.getJSONArray(keys[keys.length-1]);
            for( int a=0;a<ar.length();a++){
                events.add(ar.getJSONObject(a));
            }
        }
        return events;
    }
    /* ************************** not used ***************************************************** */
    public void pushers(){
        try{
            String url = server+push+"?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .GET()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                                System.out.println("Pushers?");
                                System.out.println(res.toString());
                                System.out.println(res.body());
                                return 0;
                            }
                    );
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    public void addDefaultPusher(){
        try{
            String url = server+addPush+"?access_token="+accessToken;

            var js = new JSONObject();
            js.put("app_display_name","d c")
                    .put("app_id","")
                    .put("append",false)
                    .put("data", new JSONObject().put("format","event_id_only").put("url",""))
                    .put("device_display_name","")
                    .put("kind","")
                    .put("lang","")
                    .put("profile_tag","")
                    .put("pushkey","");

            var request = HttpRequest.newBuilder(new URI(url))
                    //.header("access_token",accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(js.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                                System.out.println("Got pusher?");
                                System.out.println(res.toString());
                                System.out.println(res.body());
                                return 0;
                            }
                    );
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
    /* ******************* Writable **************************** */
    @Override
    public boolean writeString(String data) {
        return writeLine(data);
    }

    @Override
    public boolean writeLine(String data) {
        var d = data.split("\\|"); //0=room,1=from,2=data
        sendMessage(d[0],d[2]);
        return true;
    }

    @Override
    public boolean writeBytes(byte[] data) {
        return false;
    }

    @Override
    public String getID() {
        return "matrix:"+userName;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }

    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        var cmd = request[1].substring(0,request[1].indexOf(","));
        var rest = request[1].substring(request[1].indexOf(",")+1);
        StringJoiner j = new StringJoiner("\r\n");
        switch(cmd){
            case "?":

                j.add("matrix:send,roomid,message -> Send the given message to the room");
                return j.toString();
            case "send":
                String to = rest.substring(0,rest.indexOf(","));
                String what = rest.substring(to.length()+1);
                sendMessage(roomPresence.get(to),what);
                break;
            case "rooms":
                roomPresence.forEach( (key,val) -> j.add(key +" -> "+val));
                return j.toString();
            case "files":
                j.setEmptyValue("No files yet");
                files.keySet().forEach( k -> j.add(k));
                break;
            case "get":
                if( downloadFile(rest,wr) ){
                    return "Valid file chosen";
                }else{
                    return "No such file";
                }
        }
        return null;
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }

    private class RoomSetup {
        String localId="";
        String globalID="";
        ArrayList<Writable> targets;

    }
}
