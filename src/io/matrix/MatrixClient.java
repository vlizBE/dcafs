package io.matrix;

import io.Writable;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.FileTools;
import util.xml.XMLtools;
import worker.Datagram;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class MatrixClient implements Writable {

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
    ArrayList<String> roomPresence = new ArrayList<>();
    String since = "";
    HttpClient httpClient;

    BlockingQueue<Datagram> dQueue;

    public MatrixClient(BlockingQueue<Datagram> dQueue, Element matrixEle){
        this.dQueue=dQueue;
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
            roomPresence.add( rm.getTextContent() );
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
                                        for( var room : roomPresence)
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

        for( var event :events){
            System.out.println("type:"+event.getString("type"));
            String from = event.getString("sender");

            if( from.equalsIgnoreCase(userID)){
                System.out.println("Ignored own event"); // confirm?
                System.out.println(event);
                continue;
            }

            switch( event.getString("type")){
                case "m.room.message":
                    String body = event.getJSONObject("content").getString("body");
                    Logger.info("Received message in "+originRoom+" from "+from+": "+body);
                    if( body.startsWith("das") || body.startsWith(userName)){ // check if message for us
                        body = body.replaceAll("("+userName+"|das):?","").trim();
                        var d = Datagram.build(body).label("matrix").origin(originRoom +"|"+ from).writable(this);
                        dQueue.add(d);
                    }else{
                        Logger.info(from +" said "+body+" to someone else");
                    }
                    break;
                    default:
                        Logger.info("Event of type:"+event.getString("type"));
                        break;
            }
        }
        /*


        events.forEach( ev -> System.out.println(ev));

        events.forEach( ev -> {
            String body = ev.getJSONObject("content").getString("body");
            String from = ev.getString("sender");
            if( !from.equalsIgnoreCase(userID)){
                if( body.startsWith("das") || body.startsWith(userName)){
                    var d = Datagram.system(body.substring(body.indexOf(":")+1)).origin(originRoom + ev.getString("sender")).writable(this);
                    System.out.println(d);
                    dQueue.add(d);
                    // Origin becomes roomid@sender
                }
            }
        });*/
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

        if( res.statusCode()==403){
            Logger.error("matrix -> "+body.getString("error"));
            switch(error){
                case "You are not invited to this room.":
                    //requestRoomInvite(room); break;
                case "You don't have permission to knock":
                    break;
            }
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
}
