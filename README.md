dcafs
=========
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)  

> **Note: Because of reduction to possible worktime allocated to this project, this has been reduced to 'maintenance' 
only. [Hobby Project active fork, not backwards compatible](https://github.com/michieltjampens/dcafs)**

A Java tool (or can also be used as a library) that takes care of all the nitty-gritty that needs to be done when a sensor has generated data and you want to find that data in a database. Hence _data collect alter forward store_. If the device needs to be interrogated or any other sort of control, that's also possible (hidden under _forward_).   
That is in broad terms what it is capable of.

## Main features
* Collect data from TCP, UDP, MQTT broker, serial/tty, I2C, SPI, email, ascii files
* Alter with string and math operations, or filter out lines
* Forward back to source/origin, to any other source, a hosted TCP server or email (ie. create a serial to tcp converter)
* Store processed data in SQLite (dcafs will create the db), MariaDB, MySQL, InfluxDB, PostgreSQL and MSSQL (dcafs can create/read the table structure) while additionally raw/altered data can be kept in (timestamped) .log files
* XML based scheduling engine capabable of interacting with all connected sources and respond to realtime data
* Single control pipeline that can be accessed via telnet, email or the earlier mentioned scheduling engine
* Update itself via email (linux only for now)

## Installation
* Make sure you have _at least_ java17 installed. If not, [download and install java 21](https://adoptium.net/)
* Either download the most recent (pre)release [here](https://github.com/vlizBE/dcafs/releases)
  * Unpack to the working folder  
* Or clone the repo and build it with maven (mvn install) directly or through IDE.
  * copy the resulting dcafs*.jar and lib folder to a working dir

## Running it
### Windows
* If you have java11+ installed properly, just doubleclick the dcafs*.jar
  * If extra folders and a settings.xml appear, this worked
* If java 11+ isn't installed properly, check the installation step
   
### Linux
* In a terminal
  * Go to the folder containing the .jar
  * sudo java -jar dcafs-*.jar  (sudo is required to be able to open the telnet port)
  * To make this survive closing the terminal, use [tmux](https://linuxize.com/post/getting-started-with-tmux/) to start it or run it as a service
* As a service:
  * If going the repo route, first copy past the install_as_service.sh file to the same folder as the dcafs*.jar 
  * chmod +x install_as_service.sh file
  * ./install_as_service.sh
    * Restarting the service: sudo systemctl restart dcafs
    * Get the status: sudo systemctl status dcafs
    * Read the full log: sudo journalctl -u dcafs.service
    * Follow the console: sudo journalctl -u dcafs.service -f
   * Optional, add bash Alias for easier usage (apply with source ~/.bashrc)
     * echo "alias dcafs_restart='sudo systemctl restart dcafs'" >> ~/.bashrc
     * echo "alias dcafs_start='sudo systemctl start dcafs'" >> ~/.bashrc
     * echo "alias dcafs_stop='sudo systemctl stop dcafs'" >> ~/.bashrc
     * echo "alias dcafs_log='sudo journalctl -u dcafs.service'" >> ~/.bashrc
     * echo "alias dcafs_track='sudo journalctl -u dcafs.service -f'" >> ~/.bashrc
  
## First steps

It's recommended to follow [this](https://github.com/vlizBE/dcafs/wiki/Getting-to-know-dcafs) guide if it's your first time using it.

Once running and after opening a telnet connection to it, you'll be greeted with the following screen.

<img src="https://user-images.githubusercontent.com/60646590/112713982-65630380-8ed8-11eb-8987-109a2a066b66.png" width="500" height="300">

In the background, a fresh settings.xml was generated.
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="dCafs"/>
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
  </streams>
</dcafs>
````
Back in the telnet client, add a data source:
* `ss:addserial,serialsensor,COM1:19200`  --> adds a serial connection to a sensor called serialsensor that runs at 19200 Baud
* `ss:addtcp,tcpsensor,localhost:4000`  --> adds a tcp connection to a sensor called tcpsensor with a locally hosted tcp server

Assuming the data has the default eol sequence (check this by using: TODO), you'll receive the data in the window by typing
* `raw:serialsensor` --> for the serial sensor
* `raw:tcpsensor` --> for the tcp sensor

Meanwhile, in the background, the settings.xml was updated as follows:
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
   <telnet port="23" title="dCafs"/>
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
</dcafs>
````
Sending 'help' in the telnet interface should provide enough information for the next recommended steps but for more indepth and extensive information, check the docs/wiki.   

Oh, and the command `sd` shuts it down.

# History

Although this repository is new, the project isn't. It has been in semi-active development since 2012 as an internal tool at a marine institute and has since grown from just another piece of data acquision software to what it is today... 

The instance that started it all 8 years ago (and is still very active onboard the research vessel [RV Simon Stevin](https://www.vliz.be/en/rv-simon-stevin) ):
* collects data from about 20 sensors (serial,tcp,polled,freerunning ...)
* controls the calibration and verification process of scientific equipment for the [ICOS project](https://www.icos-belgium.be/)
* sends out emails when the ships leaves or returns to the harbour
* can be monitored from shore using email

The above is the biggest project it's used for, here are some smaller ones:

* The earlier mentioned 'calibration and verification process' is also used in a mobile setup build that has visited the RV James Cook.
* Multiple Beaglebones are running dcafs to log salinity data for an experimental setup that tracks Enhanced silicate weathering
* Inside an ROV (remotely operated underwater vehicle) control container: storing environmnental data and altering datastreams to prepare for use in other software
* At home: running on a Neo Pi air and reading a BME280 sensor, storing the data on a local InfluxDB and presenting it with Grafana
