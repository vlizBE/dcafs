dcafs
=========

A Java tool (or can also be used as a library) that takes care of all the nitty-gritty that needs to be done when a sensor has generated data and you want to find that data in a database. Hence _data collect alter forward store_. This is in broad terms what it is capable of.


## Main features
* Collect data from TCP, UDP, MQTT, Websocket, serial/tty, I2C, SPI, email
* Alter with string and math operations, or filter lines out
* Forward back to origin, to any other source, a hosted tcp server or email (eg. even create a serial to tcp converter)
* Store processed data in SQLite (dcafs will create these for you!), MariaDB, MySQL, InfluxDB and MSSQL  while additionally raw can be kept in timestamped .log files
* XML based scheduling engine capabable of interacting with all connected sources and respond to realtime data
* Single control pipeline that can be accessed by telnet, email or the earlier mentioned scheduling engine
* Update itself via email (linux only for now)

## Installation
* Make sure you have atleast java11 installed 
* Either download the most recent (pre)release [here](https://github.com/vlizBE/dcafs/releases)
  * Unpack to the working folder  
* Or clone the repo
  * copy the resulting dcafs*.jar and lib folder to a working dir

## Running it
### Windows
* If you have java11+ installed properly, just doubleclick the dcafs*.jar
  * If extra folders and a settings.xml appear, this worked
* If java 11+ isn't installed properly...
   
### Linux
* In a terminal
  * Go to the folder containing the .jar
  * sudo java -jar dcafs-*.jar  (sudo is required to be able to open the telnet port)
  * To make this survive closing the terminal, use [tmux](https://linuxize.com/post/getting-started-with-tmux/) to start it
* As a service:
  * If going the repo route, first copy past the install_as_service.sh file to the same folder as the dcafs*.jar 
  * chmod +x install_as_service.sh file
  * ./install_as_service.sh
  
## First steps

Once running, and after opening a telnet connection to it, you'll be greeted with the following screen.

<img src="https://user-images.githubusercontent.com/60646590/112713982-65630380-8ed8-11eb-8987-109a2a066b66.png" width="500" height="300">

In the background, a fresh settings.xml was generated.
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<das>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="DAS">
      <ignore/>
    </telnet>
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
  </streams>
</das>
````
Back in the telnet client, add a data source:
* `ss:addserial,serialsensor,COM1:19200`  --> adds a serial connection to a sensor called serialsensor that uses 19200Bd
* `ss:addtcp,tcpsensor,localhost:4000`  --> adds a tcp connection to a sensor called tcpsensor with a locally hosted tcp server

Assuming the data has the default eol sequence (check this by using: TODO), you'll receive the data in the window by typing
* `raw:serialsensor` --> for the serial sensor
* `raw:tcpsensor` --> for the tcp sensor

Meanwhile, in the background, the settings.xml was updated to this:
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<das>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="DAS">
      <ignore/>
    </telnet>
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
    <stream id="serialsensor" type="serial">
      <eol>crlf</eol>
      <serialsettings>19200,8,1,none</serialsettings>
      <port>COM1</port>
    </stream>
    <stream id="tcpsensor" type="tcp">
      <eol>crlf</eol>
      <address>localhost:4000</address>
    </stream>
  </streams>
</das>
````
Sending 'help' in the telnet interface should provide enough information for the next recommended steps but for more indepth and extensive information, check the docs/wiki.   

Oh, and the command `sd` shuts it down.

# History

Although this git is new, the project isn't. It has been in semi-active development since 2012 as an internal tool and has since grown from just another piece of data acquision software to what it is today... 

The instance that started it all 8 years ago (and is still very active onboard [RV Simon Stevin](https://www.vliz.be/en/rv-simon-stevin) ):
* collects data from about 20 sensors (serial,tcp,polled,freerunning ...)
* controls the calibration and verification process of scientific equipment for the [ICOS project](https://www.icos-belgium.be/)
* sends out emails when the ships leaves or returns to the harbour
* can be monitored from shore using email

The above is the biggest project it's used for, here some smaller ones:

* The earlier mentioned 'calibration and verification process' is also used in a mobile setup build that has visited the RV James Cook.
* Multiple Beaglebones are running dcafs to log salinity data for an experimental setup that tracks Enhanced silicate weathering
* Inside an ROV (remotely operated underwater vehicle) control container, storing environmnental data and altering datastreams to prepare for use in other software
* At home, running on a Neo Pi air and reading a BME280 sensor, storing the data on a local InfluxDB and presenting it with Grafana
