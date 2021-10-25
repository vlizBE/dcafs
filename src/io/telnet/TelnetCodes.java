package io.telnet;

public class TelnetCodes {

	private TelnetCodes() {
		throw new IllegalStateException("Utility class");
	}

	// IAC Options
	public static final byte IAC = (byte) 0xFF;

	public static final byte WILL =(byte)251;
	public static final byte DO = (byte)252;
	public static final byte WONT = (byte)253;
	public static final byte DONT = (byte)254;

	public static final byte ECHO = (byte)1;
	public static final byte SURPRESS_GO_AHEAD = (byte)3;
	public static final byte[] WILL_SGA = new byte[]{IAC,WILL,SURPRESS_GO_AHEAD};
	public static final byte[] WILL_ECHO = new byte[]{IAC,WILL,ECHO};

	// Escape Characters
	public static final String ESCAPE = Character.toString((char)27);

	public static final String TEXT_RESET = ESCAPE + "[0m";

	public static final String TEXT_BRIGHT = ESCAPE+"[1m";
	public static final String TEXT_FAINT = ESCAPE+"[2m";
	public static final String TEXT_REGULAR = ESCAPE+"[22m";


	public static final String TEXT_BOLD    = ESCAPE+"[1m";
	public static final String TEXT_ITALIC    = ESCAPE+"[3m";
	public static final String TEXT_UNDERLINE = ESCAPE+"[4m";
	public static final String UNDERLINE_OFF  = ESCAPE+"[24m";

	public static final String TEXT_BLINK = ESCAPE+"[5m";  		//NOPE
	public static final String TEXT_STRIKETHROUGH = ESCAPE+"[9m";
	public static final String TEXT_BLACK  = ESCAPE+"[0;30m";
	public static final String TEXT_GRAY  = ESCAPE+"[1;90m";
	public static final String TEXT_RED    = ESCAPE+"[0;31m";
	public static final String TEXT_GREEN  = ESCAPE+"[0;32m";
	public static final String TEXT_YELLOW = ESCAPE+"[0;33m";
	public static final String TEXT_BRIGHT_YELLOW  = ESCAPE+"[1;33m";
	public static final String TEXT_BLUE   		 =  ESCAPE+"[0;34m";
	public static final String TEXT_BRIGHT_BLUE    =  ESCAPE+"[1;34m";
	public static final String TEXT_MAGENTA = ESCAPE+"[0;35m";
	public static final String TEXT_BRIGHT_MAGENTA = ESCAPE+"[1;35m";
	public static final String TEXT_CYAN    = ESCAPE+"[0;36m";
	public static final String TEXT_BRIGHT_CYAN    = ESCAPE+"[1;36m";
	public static final String TEXT_ORANGE  = ESCAPE+"[0;38;5;208m";
	public static final String TEXT_LIGHT_GRAY   = ESCAPE + "[0;37m";
	public static final String TEXT_WHITE   = ESCAPE + "[1;37m";

	public static final String BG_LIGHT_GREY = ESCAPE+"[48;5;7m";
	public static final String BG_DARK_GREY = ESCAPE+"[48;5;8m";
	public static final String BACK_BLACK = ESCAPE + "[40m";
	public static final String BACK_RED = ESCAPE + "[41m";
	public static final String BACK_GREEN = ESCAPE + "[42m";
	public static final String BACK_YELLOW = ESCAPE + "[43m";
	public static final String BACK_BLUE =  ESCAPE + "[44m";
	public static final String BACK_MAGENTA =  ESCAPE + "[45m";
	public static final String BACK_CYAN =  ESCAPE + "[46m";
	public static final String BACK_WHITE = ESCAPE + "[47m";

	
	public static final String TEXT_FRAMED = ESCAPE + "[51m";   //NOPE	
	public static final String TEXT_ENCIRCLED = ESCAPE + "[52m";//NOPE
	public static final String TEXT_OVERLINED = ESCAPE + "[53m";//NOPE
	
	public static final String HIDE_SCREEN = ESCAPE + "[2J";//Hides everything up to the cursor, doesn't reset the cursor
	public static final String CURSOR_LINESTART = ESCAPE + "[0;0F";

	public static final String PREV_LINE = ESCAPE + "[F"; // works
	public static final String CLEAR_LINE = ESCAPE + "[2K"; // works
	public static final String CLEAR_LINE_END = ESCAPE + "[K";
	public static final String CURSOR_LEFT = ESCAPE + "[1D";
	public static final String CURSOR_RIGHT = ESCAPE + "[1C";

	public static String cursorLeft( int steps ){
		return ESCAPE + "["+steps+"D";
	}
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
	public static String toReadableIAC( byte b ) {
		switch (b) {
			// IAC Control
			case (byte) 240:
				return "SE";
			case (byte) 241:
				return "NOP";
			case (byte) 242:
				return "DM";
			case (byte) 243:
				return "BRK";
			case (byte) 244:
				return "IP";
			case (byte) 245:
				return "AO";
			case (byte) 246:
				return "AYT";
			case (byte) 247:
				return "EC";
			case (byte) 248:
				return "EL";
			case (byte) 249:
				return "GA";
			case (byte) 250:
				return "SB";
			case (byte) 251:
				return "WILL";
			case (byte) 252:
				return "WONT";
			case (byte) 253:
				return "DO";
			case (byte) 254:
				return "DONT";
			case (byte) 255:
				return "IAC";
			// Negotiations
			case (byte) 1:
				return "ECHO";
			case (byte) 3:
				return "SGA";
			case (byte) 5:
				return "STATUS";
			case (byte) 6:
				return "TimingMark";
			case (byte) 24:
				return "TermType";
			case (byte) 31:
				return "Window Size";
			case (byte) 32:
				return "Term Speed";
			case (byte) 33:
				return "Rem Flow Cont";
			case (byte) 34:
				return "LineMode";
			case (byte) 36:
				return "EnvVar";
			default:
				return "" + (int) b;
		}
	}
}