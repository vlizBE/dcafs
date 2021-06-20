package com.email;

public interface EmailSending {

    void sendEmail( Email email );
    //void sendEmail( String to, String subject, String content,String attachment,boolean deleteAttachment );
}
