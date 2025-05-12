package io.stream.tcp;

import io.stream.BaseStream;
import io.Writable;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.FutureListener;
import io.stream.StreamManager;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class TcpStream extends BaseStream implements Writable {

    TcpHandler handler;
    InetSocketAddress ipsock;
    ByteBuf[] deli;
    Bootstrap bootstrap;		// Bootstrap for TCP connections
    static int bufferSize = 2048; 	// How many bytes are stored before a dump

    public TcpStream( String id, String ipport, BlockingQueue<Datagram> dQueue, String label, int priority ){
        super(id,label,dQueue);
        this.priority=priority;
        this.id=id;

        String ip = ipport.substring(0,ipport.lastIndexOf(":"));
        int port = Tools.parseInt( ipport.substring(ipport.lastIndexOf(":")+1) , -1);
    
        ipsock = new InetSocketAddress( ip,port );
        deli = new ByteBuf[]{ Unpooled.copiedBuffer( eol.getBytes())};
    }
    public TcpStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
    }
    public String getType(){
        return "tcp";
    }
    public Bootstrap setBootstrap( Bootstrap strap ){
        if( strap == null ){
            if(eventLoopGroup==null){
                Logger.error("No eventloopgroup yet");
                return null;
            }
            bootstrap = new Bootstrap();
			bootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
					.option(ChannelOption.SO_KEEPALIVE, true)
					.option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 6000);
        }else{
            this.bootstrap=strap;
        }
        return bootstrap;
    }
    public void setHandler( TcpHandler handler ){
        this.handler=handler;
    }

    @Override
    public void setLabel(String label) {
        this.label=label;
        handler.label=label;
    }
    @Override
    public boolean connect() {
        ChannelFuture f;

        if( eventLoopGroup==null){
            Logger.error("Event loop group still null, can't connect to "+id);
            return false;
        }
        if(StreamManager.isNthAttempt(connectionAttempts))
            Logger.info("Trying to connect to tcp: "+id);
		if( bootstrap == null ){
			bootstrap = new Bootstrap();
			bootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
					.option(ChannelOption.SO_KEEPALIVE, true)
					.option(ChannelOption.TCP_NODELAY, true)
					.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 6000);
        }

        connectionAttempts++;

		bootstrap.handler(new ChannelInitializer<SocketChannel>() {
	        @Override
	        public void initChannel(SocketChannel ch) {
				try{
					if( deli != null ){
						ch.pipeline().addLast("framer",  new DelimiterBasedFrameDecoder(bufferSize,deli) );

					}else{
                        Logger.error("Deli still null, assuming fixed size...");
						ch.pipeline().addLast("framer", new FixedLengthFrameDecoder(3) );
					}	        	 	
					ch.pipeline().addLast( "decoder", new ByteArrayDecoder() );
					ch.pipeline().addLast( "encoder", new ByteArrayEncoder() );
					if( readerIdleSeconds !=-1 ) {
						Logger.info( "Adding idle state handler at "+ TimeTools.convertPeriodtoString(readerIdleSeconds, TimeUnit.SECONDS) +" to "+id);
						ch.pipeline().addLast( "idleStateHandler", new IdleStateHandler((int) readerIdleSeconds, 0, 0));
                    }
                    if( handler != null )
                        handler.disconnect();	
                    handler = new TcpHandler( id, label, dQueue, TcpStream.this );
                    handler.setPriority(priority);
                    handler.setTargets(targets);
                    handler.setStreamListeners( listeners );
                    handler.setEventLoopGroup(eventLoopGroup);
                    handler.setRTvals(getAbstractVals(),delimiter);
					ch.pipeline().addLast( handler );	   
				}catch( io.netty.channel.ChannelPipelineException e ){
					Logger.error("Issue trying to use handler for "+id);
					Logger.error( e );
				}                 
	        }
		});
        if( ipsock == null ){
            Logger.error("No proper ipsock");
            return false;
        }
		f = bootstrap.connect(ipsock).awaitUninterruptibly();
		f.addListener((FutureListener<Void>) future -> {
            if (!f.isSuccess() && StreamManager.isNthAttempt(connectionAttempts) ) {
                String cause = ""+future.cause();
                Logger.error( "Failed to connect to "+id+" : "+cause.substring(cause.indexOf(":")+1));
            }
        });
		if (f.isCancelled()) {
		    return false;
		 } else if (!f.isSuccess()) {
            if(StreamManager.isNthAttempt(connectionAttempts))
			    Logger.error( "Failed to connect to "+id );
		 } else {
		    return true;
		 }
		 return false;
    }

    @Override
    public boolean disconnect() {
        if( handler!=null ){
            return handler.disconnect();
        } 
        return true;
    }

    @Override
    public boolean isConnectionValid() {
        return handler!=null && handler.isConnectionValid();
    }

    @Override
    public boolean readExtraFromXML(Element stream) {
        // Address
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
    public boolean writeExtraToXML(XMLfab fab) {
        fab.alterChild("address",ipsock.getHostName()+":"+ipsock.getPort());
        return true;
    }
    @Override
    public long getLastTimestamp() {
        return handler==null?-1:handler.timeStamp;
    }

    @Override
    public String getInfo() {
        return "TCP ["+id+"|"+label+"] "+ ipsock.toString();
    }

    @Override
    public boolean writeString(String data) {
        if( handler==null || !isConnectionValid())
            return false;
        return handler.writeString(data);
    }

    @Override
    public boolean writeLine(String data) {
        if( handler==null || !isConnectionValid())
            return false;
        return handler.writeLine(data);
    }
    @Override
    public boolean writeBytes( byte[] data){
        if( handler==null || !isConnectionValid())
            return false;
        return handler.writeBytes(data);
    }
    @Override
    public Writable getWritable(){
        return this;
    }
}
