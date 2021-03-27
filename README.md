dcafs
=========

A Java tool (or library) that takes care of all the nitty-gritty that needs to be done when a sensor generated data and you want to find that data in a database. Hence _data collect alter forward store_ this is in broad terms what it is capable of.


## Installation

* Option one is cloning the hub, and run it/package it in your preffered IDE.
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
* serial sensor: ss:addserial,serialsensor,COM1:19200,void
* tcp server: ss:addtcp,tcpsensor,localhost:4000,void

Assuming the data has the default eol sequence, you'll receive the data in the window by typing
* raw:id:serialsensor for th serial sensor
* raw:id:tcpsensor for the tcp sensor

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
For the rest of the functionality, check the wiki or the manual in the docs folder.
