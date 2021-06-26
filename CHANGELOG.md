## Changelog
Note: Version numbering: x.y.z 
  -> x goes up with major breaking api changes
  -> Y goes up with major addition/changes
  -> z goes up for minor additions and bugfixes
From 0.5.0 onwards, this should be better documented...

#### Todo
- db connect in separate thread
- Writable comparable on id?
- ConfirmCollector give ok on no reply or nok on certain reply?
- Trans as output in taskmanager?
- Trans command >>>signin:id to claim to be a certain id and pass the ip test

## Work in progress
## 0.10.x
- Goals for this version series (removed when as they are done)
  * Code cleanup
    * Bring javadoc up to date
    * Decide on final class structure (mainly StreamPool)   
  * Rework the TaskManager (will be trigger for 0.11.0)

## 0.10.2 (work in progress)

## 0.10.1 (26/06/21)
Breaking: Removed below,above etc in favor off <, > etc from task req etc

### Refactoring
- StreamPool -> StreamManager for consistency, and some methods
- Moved forward,collector, Writable, Readable to a level higher out of stream
- Influx -> InfluxDB
- Renamed TaskList back to TaskManager and TaskManager to TaskManagerPool

### Task
- Replaced Verify with RtvalCheck, uses MathUtils and should be cleaner code
- Added option to add {rtval:xxx} and {rttext:xxx} in the value of a task node
- Fixed repeats, were executed once to many (counted down to zero)
- Added option to use an attribute for while/waitfor checks, to allow usage of < and > etc
- Added option to use an attribute stoponfail (boolean) to not stop a taskset on failure of the task
- Removed below,above etc in favor off <, > etc

### CommandPool
- The store command now accepts formulas fe. store:dp1,dp1+5 or store:dp1,dp2/6 etc. This can be used in a 
task...
- Removed doEditor,doFilter,doMath were replaced by Commandable in 0.10.0

### Other
- Fixed ef:? command

## 0.10.0 (24/06/21)

Too much breaking stuff, so version bump and update guide.

## Update guide from 0.9.x
- databases node: replace 'setup' with 'flush' and 'flushtime' with 'age'
- databases node: replace 'idle' attribute with 'idleclose' node
- Tasklist: Replace the @fillin with {fillin}
- FilterForward: rename source node to src node

## Changes

### BaseReq
- Renamed to CommandPool
- Added interface Commandable, these can be given to CommandPool to use as custom commands
- Removed option from dcafs to extend CommandPool, should now use the interface

### DatabaseManager
- BREAKING: renamed the setup node to flush and flushtime to age, move idle out of it
- Added interface for simple querying, used by realtimevalues

### Datagram
- Renamed DataWorker to LabelWorker, fits a bit better... i think
- Replaced the Datagram constructors with a fluid api, should make them easier to read
````java
// For example
var d = new Datagram( "message", 1, "system");
d.setWritable(this)
d.setOriginID(id);
// Became
var d = Datagram.build("message").label("system").priority(1).origin(id).writable(this);
// But priority is 1 by default, id is taken from the writable, so this does the same
var d = Datagram.build("message").label("system").writable(this);
//or even shorter, system is build with system label (default label is void)
var d = Datagram.system("message").writable(this);
````
### TaskManager
- BREAKING: Replaced @fillin with {fillin}
- Moved it out of the main and renamed taskmanager to tasklist, made taskmanager that holds the main code  
- Implemented Commandable to access it via CommandReq
- Added the trigger 'waitfor', this can be used in a tasket to wait for a check to be correct a number of
times with the given interval so trigger="waitfor:5s,5" will check 5 times with 5 seconds between each check (so 20s in total)
- Bugfix: Not sure why the while actually worked... because fixed it. Runs never got reset
- Added an alternative way to add while and waitfor:
````xml
<!-- This will wait till 5 check return ok, restarting the count on a failure -->
<task trigger="waitfor:5s,5" req="value below 10"/>
<!-- can now also be written as -->
<waitfor interval="5s" checks="5">value below 10</waitfor>
<!-- This will wait till 5 checks return ok, stopping the taskset on a failure -->
<while interval="5s" checks="5">value below 10</while>
````
- Added the attribute 'atstartup' for single tasks that shouldn't be started on startup, default true
- Verify to string didn't take math into account

### DigiWorker
- Added interface SMSSending to use instead of passing the queue

### MQTTWorker
- Added MqttPool that interfaces the mqttworkers, to move code out of das.java
- MqttPool implements the Commandable interface

### EmailWorker
- Renamed EmailWork to Email and added fluid api
```java
var email = new EmailWork(to,subject,content,attachment,deleteattachment);
//became
var email = Email.to(to).subject(subject).content(content).tempAttachment(attachment);
// Also possible
Email.to(to).from(from);
// Special case
var adminEmail = Email.toAdminAbout(subject);
```  
- Removed regular options in favor of the fluid api and applied it throughout

### Forwards
- Moved the code out of StreamPool and into ForwardsPool
- BREAKING Changed source node option to src in filterforward for consistency with mathforward & attribute  
- ForwardsPool implements Commandable
- No functionality change (normally), just code separation
- EditorForward: now has addblank command, no idea yet on how to do the edits...

### Other
- cmds command now supports regex, uses startswith by default (meaning appends .*)
- Changed `update:setup` to `update:settings` because it's the settings file
- Updated dependencies
- Triggered cmds now support having a command as content of email:send,to,subject,content

### Bugfixes
- update command still referred to scripts instead of tmscripts, now also looks up the path instead of assuming default 
so `update:scripts,scriptfilename` is now `update:tmscript,taskmanager id`
- Same for retrieve command
- `ff:addblank` and `ff:addshort` didn't notify on errors but claimed 'ok'
- TransServer: >>>label: wasn't working because of bad unaltered substring
- Regex that determines workpath was wrong

## 0.9.9 (11/06/21)

### Modbus
- Added support for being a src
- Improved writing support, will calculate and append the crc16 by default

### Streampool
- Added writeBytes to the Writable interface
- Writebytestostream no longer allows appending eol

### Fixes 
- FileCollector: didn't reset headers,cmds before reading xml (only relevant on a reload)
- Modbus: Set the eol to empty on default instead of crlf
- MathUtils: modbus crc append only appended the first byte
- TaskManager: when creating a blank the path given to the object was still the old scripts instead of tmscripts

## 0.9.8 (09/06/21)

### FileCollector
- Added rollover (like sqlite) but with possible zipping
- Added max size limit. When going over file gets renamed to oldname.x.ext where x is 1-1000, with option to zip  
- Added flush on shutdown
- Improved timeout write to take in account last write attempt
- Added possibility to execute commands on idle, rollover and max size reached.
- Added macro {path} for the commands that gets replaced with the path
- Added telnet/cmd interface see fc:?

### SQLiteDB
- Moved the rollover timestamp update code to TimeTools, so FileCollector can use it
- Added option to put {rollover} in the path to have this replaced with the timestamp

### Other
- Added command 'stop', wil cause all src to stop sending to the writable

## 0.9.7 (05/06/21)

### Streampool
- When connecting to TCP stream, the stream is only added if connected.
- Give feedback to user if a TCP connection was a success

### FileCollector
- Allows src to be written to a file with custom header and flush settings
- Still todo: telnet/command interface

### SQLite
- Fixed absolute versus relative path
- Fixed rollover again... wasn't used correctly when added through telnet

## 0.9.6 (31/05/2021)

### General
- Added check for \<tinylog> node in settings.xml to override the path to the logs

### FilterForward
- Added option to get the discarded data instead (could be used to chain filters mor efficiently)
used with filter:!id.
- Added ff:swaprawsrc,id,ori,new to swap from one raw source to another. Can be used in combination
with the idle trigger to swap to secondary device and back to primary on !idle
  
### EditorForward
- replace type now works with hex and escape chars

### BugFixes
- clearGenerics actually cleared another map (hurray for copy paste)
- Before a full reload of forwards, the current once are first set to invalid (because 
  clearing the map only removes those references not the ones in other objects) 
- SQLite got double extension if user provided it, then didn't work with rollover...

## 0.9.5 (15/05/2021)

### Breaking changes
- default path for i2c xml is moved from devices to i2cscripts
- default node of the xml changed to dcafs from das

### BaseWorker
- Added the labels rtval:x and rttext:x, to directly store as rtval/rtext without generic
the rtval part only works if the data = a number (so no delimiting etc)

### MathForward
- supports sqrt with ^0.5
- added def node \<def ref="name">value\</def> to use defaults in a formula (type=complex only), useful
for calibration coefficients that might otherwise be buried...
- mf:reload now reloads all of them from the xml
- Added type salinity to calculate salinity based on temp,cond and pressure
- Added type svc to calculate soundvelocity based on temp,salinity and pressure

### EditorForward
- Renamed from TextForward
- Started adding commands with ef:x
- Added charsplit to split a string based on charposition (fe. ABCDEF -> 2,4 => AB,CD,EF )
- Added trim, to remove leading an trailing spaces (instead of remove ' ')

### TaskManager
- Changed the default folder to tmscripts, this doesn't affect current installs

### Fixes
- dbm:store command was missing from dbm:?
- TransHandler: label containing a : wasn't processed properly
- FilterForward: without rules had issues writing to xml
- EditorForward: resplit now uses the chars in front of the first index (were forgotten)

## RELEASED
## 0.9.4 (11/05/2021)

### Streams
- Added the trigger !idle to run if idle condition is lifted

### SQLiteDB
- Fixed rollover so the error mentioned in 0.9.2 is no longer thrown

### Bugfixes
- Tools get ip failed when no network connectivity was active
- TransServer: For some reason the address was missing from the store command

## 0.9.3 (03/05/2021)
This seems like it's going to be a bugfix/prevent issues release...
ToDo: Sometimes a rollover doesn't properly generate the tables...

### SQLLiteDB
- If a 'no such table error' is thrown while processing an sqlite prep.
  
### BaseWorker
- Replaced Executioner with Threadpool for more runtime info
- Added selfcheck on 10min interval that for now only gives info in the logs about processing

### Other
- After email:addblank, loading is possible with email:reload

### Bugfixes
- DebugWorker: if the raw data contains tabs, this wasn't processed properly
- SQLiteDB: First change path to db and then disconnect, to be sure nothing else gets in between...


## 0.9.2 (30/04/2021)

### Email
- Added extra restrictions to commands via email
  * Emails not from someone in contactlist are considered spam
  * commands from someone in contactlist without permission is ignored
- Multiple instances can share an inbox, subject end with 'for ...' where
... is whatever is in front of the @ in the from email. Multiple can be seperated
  with ,
- No wildcard yet to send to all instances without the 'for' only the first check
will get it.

### Databases
- Added PostgreSQL support because of #16
  * Added support for timestamp/timestamptz
- Now writing the OffsetDateTime class to the database instead of converting to string when possible
- Added the columns datetime,localdtnow and utcdtnow last two are filled in by dcafs

### Generics
- Added filler localdt and utcdt to present the offsetdatetime object of 'now'

### Forwards
- Added attribute 'log' (default false) to indicate if the result should be written to the raw files

### Other
 - Added concept of Readable, with this an object can declare that it is willing
to accept writables. Applied it to debugworker to use and baseworker to interconnect.
 - jvm version is now added to the status page, after dcafs version

### Bugfixes
- IssueCollector had a missing bracket in a to string that changed formatting


## 0.9.1 (25/04/2021)
Early release because of the way Influxdb was badly handled on bad connections and that 
tinylog didn't write to workpath (on linux when using service) because of relative paths.

### Influx
- Added auto reconnect (state checker)
- Written points are buffered if no connection

### Other
- Added admin:gc, this forces the jvm to do garbage collection
- Added memory info to st report (used/total)

### Bugfixes
- TimeTools.formatLongNow didn't specify a zone
- Paths for tinylog are relative which is an issue if the jar isn't started directly, 
now the paths get set at the beginning.
  

## 0.9.0 (24/04/2021)
- Updated dependencies
- Rewrote BaseWorker to use multiple threads (reason for 0.8.x -> 0.9.x )

#### Emailworker
- Used to be domain restricted cmd request now it's ref restricted meaning that if the from isn't mentioned in 
the emailbook it can't issue cmd's
- only admin's can issue 'admin' (admin:, retrieve, update,sd,sleep) cmds by default, others need permission
- now uses XMLfab for the write to xml

#### Mathforward
- Added support for i0++ and i+=2 etc
- Added mf:addcomplex,id,src,op
- Added single line xml (like filter has) if one op & one src
- bugfix: mf:addop now actually writes to xml

#### TransServer
With all the changes the server is now fit to receive data from sensors (instead of only serving).
- Changed command to ts or transserver (fe. ts:list instead of trans:list)
- Transserver connections are now available as forward, use trans:id 
- Label is now an attribute for a default etc and can be altered
- History recording is now optional, default off
- Now supports !! like telnet
- Added >>>? to get a list of available commands

#### Others
- added selectOrCreate to XMLfab without a specified attribute
- removed the doDAS command, was not maintained anyway (and had become outdated)
- Updated the SQLite dependency, no changes in performance noticed
- Altered install service script to use wildcard
- Timetools using instant more (slightly faster than previous)
- Generic uses NumberUtils instead of own parseDouble
- Debugworker allows looping x times

#### BugFixes
- Influx wasn't mentioned in generic info if other dbid is present

### 0.8.3 (09/04/2021)

#### Taskmanager
- Removed the sqlite from the taskmanager, wasn't used anyway
- Added @rand6,@rand20 and @rand100 to task fill in

#### Streampool
- added rttext:x equivalent of rtval:x
- ss:addtcp and ss:addserial no longer require a label defined

#### Generics
- added gens:fromdb,dbid to generate all generics at once (if new)
- gens:addblank was missing info on how to add text (t)
- generics label is now case sensitive but accepts regex

#### Databases
- Added dbm:addrollover to add rollover to an sqlite
- Table no longer contains a default empty alias
- When tables are read from xml, table name and columnname are trimmed
- It's assumed that if there's an autofill timestamp column it's the first one
- Tables are no longer written to the xml if present in db on first connect
- Added command to write a table in memory to xml dbm:tablexml

#### Forwards  
- Added min length and max length filters to filterforward
- Added ff:alter command to alter the label (nothing else yet)
- Added nmea:yes/no as a possible filterforward rule, checks if it has a proper *hh
- TextForward resplit now actually does something with leftover

#### Other
- Updated dependencies
- Removed the sources inside devices, didn't belong  in the repo
- Trans now allows editing id during store command
- Removed the default sqlite from Issuecollector, need to actually assign one
- setRealtimevalue retains case
- Path to settings.xml etc no longer relative but based on the .jar location
  * takes in account if used as lib (if inside a folder .*[lib])
  
#### Bugfixes
- sleep command was always using rtc0, now it can be selected
- Influx.java was using the wrong dependency for String operations
- Influx should now appear in the list from st etc
- trans:store,x removed wrong nodes on adding history
- timestamp column is now really a timestamp column on database servers (used to be text by default)
- the gen:addblank format f made a text node instead of filler
- starting a task directly called the wrong method doTask instead of startTask
  in that case the interval etc tasks aren't run properly
- ff:addrule wasn't working as it should nor was the xmlread/write on for start type 
- tables read from a server weren't marked as such
- index of label read from commands wasn't correct
- removed default alarms taskmanager but didn't check if one was made later

### 0.8.2 (27/03/2021)
#### I2CWorker
 * Added command to add a device (and generate empty script)
 * Altered i2c:list to only show list of devices and commands
 * Added i2c:cmds to get full list of commands (eg. include the read/write)

#### Streampool
 * raw:id:sensorid now looks for a 'startswith' match if exact match is missing
 * Added calc:reqs and rtval:reqs to get a rough idea on the global requests made (reason, recent bug)
 * ff:reload now reloads all filters (previously only ff:reload,id was possible)

#### BaseReq
* dbm:reload,id now gives error feedback if any
* scriptid:list now gives a better listing
* added admin:gettasklog, this sends the taskmanager log
* added admin:getlastraw, this sends the latest raw file

#### Other
 * mf:addblank now supports source containing a ',' (eg. to i2c:forward,id)
 * Database tables are now also generated serverside
 * rtvals listing is now split according to prefix if any...
 
#### Bugfixes
- Email started BufferCollector now collects one minute of data again
- Multiple 'calc:' requests for the same thing across multiple telnet sessions weren't allowed anymore. Caused by a
previous change that should have fixed removing them.
- Database rollover screwed up in the beginning of a month due to weekly rollover
- Database no longer gets default 'remote' name when it already has a table
- script to command now allows numbers in the name/id

### 0.8.1  (14/03/2021)
####TaskManager  
 * Task(sets) with an id can now be started as a single command:
    * taskmanagerid:taskid/tasksetid => start the task/taskset (first tasksets are checked) same as tm:run,manid:taskid
    * taskmanagerid:? => get a list of all available tasks/tasksets
    * If a command exists that's the same as the taskmanager id, then the command is run
#### Other 
* Telnet
   * Pressing up arrow followed by enter, will redo the last command (but still show a bunch of symbols) 
* Databases
   * Added limited support for InfluxDB (can connect & write to it via generics) 
   * SQLiteDB now extends SQLDB instead of Database to allow Database to be more generic (for influxdb)
    
#### Bugfixes
* Sending empty string to telnet didn't work anymore (outofbounds)
* CalcRequests now have an entry for each request from the same writable (instead of overwriting...)
* Calc and rtval requests can no longer by duplicated (eg. asking twice the same won't give twice)

### 0.8.0 (14/01/2021)

### Summary
- Stream part has been revamped to allow easier expansion, introduction of writable etc
- the database part has been rewritten to use prepared statements instead
- Added collector and forward classes that either collect data from a source or forward it after altering
- Updated dependencies to latest version (except sqlite)

### Database_redo
- tables can store prepared statements (not the object)
- generics now use prepared statements
- generics that exactly match a table now directly write a query (instead of going through rtvals)
- each database has own query write thread

### Stream_Redo

**Braking changes**
- Default for addDatarequest is now title: instead of label, so raw:id will now become raw:id:streamid instead of raw:label:streamid

**Features**
- Tasksets that contain writing to a stream now fail if this writing failed or a reply was not given. Failure means tha failure task(set) is run
- rtval:x now actually updates in 'realtime' and no longer passes the transserver
- BaseStream allows for telnet commands to be executed on stream open, can be used to request data etc
- MQTT subscriptions can now be forwarded to a stream via mqtt:forward,brokerid (stream issueing the command will be used)
- Added LocalStream to use when you want to use the result of a FilterStream etc as a datasource

**Changes**
- StreamHandler was replaced with the abstract class BaseStream TCP,serial and telnet done, UDP and trans todo
- SerialHandler now extends BaseStream and is renamed to SerialStream
- Added the interface Writable, this allows a BaseStream etc to share its writing functionality
- Waiting for reply was moved out of the StreamHandler and in a seperate class ConfirmWritable, this has an listener te report succes (or not)
- Removed the use of StreamHandler in favor of BaseStream+Writable
- EmailWorker: Buffered respons of realtimedata now works with own class instead of through transserver
- TransServer: nearly complete rewrite, now acts purely as a tcp server for incoming connections (it used to provide the data to telnet etc)
- UDP converted to BaseStream
- PDM now using to writable+ConfirmWritable instead of StreamHandler
- TODO: CTD(shouldn't be in the core)
- StreamListener is now stored in arraylist in the stream to allow multiple listeners

### 0.7.2 (22/10/2020)

**Features**
- Improved command: rtval: now allows wildcards rtval:*temp -> rtvals that end with temp, rtval:temp* -> rtvals that start with temp, rtval:*temp* > rtvals that contain temp
- Improved command: rtvals now allows a second parameter that makes it get the same result als rtval but only once (not updated every second)
- XMLfab: added down() this allows to go down a level (aka child becomes parent) go back up with up()

**Changes**
- When a task checks a rtval and that rtval doesn't exist, the task and future executions (eg interval) are cancelled
- Changed command: gens:astable to gens:fromtable
- TransServer: rewrote the alterxml code to use XMLfab

**Bug Fixes**
- Issuecollector.Resolveds: getCount() was still size of the arraylist instead of the count
- StreamPool: When storing a stream to xml, the document wasn't reloaded so anything done to it since startup was lost
- SQLite rollover now actually happens 'cleanly' meaning that 1day is actually starts at midnight etc, cause was using the wrong object (original instead of temp)

### 0.7.1
**Features**
- Added command (gens:astable) that creates a generic based on a database table, existing one is updated
- Added command (dbm:tables,dbid) that give info on the stored tables
- Added command (tasks:addblank,id) that creates a blank tasks xml
- Added command (tasks:reload,id) that reloads a specific taskmanager

**Changes**
- Made database abstract and connect() and getcurrenttables() methods aswell
- gens:addblank now allows for delimiter to be given at the end (not mandatory)

**Bug Fixes**
- When adding a tcp stream with the streams:addtcp command, hostnames are now properly used.
- trans:index,request didn't process trans:0,raw:title:something correctly because split in : instead of substring
- Mathtools.calstdev tried running with empty array, caused out of bounds
- IssueCollector: the resolved instances were kept track of, this can get huge so limited it to the last 15

### 0.7.0 

**Summary**
- Database code revised
- TransServer operating revised
- Generics expanded
- XML syntax unified, added XMLfab to write XML files hiding boilerplate code from XMLtools

**Features**
- Generics can now:
  * write to database servers, this means that the database is interrogated regarding tables and the columns in order to know what to write. It also possible to write to multiple db's from a single generic ie. dbid="db1,db2"
  * Use received data to determine the rtval reference, so if logging multiple of the same sensor and the data contains an identifier this can be used with @macro (and <macro index="1"/> to define it).
    This can also used with a database writeRecord using the 'alias' functionity with @macro added.
  * Write to a MQTT broker (see manual for usage)
- Streams that use generics can be requested from the transserver, raw:generic:id or raw:generic:id1,id2,id3 etc
- SQLiteTable: Added the option to use a default value incase the rtval isn't found
- TransServer: Added trans:reqs to see the currently active requests (includes email)
- Added some commands to add blank xml elements for generics,tables etc

**Changes**
- QueryWorker: removed
- Database: replaced
- SQLDB,Database: result of rewriting database code so the usage of sqlite and sql server is the same for the end user
- All DAS related things are now in a single sqlite with the id 'das'
- SQLiteDB: moved all general stuff to Database.java and it extends this (SQLDB does this to)
- Status command: Now shows '!!!' if a database is not connected
- Created a class called XMLfab to write xml files with less boilerplate code
- XMLtools getChild... now returns ArrayList instead of array to be able to use lambda's
- das.getXMLdoc() now reads the file, use das.getXMLdoc(false) to use the one in memory

**Breaking changes** (or the reason for the version bump)
- Database tag replaced with server
- Databases->server tag in the xml now requires a id attribute from the second database onwards, first one gets 'main' if none given
- RealtimeValues: all query write methods have been renamed to reflect the database independence (eg. same method for sqlite and server)
- TaskSet: changed attribute from 'short' to 'id' and 'name' to 'info'
- Database in xml: renamed server tag to address
- StreamPool: changed methods etc to consistenly use stream instead of mixing stream and channel and tag title replaced with attribute id
- BaseReq: The methods now receive String[] request instead of String request, request[0]=title, request[1]=command
- Digiworker: password attribute changed to pass
- Telnet: title and port are now attributes in xml instead of elements/nodes

**Bugfixes**
- TaskManager: fillin with both @mac and @ipv4 present didn't work, looked for 'lastindexof' @ which is incorrect if both present
- IssueCollector: added newline to getIssues result if 'none yet'
- TransServer: 
  * rtval: and calc: were broken, no idea since when the default ending wasn't processed correctly
  * rtval/calc didn't process buffer full for email requests and now only the queue only processes nmea or emailrequest data so that full wasn't checked anymore
- SQLiteDB: Only giving the filename in the xml path resulted in a nullpointer because no actual parent, so now this is checked and absolute path is used if so.
- MQTTworker: only last subscribe was kept, the clear was in the for loop instead of before
- StreamHandler: notifyIdle was called twice instead of notifyIdle and notifyActive, so this causes a permanent idle
- DatabaseManager: having a threadpool of 2 causes issues if there are two databases rolling over at the same time... because both threads are used and none are left for queries, so it waits forever.
- Database: when not all tables are defined in the xml the rest wasn't retrieved from the db, now if it doesn't find a table it tries retrieving once

### 0.6.2
**Features**
- Added 2 macro's for the task system commands: @ipv4:interface and @mac:interface@ to retrieve the IP or MAC of a networkinterface (fe. @mac:wlan0@)
- Added system command to add a tcp stream: streams:addtcp,title,ip:port,label uses default delimiter (crlf), priority (1) and ttl (-1)
- Added system command to save the settings of a stream to the xml: streams:store,title

**Changes**
- StreamPool: 
  * now uses an interface/listener to get notifications from the StreamHandlers instead of going through the BaseWorker
  * Same listener is now used to get request from the TransServer. Instead of all data in the transqueue data the handler sends it through the channel directly. Not sure yet if that can cause concurrency issues... So 'old' code won't be removed yet (so commented).
  * I2C and email still follow the 'old' route

**Bugfixes**
- doConnection used the threadpool from netty, but netty doesn't like using the bootstrap concurrently, swapped it back to own scheduler
- Path to the settings.xml wasn't correctly found on linux (for some reason it worked fine on windows)

### 0.6.1
**Features**
- Added 'Generic' this allow for processing streams without writing BaseWorker code. This is limited to simple delimited data that doesn't require processing
  * All the setup is done in the settings.xml
  * Need to define delimiter and the table/database to write to, for now only sqlite is supported
  * For now only real and integer are supported, string not (yet)
  * Can be updated during runtime with generics:reload or gens:reload command
- SQLiteDB can now be defined in the settings.xml, rollover is supported but things like 'unique,not null,primary key' not (yet)

**Changes**

- TaskManager: 
  * Now has it's own file for logging, taskmanager.log
  * taskset have the interruptable attribute to prevent a reload while the set is active
  * Taskset 'short' is deprecated and replaced with id (will be removed in 0.7.x)
- RealtimeValues: added .getSQLiteInsert( String table ), gets the Insert for this table with the current values
- SQLiteDB: Added createDontDisconnect
- MQTT: Add MQTT to status info (telnet st command), decreased the amount of debug info
- TransServer: Add command to reload defaults (trans:reload)
- SQLiteDB: Timestamp and epoch are 'is not null' by default

**Bugfixes**
- SQLiteDB: When using monthly/weekly rollover that current filename was wrong because offset wasn't applied
- BaseReq: Commands to a TaskManager that include a ',' got cut off (.split(",") was applied)
- SQLiteManager: RunQueries assumes the connection is ok, but the code calling it wasn't. Now a method is called that first checks and connects if needed.
- Task: Interval trigger was wrongly interpreted (interval was used twice instead of delay,interval)

### 0.6.0
**Features**
- Easy to use API to work with (time limited) SQLite databases added, see DAS Core manual for usage

**Changes**
- SQLiteManager added (user won't interface directly to this), this takes care of managing all the SQLite databases in use, managing means:
  - Take care of query execution in a thread
  - Check if the age of the oldest query hasn't passed the threshold for writing the queries to the database
  - Take care of the 'roll over' from one file to the next (hourly,daily databases etc)
- SQLiteWorker, QueryWork removed as no longer used
- All the netty stuff (telnet,transserver,streampool) use the same worker pool instead of each their own

### 0.5.1
**Features**
- Added an Insert class that helps creating an insert statement but isn't required to use (hence only minor version update)

### 0.5.0
**Features**
- Added command (admin:sqlfile,yes/no) to dump queries processed with QueryWorker to file, uses tinylog for this with tag=SQL (and warn level).  
- Inbox check interval can now be alterd with a command  
- Emails can have an alias, but this is only useful in the case of admin notification if dasbot receives a command so now e-mails send from the admin alias don't trigger an admin notification.
- Added tasks:manager_id,run,tasksetid to run a certain taskset
- Added alternative way to construct the 'create table' statement for SQLITE

**Changes**
- Reworked xml syntax for EmailWorker (old one no longer works) and other related things
- Removed the check done by DAS.java for processed raw/query per second, this is now done by the class themselves instead.
- Uptime is now mentioned in status
- Changelog is now in md format
- Improved robustness of email checker
- Update the DAS Core manual to reflect recent changes
- Version numbering changed from 1.x.y to 0.x.y to reflect none fixed API
- toString of a task with output log now shows time till next execution if trigger is clock
- Updated the dependencies to the latest version (except SQLite because armhf issues)

**Bugfixes**
- Forgot to alter after copy paste of the starttaskset method causing 'no such manager' reply because manager id was swapped with taskset id
- Time format in the status email was wrong (MM vs mm)
- Normally emails to the emailbot from the admin should trigger a notification to the admin(s), but was the case if admin email and sent from email isn't the same (aliasses), added feature fixed this.
- Notify of thread dead was in the wrong place for digiworker and emailwoker causing fake-death messages, moved to correct spot
- The admin:setup command required another argument this was wrong (reason was admin:script,scriptname earlier) now it is happy with just one
- Email checker issues should now be resolved, probably a timeout that wasn't set which means infinite
- StreamHandler now ignores 'PortUnreachable' when used for UDP clients (was only an issue in rare cases).
- Double execution of timebased tasks might no longer occur... (hard to reproduce).
- QueryWorker reconnect wasn't closing open connections when reconnecting (con=null without checking) now it is (fixes SQLite usage)
- QueryWorker executed queries count fixed
- double execution of time based tasks fixed

### 0.4.2
- never released, skipped to 0.5.0

### 0.4.1
**Features**
- Added tasks:remove,x to remove a taskmanager
- Added sleep:<time> this only works on linux for now but puts the processor in sleep for <time> fe. sleep:5m => sleep 5 minutes
  After sleeping, tasks with the keyword "sleep:wokeup" are executed.

**Changes**
- Made static variables final
- Replaced last system.out with Logger.info/Logger.debug 

### 0.4.0 Database rework
- QueryWorker, SQLiteWorker and Database got reworked for better error handling and easier debug
- Replaced .equals("") with .isBlank()
- Added boolean get methods to the XMLTools (to get a boolean textnode or attribute)
- Reverted a lot of the \r\n replacments... should only be done for file interaction and not telnet...
- fixed the calculateseconds method, 24h wasn't done properly (1s off), added nanos to tasktime to fix it

### 0.3.0 JDK8 update
- Replaced some loops with foreach lambda
- Replaced most StringBuilder with StringJoiner
- Replaced \r\n with System.lineSeparator()
- Replaced System.currentTimeInMillis with Instant.now().toEpochMilli()
- Updated the TaskManager calculateseconds to the time classes

### 0.2.1 
- queue properly handed to RealtimeValues (was only to extended version)
- SQLite now checks for each query if the table exists before executing the queries (should fix the bottles issue)
  This also means that the only create table to pass is to one needed for the query.
- DAS used to check the threads every minute to see if still alive, this was replaced with a listener
  This now also raises an issue which can be acted upon.

### 1.2.0 
- I²C support improved a lot

### 0.1.0 
- MQTT support added 
- Fresh start, initial commit of the core





