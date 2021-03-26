> Note: dcafs has been a while in development for a couple of years but is new on github, so below and other documentation is being worked on/updated.
> 
# dcafs

What's in an name? It's an acronym for _data collect alter forward store_ which is in broad terms what it actually does.

To go into a bit more detail

## Collect

* Using [Netty](https://netty.io/) to allow to collect from TCP and UDP servers
* Using [jSerialComm](https://fazecast.github.io/jSerialComm/) to collect from to serialports/tty.
* Using [DioZero](https://github.com/mattjlewis/diozero) to collect from I2C and SPI chips.
* Using [Eclipse Paho](https://www.eclipse.org/paho/) to collect from an MQTT broker
* Using [Eclipse Tyrus](https://eclipse-ee4j.github.io/tyrus/) to collect from a Websocket server (still to integrate)

Now that data has been collected, it needs to be altered to suit the next steps

## Alter

* Apply edits to the ascii based data 
  * Remove/replace/append characters
  * Change the order of delimited values
  * ...
* Apply arithmetic operations
  * Allows for addition, substraction, multiplication, division (and remainder), power following the rules

## Forward
* Either to a straight forward eg. data arriving on serialport is forwarded to a tcp port
* Do a filtered forward eg. i don't want the GPGGA from the GPS but do want all the others
* Combine multiple ports eg. make an nmea multiplexer
* ...

## Store
* All raw data is stored in log files using [Tinylog](https://tinylog.org/v2/)
* SQLite is an option for a local filebased database
* Supported database servers are : MariaDB, MySQL, influxDB and MSSQL

## What else?

All of the above is fairly standard for most Data Acquisition Software. What might set dcafs apart:
* Written in Java, so works on windows and linux (not tested on mac) but no java knowledgde required (but can be used as a library) 
* All setup, from connecting to a port to communicating with a I2C chip is defined in XML
* There's no GUI, all communication happens through telnet (with Netty)
* It comes with a scheduler that can connect to everything and issue commands just like telnet and respond to realtime data
* It hosts a TCP server on which all the data is made available
* If it's possible through telnet, it can be done via email
* It can update itself via email (on linux)
* ...
