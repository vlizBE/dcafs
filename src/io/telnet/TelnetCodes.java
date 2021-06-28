package io.telnet;

public class TelnetCodes {

	private TelnetCodes() {
		throw new IllegalStateException("Utility class");
	}
	
	public static final String ESCAPE = Character.toString((char)27);

	public static final String TEXT_RESET = ESCAPE + "[0m";

	public static final String TEXT_BRIGHT = ESCAPE+"[1m";
	public static final String TEXT_NOTBRIGHT = ESCAPE+"[22m";

	public static final String TEXT_ITALIC    = ESCAPE+"[3m";
	public static final String TEXT_UNDERLINE = ESCAPE+"[4m";
	public static final String UNDERLINE_OFF  = ESCAPE+"[24m";

	public static final String TEXT_BLINK = ESCAPE+"[5m";  		//NOPE
	public static final String TEXT_STRIKETHROUGH = ESCAPE+"[9m";
	public static final String TEXT_BLACK  = ESCAPE+"[30m";
	public static final String TEXT_RED    = ESCAPE+"[31m";
	public static final String TEXT_GREEN  = ESCAPE+"[32m";
	public static final String TEXT_YELLOW = ESCAPE+"[33m";
	public static final String TEXT_BRIGHT_YELLOW  = TEXT_BRIGHT + ESCAPE+"[33m";
	public static final String TEXT_BLUE   		 =  ESCAPE+"[34m";
	public static final String TEXT_BRIGHT_BLUE    =  ESCAPE+"[1;34m";
	public static final String TEXT_MAGENTA = ESCAPE+"[35m";
	public static final String TEXT_CYAN    = ESCAPE+"[36m";
	public static final String TEXT_WHITE   = ESCAPE + "[37m";

	public static final String BACK_RED = ESCAPE + "[41m";
	public static final String BACK_GREEN = ESCAPE + "[42m";
	public static final String BACK_YELLOW = ESCAPE + "[43m";
	public static final String BACK_BLUE =  ESCAPE + "[44m";
	public static final String BACK_MAGENTA =  ESCAPE + "[45m";
	public static final String BACK_CYAN =  ESCAPE + "[46m";
	public static final String BACK_WHITE = ESCAPE + "[47m";
	public static final String BACK_BLACK = ESCAPE + "[40m";
	
	public static final String TEXT_FRAMED = ESCAPE + "[51m";   //NOPE	
	public static final String TEXT_ENCIRCLED = ESCAPE + "[52m";//NOPE
	public static final String TEXT_OVERLINED = ESCAPE + "[53m";//NOPE
	
	public static final String HIDE_SCREEN = ESCAPE + "[2J";//Hides everything up to the cursor, doesn't reset the cursor
	public static final String CURSOR_LINESTART = ESCAPE + "[0;0F";

	public static String colorNumbers( String defaultTextColor, String s) {
    	StringBuilder b = new StringBuilder();
    	for( String line : s.split("\r\n")) {
    		int i = line.indexOf(":");
    		if( i != -1 ) {
    			b.append(defaultTextColor).append(line, 0, i+1);
    			b.append(TEXT_GREEN).append(line.substring(i+1));
    		}else {
    			b.append(line);
    		}
    		b.append("\r\n"); 
    	}
    	
    	return b.toString().replaceAll("-999.0", TEXT_RED+"-999"+TEXT_GREEN)+defaultTextColor;
    }
	public static String colorText( String defaultTextColor, String s, String text) {
		StringBuilder b = new StringBuilder();
		for( String line : s.split("\r\n")) {
			int i = line.indexOf(":");
			if( i != -1 ) {
				b.append(defaultTextColor).append(line, 0, i+1);
				b.append(TEXT_GREEN).append(line.substring(i+1));
			}else {
				b.append(line);
			}
			b.append("\r\n");
		}

		return b.toString().replace(text, TEXT_RED+text+TEXT_GREEN)+defaultTextColor;
	}
}