package util.other;

import io.matrix.CompletionEvent;
import io.matrix.FailureEvent;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.xml.XMLdigger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Z3Api {
    ScheduledExecutorService executorService = Executors.newScheduledThreadPool(2);
    String server;
    HttpClient httpClient;
    int fontsize = 32;
    boolean connected=false;
    ArrayList<String> overlay=new ArrayList<>();
    int retry=0;
    String ip="192.168.88.10";
    String channel="2";
    boolean fix;
    RealtimeValues rtvals;

    public Z3Api( XMLdigger digger,RealtimeValues rtvals ){
        loadSettings(digger);
        this.rtvals=rtvals;
        httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();
        if( testConnection() )
            enableOverlay();

        executorService.scheduleAtFixedRate(this::updateCustomOverlay, 0, 10, TimeUnit.SECONDS);
    }

    /**
     * Check if there's a valid connection possible to the encoder with a 5s timeout
     * @return True if connection can be established
     */
    public boolean testConnection() {
        boolean ok = false;
        try {
            URL url = new URL(server);
            HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
            urlConn.setConnectTimeout(5000);
            urlConn.connect();
            ok = urlConn.getResponseCode() == HttpURLConnection.HTTP_OK;
            if( ok )
                urlConn.disconnect();
        } catch (IOException e) {
            Logger.error(e.getMessage());
            connected=false;
            return false;
        }
        connected=true;
        return ok;
    }
    public void loadSettings( XMLdigger digger ){
        overlay.clear();
        // Check the settings.xml for other runtime parameters
        digger.goDown("z3api");
        ip = digger.peekAt("ip").value(ip); // Get the ip
        server = "http://"+ip+"/cgi-bin/control.cgi";
        fontsize = digger.peekAt("fontsize").value(fontsize);
        fix = digger.peekAt("fix").value("time").equals("time");

        digger.goDown("overlay");
        if( digger.isValid()){
            fontsize=digger.attr("fontsize",42);
            channel=digger.attr("chn",channel);
            digger.goDown("line");
            while(digger.iterate() ){
                var l = digger.value("");
                overlay.add(l);
            }
        }
    }
    public void setOverlay( String txt,int index, int x,int y){

        HashMap<String,String> params = new HashMap<>();
        var p = new Param();
        p.add("action","Overlay");
        p.add("chn",channel);
        p.add("rgn_idx",String.valueOf(index));
        p.add("source",txt);
        p.add("type","text");
        p.add("location",x+","+y);
        p.add("char_size",""+fontsize);
        p.add("layer","1");
       // params.put("text_color","");
        // Outline
       // params.put("outline_enable","off");
       // params.put("outline_color","");
       // params.put("outline_stroke","");
        Logger.info("POST: "+p.toString());
        asyncPOST( server, p.toString(),res -> {
            //Logger.info("Overlay => "+res.body());
            connected=true;
            return true;
        }, fail -> {
            Logger.error(fail.getMessage());
            connected=false;
            return false;
        });
    }
    public boolean isConnected(){
        return connected;
    }
    public void enableOverlay(){
        asyncGET( server, "telopenable=on", res -> {
            Logger.info("Overlay => "+res.body());
            return true;
        }, fail -> {
            Logger.error(fail.getMessage());
            return false;
        });
    }
    public void getStatus(){
        var req = new ArrayList<String>();
        req.add("action=EncoderStatus&chn=1");
        req.add("action=StreamStatus&chn=1");
        req.add("action=SourceStatus&chn=1");
        req.add("action=TempStatus&chn=1");

        for( var r : req ){
            asyncPOST( server, r, res -> {
                Logger.info(r+" => "+res.body());
                return true;
            }, fail -> {
                Logger.error(fail.getMessage());
                return false;
            });
        }
        asyncGET(server,"ctrl=stats&chn=null", res -> {
            Logger.info("stats => "+res.body());
            return true;
        }, fail -> {
            Logger.error(fail.getMessage());
            return false;
        });
    }
    public void updateCustomOverlay(){

        if(retry!=0 ){
            retry--;
            return;
        }
        if(!isConnected()){
            Logger.warn("No overlay updates because no connection to encoder!");
            retry=3;
            return;
        }
        Logger.info("Updating overlay...?");
        int index=1;
        for( int x=0;x<overlay.size();x++ ){
            var line = overlay.get(x);
            int b =0;
            while( b!=-1) {
                int a = line.indexOf("{");
                b = line.indexOf("}");
                if (a != -1 && b != -1) {
                    var val = line.substring(a+1,b); // Get the val without the brackets
                    if( val.contains(":")){
                        var parts = val.split(":");
                        switch( parts[0]){
                            case "r" -> {
                                var res = rtvals.getReal(parts[1],Double.NaN);
                                line=line.replace("{"+val+"}",String.valueOf(res));
                            }
                            case "t" -> {
                                var t = rtvals.getText(parts[1],"-");
                                line=line.replace("{" + val + "}", t);
                            }
                            case "i" -> {
                                var t = rtvals.getInteger(parts[1],-1);
                                line=line.replace("{" + val + "}", String.valueOf(t));
                            }
                        }
                    }else{
                        var res = rtvals.getReal(val,Double.NaN);
                        if( Double.isNaN(res)){
                            var t = rtvals.getText(val,"-");
                            if( !t.equals("-")) {
                                overlay.set(x,line.replace("{" + val + "}","{t:" + val + "}"));
                                line=line.replace("{" + val + "}", t);

                            }else{
                                var i = rtvals.getInteger(val,Integer.MAX_VALUE);
                                if( i != Integer.MAX_VALUE){
                                    overlay.set(x,line.replace("{" + val + "}","{i:" + val + "}"));
                                    line=line.replace("{"+val+"}", String.valueOf(res));
                                }else {
                                    Logger.error("No such value: " + val);
                                    b = -1;
                                }
                            }
                        }else{
                            overlay.set(x,line.replace("{" + val + "}","{r:" + val + "}"));
                            line=line.replace("{"+val+"}",""+res);
                        }
                    }
                }else if( b<a){
                    Logger.error("Error trying to find the : pairs, closing bracket earlier than opening one");
                    break;
                }
            }
            //Logger.info("Overlay line: "+line);
            setOverlay(line,index,3,5+(index-1)*(fontsize+10));
            index++;
        }
    }
    private void asyncPOST(String url, String data, CompletionEvent onCompletion, FailureEvent onFailure){
        if( data==null){
            Logger.error("Z3Api -> No valid data received to send to "+url);
            return;
        }

        try{
            var request = HttpRequest.newBuilder(new URI(url))
                    .headers("Content-Type","application/x-www-form-urlencoded:charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(data))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(10, TimeUnit.SECONDS)
                    .thenApply(onCompletion::onCompletion)
                    .exceptionally(onFailure::onFailure);
        } catch (URISyntaxException e) {
            Logger.error(e);
        }
    }

    private void asyncGET(String url, String data, CompletionEvent onCompletion, FailureEvent onFailure){
        if( data==null){
            Logger.error("Z3Api -> No valid data received to send to "+url);
            return;
        }

        try{
            url=url+"?"+data;

            var request = HttpRequest.newBuilder(new URI(url))
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(10, TimeUnit.SECONDS)
                    .thenApply(onCompletion::onCompletion)
                    .exceptionally(onFailure::onFailure);
        } catch (URISyntaxException e) {
            Logger.error(e);
        }
    }
    public static class Param{
        StringJoiner join = new StringJoiner("&");

        public Param add( String key, String value ){
            join.add(key+"="+value);
            return this;
        }
        public Param newSet(){
            return new Param();
        }
        public String toString(){
            return join.toString();
        }
    }
}