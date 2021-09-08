package io.telnet;

import das.Commandable;
import io.Writable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

/**
 * Simplistic telnet server.
 */
public class TelnetServer implements Commandable {

    EventLoopGroup bossGroup = new NioEventLoopGroup(1);	// Server thread group
    EventLoopGroup workerGroup;	// Worker thread group

    ChannelFuture telnetFuture;
    
    int port = 23;
    String title = "dcafs";
    String ignore = "";
    
    BlockingQueue<Datagram> dQueue;
    static final String XML_PARENT_TAG = "telnet";
    ArrayList<Writable> writables = new ArrayList<>();
    private final SslContext sslCtx=null;

    public TelnetServer( String title, String ignore, BlockingQueue<Datagram> dQueue, int port ) {
        this.title=title;
        this.ignore=ignore;
        this.dQueue=dQueue;
        this.port=port;
        workerGroup = new NioEventLoopGroup();
        run();
    }
    public TelnetServer( String title, String ignore, BlockingQueue<Datagram> dQueue ) { 
        this( title,ignore,dQueue,23);        
    }

    public TelnetServer( BlockingQueue<Datagram> dQueue, Document xml, EventLoopGroup eventGroup ) { 
        this.dQueue=dQueue;
        this.workerGroup = eventGroup;
        this.readSettingsFromXML(xml);
    }
    public String getTitle(){
        return this.title;
    }
    public static boolean inXML(Document xml) {
		return XMLtools.getFirstElementByTag(xml, XML_PARENT_TAG) != null;
	}
    public boolean readSettingsFromXML( Document xml ) {
        Element settings = XMLtools.getFirstElementByTag( xml, XML_PARENT_TAG );
        if( settings != null ){
            port = XMLtools.getIntAttribute(settings, "port", 23 );
            title = XMLtools.getStringAttribute( settings, "title", "DCAFS" );
            ignore = XMLtools.getChildValueByTag( settings, "ignore", "" );
            return true;
        }
        addBlankTelnetToXML(xml);
        return false;
    }
    public static boolean addBlankTelnetToXML( Document xmlDoc ){
        return XMLfab.withRoot(xmlDoc, "settings")                
                        .addParentToRoot(XML_PARENT_TAG,"Settings related to the telnet server").attr("title","DCAFS").attr("port",23)
                            .build() !=null;
    }
    public void run(){
            
        ServerBootstrap b = new ServerBootstrap();			// Server bootstrap connection
        b.group(bossGroup, workerGroup)						// Adding thread groups to the connection
            .channel(NioServerSocketChannel.class)				// Setting up the connection/channel             
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch){
                        var pipeline = ch.pipeline();
                        pipeline.addLast("framer",
                                new DelimiterBasedFrameDecoder(512, true, Delimiters.lineDelimiter())); // Max 512
                        // char,
                        // strip
                        // delimiter
                        pipeline.addLast("decoder", new ByteArrayDecoder())
                                .addLast("encoder", new ByteArrayEncoder())
                                .addLast( new ReadTimeoutHandler(600) );// close connection after set time without traffic

                        // and then business logic.
                        TelnetHandler handler = new TelnetHandler( dQueue,ignore ) ;
                        handler.setTitle(title);
                        writables.add(handler.getWritable());
                        pipeline.addLast( handler );
                    }
                });	// Let clients connect to the DAS interface

        try {
            Logger.info("Trying to start the telnet server on port "+port);
            telnetFuture = b.bind(port);
            telnetFuture.addListener((ChannelFutureListener) future -> {
                if( !future.isSuccess() ){
                    Logger.error( "Failed to start the Telnet server (bind issue?), shutting down." );
                    System.exit(0);
                }else if( future.isSuccess() ){
                    Logger.info( "Started the Telnet server." );
                }
            });
            telnetFuture.sync();	// Connect on port 23 (default)
            
        } catch (InterruptedException  e) {
            if(e.getMessage().contains("bind")){
                Logger.error("Telnet port "+port+" already in use. Shutting down.");
                System.exit(0);
            }
            Logger.error("Issue trying to connect...");
            Logger.error(e);
            // Restore interrupted state...
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        var cmds = request[1].split(",");
        if( request[0].equalsIgnoreCase("nb") || request[1].equalsIgnoreCase("nb")){
            int s = writables.size();
            writables.remove(wr);
            return (s==writables.size())?"Failed to remove":"Removed from targets";
        }else {
            switch (cmds[0]) {
                case "?":
                    var join = new StringJoiner("\r\n");
                    join.add( "telnet:broadcast,message -> Broadcast the message to all active telnet sessions at info level.")
                        .add( "telnet:broadcast,!message -> Broadcast the message to all active telnet sessions at error level.")
                        .add( "telnet:broadcast,level,message -> Broadcast the message to all active telnet sessions at the given level. (info,warn,error)")
                        .add( "telnet:bt -> Get the broadcast target count")
                        .add(" telnet:nb or nb -> Disable showing broadcasts" );
                    return join.toString();
                case "broadcast":
                    String send;
                    if( cmds.length < 2)
                        return "Not enough arguments, telnet:broadcast,level,message or telnet:broadcast,message for info level";
                    switch(cmds[1]){
                        case "warn":  send = TelnetCodes.TEXT_ORANGE+request[1].substring(15); break;
                        case "error": send = TelnetCodes.TEXT_RED+request[1].substring(16);    break;
                        case "info":  send = TelnetCodes.TEXT_GREEN+request[1].substring(15);  break;
                        default:
                            var d = request[1].substring(10);
                            if( d.startsWith("!")){
                                send =  TelnetCodes.TEXT_RED + d.substring(1);
                            }else {
                                send = TelnetCodes.TEXT_GREEN + d;
                            }
                            break;
                    }

                    writables.removeIf(w -> !w.writeLine(send+TelnetCodes.TEXT_YELLOW));
                    return "";
                case "write":
                    var wrs = writables.stream().filter( w -> w.getID().equalsIgnoreCase(cmds[1])).collect(Collectors.toList());
                    if( wrs.isEmpty())
                        return "No such id";
                    var mes = TelnetCodes.TEXT_MAGENTA+wr.getID()+": "+request[1].substring(7+cmds[1].length())+TelnetCodes.TEXT_YELLOW;
                    wrs.forEach( w->w.writeLine(mes));
                    return mes.replace(TelnetCodes.TEXT_MAGENTA,TelnetCodes.TEXT_ORANGE);
                case "bt":
                    return "Currently has " + writables.size() + " broadcast targets.";

            }
        }
        return "unknown command "+ String.join(":",request);
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
