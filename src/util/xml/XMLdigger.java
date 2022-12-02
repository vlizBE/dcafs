package util.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;

public class XMLdigger {

    boolean valid = true;
    Path xmlPath;
    Document xmlDoc;        // The xml document
    Element root;

    private XMLdigger( Path xml ){
        xmlPath=xml;
        if( Files.exists(xml) ){
            xmlDoc = XMLtools.readXML(xml).get();
        }else{
           valid=false;
        }
    }
    public static XMLdigger digRoot(Path xml, String rootNode ){
        var digger = new XMLdigger(xml);
        if( !digger.valid)
            return digger;
        digger.root = XMLtools.getFirstElementByTag(digger.xmlDoc, rootNode ).get();
        return digger;
    }
    public XMLdigger goDown( String... tags ){
        if(!valid)
            return this;
        for( String tag :tags) {
            var eleOpt = XMLtools.getFirstChildByTag(root, tag);
            if (eleOpt.isEmpty()) {
                valid = false;
                return this;
            } else {
                root = eleOpt.get();
            }
        }
        return this;
    }
    public XMLdigger goDown( String tag, String attr, String value ){
        if(!valid)
            return this;
        var opt = XMLtools.getChildElements(root, tag).stream().filter(x ->
                x.getAttribute(attr).equalsIgnoreCase(value)).findFirst();
        if( opt.isEmpty() ) {
            valid = false;
        }else{
            root = opt.get();
        }
        return this;
    }
    public boolean isValid(){
        return valid;
    }
    public Element current(){
        return root;
    }
    public boolean alterAttrAndBuild( String attr, String value){
        if( !valid )
            return false;
        root.setAttribute(attr,value);
        XMLtools.writeXML(xmlPath, xmlDoc);
        return true;
    }
}
