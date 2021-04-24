/*
 * Class that holds all the required information to send an email via an EmailWorker.
 */
package com.email;

import util.tools.TimeTools;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class EmailWork {
	String toRaw;				  	  // The destination without conversion between reference and email address
	String subject;					  // The subject of the email
	String content;					  // The content of the email
	String attachment="";			  // Link to the attachment
	boolean valid = false;			  // Flag whether or not the email is valid
	int tries = 0;					  // How many tries have been done to send the email
	int delay = 0;					  // Delay between attempts to send the email
	boolean deleteAttachment = false; // Whether or not to delete the attachment after sending it
	long creationTime = Instant.now().toEpochMilli();  //Keep track of when this object was created

	/* ******************************* C O N S T R U C T O R S ************************************************* */
	/**
	 * Short constructor with the mininum required info
	 * 
	 * @param to Destination email adres of reference
	 * @param subject The subject of the email
	 * @param content The text content of the email
	 */
	public EmailWork( String to, String subject, String content){
		if( to.isBlank() ){ // Can't do anything without a valid destination
			return;
		}
		toRaw=to;
		this.subject = subject;
		this.content = content;
		valid = true;				
	}
	/**
	 * Full constructor
	 * 
	 * @param to Destination email adres of reference
	 * @param subject The subject of the email
	 * @param content The text content of the email
	 * @param attachment Link to the file to attach
	 * @param delete True if the linked file should be deleted afterwards (if email was successfully send)
	 */
	public EmailWork( String to, String subject, String content, String attachment, boolean delete ){
		this(to,subject,content+"\r\n" );
		this.attachment=attachment;
		deleteAttachment = delete;
	}
	/**
	 * Check if the object is valid
	 * 
	 * @return True if valid
	 */
	public boolean isValid(){
		return valid;
	}

	/**
	 * Check is this email is older than the given amount of hours
	 * @param hours The amount of hours to compare the age to
	 * @return True if older than the given period
	 */
	public boolean isFresh(int hours){
		if(hours <=0)
			return true;
		long seconds=(Instant.now().toEpochMilli()-creationTime)/1000;
		return (seconds/60) < hours;
	}
	/**
	 * Check if an attachment was defined
	 * 
	 * @return True if an attachment is defined
	 */
	public boolean hasAttachment(){		
		return !attachment.isBlank();
	}
	/**
	 * Retrieve the filename of the attachment
	 * 
	 * @return The filename of the attachment
	 */
	public String getAttachmentName(){
		return Path.of(attachment).getFileName().toString();
	}
	/**
	 * Add one to the count if email send attempts
	 */
	public void addAttempt(){
		tries++;
		delay = tries*10;
		if( tries > 20 )
			valid = false;
	}
	/**
	 * Retrieve the amount of attempts that have been done to send this email
	 * 
	 * @return Attempts done.
	 */
	public int getAttempts(){
		return tries;
	}
	/**
	 * Check if the attachment should be deleted after a succesful send.
	 * 
	 * @return Whether or not to delete on send
	 */
	public boolean deleteOnSend(){
		return deleteAttachment;
	}
}
