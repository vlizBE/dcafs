package util.taskblocks;

import io.email.Email;
import io.email.EmailSending;

public class EmailBlock extends AbstractBlock{

    EmailSending es;
    Email email;
    boolean html=true;

    public EmailBlock(EmailSending es, Email email ){
        this.es=es;
        this.email=email;
    }
    public static EmailBlock prepBlock( EmailSending es, Email email){
        return new EmailBlock(es,email);
    }

    @Override
    public boolean addData(String data) {
        if( !data.startsWith("unknown")) {
            if (html) {
                data = "<html>" + data.replace("\r\n", "<br>") + "</html>";
                data = data.replace("<html><br>", "<html>");
                data = data.replace("\t", "      ");
            }
            email.content(data);
        }
        return super.addData(data);
    }

    @Override
    public boolean build() {
        return true;
    }

    @Override
    public boolean start() {
        es.sendEmail(email);
        return super.start();
    }

    @Override
    public void nextOk() {

    }

    @Override
    public void nextFailed() {

    }
    public String toString(){
        return "Email to '"+email.to()+"' with subject '"+email.subject()+"' and content/cmd '"+email.content()+"'";
    }
}
