package io.stream.udp;

import io.stream.BaseStream;
import io.Writable;
import io.stream.tcp.TcpHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.concurrent.FutureListener;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;

public class UdpStream extends BaseStream implements Writable {

    TcpHandler handler;
    InetSocketAddress ipsock;

    ByteBuf[] deli = new ByteBuf[]{ Unpooled.copiedBuffer(new byte[]{13,10}), Unpooled.copiedBuffer(new byte[]{10,13}) };
    Bootstrap bootstrapUDP;		// Bootstrap for TCP connections
    EventLoopGroup group;		    // Eventloop used by the netty stuff

    static int bufferSize = 4096; 	// How many bytes are stored before a dump

    public UdpStream( String id, String ipport, BlockingQueue<Datagram> dQueue, String label, int priority ){
        super(id,label,dQueue);
        this.priority=priority;
        this.id=id;

        String ip = ipport.substring(0,ipport.lastIndexOf(":"));
        int port = Tools.parseInt( ipport.substring(ipport.lastIndexOf(":")+1) , -1);
    
        ipsock = new InetSocketAddress( ip,port );
    }
    public UdpStream( BlockingQueue<Datagram> dQueue, Element stream  ){
        this.dQueue=dQueue;
        readFromXML(stream);
    }
    protected String getType(){
        return "udp";
    }
    public Bootstrap setBootstrap( Bootstrap strap ){
        if( strap == null ){
            if(group==null){
                Logger.error("No eventloopgroup yet");
                return null;
            }
            bootstrapUDP = new Bootstrap();
            bootstrapUDP.group(group)
                         .channel(NioDatagramChannel.class)
                         .option(ChannelOption.SO_BROADCAST, true);
        }else{
            this.bootstrapUDP=strap;
        }
        return bootstrapUDP;
    }
    public void setEventLoopGroup( EventLoopGroup group ){
        this.group=group;
    }

    @Override
    public boolean connect() {

        if( bootstrapUDP == null ){
            bootstrapUDP = new Bootstrap();
            bootstrapUDP.group(group).channel(NioDatagramChannel.class).option(ChannelOption.SO_BROADCAST, true);
        }
        Logger.debug("Port and IP defined for UDP, meaning writing so connecting channel...?");
        bootstrapUDP.option(ChannelOption.SO_REUSEADDR,true);
        bootstrapUDP.handler( new ChannelInitializer<NioDatagramChannel>() {
            @Override
            public void initChannel(NioDatagramChannel ch) {
                ch.pipeline().addLast( "decoder", new ByteArrayDecoder() );
                ch.pipeline().addLast( "encoder", new ByteArrayEncoder() );

                if( handler != null )
                    handler.disconnect();	
                handler = new TcpHandler( id, label, dQueue, UdpStream.this );
                handler.setTargets(targets);
                handler.setStreamListeners(listeners);
                handler.toggleUDP();
                ch.pipeline().addLast( handler ); 
            }
        });

        ChannelFuture f = bootstrapUDP.connect(ipsock);
        
        f.awaitUninterruptibly();
        f.addListener((FutureListener<Void>) future -> {
            if (f.isSuccess()) {
                Logger.info("Operation complete");
            } else {
                String cause = ""+future.cause();
                Logger.error( "ISSUE TRYING TO CONNECT to "+id+" : "+cause);
            }
        });
        return true;
    }

    @Override
    public boolean disconnect() {
        if( handler==null)
            return false;
        return handler.disconnect();
    }

    @Override
    public boolean isConnectionValid() {
        return handler!=null&&handler.isConnectionValid();
    }

    @Override
    protected boolean readExtraFromXML(Element stream) {
        // Process the address
        String address = XMLtools.getChildStringValueByTag( stream, "address", "");

        if( !address.contains(":") ){
            Logger.error("Not proper ip:port for "+id+" -> "+address);
            return false;
        }else{
            ipsock = new InetSocketAddress( address.substring(0,address.lastIndexOf(":")),
                                                Tools.parseInt( address.substring(address.lastIndexOf(":")+1) , -1) );
        }
        
        // Alter eol
        if( eol.isEmpty() ){
            Logger.error("No EOL defined for "+id);
            return false;
        }
        deli = new ByteBuf[]{ Unpooled.copiedBuffer( eol.getBytes())};
        return true;
    }

    @Override
    protected boolean writeExtraToXML(XMLfab fab) {
        fab.alterChild("address",ipsock.getHostName()+":"+ipsock.getPort());
        return true;
    }

    @Override
    public long getLastTimestamp() {
        return handler==null?-1:handler.getTimestamp();
    }

    @Override
    public String getInfo() {
        return "UDP ["+id+"|"+label+"] "+ ipsock.toString();
    }

    @Override
    public boolean writeString(String data) {
        return handler.writeString(data);
    }

    @Override
    public boolean writeLine(String data) {
        return handler.writeLine(data);
    }
    @Override
    public boolean writeBytes(byte[] data) {
        return handler.writeBytes(data);
    }
    @Override
    public Writable getWritable() {
        return this;
    }
    
}
