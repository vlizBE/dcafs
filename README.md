dcafs
=========

A Java tool (or library) that takes care of all the nitty-gritty that needs to be done when a sensor generated data and you want to find that data in a database. Hence _data collect alter forward store_ this is in broad terms what it is capable of.


## Main features
* Collect data from TCP, UDP, MQTT, Websocket, serial/tty, I2C, SPI, email
* Alter with string and math operations, or filter lines out
* Forward back to origin, any other source, hosted tcp server or email eg. create a serial to tcp converter
* Store processed data in SQLite, MariaDB, MySQL, InfluxDB and MSSQL while raw is stored in timestamped .log files
* XML based scheduling engine capabable of interacting with all connected sources and respond to realtime data
* Single control pipeline that can be accessed by telnet, email or the earlier mentioned scheduling engine
* Update itself via email (linux only for now)

## Installation

* Option one is cloning the hub, and run it/package it in your preferred IDE.
* Option two is look at the most recent release and download that. There's no installer involved, just unpack the zip.

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
Back in the telnet client, add a data source.
* ss:addserial,serialsensor,COM1:19200,void  --> adds a serial connection to a sensor called serialsensor that uses 19200Bd
* ss:addtcp,tcpsensor,localhost:4000,void  --> adds a tcp connection to a sensor called tcpsensor with a locally hosted tcp server

Assuming the data has the default eol sequence, you'll receive the data in the window by typing
* raw:serialsensor --> for the serial sensor
* raw:tcpsensor --> for the tcp sensor

Again in the background, the settings.xml will now look like this:
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
      <label>void</label>
      <eol>crlf</eol>
      <serialsettings>19200,8,1,none</serialsettings>
      <port>COM1</port>
    </stream>
    <stream id="tcpsensor" type="tcp">
      <label>void</label>
      <eol>crlf</eol>
      <address>localhost:4000</address>
    </stream>
  </streams>
</das>
````
Sending 'help' in the telnet interface should provide enough information for the next recommended steps but for more indepth and extensive information, check the docs/wiki.

# History

Although this hub is new, the project isn't. The internal tool has been in semi-active development since 2012 and has since grown from just another piece of data acquision software to what it is today... 

The instance that started it all, and is still running is onboard [RV Simon Stevin](https://www.vliz.be/en/rv-simon-stevin), it:
* collects data from about 20 sensors (serial,tcp,polled,freerunning ...)
* Controls the calibration and verification process of scientific equipment by controlling pumps & solinoids on a scientist defined and editable schedule
* Informs those interested if the ship leaves or returns to the dock
* Monitor and control it via email

The above is the biggest projects it's used for, some smaller ones:

* The earlier mentioned 'calibration and verification process' is also used in a mobile setup build for [ICOS](http://icos-belgium.be/) and the has visited the RV James Cook.
* A experimental setup for tracking Enhanced silicate weathering in which dcafs is running on multiple beaglebone that monitor salinity sensors and connects to a central database
* Inside an ROV control container, storing environmnentals and altering datastreams for other software
* At home, running on a Neo Pi air and reading a BME280 sensor, storing the data on a local InfluxDB and presenting it with Grafana
