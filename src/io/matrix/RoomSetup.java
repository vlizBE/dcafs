package io.matrix;

import io.Writable;

import java.util.ArrayList;
import java.util.HashMap;

public class RoomSetup {

    private String localId="";
    private String url="";
    private ArrayList<Writable> targets;
    private String hello="";
    private String welcome="";
    private String bye="";
    private HashMap<String,String> macros = new HashMap<>();
    private String alias="";
    
    public RoomSetup(String localId){
        this.localId=localId;
    }
    public String id(){
        return localId;
    }
    public static RoomSetup withID( String localId ){
        return new RoomSetup(localId);
    }
    public RoomSetup url( String url){
        this.url=url;
        return this;
    }
    public String url(){
        return url;
    }
    public RoomSetup welcome(String welcome){
        this.welcome=welcome;
        return this;
    }
    public RoomSetup entering(String entering){
        this.hello=entering;
        return this;
    }
    public String entering(){
        return hello;
    }
    public RoomSetup leaving(String bye){
        this.bye=bye;
        return this;
    }
    public RoomSetup macro( String id, String cmd){
        macros.put(id,cmd);
        return this;
    }

}