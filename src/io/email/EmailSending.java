package io.email;

public interface EmailSending {

    void sendEmail( Email email );
    boolean isAddressInRef( String ref, String address );
    //void sendEmail( String to, String subject, String content,String attachment,boolean deleteAttachment );
}
