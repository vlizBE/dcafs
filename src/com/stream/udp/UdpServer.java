package com.stream.udp;

import com.stream.BaseStream;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.Future;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.concurrent.BlockingQueue;

public class UdpServer extends BaseStream {

  EventLoopGroup group;
  int port;
  UDPhandler handler;
  Future<?> serverFuture;

  public UdpServer(BlockingQueue<Datagram> dQueue, Element stream ) {
    this.dQueue=dQueue;
    readFromXML(stream);
  }
  public UdpServer( String id, int port, BlockingQueue<Datagram> dQueue, String label ){
      super(id,label,dQueue);
      this.port=port;
  }
  public void setEventLoopGroup( EventLoopGroup group ){
    this.group=group;
  }

  class RunServer implements Runnable{ 
    @Override
    public void run() {
      try {
        Bootstrap b = new Bootstrap();

        handler = new UDPhandler(dQueue, label, priority);
        handler.setTargets(targets);
        
        b.group(group).channel(NioDatagramChannel.class).option(ChannelOption.SO_BROADCAST, true).handler(handler);
        b.bind(port).sync().channel().closeFuture().await();
      } catch (InterruptedException e) {
        Logger.error(e);
        Logger.error("InterruptedException for UDP server on port " + port);
        // Restore interrupted state...
        Thread.currentThread().interrupt();
      }
    }
  }
  @Override
  protected boolean readExtraFromXML(Element stream) {
    port = XMLtools.getChildIntValueByTag(stream, "port", -1);
    return port != -1;
  }

  @Override
  protected boolean writeExtraToXML(XMLfab fab) {
    fab.addChild("port",""+port);
    return false;
  }

  @Override
  public boolean connect() {
    serverFuture = group.submit(new RunServer());
    return true;
  }

  @Override
  public boolean disconnect() {
    return serverFuture.cancel(true);
  }

  @Override
  public boolean isConnectionValid() {
    return serverFuture != null;
  }

  @Override
  public long getLastTimestamp() {
    return handler.getTimestamp();
  }

  @Override
  public String getInfo() {
    return "UDP server ["+id+"|"+label+"] listens on "+ port;
  }

  @Override
  protected String getType() {
    return "udpserver";
  }
}