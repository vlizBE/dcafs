package util.xml;

import org.apache.commons.lang3.math.NumberUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.tools.Tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    Element peek;
    boolean peeked=false;
    ArrayList<Element> siblings = new ArrayList<>();

    private XMLdigger( Path xml ){
        xmlPath=xml;
        XMLtools.readXML(xml).ifPresentOrElse( d -> xmlDoc=d,()->invalidate());
    }
    private XMLdigger( Element cur ){
        if( cur == null){
            valid=false;
        }else {
            last = cur;
            root = (Element) cur.getParentNode();
            root = root == null ? last : root;
        }
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
            XMLtools.getFirstElementByTag(digger.xmlDoc, rootNode ).ifPresentOrElse( d -> digger.stepDown(d),()->digger.invalidate());
        return digger;
    }
    public static XMLdigger goIn( Element ele){
        return new XMLdigger(ele);
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
            stepDown(siblings.get(0));
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
    public XMLdigger peekAt( String tag ){
        peeked=true;
        peek = XMLtools.getFirstChildByTag(last,tag).orElse(null);
        return this;
    }
    public XMLdigger goUp(){
        last = root;
        peeked=false;
        var parent = (Element) root.getParentNode();
        if(  validated(parent!=null) ) {
            root = (Element)root.getParentNode();
        }
        return this;
    }
    public void makeValid(){
        valid=true;
    }
    private boolean invalidate(){
        valid=false;
        return false;
    }
    private boolean validated(boolean check){
        valid = valid && check;
        return valid;
    }
    private void stepDown( Element ele ){
        peeked=false;
        if( root == null) {
            root = ele;
        }else if (last !=null){
            root = last;
        }
        last = ele;
    }
    public boolean isValid(){
        return valid;
    }
    public boolean hasValidPeek(){ return peeked && peek!=null; }
    public Optional<Element> current(){
        return valid?Optional.of(last):Optional.empty();
    }
    public Optional<Element> currentPeek(){
        return Optional.ofNullable(peek);
    }
    public List<Element> currentSubs(){
        if( !valid )
            return new ArrayList<>();
        return XMLtools.getChildElements(last);
    }
    /**
     * Get the next sibling if any or empty optional if all used
     * @return Optional sibling
     */
    public boolean hasNext(){
        return validated(!siblings.isEmpty());
    }
    public boolean iterate(){
        if( !hasNext() )
            return false;

        last = siblings.remove(0);
        return true;
    }
    /**
     * Get all the other elements at the same 'level' as the current one, keeping them or removing them from the digger
     * @return A (potentially empty) list of the elements
     */
    public XMLdigger drop(){
        if( hasNext() )
            last = siblings.remove(0);
        return this;
    }

    /**
     * Get all the elements at the current level, including the current one
     * @param keep If the others should be kept
     * @return The list
     */
    public ArrayList<Element> all(boolean keep){
        var temp = new ArrayList<Element>();
        if( !valid )
            return temp;

        temp.addAll(siblings);
        if( !keep )
            siblings.clear();
        return temp;
    }
    public Document doc(){
        return xmlDoc;
    }
    /* ************* Getting content **************************************** */

    public String value( String def){
        if( !valid )
            return def;

        if( peeked )
            return peek==null?def:peek.getTextContent();

        var c = last.getTextContent();
        return c.isEmpty()?def:c;

    }
    public int value( int def ){
        if( !valid )
            return def;

        if( peeked )
            return NumberUtils.toInt( peek!=null?peek.getTextContent():"",def );
        return NumberUtils.toInt(last.getTextContent(),def);
    }
    public double value( double def ){
        if( !valid )
            return def;

        if( peeked )
            return NumberUtils.toDouble( peek!=null?peek.getTextContent():"",def );

        return NumberUtils.toDouble(last.getTextContent(),def);
    }
    public boolean value( boolean def ){
        if( !valid )
            return def;

        if( peeked )
            return Tools.parseBool( peek!=null?peek.getTextContent():"",def );
        return Tools.parseBool(last.getTextContent(),def);
    }
    public Optional<Path> value( Path parent ){
        if( !valid )
            return Optional.empty();

        String at = "";
        if( peeked ) {
            at = peek == null ? "" : peek.getTextContent();
        }else{
            at = last.getTextContent();
        }
        if( at.isEmpty() )
            return Optional.empty();

        var p = Path.of(at);
        if( p.isAbsolute() ) {
            return Optional.of(p);
        }else{
            return Optional.of(parent.resolve(at));
        }
    }
    /* ****** */
    public String tagName(String def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null)
                return peek.getTagName();
        }else{
            return last.getTagName();
        }
        return def;
    }
    /*  ************ Getting attributes ************************************* */
    public String attr( String tag, String def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return peek.getAttribute(tag);
        }else if( last.hasAttribute(tag)) {
            return last.getAttribute(tag);
        }
        return def;
    }
    public int attr( String tag, int def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return NumberUtils.toInt(peek.getAttribute(tag),def);
        }else if( last.hasAttribute(tag)) {
            return NumberUtils.toInt(last.getAttribute(tag),def);
        }
        return def;
    }
    public double attr( String tag, double def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return NumberUtils.toDouble(peek.getAttribute(tag),def);
        }else if( last.hasAttribute(tag)) {
            return NumberUtils.toDouble(last.getAttribute(tag),def);
        }
        return def;
    }
    public boolean attr( String tag, boolean def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return Tools.parseBool(peek.getAttribute(tag),def);
        }else if( last.hasAttribute(tag)) {
            return Tools.parseBool(last.getAttribute(tag),def);
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
