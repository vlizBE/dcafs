/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.telnet;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.tinylog.Logger;
import worker.Datagram;

import java.util.concurrent.BlockingQueue;
/**
 * Creates a newly configured {@link ChannelPipeline} for a new channel.
 */
public class TelnetServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int TIMEOUT = 6000; //time out in seconds, connection will be closed after this time of inactivity
    
    private final SslContext sslCtx;		// Option to add SSL if ever needed
    BlockingQueue<Datagram> dQueue;
    String ignoreIPs ="";
    String title = "";

    public TelnetServerInitializer( String title, String ignore, BlockingQueue<Datagram> dQueue ) {    	
        this.sslCtx = null;
        this.dQueue=dQueue;
        this.ignoreIPs=ignore;
        this.title=title;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {    	
        ChannelPipeline pipeline = ch.pipeline();
        
        if (sslCtx != null) {
            pipeline.addLast(sslCtx.newHandler(ch.alloc()));
        }

        String ip = pipeline.channel().remoteAddress().toString();        
        ip = ip.substring(1,ip.indexOf(":"));         
        if(!ignoreIPs.contains(ip)){ // Ignore the checkups done by cockpit
        	Logger.info( "Telnet connection from "+ip);
        }
        // Add the text line codec combination first,
        pipeline.addLast(new DelimiterBasedFrameDecoder(1024, Delimiters.lineDelimiter()));
        // the encoder and decoder are static as these are sharable
        ch.pipeline().addLast( "decoder", new ByteArrayDecoder() );
        ch.pipeline().addLast( "encoder", new ByteArrayEncoder() );
      	 
        // close connection after set time without traffic
        pipeline.addLast( new ReadTimeoutHandler(TIMEOUT) );
        
        // and then business logic.
        TelnetHandler handler = new TelnetHandler( dQueue,ignoreIPs ) ;
        handler.setTitle(title);
        pipeline.addLast( handler );
    }
}
