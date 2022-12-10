package util.xml;

import org.apache.commons.lang3.math.NumberUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.tools.Tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Class that can be used to 'dig' through a XML structure for a certain element and get info from it.
 * This won't make elements or attributes, that's what the XMLfab class is for
 */
public class XMLdigger {

    boolean valid = true;
    Path xmlPath;
    Document xmlDoc;        // The xml document
    Element root;
    Element last;
    ArrayList<Element> siblings = new ArrayList<>();

    private XMLdigger( Path xml ){
        xmlPath=xml;
        XMLtools.readXML(xml).ifPresentOrElse( d -> xmlDoc=d,()->invalidate());
    }
    /**
     * Start the XML digging with selecting the first node with the given name
     * @param xml The path to the xml file
     * @param rootNode The rootnode to look for
     * @return This object
     */
    public static XMLdigger goIn(Path xml, String rootNode ){
        var digger = new XMLdigger(xml);
        if( digger.valid )
            XMLtools.getFirstElementByTag(digger.xmlDoc, rootNode ).ifPresentOrElse( d -> digger.root=d,()->digger.invalidate());
        return digger;
    }
    public XMLdigger goDown( String... tags ){
        if(!valid)
            return this;
        if( tags.length!=1){
            for( int a=0;a<tags.length-1;a++) {
                XMLtools.getFirstChildByTag(root, tags[a]).ifPresentOrElse(ele->stepDown(ele),()->invalidate());
                if( !valid )
                    return this;
            }
        }
        siblings.clear();
        siblings.addAll( XMLtools.getChildElements(last,tags[tags.length-1]));
        if (siblings.isEmpty()) {
            invalidate();
        } else {
            stepDown(siblings.remove(0));
        }
        return this;
    }
    public XMLdigger goDown( String tag, String attr, String value ){
        if(!valid)
            return this;
        var eleOpt = XMLtools.getChildElements(root, tag).stream().filter(x ->
                x.getAttribute(attr).equalsIgnoreCase(value)).findFirst();

        eleOpt.ifPresentOrElse( ele -> stepDown(ele),()->invalidate());
        siblings.clear();
        return this;
    }
    public XMLdigger goUp(){
        last = root;
        root = (Element)root.getParentNode();
        return this;
    }

    private void invalidate(){
        valid=false;
    }
    private void stepDown( Element ele ){
        if( root==null) {
            root = ele;
        }else{
            root = last;
        }
        last = ele;
    }
    public boolean isValid(){
        return valid;
    }
    public Optional<Element> current(){
        return valid?Optional.of(last):Optional.empty();
    }
    /**
     * Get the next sibling if any or empty optional if all used
     * @return Optional sibling
     */
    public Optional<Element> next(){
        if( !valid )
            return Optional.empty();
        return siblings.isEmpty()?Optional.empty():Optional.of(siblings.remove(0));
    }
    public Document doc(){
        return xmlDoc;
    }
    /* ************* Getting content **************************************** */
    public String value( String def){
        if( valid ) {
            var c = root.getTextContent();
            return c.isEmpty()?def:c;
        }
        return def;
    }
    public int value( int def ){
        if( valid && !root.getTextContent().isEmpty()) {

            return NumberUtils.toInt(root.getTextContent(),def);
        }
        return def;
    }
    public double value( double def ){
        if( valid && !root.getTextContent().isEmpty()) {
            return NumberUtils.toDouble(root.getTextContent(),def);
        }
        return def;
    }
    public boolean value( boolean def ){
        if( valid && !root.getTextContent().isEmpty()) {
            return Tools.parseBool(root.getTextContent(), def);
        }
        return def;
    }
    public Optional<Path> value( Path parent){
        if( valid && !root.getTextContent().isEmpty()) {
            var at = root.getTextContent();
            var p = Path.of(at);
            if( p.isAbsolute() ) {
                return Optional.of(p);
            }else{
                return Optional.of(parent.resolve(at));
            }
        }
        return Optional.empty();
    }
    /*  ************ Getting attributes ************************************* */
    public String attr( String tag, String def){
        if( valid && root.hasAttribute(tag)) {
            return root.getAttribute(tag);
        }
        return def;
    }
    public int attr( String tag, int def){
        if( valid && root.hasAttribute(tag)) {
            return NumberUtils.toInt(root.getAttribute(tag),def);
        }
        return def;
    }
    public double attr( String tag, double def){
        if( valid && root.hasAttribute(tag)) {
            return NumberUtils.toDouble(root.getAttribute(tag),def);
        }
        return def;
    }
    public boolean attr( String tag, boolean def){
        if( valid && root.hasAttribute(tag)) {
            return Tools.parseBool(root.getAttribute(tag), def);
        }
        return def;
    }

    /**
     * Get the path that is defined by the attribute, or an empty optional if no valid one was found
     * @param tag The tag that holds it
     * @param parent The parent path to make the found path absolute if it isn't yet
     * @return The path or an empty optional
     */
    public Optional<Path> attr( String tag, Path parent){
        if( valid && root.hasAttribute(tag)) {
            var at = root.getAttribute(tag);
            var p = Path.of(at);
            if( p.isAbsolute() ) {
                return Optional.of(p);
            }else{
                return Optional.of(parent.resolve(at));
            }
        }
        return Optional.empty();
    }
}
