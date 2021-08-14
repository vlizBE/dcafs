package io.telnet;

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

import java.util.concurrent.BlockingQueue;

/**
 * Simplistic telnet server.
 */
public class TelnetServer {

    EventLoopGroup bossGroup = new NioEventLoopGroup(1);	// Server thread group
    EventLoopGroup workerGroup;	// Worker thread group

    ChannelFuture telnetFuture;
    
    int port = 23;
    String title = "dcafs";
    String ignore = "";
    
    BlockingQueue<Datagram> dQueue;
    static final String XML_PARENT_TAG = "telnet";

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
                        .addParent(XML_PARENT_TAG,"Settings related to the telnet server").attr("title","DCAFS").attr("port",23)
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
}
