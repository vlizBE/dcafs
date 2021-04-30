package com.email;

public interface EmailSending {

    void sendEmail( String to, String subject, String content );
    void sendEmail( String to, String subject, String content,String attachment,boolean deleteAttachment );
}
