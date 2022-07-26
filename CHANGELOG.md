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
* Rework the TaskManager ( > 1.0.0 )
* Zigbee support ( > 1.0.0 )

### To fix
- default for generic val
- cmd !idle not used properly when disconnected/reconnected
- integerval telnet interface?

## 1.0.0
- Updated dependencies
- Now using java 17 (new lts)
- DoubleVal is now RealVal, so real is used throughout instead of mix of double/real

### General
- Clean up, remove unused stuff etc
- Rewrote emailworker a bit
- Removed DigiWorker and other things related to sms
- Moved methods in CommandPool that can be static to Tools package
- Removed cyclic redundancy between CommandPool and Das
- Alias in database table is replaced with rtval, but alias will still be read (backwards compatible)

### Rtvals
- The response now starts with the current datetime
- No longer show ungrouped when there's a grouped textval
- IntegerVals now show up in the rtvals listing and are available for
the rtvals commands
- Group is now mandatory (empty group also exists)
- Removed the option to use {D:...} etc, rtvals need be be defined instead
- fixed: Empty ungrouped texts no longer show in list
- fixed: int/real mix in group no longer cause duplicate entries
- fixed: rtvals:name now works again with *

### Generics
- Can't add a generic with duplicate id through telnet
- When two generics are in xml with same id, only first is used.
  This is mentioned in the errorlog
- gens:addgen now actually uses the given group
- Generics inside a path now get the id from the path id instead of the file
- If a value wasn't found, the rtvals are updated with NaN if double/real or Max_integer for integer 

### Other fixes
- Here and there the relative paths weren't converted to correct absolute ones
- ModbusTCP didn't use the inherited timestamp field
- raw: stops again when issue'ing empty cmd
- Interval task with delay more than 48 hours now works properly  
- Forwards, now giving a label with xf:alter does request src

## 0.11.x
- Goals for this version series (removed when they are done)
  * Code cleanup
    * Bring javadoc up to date

## 0.11.10 (04/05/2022)

### File monitor 
- Simple class that watches files for modification and executes a cmd on event
- Purely xml for now, now telnet interface

### Fixes
- EmailWorker check thread got called multiple times if it failed, this amount kept increasing
- The command to send a string to a stream didn't allow a single ?, now info on the cmd is with ??
- Various small fixes in the tools package

## 0.11.9 (28/03/22)

Mainly added initial matrix & modbus tcp support.

### Matrix support
- Added basic support for joining a room and sending messages.
- TaskManager can send either text or the result of a command
- Can respond to message in the form of a math formula (1+1=?) and use earlier defined variables for it
- Can upload and download files

### Modbus TCP
- Added support for receiving 03 function response
  - Data received is converted to 16bit integer and formatted regx:val,regx:val 
- When sending data, the header is attached by dcafs (so everything in front of function)

### TaskManager
- Changed the interval trigger to start from the 'clean' interval if no start delay was given.
So 5m interval will start at the next x0 or x5 minutes (0 seconds etc).
  - For some reason this doesn't work as accurately under linux as windows (or sbc vs pc) 

### Commands
- Altered admin:reboot, attempts to reboot the linux system using bash or sh.
- admin:errors now checks the daily file instead of the link because that is only valid for non-removable media
- fc:reload added do reload all filecollectors at once

### Other Fixes
- pf:reload should no longer break file collectors (writables weren't kept)
- parseRTline that checks for references to vals was to broad, use regex now.
- Various small fixes to the modbus tcp code

## 0.11.8 (11/02/2022)

- Updated jSerialComm dependency to 2.9.0

### Settings.xml
- Moved databases node out of settings node , but still finds it in settings too
- Added admin:errors,x and admin:info,x to get the last x lines in the errors/info log, or 30 if x is omitted. That 
way it's possible to check both without having direct access to the files (if the issue isn't the telnet server).
- Altered startup so that a bad xml (missing > etc) still allows the telnet server to start so the user
gets feedback on the issue (last 15 lines of the errors.log).

### Cmd's
- <taskmanager id>:? should now show the time till next occurrence for interval tasks
- Fixed: dbm:store should now work again, for some reason declarations were moved
- Fixed: pf:list should now show the steps in the path again

### Other
- SQLitedb no longer uses workpath expects a proper path instead

### Fixes
- parseRTline (used for customsrc etc.) didn't process the i/I yet
- PathForward, import now uses correct relative path instead of depending on how dcafs was started


## 0.11.7 (02/02/2022)

- Updated PostgreSQL dependency because of severe vulnerability

### Breaking
- Rtvals now use 'real' just like db and generic always have
- Rtvals now has integers, so renamed the short reference for issues to is

### Forwards
- EditorForward Resplit now allows just a filler element
- PathForward: pf:debug,id,stepid is now also possible instead of the nr

### Other
- Added streams as alias for ss
- More TimeTools use english locale
- Trim is done when reading textvalue's from a node
- Generics now give an error if the conversion failed (fe. text instead of real)
- Added IntegerVal, same as DoubleVal but for Integers

## 0.11.6 (18/01/22)

- Updated dependencies.

### CommandLineInterface
 - For some reason a 0 is left after the first cmd but only on linux... trim() as workaround
 - Fixed history, buffer.clear only resets the indexes so data remains. So this combined with checking for data after the current
writeindex caused commands to be mashed together when using the history function. So now when history is used, the rest
of the buffer is overwritten with null.

### Bugfixes
- FileCollector, Changed the join to do the same if headers are added
- TimeTools, formatter was using locale to convert month/day in text (fe. May) to number so now fixed to english

## 0.11.5 (26/10/2021)

### CommandLineInterface
- Backspace should now work as intended
- Delete button works
- Typing in random spot inserts instead of replace
- discardreadbytes didn't work as expected

### Fixes
- SQLTable, didn't include the scenario of creating a table without columns

## 0.11.4 (22/10/2021)

Mainly improvements to the (till now fairly barebone) CLI.

### Command Line Interface
- Switched from client side to server side editing
  - Meaning everything you see in the console came from the server
  - The use of left/right arrows is now supported
- Arrow up/down cycles through command history (for now only of the current session), max 50 or so

### Configurator
- Initial steps for a wizard like configuration method (building the settings.xml), started with 'cfg', still work in 
progress and limited. Not in there yet for actual use, rather for testing.

### Other
- Allow for source to be set for a generic, in reality this still alters the labels
- '?' now works the same as 'help'
- Extra functionality in the XMLfab
- 
## 0.11.3 (15/10/21)

Quick release because of the mf:addop/addblank bug.

### Forwards
- A src is now checked for the presence of ':' because a valid src should always have it
- Math and filter now use ',' as default delimiter
- Replaced addblank with addmath,addfilter,addeditor

### Fixes
- Mathforward, wasn't updating references etc when adding blank or op only on reload
because of that the highestI was wrong and this determines with indexes are converted to
bigdecimal so that stayed at -1 meaning none were converted => nullpointer.
- MathFab, didn't process op's of the form i2=i2 well
- Generic wasn't handling not receiving a delimiter in the addblank (now addgen) well

## 0.11.2 (14/10/2021)

### I2C
- Added the option to use ix (fe. i0) to refer to an earlier read result in the
return attribute of a read.
- The format of the read bytes was in decimal, now it can be chosen (dec,bin,hex,char)

### GPIO
- Newly added, option to trigger cmd's on a hardware gpio interrupt

### Other
- Updated dependencies

### Fixes
- PathForward, node structure for the import wasn't correct
- DoubleVal, catch numberformat on toBigDecimal because NaN doesn't exist as BD
- MathUtils, simpleCalculation didn't handle to simple calculations well
- MathUtils, makeBDArray failed on decimal numbers starting with 0. NumberUtils.isCreatable returned false.
But NumberUtils.createBigDecimal did work... Added NumberUtils.isParsable as extra check.

## 0.11.1 (23/09/21)

This turned out to mainly fix/improve the I2C code.

### Breaking
- Refactored datapaths to paths in xml to be consistent with filters,editors,maths

### StreamManager
- stream:id has been added as alternative to raw:id

### I2C
- fixed, adddevice didn't create directories
- fixed, adddevice always used bus 0 instead of the one given in the command
- New nodes added
  - math  to apply operations on the result of a read
  - wait to have the execution wait for a given amount of time
  - repeat to allow sections to be repeated
  - discard to remove data from the buffer
- Changed default label to void
- Added it to status in new devices section, !! in front means probing failed
- Added data request with i2c:id (used to be i2c:forward,id), allows regex to request multiple
- Moved from thread and blockingqueue to executor and submitting work
- cleanup up the i2c:detect to make it prettier and return more info

### Rtvals
- scale is now both attribute and option for double
- readFromXml now takes a fab so that it can be called from other modules
- fixed, requesting doubles now works again, and it's possible to request using regex to request multiple 
at once. Now it returns id:value, might change or add other options in the future.

### Other
- LabelWorker, added label cmd as alternative to system because it might be more logical
- PathForward, pf:reload now reloads all paths and generics
- TaskManager, tmid:reload now reloads the taskmanager (in addition to tm:reload,tmid)

### Fixes
- Trans was always started instead of only if defined in xml
- PathForward, generic and valmap id's weren't generated inside the path
- PathForward, customsrc wasn't targeted by the first step (if any)
- XMLfab, hasRoot didn't init root so always failed
- SQLiteDB, should now always generate parent dir

## 0.11.0 (12/09/21)  

### Dependencies
- Removed Jaxb, not sure why it was in there in the first place...
- Removed tinylog impl, only using api package

### Breaking changes to generics
- They now have a group attribute instead of using the table attribute to set a prefix. Group is chosen
  because this matches the group part of a doubleval etc.
- The dbid and table attributes are now combined in a single db attribute `db="dbid:tablename` multiple
  dbid's are still allowed (',' delimited).
- To update settings.xml to use these changes:
  * replace table= with group=
  * replace dbid= with db= and append the content of table= to it with a : inbetween
    *  So dbid="datas,datalite" table="ctd" -> db="datas,datalite:ctd"  group="ctd"

### Workshop prep
- Added option to give an id to the current telnet session
- Allow sessions to send exchange messages
- Allow to 'spy' on a session, only one spy allowed. This wil send all the cmds and responses issued by the session
spied on to be send to the spy. Can be used to (remotely) follow what someone else is doing (wrong)

### Tinylog
- An issue is that uncaught exceptions also don't end up in the generated errorlog. Which makes it rather hard to
debug issues like that when using dcafs outside of an ide (it happens). Made a wrapper for the system.err that
also writes to the errorlog. This isn't active when working in an ide because it no longer allows tinylog to 
write to err (otherwise this would be a loop). Which only matters in an ide (coloring).

### SQLTable
- If trying to build an insert and a val is missing, the insert is aborted instead of substituting a null

### RealtimeValues
- The store command now stores all setup instead of just name/group/id/unit

## Math
- fixed, extractparts wasn't made for double character comparisons (>= etc) altered to check this
- Added support for cosr(adian),cosd(egrees),sinr,sind and abs
- fixed, missing global surrounding brackets should be better detected
- Added op type utm and gdc, converts the two references to either utm or gdc

## CheckBlock
- The word regex didn't check for brackets but did add if not found...
- fixes for standalone use (no parent nor sharedmem)
- Is now used in the FilterForward for the type 'math'

## FileCollector
- fixed, didn't make folders when the file is new and no headers needed
- fixed, sometimes empty lines were added when writing for the first time

## 0.10.13 (04/09/21)

Cleanup release, added comments/javadoc moved stuff around in the .java files etc etc etc

### Dependencies
- Replaced Netty-all with Netty-common & Netty-Handler, saving about 2MB!
- Removed MySQL connector, shouldn't be needed given that MariaDB connector is present, saves about 4MB

### util.data
- Made AbstractVal as base class for DoubleVal and FlagVal
- FlagVal now also allows triggered commands (on flag changes)
- Added reset method, because reload didn't reset
- Added javadocs and comments here and there
- RealtimeValues, changed formatting on the telnet interface (as in the dv:? etc commands)
 
### util.database
- Cleanup up (removed unused, added comments, added javadoc etc)
- Changed some methods that could return null to return optional instead
- Made som more use of lambda's

### util.math
- Cleanup up (removed unused, added comments, added javadoc etc)

### DoubleVal
- Added stdev based trigger, requires the history option to be active `<cmd when="stdev below 0.01">dosomething</cmd>`
and a full history buffer.
- fixed, defValue only overwrites current value (on reload) if current value is NaN

### Other changes
- StreamManager, `ss:reload,all` is now done with `ss:reload` 

### Other Fixes
- MathUtil, comparisons like 1--10 weren't handled properly, the second replacement altered it too
- SQLTable, buildInsert tried using DoubleVal instead of double, forgot the .value()
- getStatus, \r\n wasn't replaced with <br> for html 
- Task, prereq now appended with delimiting space

## 0.10.12 (28/08/21)

More fixes and some improvements to rtvals xml and doubleval functionality 

### DoubleVal
- Combined timekeep, history in a single attribute 'options'
  - Usage options="time,history:10" to keep the time of last value and a history of 10 values
- Added minmax to options to keep track of min/max values (options="minmax")
- Added order to options to allow specifying in which position it is listed in the group (options="order:1"), lower number
first, default -1. Equal order is sorted as usual.
- Altered rtvals to include min/max/avg/age info if available

### CommandPool
- fixed, Still had a method that referenced issuePool while all the other code was moved to Realtimevalues
this caused a nullpointer when doing a check for a taskmanager

### RealtimeValues
- `rtvals:store`,`dv:new` etc now stores in groups
- rtvals listings are now sorted
- added `rtvals:reload` to reload the rtvals
- fixed, "dv:new" always wrote to xml instead of only on new
- fixed, getoradddouble wasn't writing according to the newer group layout
- fixed, multiple underscore id's again
- fixed, isFlagDown was inverted when it shouldn't be

### Other Fixes
- Pathforward, for some reason initial src setup when using generics wasn't working anymore
- Generic, when using the datagram payload, it used the wrong index
- MathForward, the check to see it can be stopped didn't check the update flag
- IssuePool, message was read from a node instead of both node and attribute

## 0.10.11 (24/08/21)
Bugfixes!

### RealtimeValues
- Fixed, reading from xml caused writing to xml...

### FileCollector
- Fixed, path was recognized as absolute but workpath still got prepended
- Fixed, fc:list used , as delimiter but should be eol
- Fixed, on startup it checks if the headers are changed, but not if that file exists first
- Fixed, `fc:reload,id` checked for three arguments instead of two

### MathForward
- Fixed, now checks the size of the array after split to prevent out of bounds
- Fixed, now it actually cancels when too much bad data was received

### PathForward
- ID's and local src's are now possible, !id is allowed to get reversed/negated filter output
- Multiple customsrc are now possible
- pf:list should now give useful info

## 0.10.10 (22/08/21)

Note: Contains TaskBlocks code but without interface and standalone meaning, code is present but not usable nor 
affecting anything else.

### Breaking
- Changed `<cmd trigger="open"></cmd` to `<cmd when="open"></cmd>` in a stream node because all the other cmd node use when
- Changed the hello/wakeup cmd node in a stream from cmd to write node to limit possible confusion (cmd node didn't actually apply a cmd)

### TaskBlocks (name subject to change)
- Will replace TaskManager
- Current blocks
  - TriggerBlock - replaces the trigger attribute
  - CmdBlock - replaces the output=system attribute
  - MetaBlock - serves as the starting block of a link 
  - ControlBlock - replaces output=manager
  - CheckBlock - replaces req/check
  - LabelBlock - allows for data received from other blocks to be labeled
  - EmailBlock - send an email
  - WritableBlock - send (received) data to a writable with reply option
- Utility classes
  - BlockTree - helps settings up a link
  - BlockPool - manages the MetaBlocks
- Progress
  - TriggerBlock, CmdBlock, CheckBlock, MetaBlock, EmailBlock, WritableBlock functional
  - TriggerBlock and CheckBlock will not be added if duplicates on the link level, will link to the 'original' instead
  - CmdBlock aggregates commands if successive blocks are also CmdBlocks
  - EmailBlock prepends a CmdBlock to convert the email content to the result of a command (if any)
  - Can read the taskmanager script variant of the functional blocks
  - Implemented step/oneshot (taskset runtype)
  - Implemented state attribute (task attribute)
- Improvements compared to TaskManager
  - Checks and triggers are aggregated if possible
  - Checks can be a lot more complex, taskmanager had the limit of either an ands or ors while checkblock doesn't have
  such a limit and allows brackets and the math part is equivalent to the mathforward
  - Functionality split over multiple blocks instead of two classes, should make it clearer to work with
  - Has a base interface and an abstract class on top of that, make it easier to expand (adding blocks)

### CommandPool
- Removed used of reflection because the commandable interface replaces this

### TaskManager
- Tasksets now allow for the if attribute do to a check before starting the set
- Replaced 'state' logic with rtval text

### MathForward
- Fixed, Addop/addblank now works as intended again (instead of i1=i1=...)
- Addblank altered ? to show that it could also add an optional op `mf:addblank,id,src<,op>`
- Fixed an op with only an index as argument `<op cmd="doubles:new,temp,$" scale="1">i0</op>`
- Allows for creating doubles/flags by using uppercase {D:id} or {F:id} instead of lowercase
- Fixed op that just sets a double `<op scale="1">{D:temp}=i0</op>`

### DoubleVals
- Now writes changes to XML (new,alter,addcmd)
- Added `dv:addcmd,id,when:cmd` to add a cmd
- Added `dv:alter,id,unit:value` to change the unit

### Datapaths
- to remain consistent, changed the cmd to pf(pathforward) or paths, path:id is still used to request data
- Added the pf:addgen command to directly add a 'full' generic to a path (uses the same code as the gens:addblank)
- Added the pf:? cmd to get info on the possible cmds
- refactored the .java to PathForward.java

### XMLfab
- Refactored the 'parent' methods to make it clear when this is actually 'child becomes parent'
- Select methods can now return an Optional fab (so can be continued with ifPresent)

### Fixes
- rtval:id works again, wasn't moved to the new commandable format
- rtvals was missing the eol delimiter (mistake between delimiter and prefix)
- EditorForward, resplit fixed when using i's above 10

### Other
- Generics, improved the addblank cmd to also set indexes, names and reload afterwards
- Moved IssuePool and Waypoints into RealtimeValus
- Math,Added th ~ operand, which translates A~B to ABS(A-B)

## 0.10.9 (14/08/21)

### Other
- Datagram, now has an optional 'payload' (Object)

### MathForward
- Now only converts the part of the raw data that contains the used indexes
- Adds the converted (altered) data in the payload of the datagram so fe. generic doesn't parse it again
- Removed scratchpad functionality, recent addition of {d:doubleid} made it obsolete
- fixed, if no i's are in the expression settings highestI wasn't skipped so tried to check what was higher
  previous value or null (=not good)

### Telnet
- added broadcast command to broadcast a message to all telnet sessions
  - `telnet:broadcast,info,message` or`telnet:broadcast,message` will display it in green
  - `telnet:broadcast,warn,message` will display it in 'orange' (as close as it gets to the color...)
  - `telnet:broadcast,error,message` or`telnet:broadcast,!message` will display it in red
  - Sending `nb` stops the current session from receiving broadcasts 

### TaskManager
- Added 'load' command to load a script from file
- Added 'telnet' output to broadcast text to telnet instances
- Changed the blank taskmanager to use the new broadcast functionality
- Fixed, previous version of checks were predefined, current one aren't so nullpointer wasn't checked for.
  This caused all task without req/check to fail the test.
- fixed, fillin didn't use the new references {d: instead of {rtval: etc

### Fixes
- MathUtils
  - extractParts didn't remove _ (in doublevals) or : (in flags/issues)
  - break was missing after diff
  - comparing doubles should be done with Double.compare
- RtvalCheck, the '!' in front of a flag/issue wasn't processed correctly
- FilterForward, successive filters (so if no other steps are in between, generics aren't steps) will use data given by
that filter instead of the reverse
- DoubleVal/FlagVal, didn't take in account the use of _ in the name
- TCPserver, removeTarget didn't take in account that it uses arraylists in the map
- CommandPool, new shutdown prevention prevented shut down because of nullpointer...
- MathFab, ox start index depended on the scratchpad that was removed


## 0.10.8 (12/08/21)

### RealtimeValues
- Centralized all code in the class that was in Commandable.java and DAS.java
- Replaced the update and create commands with double:update etc
- Moved to other package
- Added FlagVal

### MathForward
- Now supports referring to double's in the operations {double:id} or {d:id}
- Now supports referring to flags in the operations {flag:id} or {f:id}
- Experimental way of working with DoubleVal/FlagVal, if positive this will be replicated.
- Allows for the part left of the = to be a {d:id} and/or index ( , delimited)

````xml
<!-- Before -->
<path id="example">
    <editor type="resplit">i0;{double:offset}</editor>
    <math cmd="double:update,temp,$">i0=i0+2*i1</math>
</path>
````
````xml
<!-- Now -->
<math id="example">{d:temp},i0=i0+2*{d:offset}</math>
````
### Other
- LabelWorker, removed method reference functionality

### Fixes
- ForwardPool, nettygroup should have been before the xml reading, not after.
- MathForward, op with scale attribute should give the cmd to the scale op instead

## 0.10.7 (09/08/21)

### RealtimeValues
- simpleRTval, now also checks the flags and replaces them with 0 or 1 and the splitter is more inclusive
  ( was simple split on space, now regex that looks for words that might contain a _ and end on a number)
- Added methods that give listings of the stored variables based on name or group
- The rtvals node now allows for group subnode to group vals

### ForwardPath
- Renamed 'predefine' to 'customsrc'
- Moved from innerclass to ForwardPath.java
- Now it's possible to reload these with path:reload,id targets are maintained
- No longer uses the central maps to store the forwards, so can't add target to individual steps for now

### MathForward
- Just like the short version of the math, an <op> node can now receive a 'i0=i0+5'  
  form of expression instead of defining the index attribute
````xml
<!-- Both these op's result in the same operation -->
<op index="0">i0+i2+25</op>
<op>i0=i0+i2+25.123</op>
````
- Added an extra attribute 'scale' so that you don't need to define an extra op just for that
````xml
<math id="scale">
  <op scale="2">i0=i0+i2+25</op>  
</math>
<!-- i0 will be scaled to two fractional digits (half up) after the opeation -->
<!-- Or shorter -->
<math id="scale" scale="2">i0=i0+i2+25</math>
````
### Other
- Added interface to allow components to prevent shutdown, can be skipped with sd:force
- Math, added extra checks and catches to the whole processing chain
- EditorForward, Expanded the UI and added option of global delimiter 

### Fixes
- Waypoints, latitude was set twice instead of lat and lon

## 0.10.6 (05/08/21)

### Updated dependencies minor versions
- DioZero
- PostgresSQL
- mysql-connector-java
- netty

### RealtimeValues
- Added flags (in addition to rtvals and rttexts)
- Added commands for it to the pool (flags:cmd)
- TaskManagers now use the global flags instead of local ones

### IssuePool
- Now allows to set a start & stop test to run.
- Tests allow for dual test split with  'and' or 'or', atm can only be used once
- Cmd can contain {message}, this will be replaced with the message of the issue

### RTvalcheck
- flag or issue no longer require equals 1 or 0, flag:state and !flag:state can be used instead
- Now the check can contain 'and ' or 'or' to divide two tests

### Other
- Labelworker, added label 'log' so you can have data written to the info/warn/error logs.
  label="log:info" etc
- rtvals, double added support for not between x and y 
- EmailWorker, added email:toadmin,subject,content for a short way to email admin
- datapaths, can generate their own src data based on rtvals 
- doCreate,doUpdate now accept complicated formulas (used to be single operand)
- DoubleVal, history now with defined pool and avg can be calculated
- DoubleVal, added fractionaldigits to apply rounding
- Mathforward, now has attribute suffix, for now only nmea to add calc nmea checksum

### Bugfixes
- DoubleVal, used old value instead of new for trigger apply
- ForwardPool, global path delimiter wasn't read...

## 0.10.5 (29/07/2021)

### Datapaths
- New functionality, allows for filter,math,editor, generic and valmap to be added in a single node
- path can be in a separate file instead of the main settings.xml
- less boilerplate because the path will assume steps are following each other (so step 1 is the src for
  step 2 etc.) and will generate id's
- delimiter can be set globally in a path

### Digiworker
- Implemented Commandable
- The sms:send command now also replaces {localtime} and {utctime}

### Other
- Waypoints, travel bearing is now default 0-360 (so can be omitted)
- StreamManager, the send command now allows optional expected reply `ss:send,id,data(,reply)`
- BaseStream, now has access to the Netty threadpool and uses it for threaded forwarding  
- EmailWorker, the email:send command now also replaces {localtime} and {utctime}
- FilterForward, added regex type to match on regex
- Valmap, now allows for multiple pairs to be in a single dataline with given delimiter
- SerialStream, better nullpointer catches 
- Waypoint, added fluid api to replace constructor

### TaskManager
- Task, added replywindow attribute to provide a reply window te replace the standard 3s wait and 3 retries
 after sending something to a stream that wants a reply, default retries will be 0.
- tm:addblank now checks if a script with the given name already exists before creating it
- Fixed run="step" to actually wait for a reply

### Bugfixes
- LabelWorker didn't give an error if a generic doesn't exist
- MathForward, cmd in an op didn't make the forward valid
- TaskManager, clock tasks weren't properly cancelled on a reload

## 0.10.4 (15/07/2021)

### DebugWorker
- Using the command `read:debugworker` has been altered to `read:debugworker,originid` to only request
data from a specific id or * for everything. If no originid is given, * is assumed.

### RealtimeValues
- Implements the DataProviding interface to provide rtval and rttext data 

### Forwards
- DataProviding is added to the abstract class
- EditorForward uses it to add realtime data (and timestamping) to a resplit action
- Usage example:
````xml
<editor id="pd_parsec" src="filter:vout">
    <!-- vout1;0;vout2;0;vout3;0;vout4;0;vout5;125;vout6;0;vout7:0;vout8;0 -->
    <edit type="resplit" delimiter=";" leftover="remove">"{utc}";i1;{rtval:pd_iout1};i3;{rttext:pd_error}</edit>
</editor>
````
### FileCollector
- If the header is changed, a new file will be started and the old one renamed to name.x.ext where x is the first available 
number.

### Bugfixes
- FileCollector, batchsize of -1 didn't mean ignore the batchsize
- FileCollector, batchsize of -1 didn't allow for timeout flush

## 0.10.3 (13/07/2021)

### Waypoints
- Reworked the waypoints code to include new code:
  - 'from x to y' translation to a function (added in 0.10.2)
  - DoubleVal to have a local reference to latitude, longitude and sog
  - Implement Commandable (still wpts)
- Now executes commands by itself instead of via taskmanager
```xml
    <waypoints latval="lat_rtval" lonval="lon_rtval" sogval="sog_rtval">
      <waypoint id="wp_id" lat="1" lon="1" range="50">
          <name>dock</name>
          <travel id="leave_harbor" dir="out" bearing="from 100 to 180">
            <cmd>dosomething</cmd>
          </travel>
      </waypoint>
    </waypoints>
```

### CommandPool
- Refactored the store command to update and added create. Difference being that update requires the rtvals to exist
already and create will create them if needed.
- Renamed the update command (update dcafs,scripts etc ) to upgrade.  

### Bugfixes
- Building insert statement failed because it took the doubleval instead of val
- setting an rtval for the first time (and create it) didn't actually set the value
- doubleval getvalue, was endless loop
- starting with dcafs in repo didn't work properly
- when sending emails, the refs weren't replaced with the emailaddress anymore

## 0.10.2 (01/07/21)

### RealtimeValues
- Added metadata class for double data called DoubleVal, allows for:
  - default value
  - unit
  - triggered cmds based on single (<50) or double (10<x<50) comparison in plain text
    - Examples:
      - <50 or 'below 50'
      - 10<x<50 or 'between 10 and 50'
      - 10<=x<=50 or '10 through 50' or 'not below 10, not below 50' or '10-50'
    - Only triggers once when reaching range until reset by leaving said range
  - optional timekeeping (last data timestamp)
  - optional historical data (up to 100 values)
````xml
    <rtvals>
      <double id="tof_range" unit="mm" keeptime="false" >
        <name>distance</name>
        <group>laser</group>
        <cmd when="above 150">issue:start,overrange</cmd>
        <cmd when="below 100">issue:stop,overrange</cmd>
      </double>
    </rtvals>
````  
- Replaced rtvals hashmap <String,Double> with <String,DoubleVal>

### IssueCollector
- Replaced by IssuePool (complete rewrite)
- In combination with DoubleVal, this allows for cmds to be started on one condition and the reset on another
````xml
<issues>
    <issue id="overrange">
      <message>Warning range over the limit</message>
      <cmd when="start">fix the overrange</cmd>
      <cmd when="start">email:admin,Overrange detected,rtvals</cmd>
      <cmd when="stop">email:admin,Overrange fixed,rtvals</cmd>
    </issue>
</issues>
````


## 0.10.1 (26/06/21)

### Refactoring
- StreamPool -> StreamManager for consistency, and some methods
- Moved forward,collector, Writable, Readable to a level higher out of stream
- Influx -> InfluxDB
- Renamed TaskList back to TaskManager and TaskManager to TaskManagerPool

### Task
- Replaced Verify with RtvalCheck, uses MathUtils and should be cleaner code
- Added option to add {rtval:xxx} and {rttext:xxx} in the value of a task node
- Fixed repeats, were executed once to many (counted down to zero)
- Added option to use an attribute for while/waitfor checks
- Added option to use an attribute stoponfail (boolean) to not stop a taskset on failure of the task

### CommandPool
- The store command now accepts simple formulas fe. `store:dp1,dp1+5` or `store:dp1,dp2/6` etc. This can be used in a 
task...
- Removed doEditor,doFilter,doMath were replaced by Commandable in 0.10.0

### Other
- Fixed ef:? command

## 0.10.0 (24/06/21)

Too much breaking stuff, so version bump and update guide.

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
- Added the trigger 'waitfor', this can be used in a taskset to wait for a check to be correct a number of
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
- Triggered cmds now support having a command as content of `email:send,to,subject,content`

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





