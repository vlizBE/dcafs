# Dcafs manual

<div class="page"/>

## Version history
|Version|Data      |Author|Changes             |
|-------|:--------:|:----:|--------------------|
|alpha  |02/04/2020|MTJ,DV|Initial test release|
|beta  |17/04/2020|MTJ|Changes based on feedback|
|beta  |23/04/2020|MTJ|Changes based on feedback|
|0.1   |06/05/2020|MTJ|Expanded the summary and added java structure schematic|
|0.2   |15/05/2020|MTJ|Added I2C|
|0.3   |18/05/2020|MTJ|Added extra info on streampool,dataworker,taskmanager|
|0.4   |25/05/2020|MTJ|Added section about real-time data usage|
|0.5   |26/05/2020|MTJ|Started tools, altered toc|
|0.6   |24/06/2020|MTJ|Altered to reflect 0.5.0|
|0.7   |06/07/2020|MTJ|Altered to reflect 0.5.1|
|0.8   |27/07/2020|MTJ|Altered to reflect 0.6.0|
|0.9   |05/08/2020|MTJ|Altered to reflect 0.6.x, cleanup,SQLiteWorker removed, added generics|
|0.10  |13/08/2020|MTJ|Altered to reflect 0.7.0|
|0.11  |19/08/2020|MTJ|Added sqlitetable 'alias',@macro and xml|
|0.12  |29/08/2020|MTJ|Altered to reflect 0.7.0|
|0.13  |06/11/2020|MTJ|XMLfab, altered to reflect 0.8.0|
|0.14  |23/11/2020|MTJ|More for 0.8.0|
|0.15  |03/01/2021|MTJ|Added MathForward, updated I2C|
|0.16  |08/02/2021|MTJ|Made sure Beginners guide is still correct|

<div class="page"/>

# Table of content

1. Introduction
    * [A. What is Dcafs](#whatisdcafs)  
    * [B. The purpose of this manual](#purpose)
    
1. As a library
   * A. Requirements
   * B. Preparation
   * C. RealtimeValues
   * D. Adding a label
   * E. Adding a command
1. As a standalone application
    * A. Requirements
    * B. Preparation
    
1. Adding a sensor  
    * [A.  With a continuous output interval](#step1a)
      * [Sensor connected through serial port](#sensor-serial)
      * [Sensor connected through TCP](#sensor-tcp)
    * [B.  Adding a polled sensor](#step1b)  
1. Storing processed data
    * [A. Inside the application](#step2a)
    * [B. In a predefined database](#step2b)
    * [C. In a periodic SQLite database](#step2c)
1. [Generics](#genericsexample)
1. Using the data
    * [A. The Telnet interface](#telnetdata)
    * [B. The TaskManager](#taskdata)
1. Monitoring data 
    * [A. Sending e-mails](#sendemail)
    * [B. Alarms](#alarms)

**Reference Manual**
1. [StreamPool](#streampool)
1. [Generics](#genericsref)
1. [FilterForward](#filters)
1. [MathForward](#mathforward)   
1. [EditorForward](#editorforward)
1. [TaskManager](#taskmanager)
1. [TransServer](#transserver)
1. [Workers](#workers)
   * [A. BaseWorker](#baseworker)   
   * [B. EmailWorker](#emailworker)
   * [C. DigiWorker](#digiworker)
   * [D. MqttWorker](#mqttworker)
   * [E. I2CWorker](#i2cworker)
1. [Tools](#tools)
    * [A. General Tools](#gentools)  (wip)
    * [B. TimeTools](#timetools)  (todo)
    * [C. CalcTools](#calctools)  (todo)
    * [D. GISTools](#gistools)  (todo)
    * [E. XMLtools](xmltools) (rdy)
    * [F. XMLfab](xmlfab) (todo)
    * [G. FileTools](#filetools) (todo)
    * [H. Database](#database) (wip)
      * [1. SQLiteTable](#sqlitetable)  
      * [2. SQLiteDB](#sqlitedb)  
      * [3. SQLDB](#sqldb)
      * [4. Insert](#insert)  



<div class="page"/>

# Beginners Guide

## 1. Introduction

### A. What is Dcafs <a name="whatisdcafs"></a>

Shortest description possible would be a tool that does all the nitty-gritty between generating data and finding it in 
a database. 

### B. Basics

To start of with a glossary:
* stream: tcp/udp/serial connection that can receive/transmit data
* label: a designation used by a worker thread to determine how to process the data
* source: any possible source of (processed) data
* writable: a reference to a target, this is given to a source to receive data from it
* datagram: an object containing the raw data and metadata like label, writable etc
* command: a readable instruction that can affect any part of the program
* forward: an object that receives data from a source, does something with it and then gives it to a writable

#### Getting data in

The basic entry point for data is a stream (there are others mqtt, i2c, ...).  
A stream:
 * is a source, because it provides the data received through the tcp/udp/serial interface
 * has a writable (exception to this is the udp client), because it allows data to be send over the tcp/udp/serial link
 * has a label, so the worker thread knows what to do with it could be 'system' which means the data will be considered a command.

Because of this:
 * a stream can be the source, and the writable for another stream (linking streams)
 * a stream can control das because it can have the label 'system'
 * a stream can be setup to echo data

For more information on streams, see streampool.
Other entry points for data are MQTT, I2C and SPI (and email).
For an overview of the whole source/writable/label interactions, see this table.

#### Altering data

Once data is made available either through a designated source (or label) it can be altered.
The main reason for altering data is to be able to write it to a database. 

There are currently three forwards:
* Filter, which can remove lines from the data based on rules (eg. startswith ... )
* Math, which does mathematical operations on the data (eg. multiply to convert m to cm)
* Editor, which does text edits on the data (eg. remove all 0's)

These three combined are used to get the data in a simple delimited format.
Just like streams, forwards can work with labels.

For more information on forwards, go here.

#### Storing data in dcafs

Now that data is in the preferred format it should be kept in memory.
The main label responsible for this is generic. This allows for delimited data to be converted to an array of doubles
which in turn are stored as 'rtval' or remain text and stored as 'rttext'. Just like stream and forward, rtval and rttext 
are sources. The generic will map a value to a rtval/rttext reference.  

Once this mapping is done, the data is available for other components.

For more information on Generic, go here.

#### Logging the data

The earlier mentioned generic can act as a trigger for an insert query, this will gather all needed data from
the rtval/rttext collection based on the database table and give it in the form of a prepared statement.

See databasemanager for more info on the interaction with databases.

>Note: By default, all data received through a stream is stored in a single raw data log file.

#### Conclusion

This was a brief overview of how data is received and stored in a database, but is far from all functionality.

Major features not mentioned:
* telnet (user interface for commands that is also writable)
* TaskManager (a scheduling engine using xml scripts)
* Email (control dcafs and can be a source/writable and used for alerts etc)
* Collectors (a variant on forward)
* Valmap (a variant on generic)
* trans (a tcp server, that can act as a telnet client or a stream)
* debugworker (can read the raw log files and act as if (multiple) streams for debugging)

All of the above will slowly be introduced in the manual.

### B. The purpose of this guide <a name="purpose"></a>

The goal is to explain how to use dcafs either as a library, or a stand-alone application.

When to use it as a stand-alone application:
 * The devices to interact with use a simple, straightforward protocol (not binary etc).
 * Performance isn't the main concern.
 * Knowledge of java is limited.
 * You don't need custom commands or labels 

In all other cases, it's recommended to use it as a library. It's also recommended to first use it standalone to get to
know how it works.

### C. Requirements & useful tools
* Java11 eg. [AdoptOpenJDK](https://adoptopenjdk.net/releases.html) and select OpenJDK11 (or later), the appropriate OS
    * download the JRE if you plan to use the binaries
    * download the JDK when building the binaries or using it as a library
* Get the source/binaries
  * To build the binaries: clone the repo from ...
  * To use as a library: add ... as a maven dependency or download the binaries
  * The use standalone, get the binaries from ...
* Recommended tools: 
  * a telnet client eg. [Putty](https://www.chiark.greenend.org.uk/~sgtatham/putty/latest.html)
  * a text editor with xml syntax highlighting [Notepad++](https://notepad-plus-plus.org/downloads/)
  * to view/edit SQLite files [DB Browser for SQLite](https://download.sqlitebrowser.org/DB.Browser.for.SQLite-3.12.0-win64.msi) 
  * to test tcp/udp connections [Yat](https://sourceforge.net/projects/y-a-terminal/) 
  * a java IDE [Intellij Idea Community Edition](https://download.jetbrains.com/idea/ideaIC-2020.3.2.exe) or [Visual studio code (VSC)](https://aka.ms/win32-x64-user-stable)


<div class="page"/>

## 2. As a standalone application

### A. Quickstart

#### Connect to the telnet interface
* If you use the binaries it's recommended to use the .bat or .sh to start it in a console window. All the feedback/errors
  should appear in the logs but it's easier than refreshing those.
* The last line in the console should be "Barebone boot finished!" if not using console, you know dcafs has booted if a
  settings.xml file (and other folders) have been generated near the .jar.

>Note: If this doesn't work, chances are Java wasn't installed properly.

#### First steps
* Open the created settings.xml (in the recommended notepad++ or any other xml syntax highlighting editor), the reason for
  this is that you can follow what dcafs generates. Furthermore, not everything is possible through the telnet interface (yet),
  so at some point it will be necessary/quicker to alter that file.
* Open Putty and set up a telnet connection to hostname localhost, port23 and connection type telnet.  
  Save this as dcafs_local.
* Open two or three dcafs_local sessions (while setting up, it's easier if there are two or more)
    * One session to issue commands
    * Once session to see the relevant help or command overview
    * Once session to see incoming data
* Now you should have the settings.xml and two or more telnet sessions in view and the console somewhere.
* Start with sending the command 'h' or 'help' in one session, then adjust the windows size and send it again...
* Follow the workflow detailed in the help.
  
### B. Examples

A series of examples will be given that add functionality in each step.

#### Log the data received on a serialport

* If a settings.xml is present, move or remove it.
* Start a telnet session
* If the serialport in question is COM1 and the baudrate 19200, lets just call the sensor, 'sensor'
  * Send 'serialports' to check if that COM actually exists, if so
  * Send 'ss:addserial,sensor,COM1:19200' to connect to it
* Now the text 'Connected to COM1' should appear
* Check the settings.xml to see how this was defined  
* By default, dcafs assumes that messages are terminated with \r\n (carriage return, line feed). If this is incorrect and it's
  actually just the carriage return:  
  * Send 'ss:alter,sensor,eol:\r' or 'ss:alter,sensor,eol:cr' the reply should be 'Alteration applied' and the xml updated.
  * Alternatively, edit the xml and send 'ss:reload,sensor' to reload the xml settings.
* To actually see the data send 'raw:id:sensor' (and press enter twice to stop this)
* Another option is sending 'st' or 'status', this will contain a title named streams and the line below should be 
  'SERIAL [sensor|void] COM1 | 19200,8,1,none      18s [-1s]'
  * SERIAL because It's a serial connection
  * [sensor|void] this is the id of the connection followed by the label, void means 'don't do anything with it'
  * COM1 | 19200,8,1,none are the settings of the serialport
  * 18s is the time since the last data was received
    * [-1s] means there is not time set before the connection is considered idle
* That's it! Oh, logging the data is done by default, see the raw folder.

#### Store the data in dcafs
For the sake of simplicity, we'll assume the data looks like this 12.5,56,1024\r\n. First number is temperature, 
second humidity and third pressure. To store these, we'll make a generic.
* Back in the telnet session
  * Send 'gens:addblank,sensor,rii' which asks to create a generic with id sensor and format real,int,int. 
    The result is the following section added to settings.xml
````xml
          <generics>
            <generic delimiter="," id="sensor" table="">
              <real index="0">.</real>
              <integer index="1">.</integer>
              <integer index="2">.</integer>
            </generic>
          </generics>
````
   * Alter this to correspond to the data
````xml
          <generics>
            <generic delimiter="," id="sensor" table=""> <!-- the delimiter is correct -->
              <real index="0">temperature</real>
              <integer index="1">humidity</integer>
              <integer index="2">pressure</integer>
            </generic>
          </generics>
````
  * Apply it with the command 'gens:reload'
  * Next up is changing the label of the connection
    * Send 'ss:alter,sensor,label:generic:sensor'
    * Verify with 'st', it should have become SERIAL [sensor|generic:sensor] COM1 | 19200,8,1,none 
    * Use 'rtvals' to see which data was last received
    
#### Store the data in a SQLite database

* Back in the telnet session
* Send 'dbm:addsqlite,sensordb' to create the database
  * Reply will be: Created SQLite at db\sensordb.sqlite and wrote to settings.xml
* Send 'dbm:addtable,sensordb,data,trii' to create a table with a timestamp, real and two integer columns
  * Reply will be: Added a partially setup table to sensordb in the settings.xml, edit it to set column names et
  * This section will have been added to the xml (without the comments for now).
````xml
    <databases>
      <sqlite id="sensordb" path="db\sensordb.sqlite">
        <!-- combine 30 queries in a single batch, max batch age is 30s, don't close connection on idle -->
        <setup batchsize="30" flushtime="30s" idletime="-1"/> 
        <table name="data">
          <timestamp alias="">columnname</timestamp>
          <real alias="">columnname</real>
          <integer alias="">columnname</integer>
          <integer alias="">columnname</integer>
        </table>
      </sqlite>
    </databases>

    <!-- Altering the columnnames -->
    <timestamp alias="">timestamp</timestamp>
    <real alias="">temperature</real>
    <integer alias="">humidity</integer>
    <integer alias="">pressure</integer>
````
   * Now reload the database with 'dbm:reload,sensordb'
   * Check if this has been applied with 'dbm:tables,sensordb'
   * Now the generic needs to be alterd to trigger an insert, there's no command for that (yet).
     ```xml
     <!-- Change -->   
     <generic delimiter="," id="sensor" table="">
     <!-- To -->
     <generic delimiter="," id="sensor" dbid="sensordb" table="data">
     <!-- Note: You can specify multiple dbid's but the table must be identical -->
    ```
  * Like before, apply with 'gens:reload'
    * Do note that when using 'rtvals' now, 'data_' will be prepended (name of the table). This is to allow multiple
    temperatures etc. If this is unwanted the "alias" can be used, see the dbm section for that.
  * That's it, depending on the datarate the data should appear in the database.
   
#### Conclusion

That's it for the quickstart examples. For more (extensive) examples see the 'use case' section (todo).

<div class="page"/>

## 3. As a library <a name="install"></a>

### A. Preparation

Create a new blank Maven project and add dcafs as a dependency. 
````xml
<dependencies>
   <dependency>
      <groupId>vlizBE</groupId>
      <artifactId>dcafs</artifactId>
      <version>0.8.1</version>
   </dependency>
</dependencies>
````
Then create an empty java class, name it as you like. For the examples, we'll useDcafsTest.java.  
Below is the recommended bare minimum:
```java
package core;  

import org.tinylog.Logger; // TinyLog for logging purposes
import das.DAS;            

public class DcafsTest{ 
    
    public DcafsTest() { // It also starts with {

        DAS das = new DAS();	
        
        // Let DAS start 
        das.startAll();	
        
        Logger.info("Everything started up, waiting for things to happen...");
        /* 
         * Extra info:
         * Logger.info("xxxxx"); is written to console and logs/info.log
         * Logger.error("xxxx"); is written to console and logs/errors_yymmdd.log
         * 
         * Thus, we can log serious errors using Logger.error(“This went wrong”), and keep info 
         * (to help us troubleshoot) in a separate file.
        */

    }
    public static void main(String[] args) { 
       new DcafsTest(); 
    }
}
````
### D. Adding a label

Labels are build in the BaseWorker class. In order to add a label this class needs to be extended.

So create a blank java class with a suitable name. For the example this will be Worker.

````java
package core;

import worker.LabelWorker; // It will use BaseWorker
import worker.Datagram;   /* Data arrives in the BaseWorker in the form of Datagram's which is an object to
 * transport data between classes as a single object */

public class Worker extends LabelWorker { // The class Worker extends the functionality of BaseWorker

    /**
     * Labels are added by creating a method which name starts with 'do' and  the remaining text will 
     * be considered the label. 
     *
     * Nothing should be returned but public isn't a requirement, can be private.
     * @param d It should accept a single Datagram
     */
    public void doSOMETHING(Datagram d) {
        // Processing code goes here, the content of datagram
        d.getData(); // Get the data from the datagram as a string
        d.getLabel(); // get the label 
        d.getOriginID(); // get the ID of the object that created the datagram
        d.getRaw(); // the raw data bytes
        // A datagram contains more but those are less used
    }
}
````
That's it for the extended BaseWorker. 
To actually make dcafs use it instead of the default one.

```java
package core;

import org.tinylog.Logger; // TinyLog for logging purposes
import das.DAS;

public class DcafsTest{

    public DasTest() { 

        DAS das = new DAS();	
        
        // Use the extended BaseWorker instead of the default one
        das.alterBaseWorker( new Worker() );
        
        // Let DAS start 
        das.startAll();	
        
        Logger.info("Everything started up, waiting for things to happen...");
    }
    public static void main(String[] args) { 
       new DcafsTest(); 
    }
}
```
>**Note:** A more detailed look at using BaseWorker can be found [here](#baseworker). 

<div class="page"/>

### E. Adding a Command

In order to add a command, BaseReq needs to be extended.

````java
package core;


public class ExtReq extends BaseReq { // The class Worker extends the functionality of BaseWorker

    /**
     * The name of the method should start with 'do' (same as a label) but the following text can be a mix of up
     * and lowercase. The full word will be the long command and the uppercase part the abbreviation.
     * So both the command something and st will call this method.
     *
     * Parameters can be passed along with the command by separating them with a : and delimiting multiple with ,.
     * The : is mandatory, the , is recommended.
     * @param request An array container the command in [0] and the parameter(s) in [1] if : was used
     * @param wr The writable if the origin of the command has it
     * @param html Whether the reply is preferably in html or not
     */
    public String doSomeThing(String[] request, Writable wr, boolean html) {
        // Do something
        // BaseReq gives access to the DAS object, so pretty much everything is accessible.
        return "Something was done, or wasn't it?";
    }
}
````
Same as worker, applying it requires passing to the DAS object.
```java
package core;

import org.tinylog.Logger; // TinyLog for logging purposes
import das.DAS;

public class DcafsTest {

    public DcafsTest() {

        DAS das = new DAS();

        // Use the extended BaseReq instead of the default one
        das.alterBaseReq(new ExtReq());

        // Let DAS start 
        das.startAll();

        Logger.info("Everything started up, waiting for things to happen...");
    }

    public static void main(String[] args) {
        new DcafsTest();
    }
}
```

<div class="page"/>

# Reference Manual

## 1. StreamPool <a name="streampool"></a>

As one of the managers, this component is responsible for the TCP, UDP and serial connections.

### Purpose

Manage all the (incoming) TCP, UDP and serial connections and provide an interface through telnet. 

### Main features
* Connect to TCP servers, UDP servers and serial ports (real and virtual)
* Auto reconnect if a connection is lost (or idle)
* Keep track if a connection becomes idle
* Act as interface between the other components and the streams
* Execute commands based on triggers (stream connection opened,closed or idle)

### Usage

To get a list of all available commands, type streams:? or ss:? in telnet.
The resulting xml section will look like this
```xml
<streams>
  <stream id="unique_name" type="tcp"> <!-- type of stream, other option are: udp (server or client), serial -->
    <address>192.168.1.1:1025</address> <!-- IP:Port if TCP/UDP client, <port> if UDP server or COMx/ttySx for serial -->
    <label>label</label> <!-- Will be used to determine the BaseWorker method -->
    <eol>crlf</eol> <!-- What signifies the end of a message (line), other options: cr, lf,lfcr  -->
    <ttl>5s</ttl> <!-- Time before the device is considered unresponsive, -1 means never -->
    
    <!-- Less common options -->
    <cmd trigger="open">calc:clock</cmd> <!-- command to be issued on a trigger, eg. when the stream is (re)opened -->
    <log>false</log> <!-- This disables the logging of raw data from this stream, default is true -->
    <priority>1</priority> <!-- For redundancy devices, this can be added here. Default is 1 -->
  </stream>
    <!-- Serial ports -->
    <stream id="unique_name2" type="serial">
        <port>COM1</port> <!-- or ttyS1 (/dev/ is prepended) -->
        <serialsettings>19200,8,1,none</serialsettings> <!-- baudrate, databits,stopbits,parity -->
        <!-- all the rest is the same as tcp eol,ttl,label,log,cmd ... -->
  </stream>
</streams>
```

### Triggered actions

This allows for certain actions to initiate any command or send data.  
Current command triggers:
* opened, when the connection is (re)opened do something
* idle, when the connection has been idle longer than ttl, do something
* closed, when the connection is closed, do something

Current data send triggers:
* welcome, when the connection is (re)opened send the data
* waiting, when the connection was idle send the data

To give a possible use case:
````xml
<stream id="tempsensor"> 
    <cmd trigger="welcome">give data please?</cmd> <!-- Send the command that starts the sampling process -->
    <cmd trigger="idle">email:send,admin,Tempsensor stopped sending data,Is it dead?</cmd> <!-- warn the admin -->
</stream>
````
### Commands list

#### Typical command flow:
* **Adding the connection** ss:addtcp,name,192.168.1.5:1234,generic:genid
* **Altering the eol chars from \r\n to \r or cr** ss:alter,name,eol:cr
* **Requesting the raw data via telnet** raw:id:name

**Add new streams**  
ss:addtcp,id,ip:port,label -> Add a TCP stream to xml and try to connect  
ss:addudp -> Add a UDP stream to xml and connect  
ss:addserial,id,port:baudrate,label -> Add a serial stream to xml and try to connect  
ss:addlocal,id,label,source -> Add a internal stream that handles internal data  

**Info about streams**  
ss:labels -> get active labels.  
ss:buffers -> Get confirm buffers.  
ss:status -> Get streamlist.  
ss:requests -> Get an overview of all the datarequests held by the streams  

**Interact with stream objects**  
ss:recon,id -> Try reconnecting the stream  
ss:reload,id -> Reload the stream or 'all' for all from xml.  
ss:store,id -> Update the xml entry for this stream  
ss:alter,id,parameter:value -> Alter the given parameter options label,baudrate,ttl  

**Route data from or to a stream**  
ss:forward,source,id -> Forward the data from a source to the stream, source can be any object that accepts a writable  
ss:connect,id1,id2 -> Data is interchanged between the streams with the given id's  
ss:echo,id -> Toggles that all the data received on this stream will be returned to sender  

**Send data to a stream**  
Option 1
 * Get the index of the streams with ss or streams    
 * Use S<index>:data to send data to the given stream (eol will be added)    

Option 2     
   * ss:send,id,data -> Send data to the stream with that id and append eol characters  

> **Note:** All the commands can also be used in a Task

### Source code

The only two methods that are relevant are those used to send data.
```java
// Send binary data over a stream (title from the stream node), with or without the streams delimiter	 
public String writeBytesToStream(String stream, byte[] data, boolean addDelimiter )
// The string returned is the send data or an empty string if failed

// Send ascii data over a stream (title from the stream node) and check for a reply (or empty "" for none)	 
public String writeToStream(String stream, String data, String reply)
// The string returned is the send data or an empty string if failed
```

<div class="page"/>

## 2. Generics <a name="generics"></a>


### Purpose
Allow using Dcafs without writing java code or to be able to add a sensor at runtime.

### Usage
Everything is defined in the settings.xml and needs to be in the 'generics' tag. An example of the minimum
```xml
<generics>
  <generic id="sbe38" table="sbe38">
    <real index="0">temperature</real>
   <generic> 
</generics>
```

This tells Dcafs to process data with the label generic:sbe38 by converting the received data to a real/double and store it in a table called sbe38.
How to setup this [sqlite](##sqlitedb) and the [stream](#streampool) can be found in their respective part of the reference guide.

The above is the minimum...
For example, a meteo sensor outputs two distinct NMEA data lines. One contains the environmentals and the other wind info.
We want both in the same datatable and know that WIXDR is always received first.

```xml
<generics>	
  <generic id="meteo" delimiter="," dbid="sensors" table="aws" dbwrite="no" startswith="$WIXDR">			
    <real index="2">temperature</real>
    <integer index="6">humidity</integer> <!-- low accuracy humidity in percentage -->
    <real index="10">pressure</real>			
  </generic>
  <generic id="wind" delimiter="," dbid="sensors" table="aws" startswith="$WIMWV">			
    <real index="1">winddirection</real>
    <real index="3">windspeed</real>
  </generic>
</generics>
```
To use both, the label needs to be generic:meteo,wind

This tells Dcafs:
* Check if the data starts with $WIXDR if so, use that generic.
    * Store the received data in the realtimevalues hashmap using aws_temperature etc
    * Do note write to the database (dbwrite="no"), just use the table info
* Check if the data starts with $WIMWV if so, use that generic.
    * Store the received data in the realtimevalues hashmap using aws_winddirection etc
    * Write in the table aws of the database with id sensors

### Other tags

#### Macro

This is added for the use case that multiple of the same devices are connected and part of the data contains an unique identifier.
```xml
<macro index="0"/>  <!-- This means that the first element of the array contains the unique identifier -->
<real index="1">@macro_temperature</real> <!-- When this value is written to rtvals, @macro will be replaced with the content of value found with index="0" -->
```
When this generic is used to fill in a database table, this needs to be defined during table creation
```xml
<real alias="@macro_temperature">temperature</real> <!-- this is how it should be used, see the SqliteDB/SQliteTable section for the rest of th -->
```

#### Filters

If you want to alter a part of the data before it is processed (for example a variable space delimiter) there are currently two filters that can be applied
```xml
<filter replaceall="  " with=" "/> <!-- Reduce the amount of consecutive spaces to 1 -->
<filter replacefirst="." with=","/> <!-- Replace the first encountered . with a , -->
```
The filters will be processed in order and will be executed independent of their position in the tag.
```xml
<generics>	
  <generic id="meteo" delimiter="," dbid="sensors" table="aws" dbwrite="no" startswith="$WIXDR">			
    <filter replaceall="  " with=" "/><!-- Can be placed at the start -->
    <real index="2">temperature</real>
    <integer index="6">humidity</integer>
    <real index="10">pressure</real>		
    <filter replaceall="  " with=" "/> <!-- Or at the end, only the order in relation to other filters matter -->	
  </generic>
</generics>
```

<div class="page"/>


## 3. Databasemanager

### Purpose

Act as interface between the databases and the rest of the program.

### Main features
* Connect to MySQL, MariaDB and MSSQL servers
* Connect & create SQLite databases and the tables
* Combine queries in PreparedStatements (but also accepts plain queries) and execute them in batches

### Usage

To get a list of all available commands use the command dbm:? or databasemanager:?.
A less typical xml section will look like this
```xml
    <databases>
    <sqlite id="sensordata" path="db\sensordata.sqlite">
        <setup batchsize="30" flushtime="30s" idletime="-1"/>
        <table name="data">
            <timestamp alias="">timestamp</timestamp>
            <integer alias="@macro_serial">serial</integer>
            <real alias="@macro_temperature">temperature</real>
            <integer alias="@macro_humidity">humidity</integer>
            <integer alias="@macro_pressure">pressure</integer>
        </table>
    </sqlite>
</databases>
```
#### Timestamp

This might change but for now this column type means that the system time will be used.

#### Alias

By default, when building the query for a table, the code will look for tablename_columnname in the rtval/rttext map.
If this is not wanted, the alias can be filled in and then that will be used to look for a corresponding value.

Why would you want this?
* If multiple tables (across different databases perhaps) use the same value then this might not match tablename_columnname.
* If there are duplicate sensors (eg. a temperature sensor grid) and the data string contains an unique identifier than this identifier can be used with @macro, so this only works with generics. Then the alias would look like @macro_temperature.
* If there are duplicate sensors but no unique identifier, this can't be covered in the generics.

#### Macro

By default, the trigger for a write is done by a generic as seen in that section a generic can have a macro value.
This value is passed along when the insert trigger is received, so it can be used to fill in the alias. Because of this,
it's possible to insert data from multiple sensors in the same table as long as there's a unique identifier.

So in the above xml, if the macro value received from the generic is '1234' then for the temperature value the code will 
look for the rtval with name 1234_temperature and the same thing is done for the other ones.


## 4. TransServer <a name="transserver"></a>

The TransServer acts as data hub where (raw) data can be requested. All datarequests are handled by this class, independent if this request is made in telnet, taskmanager or email.

These requests can be made at runtime or stored as defaults associated with a IP/hostname (and port).

### Setup

There are three ways.

**Option 1**

Connect to the telnet server, send trans:create .
This will update the settings.xml like was done manually in option one.

**Option 2**

Alter the settings.xml to contain the node below (within the settings node).
```xml
<transserver port="5542">
    
</transserver>
```

**Option 3**

Below the declaration of the DAS object, add das.addTransServer( port );
```java
/* Create an instance of DAS and read the settings file */
das = new DAS();
/* Add a transserver listening on port 5542 */
das.addTcpServer(5542);    // For now this DOESN'T allow multiple servers
```

This will update the settings.xml like was done manually in option one.

### Usage

#### Direct connection

When connected to the server directly (so raw tcp connection to the port the server listens on), the server starts with 'hello!'. From then on both commands and requests can be send. The difference being that commands alter settings of the connection while requests ask for data.

**Commands**
* p -> pauses the datastream
* priority:x -> the highest allowed priority (otherwise fe. both the main and secondary GPS are received)
* name:x -> sets the name of the connection (this is displayed in the earlier trans:active command)
* save -> saves the current session as a default if the ip is new

**Requests**
* nmea:x -> the nmea messages received by the StreamPool. fe. gga,zda etc
* raw:x -> the messages received with a certain id fe. sbe38
  * raw:title: for the title (so raw:x and raw:title:x should do the same) 
  * raw:generic: for generics
  * raw:label: for the label
* rtval:x -> a value calculated stored in the rtvals hashmap of RealtimeValues
* calc:x -> a combination of values and text in a format, by default there is calc:clock that returns UTC timestamp.
* ignore:x -> filter messages from being send for example if a certain NMEA strings is send by two devices (with different id)

> **Note:** Multiple requests of the same type can be combined by separating with ',' so nmea:gga,vtg,zda is allowed

<div class="page"/>

#### Telnet

The earlier mentioned requests work exactly the same if send during a telnet connection so this won't be repeated. Telnet interface adds a set of commands that are mainly used to alter other active connections. So it's possible to have a device connect to the server and then use PC to set up the data that is send to this device.

**Commands**

A list of these can be obtained by sending the command 'trans:?'.

This list:
* trans:? -> send the list of available commands
* trans:defaults -> get the currently registered defaults
* trans:restart -> restart the synchronous worker
* trans:active -> get a list of the currently active connections (the number is used in the trans:x,y command)
* trans:serial -> add a serial port to the server as output eg. trans:serial,COM1,19200
* trans:save -> save the current session as a default (so the PC's IP will be used)
* trans:x,y -> send a command/request y that applies to connection x so this allows the commands from earlier to be used

#### Defaults

Like mentioned earlier it's possible to connect with a device and set it up via (another) PC. The permanent alternative is to add requests to the transserver node in the settings.xml.
These look like this:
```xml
<!-- Have Dcafs send current timestamp to the ADCP PC upon connecting -->
<client address="192.168.1.2" title="ADCP-PC">
      <cmd>calc:clock</cmd>      
</client>
````

<div class="page"/>

# 2. TaskManager <a name="taskmanager"></a>

TaskManagers are used for all the scripted functionality in Dcafs. The language chosen for this is XML given that the syntax based on nodes corresponds well to the nature of the script.

### Glossary
* task: a single action to execute, once or an (un)limited amount of times
* taskset: an amount of tasks grouped together
* state: the way to describe the current situation of an object for example schip:sea, ship:harbor, ship:docked 
* flag: similar to a state but only has two options, a flag can be raised or lowered
* XML
  * node: structure \<node>value \</node>
  * attribute: 

## Usage

### Source
To add a TaskManager, the following needs to be executed. There's no actual limit to the amount of TaskManager's active but do consider the platform used.

```java
DAS das = new DAS();
das.addTaskManager( "manager id", Paths.get("scripts","filename.xml"));

das.startAll(); // Start everything and this includes the TaskManagers
```
The above is the only code required to set up a TaskManager.
By default, the Telnet interface has a some commands related to TaskManagers
> **Note:** There is one TaskManager active by default that handles alarms, so use any file besides scripts/alarms.xml
### Telnet

**Global commands**
* tasks:reloadall -> reload all the taskmanagers
* tasks:stopall -> stop all the taskmanagers
* tasks:managers -> get a list of currently active TaskManagers
* tasks:remove,x -> remove the manager with id x
* tasks:x,y -> send command y to manager x, options for y:  
  * reload/reloadtasks -> reload the associated xml file
  * listtasks -> give a listing of all the loaded tasks
  * listsets -> give a listing of all the loaded tasksets
  * states -> get a listing of all the current states
  * stop -> cancel all active and scheduled tasks.";

<div class="page"/>

## Creating a script

The general structure of a script is as follows:
```xml
<tasklist> 
      <tasksets>
            <!-- add sets of tasks here -->
      </tasksets>
      <tasks>      
            <!-- add single tasks here -->
      </tasks>
</tasklist>
```
### Taskset

Multiple tasks can be grouped together to form a set. At the moment there are two ways iterating through the set, namely oneshot and step. The first one means that all the tasks in the set will be started at the same time, the second one that execution of one task will initiate the following.

The minimum format of a taskset is as follows:

```xml
<taskset name="Longer descriptive name for the set" id="taskset_id" >
	<task></task> 
</taskset>
```

**Possible attributes**

* name : descriptive name for the taskset, used in telnet for representation
* id : the reference to use in other tasks to execute it
* run : how the taskset should be executed, options:
  * oneshot -> default, all planned at once
  * step -> step by step, if a step fails the taskset is stopped
  * no -> meaning it's a collection of tasks not used to run as a set
* repeat: how many time the taskset should be executed, default is 1, -1 means infinite.
* failure: only usable when run="step", this will run another taskset if this one failed


### Task

A task is a single XML node with multiple attribute options and a value that depends on those attributes.

#### Short summary of the attributes.
**The standard attributes:**
* output: where should the result of the task go
  * stream: to a device eg. output="stream:SBE38" this must match the title given to it in the settings file
  * email: to an email address/ref eg.output="email:admin" (requires a EmailWorker to be active)
  * sms: to an cellphone number eg. output="sms:admin" (requires a DigiWorker to be active)
  * manager: to run a command that affects the manager that runs this task
  * system: to run a telnet command
  * log: to write the result to the logs eg. output="log:info"
  * file: to write the result to a given file
* trigger: what should be the trigger for this task to be run
  * time: at which UTC time and day(s) should the task run eg. trigger="time:14:00,mo" is monday at 14:00
  * localtime: at which local time should the task run
  * delay: amount of time to run after the task is called eg. trigger="delay:2m10s" after 2 minutes and 10 seconds
  * interval: run continuously at a set interval with optional initial delay eg. trigger="interval:10s,5s" run every 10s after 5s initial delay (default is initial delay=interval)
  * retry: try to run (requirement must be met) at a set interval and amount of max attempts eg. trigger="retry:10s,5"
  * while: 

<div class="page"/>

**The optional attributes:**
* req: a requirement that must be fullfilled before a task is executed
* check: a check that is run to verify that the task was executed properly
* reply: when the output is a device, this reply is expected
* attachment: used when the output is email, this defines a file to attach
* state: this task will only be run if the given state is active
* id: give an id to this task, only used for the link attribute
* link: links this task with another one eg. link="nottoday,taskid" 
  * nottoday : the given task can't be triggered today
  * disable24h : the given task can't be triggered in the next 24 hours
  * donow : the given task will be triggered now
  * skipone : the next trigger for the given task will be ignored, but subsequent ones will be executed

```xml
<!-- some simple examples -->
<task output="system" trigger="delay:5s" >command</task>
<task output="email:admin" >The header of the email;the body</task>
```

#### The value

In the earlier examples, 'command' and 'The header of the email;the body' are considered the value of the task. How this should be constructed depends on the output but for the outputs system, stream, email there are a couple of macro's (meaning these words will be replaced at runtime).

 * @localtime -> replaced with the localtime in HH:mm format
 * @utcstamp -> replaced with the utc time in format dd/MM/YY HH:mm:ss
 * @localstamp -> replaced with the local time in format dd/MM/YY HH:mm:ss
 * @ipv4:interface@ -> replaced with the ipv4 address of the given interface	
 * @ipv6:interface@ -> replaced with the ipv6 address of the given interface		
 * @mac:interface@ -> replaced with the mac address of the given interface

#### Attributes in detail

Some of the attributes need a bit more information.

##### Output

As the name implies this element determines what should be done with the result of the task. This element can be further split up in: email, file, SMS, system, stream and log.

**Stream**

Allows the value of the task to be streamed to the chosen device. The specified channel must match the **title** of the stream.
Furthermore, if multiple items need to be send these can be given separated with ;. If no reply is given, these will be send with an interval of 3 seconds otherwise as soon as the reply is received.

This looks for the attribute reply to see what should be the reply given by the device. 
How the reply works:
* the data is send and the handler informed about the expected reply
* the handler expects a the reply within five seconds by default
* if no reply is received within those five seconds a resend is done
* after five resends the tasks stops trying and is considered failed

> **Note:** The data send can be requested in the reply by putting \**. So if 'GO 1' is send to the device and the reply is 'Go 1:ok' then use reply="**:ok". 

So when combining the above, if you send Go 1;Go 2;Go 3 and set reply to \**:ok this will result in the following:
1. Send 'Go 1' wait up to five seconds for 'Go 1:ok'. If received go to 2 else try again (up to five times).
1. Send 'Go 2' wait up to five seconds for 'Go 1:ok'. If received go to 3 else try again (up to five times).
1. Send 'Go 3' wait up to five seconds for 'Go 1:ok'. If received go to next task else try again (up to five times).

> **Note:** The 'go to next task' is only if inside a taskset with the runtype 'step', mor info on this in the taskset part of the manual.

**Email**

The email address or reference to send to, a reference needs to be defined in the emailbook node of the settings.xml (see related topic).

As an extra attribute attachment can be specified. This is the filename of the attachment to add to the email. Standard datetime elements can be used between [ and ] brackets to add current datetime to the filename. So attachment="log_[ddMMyy].log" will send the file with todays date.
If the attachment is missing, this will be noted in the email. By default, these follow the same zip rules as regular emails.

The value should be consisting of two parts separated with ;. The first half is the title of the email, while the second one is either the body or a (telnet) command which result will be the body.

**SMS**

The cellphone number or reference to send to, a reference needs to be defined in the smsbook node of the settings.xml (see related topic).
The value isn't split into multiple messages, so the user needs to take care of this.

**Manager**

Commands that affect the TaskManager itself, for now this is limited to mainly setting and removing flags. These flags are stored in the same structure as the states. So a list can be requested by sending tasks:states command.

*Commands*

- raiseflag:flagname: set a flag with the given name, this can be used in the req attribute;
- lowerflag:flagname: remove the flag with the given name.
- start:tasksetid: start the taskset with the given short.
- stop:tasksetid: stop the taskset with the given short.
- tasksetid:taskid: Start a task in a taskset.

**System**

Allows all the commands available in the telnet interface to be executed from the TaskManager.

**Log**

Allows the value to be written to the logger.

Options are:

- info : Logged to the info.log, nothing else is done
- warn : Logged to the warn.log but nothing else
- error : Logged to the error.log and an email is send to admin

**File**

The file to which the result of the value should be written to.
The value can, in addition to the @ options (see below), contain [EOL] as a means to add the system specific end of line characters.

### Trigger

As the name implies this element determines when the task should be executed. This element can be further split up in: time, interval/retry, travel and delay.

**Time**

This allows tasks to be executed at a specific time (based on the PC clock as timebase) in 24h notation.

* time or utctime: The task will be executed at the specified UTC time
* localtime: The task will be executed ate the specified local time

By default, tasks are only executed on *weekdays*. If execution is required on a specific day, then this needs to be appended.

* Append the first two letters of the day(s) to the trigger
* Append the full day to the trigger

*Examples:*
  * trigger="time:08:00" -> the task will run every weekday at 08:00 UTC
  * trigger="localtime:08:00" -> the task will run every weekday at 08:00 localtime
  * trigger="time:08:00,motu" -> the task will run on monday and tuesday at 08:00 UTC
  * trigger="time:08:00,monday" -> the task will run on monday at 08:00 UTC

**Interval**

This allows tasks to be continuously executed based on a specified interval and after an initial delay. The format of both periods can be specified in any timebase of atleast a second.

For example, to execute an action every 70minutes you could use either 70m or 1h10m or even 4200s. This also applies to the initial delay.

*Examples:* 
* trigger="interval:10s,5m" -> the task will run after 10s and then every following 5 minutes
* trigger="interval:5m" -> the task will run 'now' and then every following 5 minutes
* trigger="interval:1h2m15s" -> the task will run 'now' and then every following 1 hour 2 minutes and 15 seconds

**Delay**

Mainly used for tasksets, this causes a single execution of the task after the delay has passed since the initialization. 
Delay can be specified in following formats: 5min,5sec,5m10s,3h5m4s etc

*Examples:*
* trigger="delay:0s" -> the task will run 'now', this is the default if no trigger was given
* trigger="delay:10s" -> the task will run after a 10 seconds delay

**Travel**

In the settings.xml, travel to/from a waypoint can be determined. This movement can also be used as a trigger.

*Example:*
* trigger="travel:leavedock" -> the task will run if this travel occurred

<div class="page"/>

**Retry**

Variant of the 'interval' trigger, difference being that the repetitions can be limited. 
Just as 'interval' the task is tried on a set interval but stopped once it is executed (meaning requirements were fulfilled). The first variable defines the interval and the second one the retries, the latter can be -1 for infinite, 0 for once (no retries) or any higher number for the attempts made. If the max repetitions were made and the req is still not fulfilled, the **takset** will be considered failed.

*Examples:* 
* trigger="retry:10s,-1" -> the task will be tried every 10 seconds till execution
* trigger="retry:10s,5" -> the task will be tried every 10 seconds till execution or five attempts were made

**While**

If a situation requires something to be true for a certain amount of time before doing something and only if then.
Just like retry an interval and a run count should be provided.
So this trigger has a really specific use case and is better explained with an example.

Below is the TaskSet for a solenoid that can't be on for longer than 40 minutes (those things heat up). The TaskSet is part of the alarms script, so the execution is based on source code. 

```xml
<taskset name="solenoid 1 on" run="step" id="sols.sol1:start">
  <!-- A task with the while-trigger doesn't need an output nor a  value -->
	<task trigger="while:4m,10" req="issue:sols.sol1 equals 1"></task><!-- Check every 4 minutes if the solenoid is on and do this 10 times -->
  <!-- If we get to this point, this means the solenoid was on during the full 40 minutes -->
  <!-- If the solenoid was turned off earlier, we never reach this point -->		
	<task output="stream:solenoid Control" reply="**:OK">C1</task> <!-- Send C1 turns to controller to turn solenoid 1 off -->
</taskset>
```
#### Req

Short for requirement, this can check up to two realtime values, flags or issues. If the requirement is not met, the task is not executed (but will be rescheduled if it is time based).

Possible values for req depends on the usage of RealtimeValues.

**Possible operations**

A maximum of two checks can be combined with the logical operators 'and' (&&) or 'or' (||).

These checks can be either a logical or mathematical operation.
*Logical*
* above (>)
* below (<)
* equal (\==)

*Mathematical*
* plus
* minus
* diff (minus and drop the sign)


*Example:*
* The task requires that there's a flow of more than 0,5l and the temperature from sensor1 (S1temp) and sensor2 (S2temp) are within 0.4° of eachother.
  * Attribute: req="S1temp diff S2temp below 0.4 and waterflow above 0.5"
  * In java code: Math.abs( S1temp – S2temp) < 0,4 && waterflow > 0.5

#### Check

Same as req except that the verification is done **after** the task has run. Meaning you could have the task do something and then check if this has actually been executed. Do note, that for now, there is no time delay between execution and verification. This means that it is likely that the task will be executed twice if retry trigger is used.

<div class="page"/>

#### Link

When the id of a task is known, it can be controlled through another task when the controlling task is executed. The ways of controlling a task are for now limited to the execution of it.

* nottoday : the task can't be triggered today
* disable24h : the task can't be triggered in the next 24 hours
* donow : the task will be triggered now
* skipone : the next trigger will be ignored, but subsequent ones will be executed

*Example:* 
* \<task output="log:info" id="task1" link="nottoday:task2" trigger="time:08:00" req="temperature below 10">It's cold\</task>
  If the temperature is below 10°C at 08:00 don't execute task2 today.

> **Note:** the controlled task doesn&#39;t have to be another task, this can also be used to have a task that might trigger twice in a day limited to only once.



<div class="page"/>




# 5. FilterForward <a name="filters"></a>

If you want to alter the incoming data of a stream to get a subset of the source (stream etc).

## Creating filters

### In XML

```xml
<!-- An example to filter only the gga out of the stream coming from a RTK GPS -->
<filters>
   <!-- full blown version -->
  <filter id="GGA"> <!-- How it will be referenced, filter:gga -->
    <sources>
      <source>raw:title:RTK</source> <!-- What is the WRITABLE the data comes from, so can be another filter -->
    </sources>
    <rules> <!-- the rule(s) for this filter -->
      <rule type="start">$GPGGA</rule> <!-- the message should start with $GPGGA -->
    </rules>
  </filter>
   <!-- shorter version for single source and single rule -->
   <filter id="GGA" src="raw:title:RTK" type="start">$GPGGA</filter>
</filters>
```

### In telnet

The base command is 'filters' or 'fs'.
The following will result in the same xml as above:

fs:addblank,GGA,raw:title:RTK
fs:addstep,GGA,start:$GPGGA

or

fs:addblank,GGA
fs:addsource,GGA,raw:title:RTK
fs:addstep,GGA,start:$GPGGA

If the filter shouldn't be written to the xml then addtemp should be used instead of addblank.

## Using filters

**As mentioned earlier:**
fs:addsource,id,source -> add the source to a filter
fs:addrule,id,type:value -> add the rule to a filter

**Other commands:**
fs:? -> Returns a list of all the available commands
fs:list or fs -> Returns a list of all the stored filters
fs:remove,id -> Remove the filter with the given id
fs:delrule,id,index -> Remove a rule from a filter based on the index given with fs:list
fs:rules -> Get a list of all the possible rules with a short explanation

**Remarks** 
* A filter isn't active if there's no request issued for its data. Meaning the source won't send data to a filter that isn't sending the filtered data to somewhere.
* like all the other commands, a task can create or alter a filter (not that i know a use case)
* The id is case insensitive, stored in lowercase

<div class="page"/>

# 6. MathForward <a name="mathforward"></a>

If you want to apply mathematical operations to the data from a stream fe. replace raw value by final ones.

## Creating MathForward

### In XML

```xml
<maths>
   <!-- This math is inactive till another component wants the data from it -->
   <!-- The received data will be split according to the default delimiter ; -->
   <math id="myname" src="raw:title:sensorid">
      <operations>
         <!-- The result of the given equation will be written in the given array on position 3 -->
         <operation index="3">i0*15+i1*20+i2^2+(16+i3)^2</operation>
         <operation type="scale" index="3">2</operation><!-- Round index 3 down to 2 digits -->
      </operations>
   </math>
   <!-- This math is always active and the resulting data is given to the baseworker to process according to the label -->
   <!-- The data will be split according to the given delimiter, in the below case ',' -->
   <math id="myname2" delimiter="," label="sensorname">
        <sources> <!-- If you want to use multiple sources -->
           <source>raw:title:sensorid</source>
           <source>raw:title:sensor2id</source>
        </sources>
    <operations>
       <operation giveto="maths:scratchpad,myname,$" index="1">i1*25</operation>
       <!-- 
            giveto - this will run the command with $ replaced with the result, in this case this add it
                     it to the scratchpad of the other math 
      --> 
    </operations>
</maths>
```
### In telnet

The base command maths or ms.  
ms:addblank,id,source  -> Create a blank math in the xml with the given id and optional source 
ms:addop,id,index=equation -> Add an operation to the given math that writes the result to the given index
ms:addsource,id,source -> Add the given source to the id

maths:list -> List all the available maths and their operations
maths:debug,on/off -> Turn debugging on or off
maths:test,id,data -> Give data to the math like it would receive to test it

ms:scratchpad,id,value -> Write the given value to the scratchpad of the given id (or all if id = *)

# 7. Workers

## A. BaseWorker <a name="baseworker"></a>

This class receives about 99% all data arriving at Dcafs, this is given in the form of Datagram Object:
* Raw data from Serial and TCP/UDP devices
* Commands received through Telnet
* Data received from I2C devices

### Setup

Given that this is a class to process internal message streams, this has no setup in the settings.xml. A possible future addition is being able to specify the amount of workers (instead of the default one).

### Usage

#### Class
A blank extended class looks like this:

```java
package core;

public Worker extends BaseWorker{
        }
```
To make sure Dcafs uses the extended version of this class, there are two options:

```java
Worker worker = new Worker();  // Create an object of the extending class
das.alterBaseWorker( worker ); // Make DAS use the extended version 
//or
das.alterBaseWorker( new Worker() ); // Same result, but no local object created
```

#### Methods

There are two options for the methods that process data, first there's the general one 
```java
public void doDEVICELABEL( Datagram d ){} // This receives a Datagram
```
Then there's one aimed at processing NMEA data.
```java
public synchronized FAILreason doNMEAtype(String[] split, int priority) {
  /* 
   * type refers to the NMEA type, so:
   *      -> $GPGGA -> gga => doNMEAgga 
   *      -> $GPZDA -> zda => doNMEAzda 
   * 
   * Before this method, the datagram has been handled by the BaseWorker.
   * This means checking the checksum and splitting the message according to ',' resulting in split
   * because those steps are always needed for NMEA messages.
   * Then priority is passed for redundant devices that take over in case of issues with the primary.
   * 
   * Furthermore this method returns a FAILreason, the options are:
   * FAILreason.length -> return this if the amount of items in the split isn't as expected
   * FAILreason.syntax -> the length is correct but something else is wrong with the data
   * FAILreason.priority -> this data wasn't processed because it's not the active priority
   * FAILreason.empty -> no content 
   * FAILreason.todo -> this still needs to be implemented
   * FAILreason.none -> Everything went fine, no failure
   */
}
```
<div class="page"/>

### Datagram

This is the data storage class which BaseWorker receives. It contains the data and a lot of objects that give more info about the data.

**Received data**
* message: the data as a String
* raw: the data as a byte array
* messValue: used for passing a single Double
* timestamp: when was the data received

**Info about the data source**
* title: the title of the device as defined in the xml
* label: the label used to find this method, is ':' separated and can contain additional info
* priority: the priority this device has (highest is 1)
* nettyChan: the channel/stream from which the data came
* transReq: mainly used for data from/to TransServer
* origin: if the data needs mor info regarding the origin (fe. topic in mqtt)

### Common practices

It's highly likely that one of the following methods of RealtimeValues will be used, BaseWorker has an object of this class called **rtvals**:
```java
// To add a received value to the list of realtimevalues accessible by TransServer and BaseReq
rtvals.setRealtimeValue( String parameter, double value );

// To add a query to first/only defined database
rtvals.writeQeury( String query );
// or 
rtvals.writeQeury( Insert query );

// To add the data to specific database
rtvals.writeQuery( String id, String query );
rtvals.writeQuery( String id, Insert query );

// If all data has been set with setrealtimevalues according to a table
rtvals.writeRecord( String table); // first/only db
rtvals.writeRecord( String id, String table); // specific db
```
<div class="page"/>

## B. EmailWorker <a name="emailworker"></a>

This worker is used to send and receive E-mails via a SMTP server.

### Setup

This is done in the settings.xml file or through the das command in telnet.

````xml
<email>
    <outbox>
      <!-- The server, only SMTP for now -->
      <server user="user" pass="pass" ssl="false">smtp.server.com:25</server>
      <zip_from_size_mb>0.5</zip_from_size_mb> <!-- From which size in MB to zip files -->
      <max_size_mb>2.0</max_size_mb><!-- Maximum size of (zipped) attachments -->
      <delete_rec_zip>yes</delete_rec_zip>
      <from>das@email.com</from>
    </outbox>
    <inbox>
      <server user="user" pass="pass" ssl="false">smtp.server.com:25</server>
      <checkinterval>5m</checkinterval>
    </inbox>
    <book><!-- easier to use references instead of having email addresses hardcoded -->
        <entry ref="admin">admin@gmail.be,adminn@outlook.be</entry>
    </book>
</email>
````

**Some notes:**
- Multiple entry elements can be added.
- If the zipped size is larger than max_size_mb then the attachment isn't send but a message is added to notify recipient.

### Usage

**From a class that has direct access to a DAS object**

```java
public boolean sendEmail( String to, String title,String message, String attachment, boolean deleteAttachment )
```
> **Note:** deleteAttachment is only done if the attachment was actually send

**Examples:**
```java
// Simple email, no attachment
das.sendEmail( "admin@emailhost.be", "Something went wrong", "No idea what though..." ); // Using email address
das.sendEmail( "admin", "Something went wrong", "No idea what though..."); // Using refto

// With fixed attachment
das.sendEmail( "admin", "Something went wrong", "Read the log!","logs/error.log",false);

// With changing attachment
das.sendEmail( "admin", "Something went wrong", "Read todays log!","logs/error_[ddMMyy].log",false);
```

**From any other class**

First pass the queue from the EmailWorker.
```java
Worker worker = new Worker( das.getEmailQueue() );
// the queue is
BlockingQueue<EmailWork> emailQueue = new LinkedBlockingQueue<>();
```

This queue expects objects from the EmailWork class, the constructors:
```java
EmailWork( String email, String title, String message )
//or
EmailWork( String email, String title, String message, String attachment, boolean deleteAttachment )
```
So using it:
```java
emailQueue.addWork( new EmailWork("admin","Something went wrong", "No idea what though...") );
```

<div class="page"/>

## C. DigiWorker <a name="digiworker"></a>

This worker is used to send SMS's if the logger is connected to a Digi Cellular modem. The connection is made through Telnet.

### Setup

In the settings.xml file the setup is done. This is used to connect to a Digi Cellular modem for sending SMS messages.

```xml
<digi>
	<server user="user" pass="pass">127.0.0.1</server>	
	<refto ref="admin">+32486123456</refto>			
</digi>
```

Multiple refto elements can be added and they can contain multiple numbers addresses separated by a ','.

### Usage

**From a class that has direct access to a DAS object**

```java
public boolean sendSMS( String to, String message ){
```
**Examples:**
```java
das.sendSMS( "+324861234567", "Hello World!");
//or
das.sendSMS( "admin","Hello Underworld?");
```

**From any other class**

First pass the queue from the EmailWorker.
```java
Worker worker = new Worker( das.getSMSQueue() );
// the queue is
BlockingQueue<String[]> smsQueue = new LinkedBlockingQueue<>();
```
This queue expects String array objects of which the first element is an number or refto and second the message.
```java
smsQueue.addWork( new String[]{"admin","Hello Underworld!"});
```


<div class="page"/>

## D. MqttWorker <a name="mqttworker"></a>

For now, the only option to add MQTT is by manually filling in the settings.xml.

```xml
<mqtt>
  <broker id="mqtt_broker">
      <address>tcp://127.0.0.1:1234</address>			<!-- The MQTT broker address, instead of ip, a hostname is also valid  -->
      <defaulttopic>sensor/data/</defaulttopic><!-- The default topic -->
      <subscribe label="sbe38">output/temperature</subscribe> <!-- Topic to subscribe and BaseWorker label -->      
  </broker>
</mqtt>	
```
The broker id is how it will be referenced in the code. So with the above information, DAS will create a worker and subscribe/connect to the broker.

**Subscriptions**

The label has the same usage as for a stream. Meaning for the BaseWorker to know what to do with the received data.

Subscriptions can also be added/removed through commands (telnet,dasbot).

```java
mqtt:subscribe,mqtt_broker,sbe38,output/temperature // To subscribe like in the xml example
mqtt:unsubscribe,mqtt_broker,sbe38,output/temperature // To unsubscribe
mqtt:unsubscribe,mqtt_broker,sbe38,all // To unsubscribe from all topics of sbe38
mqtt:store,mqtt_broker                // To store the changes in the xml
```

<div class="page"/>

## E. I2CWorker <a name="i2cworker"></a>

This manual won't go into the details of explaining what I2C is and how it's used. For that you should check the datasheet of the device you wish to implement.

### Setup <a name="i2csetup"></a>
First an entry is created in the settings.xml inside the settings node, this looks like this.

```xml
<i2c>
	<bus controller="1"> <!-- Number/id of the controller -->
		<device id="ROM" script="AT24C02D" label="AT24" address="0x50"/>
    <!--
      id -> this has two uses
                1) used to identify the device in telnet and TaskManager etc
                2) Will be passed along to the BaseWorker extension
      script -> name of the file and id for the command script
      label -> Same as other sensors as reference to use in the BaseWorker extension (doAT24)
               if none is given script is used
      address -> the 7-bit address of the device on the bus in hexadecimal format
      -->
	</bus>
</i2c>
```

### Commandset <a name="i2ccoms"></a>
Next a script can be made to interact with the device, below is a subset.

```xml
<commandset script="AT24C02D">
   <!-- Create a command with the id 'readall', reads are stored as 8 bit integers --> 
  <command id="readall" info="Read the content of all the registers">
		<read reg="0x20" return="32"/>
		<read reg="0x40" return="32"/>
		<read reg="0x60" return="32"/>
		<read reg="0x80" return="32"/>
		<read reg="0xA0" return="32"/>
		<read reg="0xC0" return="32"/>
		<read reg="0xE0" return="32"/>
  </command>
   <!-- The bits attribute changes  the default amount of grouped bits for the whole command to 16 -->
   <!-- By default, the first byte is considered the MSB and result unsigned -->
   <command id="readone" bits="16" signed="no" info="Read the content of that 16bit register">
      <read reg="0x22" return="2"/>
   </command>
   <command id="readthree" bits="16" info="Read the content of that 16bit register">
      <read reg="0x22" return="2"/> <!-- read two bytes and combine to single int -->
      <read reg="0x24" msbfirst="false" return="2"/> <!-- read two bytes and combine to single int in reverse order-->
      <read reg="0x27" bits="8" return="1"/> <!-- read a single byte -->
   </command>
  <command id="clear" info="Write 0x00 to all registers">
		<write reg="0x00">0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00</write>
		<wait_ack>15</wait_ack>
		<write reg="0x08">0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00</write>
		<wait_ack>15</wait_ack>
		<write reg="0x10">0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00</write>
  </clear>
</commandset>
```
This needs the filename AT24C02D.xml and be placed in subdirectory 'devices'.  
Currently there are four different instructions

#### Read <a name="i2cread"></a>

There are two ways of using read.
```xml
<!-- In order to do a read starting at a certain register -->
<read reg="0x20" return="32"/> <!-- This first does a write of 0x00 followed be a read of 32 bytes -->
```
This instruction will first send 0x20 and then request 32 bytes in return.
On the I2C bus this would look like this:  
[START]I2C_Address[W][A]->0x20[AS]->[Start]I2C_Address[R][S]->readByte[0][AM]->readByte[1][AM]->...->readByte[31][NAK][STOP]

In which:
* [start] is the start condition from the master
* [W] last bit of the address is set to signify a write operation
* [R] last bit of the address is cleared to signify a read operation
* [stop] is the stop condition from the master
* [AS] is an aknowledge from the slave
* [AM] is an aknowledge from the master
* [NAK] is a 'Not' aknowlegde issued by the master to tell the slave to stop sending

```xml
<read return="32"/> <!-- This first does a read of 32 bytes -->
```
This results in the following I2C operation: [START]I2C_Address[R][A]->readByte[0][A]->readByte[1][A]->...->readByte[31][NAK][STOP]

> Note: 32 bytes is the maximum allowed by the used library in a single instruction.

**Possible attributes**
* reg : the register to read to
* return : the amount of bytes that should be read
* bits : how many bits should be combined to a single int possible: 8,10,12,16,20,24
* signed : if the received int is signed or not (2s complement)
* msbfirst : if the bytes are received MSB>LSB or LSB>MSB


> **Note:** bits,signed,msbfirst are optional and if not given either the default is used (8,unsigned,msbfirst)
> or what has been set up in the command define.


#### Write <a name="i2cwrite"></a>

Again there are two ways to the instruction, but this time the result is identical.
```xml
<write reg="0x08">0x01 0x03 0x02</write> <!-- instead of space ',' or ';' or '\t' are also valid delimiters -->
<write>0x08 0x01 0x03 0x02</write>
```
This results in the following I2C operation: [START]I2C_Address[W][A]->0x08[AS]->0x01[AS]->0x03[AS]->0x02[AS][STOP]
Which writes the given 4 bytes.

#### Alter <a name="i2calter"></a>

This instruction is used to change the content of a byte register based on the current value and a logical operand.
```xml
<alter reg="0x0F" operand="or">0x10</alter> <!-- Read register 0x0F and apply 'OR 0x10' and overwrite it -->
<alter reg="0x0F" operand="and">0x10</alter> <!-- Read register 0x0F and apply 'AND 0x10' and overwrite it -->
<alter reg="0x0F" operand="xor">0x10</alter> <!-- Read register 0x0F and apply 'XOR 0x10' and overwrite it -->
<alter reg="0x0F" operand="not"></alter> <!-- Read register 0x0F, invert the result and overwrite it -->
```

#### Wait_ack <a name="i2cwack"></a>

This instruction is to make execution of the folllowing instructions wait till the device has responded to a address probe with Acknowlegde.

```xml
<!-- This will do up to 15 probe attempts until either an [AS] was received or 15 fails and the command is cancelled -->
<wait_ack>15</wait_ack> 
```
This results in the following I2C operation: [START]I2C_Address [START]I2C_Address [START]I2C_Address ... [START]I2C_Address[AS]

### Using commandsets  <a name="i2cuse"></a>

#### Telnet client

To use a command created with the script, type the following ->  i2c:device id,command
or to use the earlier xml as an example -> i2c:ROM,readall

> **Note:** device-id and command are case sensitive, the user doesn't need to referece the label

What the above actually does is:
* Check if there's a device with the id **ROM**
* If so check if the script **AT24C02D** contains a command **readall**
* If so, add the command to the execution queue
* Notify the TransServer that we want to see the output from the device **ROM**
* If data was read, create a datagram for the BaseWorker as raw[] with label **AT24C02D:readall** and title **ROM**
  and a datagram for TransServer that contains the message I2C(**ROM**|**readall**) data as hex string
* BaseWorker looks for a method called doAT24C02D and passes the datagram to it if found

#### Tasks

Given that all commands used in Telnet can be used in TaskManager with the system output, the earlier command can be used in a Task like this:
```xml
<!-- This will run the command 5 seconds after DAS has finished start-up -->
<task output="system" trigger="delay:5s" >i2c:ROM,readall</task>
```
> **Note:** Commands are added to a queue so will be executed in order and one at a time. For now one worker services all controllers this might change in the future to have one worker per controller.

<div class="page"/>

# 6. Tools <a name="tools"></a>

## A. General Tools <a name="gentools"></a> 

This class (Tools.java) is a collection of general functions that don't belong in any of the other *tools classes.

#### Methods
```java
/* Working with doubles */
// Parsing a string
Tools.parseDouble( String decimal, double badresult ); // Returns the double or badresult if it wasn't a valid double
// Parsing a single element from an array
Tools.parseDoubleArray(String[] array,int index, double error );// Returns the double or badresult if it wasn't a valid double
// Rounding 
Tools.roundDouble(double r,int decimalPlace);
// Getting it as a fixed length string
Tools.fixedLengthDouble(double r,int decimalPlace);

/* Integers */
// Parsing a string
Tools.parseInt( String number, int badresult ); // Returns an integer or badresult if it wasn't a valid integer
// Storing a unsigned byte as a integer
Tools.toUnsigned( byte b ); // Returns the integer
// Adding leading zero's to match the length
Tools.addLeadingZeros( int nr, int length ); // Returns string with int and maybe prepended zero's

/* String */
// Remove the characters from a string that aren't ascii
Tools.removeNonAscii( String s );
// Remove the backspace character from a string
Tools.cleanBackspace(String s);
```

#### Examples
````java
/* Double */
// String -> Double
double d = Tools.parseDouble( "1234.12", -999 ); // Result: d = 1234.12;
double d = Tools.parseDouble( "1234,12", -999 ); // Result: d = 1234.12;
double d = Tools.parseDouble( "1234", -999 );    // Result: d = 1234.0;
double d = Tools.parseDouble( "1234x12", -999 ); // Result: d = -999;

String[] dd = {"123.14","123,15","1234"};
double d = Tools.parseDoubleArray( dd, 1, -999 ); // Result: d = 123.15; 

// Rounding
double x = 123.12;
double d = Tools.roundDouble( x,1 ); // Result: x=123.1

// Fixed length
double x = 123.16;
String s = Tools.fixedLengthDouble( x, 3 ); // Result: s = "123.160";
String s = Tools.fixedLengthDouble( x, 1 ); // Result: s = "123.2";

/* Integer */
// String -> int
int i = Tools.parseInt( "1234", Integer.NaN ); //Result: i = 1234 ;
int i = Tools.parseInt( "1234.1", Integer.NaN ); //Result: i = NaN;

// From unsigned
byte b = -127;
int i = Tools.toUnsigned( b ); // Result: i = 255;

// Leading zero's
String s = Tools.addLeadingZeros( 123, 5); // Result: s = "00123";
````


<div class="page"/>

## B. TimeTools <a name="timetools"></a>

This class is a collection of functions related to time and date.

#### Methods
```java
// This formats the current UTC timestamp to a given format
TimeTools.formatUTCNow( "datetime format" );
/* Below is a summary of the most commonly used items 
 * yyyy means 4 digit year or yy if you only want the last two digits
 * MM is month while mm is minutes
 * dd is the day of the month
 * w is the week of the year
 * HH is hours in 24h notation, Americans would use hh for 12 hours
 * ss are seconds while SSS are milliseconds
 */
// This formats the current UTC timestamp according to: yyyy-MM-dd HH:mm:ss.SSS
TimeTools.formatLongUTCNow();
// So this is actually the same as
TimeTools.formatUTCNow( "yyyy-MM-dd HH:mm:ss.SSS");
// So you could say that the former is a convenience methods of the latter.
```

#### Examples

<div class="page"/>

## C. MathTools <a name="calctools"></a>

This class is a collection of functions related to mathematical operations.
#### Methods
#### Examples

<div class="page"/>

## D. GisTools <a name="gistools"></a>
This class is a collection of functions related to geographical operations.

#### Methods
#### Examples

<div class="page"/>

## E. XMLtools <a name="xmltools"></a>
This class is a collection of functions for interacting with XML files.
They help with:
* Opening an XML file
* Reading values and attributes
* Adding and removing nodes
* Writing an XML file

> **Note:**  For now, there are no methods to add attributes.

#### Methods
```java
// First a file can be read using from a certain path
XMLtools.readXML( Path xml ); // Which returns a Document or null if not found
// Or a file can be created either only in memory (write=false) or also on disk (write=true)
XMLtools.createXML( Path xmlFile, boolean write ); // Returns a document

// Then you can look for the first node with the given tag in the document
XMLtools.getFirstElementByTag( Document xmlDoc, String tag ); // Returns an Element
// Or all the nodes with the given tag
XMLtools.getAllElementsByTag( Document xmlDoc, String tag ); // Returns an Element[] (empty if none found)

/****** Reading ******/
// Once the node is found, you can look inside it for the first child node based on the tag
XMLtools.getFirstChildByTag( Element element, String tag ); // Returns an Element or null if not found
// Or all at once
XMLtools.getChildElements( Element element, String tag ); // Returns an Element[] (empty if none found)
XMLtools.getChildElements( Element element ); // Returns an Element[] with all elements (empty if none found)

//Or don't bother with retrieving the node and ask for it's String value directly
XMLtools.getChildValueByTag( Element element, String tag, String def ); // Returns the string or def if failed
// But i could also be an integer instead
XMLtools.getChildIntValueByTag( Element element, String tag, int def ); // Returns the integer or def if failed
// Or a double
XMLtools.getChildDoubleValueByTag( Element element, String tag, double def ); // Returns the double or def if failed
// Or maybe a boolean? ( yes,true and 1 will return true, no,false and 0 will return false)
XMLtools.getChildBooleanValueByTag( Element element, String tag, boolean def) ; // Returns the boolean or def if failed

// Once you have an element, it's possible to retrieve an string attribute
XMLtools.getStringAttribute( Element parent, String attribute, String def ); // Returns the string or def if failed
// Or an integer
XMLtools.getIntAttribute( Element parent, String attribute, int def ); // Returns the integer or def if failed
// Or a double
XMLtools.getDoubleAttribute( Element parent, String attribute, double def ); // Returns the double or def if failed
// Or a boolean ( yes,true and 1 will return true, no,false and 0 will return false)
XMLtools.getBooleanAttribute( Element parent, String attribute, boolean def ); // Returns the boolean or def if failed

// And if you are wondering how to retrieve the string value of an element
element.getTextContent(); // So you don't need a tool for that

/****** Editing ******/ 
// Remove all the child nodes from a node
XMLtools.removeAllChildren( Node node ); // Returns the amount of child nodes removed, or -1 if the node was null
// Or add one instead
XMLtools.createChildNode( Document xmlDoc, Element parent, String tag ); // Returns the element if successful, null if not

// Make sure the XML is 'clean' before writing
XMLtools.cleanXML(Document xmlDoc); // Returns true if no errors occurred

/****** Writing ******/

// Write it to a specific path
XMLtools.writeXML(Path xmlFile, Document xmlDoc);
// Or update the original file
XMLtools.updateXML( Document xmlDoc );
````

<div class="page"/>

#### Examples

Assume the xml below is the content of writers.xml in the classpath.
```xml
<author name="Tad Williams" age="63" deceased="no">
  <book>Otherworld</book>
  <book>War of the flowers</book>
</author>
```
Then the code to interact with this file, would look like this:
```java
// The file can be read
Document xml = XMLtools.readXML(Paths.get("writers.xml");

// Retrieve the 'author' node
Element author = XMLtools.getFirstElementByTag( xml, "author");
// Get the name or return "John Doe" if none given
String name = XMLtools.getStringAttribute( author, "name", "John Doe"); //name = "Tad Williams"

// Get the age or return -1 if none given
int age = XMLtools.getIntAttribute( author, "age", -1 ); // age = 44
// Check if he/she is deceased or not if not given it is probably 'complicated'
boolean alive = XMLtools.getBooleanAttribute( author, "deceased", "false" );// alive = true

// Get the books
Element[] books = XMLtools.getChildElements( author, "book" ); // Drop "book" to get all the elements
// Print the titles to the console
for( Element book : books){
  System.out.println( book.getTextValue());
}
// Or he same but a bit shorter, this is ok because if no kids, an empty array is returned instead of null
for( Element book : XMLtools.getChildElements( author, "book" ) ){
  System.out.println( book.getTextValue() );
  /* prints:
   *  Otherworld
   *  War of the flowers
   */
}
// Or if you are only interested in the name of the first one
String firstWritten = XMLtools.getChildValueByTag( author, "book", "Doesn't exist"); // firstWritten = "Otherworld"

// Well if you really want, you can burn the books...
XMLtools.removeAllChildren( author ); 
// Or add a new one!
Element newBook = XMLtools.createChildNode( xml, author, "book" ); 
// And give it a name
newBook.setTextContent("War of the flowers 2");
// But watch out because the creation could have been a wrong rumour, so it is better to...
if( newBook != null )
    newBook.setTextContent("War of the flowers 2");

// All done, write the changes to disk
XMLtools.updateXML( xml );
// Or keep the new book a secret for now
XMLtools.updateXML( "hidden_folder/writers.xml",xml);
```


<div class="page"/>

## F. XMLfab <a name="xmlfab"></a>

XMLfab applies XMLtools to provide an interface that allows building a XML file.

```java
// First either connect to a new file or provide path to a new one,
XMLfab.withRoot( Paths.get(xmlPath),"root","subroot") //or a Document instead of a path and any level of root
      .build(); // build the document/file
```
This would be the result:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<root>
  <subroot/>
</root>
```
Or a more elaborate example:
```java
XMLfab.withRoot( Paths.get(xmlPath),"root","subroot")
      .addChild("branch") // add one or multiple in one go
      .addParent("heavybranch") // Or a node that can attach children
        .addchild("twig")      // another child without value 
        .addChild("twig","leftside") // or with a textnode
            .attr("leaves",5).attr("color","red")   // add one or more attributes to the last added node (parent or child)
    .build();
```
This would be the result:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<root>
  <subroot>
    <branch></branch>
      <heavybranch>
        <twig/>
        <twig leaves="5" color="red">lefside</twig>
      </heavybranch>
  </subroot>
</root>
```
If you want to go a level lower...
```java
XMLfab.withRoot( Paths.get("testing.xml"), "root","subroot")
      .addParent("heavybranch")
        .addChild("twig")
            .down()
            .addChild("leaf")
                .down()
                .addChild("bug")
                .addChild("bud")
            .back()
            .addChild("leaf2")
        .back()
        .addParent("twig2")
            .addChild("leafy")
      .build();
```
This results in:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<root>
  <subroot>
    <heavybranch>
      <twig>
        <leaf>
          <bug/>
          <bud/>
        </leaf>
        <leaf2/>
      </twig>
    </heavybranch>
    <twig2>
      <leafy/>
    </twig2>
  </subroot>
</root>
```
Or add comments
```java
XMLfab.withRoot( Paths.get("testing.xml"), "root","subroot")
        .comment("Really heavy!")
        .addParent("heavybranch")
            .addChild("twig")
                .comment("Information about the twig goes here")
      .build();
```
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<root>
  <subroot>
    <!--Really heavy!-->
    <heavybranch>
      <twig>
        <!--Information about the twig goes here-->
      </twig>
    </heavybranch>
  </subroot>
</root>
```
Or to alter that last file
```java
XMLfab.withRoot( Paths.get("testing.xml"), "root","subroot")
        .selectParent("heavybranch")
            .alterChild("twig", "somevalue!").attr("leaves",10)
      .build();
// Or if the file contains multple heavybranch nodes
XMLfab.withRoot( Paths.get("testing.xml"), "root","subroot")
        .selectParent("heavybranch","weight","10kg")  // A heavybranch node with a weight attribute with value 10kg
            .alterChild("twig", "somevalue!").attr("leaves",10) // If the earlier branch wasn't found, this is ignored?
      .build();
```
<div class="page"/>

## G. FileTools <a name="filetools"></a>
This class is a collection of functions for interacting with ascii files.

<div class="page"/>

## H. Database <a name="database"></a>

There are a couple helper classes to make interacting with (mainly sqlite) databases easier.

### 1. SQLiteTable <a name="sqlitetable"></a>

This class is used to build CREATE statements.

Because this is rarely used as standalone, the example for SQLiteDB shows it being used.

### 2. SQLiteDB <a name="sqlitedb"></a>

This class is used to create a SQLite database including tables.

```java
// A standard entry is like this
SQLiteDB db = new SQLiteDB( Paths.get("db","database.sqlite") );
// Or if you plan to append things
SQLiteDB db = SQLiteDB.createDB( Paths.get("db","database.sqlite") );
// Or if it's a daily database it would look like this
SQLiteDB db = new SQLiteDB( Paths.get("db","database"),"yyyy-MM-dd",1,RollUnit.DAY );
// Other RollUnit options: MINUTE,HOUR,WEEK,MONTH 
// Rollover can be added using
db.setRollOver("yyyy-MM-dd",1,RollUnit.DAY );
// NOTE: The code won't verify that the given format is consistent with the RollUnit!
// Note that in order for the rollover etc to work, it needs to be added to a SQLiteManager
das.addSQLiteDB( "db id", db );
// or combining the two 
SQLiteDB = das.addSQLiteDB( "db id", Paths.get("db","database"), "yyyy-MM-dd", 1, RollUnit.DAY );

// Then a table is added like this
db.addTableIfNotExists("tablename") // Not exist meaning don't try if a table with the name does exist, returns a SQLiteTable
  .addTimestamp("timestamp").isUnique()       // To add a timestamp column that is unique, timestamp have 'is not null' as default
  .addEpochMillies("timestamp")               // Alternative timestamp in epoch millis (ie. big number instead of string)
  .addText("column_name").isNotNull()         // To add a text column that doesn't allow a null value
  .addInteger("column_name2").isPrimarykey()  // To add an integer column that contains the primary key
  .addReal("column_name3");                   // To add a double/real/float column that has no special properties

/* To build the insert statement based on the above, rtvals is checked for tablename_columnname if this is not correct, 
 * then the alias should be given
 */
db.addTableIfNotExists("tablename")
  .addReal("columnname","alias");           // Now rtvals will be searched for alias instead of tablename_columname

// Do note, you can 'stack' properties
.addInteger("id").isNotNull().isUnique()     // To add an integer column with title id that doesn't allow null or repeated values 

// Get the associated SQLiteTable object using
SQLiteTable table = db.getTable( "tablename" );

// Once all the tables have been added, the database can be created using
db.createAndDisconnect(); // To create the database and tables and then close the connection
// Or 
db.createTables( false ); // Does exactly the same thing
// Or
db.createTables( true ); //  If for some reason, you do not want to close the connection
```

Or through the settings.xml
```xml
<databases>
  <sqlite id="db_id" path="db/sensordata_"> <!-- Path should be whatever comes before the rollover (if any) and .sqlite will be added if needed -->
    <rollover count="1" unit="day">yyMMdd</rollover>
    <setup idletime="5m" flushtime="30s" batchsize="40">
    <table name="sbe21">
      <timestamp>TimeStamp</timestamp>  <!-- This will automatically be filled in with the system utc timestamp -->
      <text alias="rtk_timestamp">TimeStamp</text> <!-- If a received timestamp should be used, it needs to be done like this -->
      <timestamp alias="rtk_timestamp">TimeStamp</timestamp> <!-- this gives the same result as the line above, alias causes timestamp to become text-->
      <integer>serial</integer>
      <real alias="rtk_longitude">longitude</real> <!-- latitude and longitude are provided by a device called RTK -->
      <real alias="rtk_latitude">latitude</real>   <!-- so sbe21_latitude and sbe21_longitude wouldn't work -->
      <real>temperature</real>      
      <real>conductivity</real> 
      <text>rawhex</real>
    </table>
  </sqlite>
<databases>    
```

#### Alias

This attribute is added to cover a couple of use case, these are mainly relevant when using writeRecord.

**Default usecase**
When writeRecord is used will check the rtvals for a parameter with the name tablename_columnname (except for timestamp, epochmillis) the alias should be left blank or omitted. So the data stored in rtvals needs to adhere to this. In this case the table also doesn't need to be defined in the xml, das will retrieve the tables upon first usage of writeRecord.

**Combined table**
If the table combines values from different sensors than tablename_columnname wont work. Then alias need to be the parameter that is in rtvals like in the above example latitude isn't from the sbe21 but from the RTK so would be stored in rtvals as 'rtk_latitude' instead of 'sbe21_latitude'.

**Multiple of the same sensor**
If there are duplicate sensors (eg. a temperature sensor grid) and the data string contains an unique identifier than this identifier can be used with @macro, so this only works with generics. Then the alias would look like @macro_temperature.

**Unsupported usecases**
* If there are duplicate sensors but no unique identifier, this can't be covered in the generics.
* 

> **Note:** By default, timestamp and epochmillis will be autofilled with the system time. If this isn't the wanted behaviour, alias can be used to point to an alternative time source. Given that rtvals only supports numbers for now, only epochmillis works like that.

### 3. SQLDB <a name="sqldb"></a>

To define a database server, the xml should look like this. Like usual, if there's a default value the tag can be omitted and the default will be used.

```xml
<databases>
  <server id="db id" type="mariadb" > <!-- types: mariadb,mysql,mssql -->
      <db user="user" pass="pass">dbname</db> <!-- user with select,insert rights -->
      <setup idletime="5m" flushtime="30s" batchsize="40">
      <!-- idltime: time since the last query before the connection is closed, default -1 or never -->
      <!-- flushtime: time since the oldest query when queries will be executed, default 30s -->
      <!-- batchsize: amount of queries to buffer before they are executed, default 30 -->

      <!-- Tables can be defined here, only needed if alias is different from the default because das will retrieve the info -->
  </server>
</databases>
```

### 4. Insert <a name="insert"></a>

This class is used to create an insert statement for a SQLite database, not checked on others.

```java
//You could, generate an Insert object and go from there
Insert insert = new Insert("tablename");
// But it might be easier to use the static method
Insert insert = Insert.into("tablename");
// Difference being that the second one allows to 'append' to it, there's a couple options.
insert.addText("text value"); // to add a text value
insert.addNumber( 15 ) // to add an integer or double
insert.addNr( 15 ) // Same thing but less typing
insert.addNumbers( 15, 17, 18) // to add multiple integer or double in one line (NOTE: don't mix!)
insert.addTimestamp() // Adds the current timestamp in UTC and looking like this '2020-07-06 18:15:15.123'
insert.addEpochMillis() // Adds the current timestamp but in millis since the epoch (so a big number)
// Next up this can be converted to a string
String insertStatement = insert.toString();
//or
String insertStatement = insert.create();

// So for example if you have a table data, that contains a timestamp column and two data columns it would look like this
String insert = Insert.into("data").addTimestamp().addNumber(15).addNumber(1.123).create();
// Which is the same as
String insert = "INSERT INTO data VALUES ( '2020-07-06 18:15:15.123',15,1.123);"

// Test have shown that using this class is offcourse slower than writing the statement yourself. 
// But the difference is less than a nano second on a 3 year old laptop.
// So it's exchanging efficiency wih less typo prone and easier altered
```

<div class="page"/>

# Background information

## <a name="serial-history"></a> History of serialports
In the early days of computers, users worked on text terminals which were connected to a central mainframe (giant computer) that did all the processing and computations. Keyboard strokes from the user were send to the mainframe and text to display on the screen was sent back to the terminal and user. In 1960, the “EIA RS-232-C” standard was created to standardize the different physical (connectors, cables, …) and electrical (voltages, pins, …) aspects of this communication and improve compatibility.  

While very old, this standard is still widely in use on a lot of instruments for simple serial communication. The default connector is a 9 pin (two rows of pins) connector that is usually labeled as “RS-232” or “COM”. Newer computers don’t have such a port anymore, and require an USB-serial adapter to connect the devices to a USB port. 

Going back to the original specification, it was clear that there was one master (the mainframe) and one slave (the user terminal). In case of the 9 pin connector, one sent its data on pin 2 and listens on pin 3, the other side does the opposite. In the current use cases, it is less clear which side is the master. Take the connection between an instrument that requires position information and a GPS receiver: which one is master? Which is slave? It is not uncommon to have both devices use the same pin for sending and the same pin for receiving. In this case, a “cross-over” cable or “null-modem” converter is used to “cross” both lines.  

Notice the “\r\n” at the end of the SBE38 message. These are not two different characters, but the common representation of a “carriage return and newline”. These two combined signal to go to the beginning of a New Line (think of a typewriter that had to move the carriage -with the paper in it- to position it back on the left side). For terminals it would position the cursor back at the left side of the screen. A terminal would need both to change the line and go back to the beginning, but recent instruments use different specifications. Some send a newline (“\n” or “NL” or “0x0A”), some send a carriage return (“\r” or “CR” or “0x0D”). Some send and require both to signals at the end of a command/message. What is sent should be described in the instrument user manual, with the rest of the protocol specification.

[Go back](#purpose)

## <a name="xml"></a> XML

```xml
<das>
  <settings/>
  <streams>
    <!--
      Setting up the sensor connections.
    -->
    <stream type="serial"> <!-- Settings for a serial sensor -->
        <serialsettings>9600,8,1,none\</serialsettings>
    </stream>
   </streams>>
</das>
```
XML is a file structure type often used to order information.

Take \<das>, this is called a node and should be terminated with \</das>. When the node contains other nodes, those are called 'child nodes' so \<settings> and \<streams> are child nodes of  \<das>.   
They can have children of their own and there's no real limit to how far this goes.

In the following line:   
\<stream type="serial">
Inside the \<stream> there's the type, this is called an attribute, this tells something about the node.  
In this case that the stream node gives the settings for a serial stream.  
\<serialsettings>9600,8,1,none\</serialsettings> in this 9600,8,1,none is the text value of the node.

If you want to add a comment to an XML file use \<!-- insert comment here --> this can span multiple lines.  

[Go back](#step1b)

<div class="page"/>

## <a name="putty"></a> Putty

Incase the .bat doesn't work.

1. Open putty.exe which can be found in the unzipped folder.
2. Mimic the settings as seens in the screenshot below. (mind the Telnet selection)  
![Run|Debug picture](images/putty.png)
3. Click on 'Save' (to the right)
4. Click on 'Open' (at the bottom)

Incase points 2 and 3 were done before:

Option A: Double click on DAS in the saved sessions list
Option B: Click on DAS, then 'Load' and then 'Open'.

[Go back](#batproblem)

## Java glossary <a name="glossary"></a>

Before we present the first example, a short explanation about Java and its terminology.

Java applications are written in .java files, the content of such a file is called a class.
Classes combined form an application or library. The difference is that:
- an application has one 'main' java file that is the entry point to the application (in our examples this is DasTest.java)
- a library provides functionality to an application but doesn't work by itself.
  In our examples, we are writing an application that uses DAS as a library.

In short, we will make a new application that uses DAS to do most of the work.

To go a bit more into detail on what a class is and consists of.
- classes are blueprints from which objects are made, with their properties and methods
- methods are blocks of code that describe a certain function
- methods can take objects as their input, do something and then return a single object (this can be one of the objects taken (and altered) or a completely different one) or nothing
- to return nothing, the term 'void' is used
- both the classes and methods code block start with { and end with }, so the methods of a class are within the { } block of that class.
- to use a regular method from a class you need to create an object of this class
- to create an new object you call the constructor of the class (this is a special kind of method that creates an object of that class).
- to add methods to a class for added functionality, it needs to be extended. This means taking the class as a basis (without changing the class itself), but adding additional methods in an 'extension'.
- to describe functionality or special cases, comments can be added in between the code. To add a single comment line, start with // and the rest of the line is ignored by the program. To insert multiple lines (a block) of comments, start with /*, and end with */


## <a name="javaglobal"></a> Global Java Application/library structure
How package, class and constructors/methods/objects are connected.
![Global Structure](images/javaStructure.jpg)

[Go back](#glossary)

# Full Examples

## Storing GPS data

A GPS is connected via TCP and sends out the standard NMEA messsages ZDA,VTG and GGA.
Position data is stored in a database with the coordinates in degrees.

$GPZDA,191336.00,10,03,2021,,*6A
$GPGGA,191336.00,5113.583281,N,00256.13935,E,4,16,0.6,8.25,M,47.15,M,1.0,2382*74
$GPVTG,,T,,M,0.0,N,0.1,K,D*27


### Final XML 
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<das>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="DAS">
      <ignore/>
    </telnet>
    <databases>
      <sqlite id="navdata" path="db\navdata.sqlite">
        <setup batchsize="30" flushtime="30s" idletime="-1"/>
        <table name="gps">
          <text alias="timestamp">timestamp</text>
          <real alias="">fixtime</real>
          <real alias="">latitude</real>
          <real alias="">longitude</real>
        </table>
      </sqlite>
    </databases>
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
    <stream id="gps" type="tcp">
      <address>192.168.17.203:2101</address>
      <eol>crlf</eol>
    </stream>
  </streams>
  <filters>
    <!-- Retain messages like $GPZDA,191336.00,10,03,2021,,*6A -->
    <filter id="zda" src="raw:id:gps" type="start">$GPZDA</filter>
    <!-- Retain messages like $GPGGA,191336.00,5113.583281,N,00256.13935,E,4,16,0.6,8.25,M,47.15,M,1.0,2382*74 -->
    <filter id="gga" src="raw:id:gps" type="start">$GPGGA</filter>
  </filters>
  <editors>
    <editor delimiter="," id="zda" src="filter:zda" label="generic:zda">
      <!-- $GPZDA,191336.00,10,03,2021,,*6A -> $GPZDA,2021-03-10 191336.00,,*6A -->
      <edit delimiter="," leftover="append" type="resplit">i0,i4-i3-i2 i1</edit>
      <!-- $GPZDA,2021-03-10 191336.00,,*6A -> $GPZDA,2021-03-10 19:13:36.000,,*6A -->
      <edit delimiter="," from="yyyy-MM-dd HHmmss.SS" index="1" type="redate">yyyy-MM-dd HH:mm:ss.SSS</edit>
    </editor>
  </editors>
  <maths>
    <!-- Convert degreesminutes to degrees -->
    <math delimiter="," id="gga" src="filter:gga" label="generic:gga">
      <!-- $GPGGA,191336.00,5113.583281,N,00256.13935,E... - > $GPGGA,191336.00,51.2263880,N,002.93565583,E -->
      <op index="2">(i2-(i2%100))/100+(i2%100)/60</op>
      <op index="4">(i4-(i4%100))/100+(i4%100)/60</op>
    </math>
  </maths>
  <generics>
    <generic delimiter="," id="zda">
      <text index="1">timestamp</text>
    </generic>
    <generic id="gga" dbid="navdata" delimiter=","  table="gps">
      <real index="1">fixtime</real>
      <real index="2">latitude</real>
      <real index="4">longitude</real>
    </generic>
  </generics>
</das>
````
### Commands to get to that xml


1. Add the connection to the GPS  
 ss:addtcp,gps,192.168.1.2:1234  
2. Create filters for the messages  
 ff:addshort,zda,raw:id:gps,start:$GPZDA  
 ff:addshort,gga,raw:id:gps,start:$GPGGA  
 ff:addshort,vtg,raw:id:gps,start:$GPVTG  
3. Reformat the zda to standard yyyy-MM-dd HH:mm:ss.SSS  
 ef:addblank,zda,filter:zda  
 ef:addedit,resplit,,,i0,i4-i3-i2 i1  
4. Recalculate the coordinates 
 mf:addblank,gga,filter:gga  
 mf:alter,gga,delim:,  
 mf:addop,gga,i4=(i2-(i2%100))/100+(i2%100)/60  
 mf:addop,gga,i4=(i4-(i4%100))/100+(i4%100)/60  
5. Create the generics  
 gens:addblank,zda,st  
 gens:addblank,gga,ssrsr  
6. Create the database  
 dbm:addsqlite,navdata  
 dbm:addtable,navdata,gps,crrr  


