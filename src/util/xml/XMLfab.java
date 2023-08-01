package util.xml;

import org.tinylog.Logger;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class XMLfab {
    private Element root;           // The highest element to which parents are added
    private Element last;           // The last created/added element
    private Element parent;         // The element to which child nodes are added
    private Document xmlDoc;        // The xml document
    private Path xmlPath;           // The path to the xml document

    /**
     * Create a fab based on the given doc and start from the given root
     * @param xml The xml document
     * @param rootTag The tag to look for as a starting point
     */
    private XMLfab( Document xml, String rootTag ){
       this(xml,rootTag,true);
    }
    private XMLfab( Document ori, Element work ){
        xmlDoc=ori;
        last=work;
        parent=last;
    }
    /**
     * Create a fab based on the given document, reload the document first if requested
     * @param doc The xml document
     * @param reload True if the document should be reloaded from disk first
     */
    private XMLfab( Document doc, boolean reload ){
        if( doc == null ){
            Logger.error("Invalid xml doc given.");
            return;
        }
        if( reload ){
            var docOpt= XMLtools.reloadXML(doc); // make sure we use latest version
            if( docOpt.isPresent()) {
                xmlDoc = docOpt.get();
            }
        }else{
            xmlDoc = doc;
        }        
    }

    /**
     * Create a fab with the xml document at the given path
     * @param path The path to the xml file
     */
    private XMLfab( Path path ){
        xmlPath=path;
        if( Files.exists(path) ){
            xmlDoc = XMLtools.readXML(path).orElse(null);
        }else{
            Logger.warn("No such XML "+path+", so creating it.");
            xmlDoc = XMLtools.createXML(path, false).orElse(null);
        }
        if( xmlDoc==null )
            Logger.error("Failed to create XMLfab for "+path);
    }
    /**
     * Create a fab from the given document and root tag
     * @param xmlDoc The xml document
     * @param rootTag The root tag to find
     */
    private XMLfab( Document xmlDoc, String rootTag, boolean reload ){
        this(xmlDoc,reload);
        getRoot(rootTag);
    }

    /**
     * Create a fab with the given path and parent tag
     * @param xmlPath The path to the xml file
     * @param rootTag The root tag to find
     */
    private XMLfab( Path xmlPath, String rootTag ){
        this(xmlPath);
        getRoot(rootTag);
    }
    public static Optional<XMLfab> alterDigger( XMLdigger dig ){
        return dig.current().map( d -> new XMLfab(dig.doc(),d));
    }
    public boolean isInvalid(){
        return xmlDoc==null;
    }
    /**
     * Get the path of the xml document
     * @return The path of the document
     */
    public Path getXMLPath(){
        return xmlPath;
    }

    /**
     * Pick the node with the given tag to become the root
     * @param tag The tag to look for
     */
    private void getRoot(String tag){
        if( isInvalid()) {
            Logger.error( "No valid xml, so can't find root");
            return;
        }
        var rootOpt= XMLtools.getFirstElementByTag(xmlDoc, tag );
        if( rootOpt.isEmpty() ){
            Logger.warn("No such root "+tag+ " in "+xmlPath.getFileName()+", so creating it.");
            root = xmlDoc.createElement(tag);
            try {
                xmlDoc.appendChild(root);
            }catch( DOMException e ){
                Logger.error( "Issue while trying to add "+tag+" to "+xmlDoc.toString()+":"+e.getMessage());
            }
        }else{
            root = rootOpt.get();
        }
        last=root;
    }

    /**
     * Check if a document contains the requested roots, creating nodes as needed
     * @param xmlPath The path to check
     * @param roots The roots to find
     * @return True if found
     */
    public static boolean hasRoot( Path xmlPath, String... roots){
        return new XMLfab(xmlPath).hasRoots(roots);
    }
    /**
     * Start a Mathfab based on the xml found at the path and after traversing the given roots/branches
     * @param xmlDoc The xml document to look into
     * @param roots The roots to look for
     * @return The fab found
     */
    public static XMLfab withRoot( Document xmlDoc, String... roots){
        return digging( new XMLfab(xmlDoc,roots[0]),roots );
    }
    /**
     * Start a XMLfab based on the xml found at the path and after traversing the given roots/branches
     * @param xmlPath The path on which to find the xml file
     * @param roots The roots to look for
     * @return The fab found
     */
    public static XMLfab withRoot( Path xmlPath, String... roots){
        return digging( new XMLfab(xmlPath,roots[0]),roots );
    }

    /**
     * Goes through the root tags given, either selecting or creating
     * @param fab The fab to work with
     * @param roots The root structure to create
     * @return The fab with the root
     */
    private static XMLfab digging( XMLfab fab, String... roots){
        for( int a=1; a<roots.length;a++)
            fab.digRoot(roots[a]);
        fab.last=fab.root;
        fab.parent=fab.root;
        return fab;
    }
    /**
     * Go one step further in the tree by selected a tag or create it if not found
     * @param tag The tag to look for
     * @return This fab after going one step lower with the root
     */
    public XMLfab digRoot( String tag ){
        var eleOpt = XMLtools.getFirstChildByTag(root, tag);
        if( eleOpt.isEmpty() ){
            root = (Element)root.appendChild( xmlDoc.createElement(tag) );    
            Logger.debug("Creating element with tag: "+tag);
        }else{
            Logger.debug("Using found element with tag: "+tag);
            root = eleOpt.get();
        }
        last = root;
        parent = root;
        return this;
    }
    /**
     * Get an Element stream with all the elements that match the last item of the given root.
     * fe. trunk,branch,twig will return all the twig elements
     * Note that twig can be * because this is considered a special tag that acts as a wildcard
     * @param xmlPath The path to the document
     * @param roots The roots to look for
     * @return The elements found at the end of the root
     */
    public static List<Element> getRootChildren( Path xmlPath, String... roots){
        if( Files.notExists(xmlPath) ){
            Logger.error("No such xml file: "+xmlPath+", looking for "+String.join("->",roots));
            return new ArrayList<>();
        }
        return XMLtools.readXML(xmlPath).map( xml -> getRootChildren(xml,roots)).orElse(new ArrayList<>());
    }

    /**
     * Get an Element stream with all the elements that match the last item of the given root.
     * fe. trunk,branch,twig will return all the twig elements.
     * Note that twig can be * because this is considered a special tag that acts as a wildcard
     * @param xml The source document
     * @param roots The roots to look for
     * @return The elements found at the end of the root
     */
    public static List<Element> getRootChildren( Document xml, String... roots){
        XMLfab fab = new XMLfab(xml,false);

        var rootOpt= XMLtools.getFirstElementByTag(fab.xmlDoc, roots[0]);
        if( rootOpt.isEmpty())
            return new ArrayList<>();

        fab.last = rootOpt.get();

        for( int a=1; a<roots.length-1;a++){
            var eleOpt = XMLtools.getFirstChildByTag(fab.last, roots[a]);
            if( eleOpt.isEmpty() )
                return new ArrayList<>();
            fab.last = eleOpt.get();
        }
        return fab.getChildren(roots[roots.length - 1]);
    }

    /**
     * Check if the current document has the given roots
     * @param roots The roots to look for (each step goes down a level)
     * @return True if found
     */
    private boolean hasRoots( String... roots ){
        if( isInvalid())
            return false;

        var rootOpt = XMLtools.getFirstElementByTag(xmlDoc, roots[0]);
        if(rootOpt.isEmpty())
            return false;

        var root=rootOpt.get();
        for( int a=1; a<roots.length;a++){
            var eleOpt = XMLtools.getFirstChildByTag(root, roots[a]);
            if( eleOpt.isEmpty() )
                return false;
            root = eleOpt.get();
        }
        return true;
    }

    /**
     * Add a child node to the current root and make it the current parent node
     *
     * @param tag The tag of the future parent node
     * @return The fab after adding the node
     */
    public XMLfab addParentToRoot(String tag ){
        var lastOpt= XMLtools.createChildElement(xmlDoc, root, tag);
        if( lastOpt.isPresent()) {
            last=lastOpt.get();
            parent = last;
        }
        return this;
    }

    /**
     * Add a child node to the current root and make it the current parent node and add a comment
     * @param tag The tag of the future parent node
     * @param comment The comment for this parent node
     * @return The fab after adding the parent node
     */
    public XMLfab addParentToRoot(String tag, String comment ){
        root.appendChild(xmlDoc.createComment(" "+comment+" "));
        addParentToRoot(tag);
        return this;
    }
    /**
     * Add a child node to the current parent
     * @param tag The tag of the child to add
     * @return The fab after adding the child node
     */
    public XMLfab addChild( String tag ){
        last = XMLtools.createChildElement(xmlDoc, parent, tag).orElse(last);
        return this;
    }

    /**
     * Add a child node to the current parent node with the given content
     * @param tag The tag of the child node to add
     * @param content The content of the child node to add
     * @return The fab after adding the child node
     */
    public XMLfab addChild( String tag, String content ){
        last = XMLtools.createChildTextElement(xmlDoc, parent, tag, content).orElse(last);
        return this;
    }

    /**
     * Remove all the children of the parent node
     * @return The fab after removing the child nodes of the current parent node
     */
    public XMLfab clearChildren(  ){
        return clearChildren("");
    }

    /**
     * Remove all children with a specified tag, or empty/* for all
     * @param tag The tag to remove (or empty for all)
     * @return This fab after removing childnodes
     */
    public XMLfab clearChildren( String tag ){
        if( tag.isEmpty() || tag.equalsIgnoreCase("*")) {
            XMLtools.removeAllChildren(parent);
        }else{
            Optional<Element> child;
            while( (child = getChild(tag)).isPresent() )
                parent.removeChild(child.get());
        }
        return this;
    }

    /**
     * Remove a single child node from the current parent node
     * @param tag The tag of the childnode to remove
     * @return This fab
     */
    public XMLfab removeChild( String tag ){
        getChild(tag).ifPresent( ch -> parent.removeChild(ch));
        return this;
    }
    /**
     * Remove a single child node from the current parent node
     * @param tag The tag of the child to remove
     * @return This fab
     */
    public boolean removeChild( String tag, String attr, String value ){
        var child = getChild(tag,attr,value);
        if( child.isPresent() ) {
            parent.removeChild(child.get());
            build();
            return true;
        }else{
            Logger.warn("Tried to remove a none-existing child "+tag);
            return false;
        }
    }
    /**
     * Get the first child node with the given tag and attribute
     * @param tag The tag of the childnode
     * @param attr The attribute of the childnode
     * @param value The value of the attribute
     * @return An optional of the result of the search
     */
    public Optional<Element> getChild( String tag, String attr, String value){
        return getChildren(tag).stream().filter(
            x -> x.getAttribute(attr).equalsIgnoreCase(value)
        ).findFirst();
    }
    /**
     * Get the child node with the given tag and attribute
     * @param tag The tag of the childnode
     * @return An optional of the result of the search
     */
    public Optional<Element> getChild( String tag ){
        return getChildren(tag).stream().findFirst();
    }
    /**
     * Check if a child node with the given tag and attribute is present
     * @param tag The tag of the child node to look for
     * @param attr The attribute to look for
     * @param value The value of the attribute
     * @return The fab if found
     */
    public Optional<XMLfab> hasChild( String tag, String attr, String value){
        return getChildren(tag).stream().anyMatch(x ->
                        x.getAttribute(attr).equalsIgnoreCase(value))?Optional.of(this):Optional.empty();

    }
    /**
     * Check if a child node with the given tag and attribute isn't present
     * @param tag The tag of the child node to look for
     * @param attr The attribute to look for
     * @param value The value of the attribute
     * @return The fab if not found
     */
    public Optional<XMLfab> noChild( String tag, String attr, String value){
        return getChildren(tag).stream().anyMatch(x ->
                x.getAttribute(attr).equalsIgnoreCase(value))?Optional.empty():Optional.of(this);

    }
    /**
     * Checks the children of the active node for a specific tag,attribute,value match and make that active and parent
     * @param tag The tag of the parent
     * @param attribute The attribute to check
     * @param value The value the attribute should be
     * @return The optional parent node or empty if none found
     */
    public Optional<XMLfab> selectChildAsParent(String tag, String attribute, String value){
        Optional<Element> found = getChildren(tag).stream()
                .filter( x -> x.getAttribute(attribute).matches(value)||attribute.isEmpty()).findFirst();
        if( found.isPresent() ){
            last = found.get();
            parent = last;
            return Optional.of(this);
        }
        return Optional.empty();
    }

    /**
     * Checks the children of the active node for a specific tag and make that active and parent
     * @param tag The tag of the parent
     * @return The optional parent node or empty if none found
     */
    public Optional<XMLfab> selectChildAsParent(String tag ){
        return selectChildAsParent(tag,"","");
    }
    /**
     * Checks the children of the active node for a specific tag,attribute,value matches and makes the last active and parent
     * If not found create it.
     * @param tag The tag of the parent
     * @param attribute The attribute to check
     * @param value The value the attribute should be
     * @return This fab
     */
    public XMLfab selectOrAddLastChildAsParent(String tag, String attribute, String value){
        var found = getChildren(tag).stream()
                .filter( x -> x.getAttribute(attribute).equalsIgnoreCase(value)||attribute.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
        if( !found.isEmpty() ){
            last = found.get(found.size()-1);
            parent = last;
        }else{
            addChild(tag);// Create the child
            if( !attribute.isEmpty())
                attr(attribute,value);
            down(); // make it the last/parent
        }
        return this;
    }
    public XMLfab selectOrAddLastChildAsParent(String tag){
        return selectOrAddChildAsParent(tag,"","");
    }
    /**
     * Checks the children of the active node for a specific tag,attribute,value match and make that active and parent
     * If not found create it.
     * @param tag The tag of the parent
     * @return This fab
     */
    public XMLfab selectOrAddChildAsParent(String tag ){
        return selectOrAddChildAsParent(tag,"","");
    }
    public XMLfab selectOrAddChildAsParent(String tag, String attribute, int value){
        return selectOrAddChildAsParent(tag,attribute,""+value);
    }
    public XMLfab selectOrAddChildAsParent(String tag, String attribute, String value){
        Optional<Element> found = getChildren(tag).stream()
                .filter( x -> x.getAttribute(attribute).matches(value)||attribute.isEmpty()).findFirst();
        if( found.isPresent() ){
            last = found.get();
            parent = last;
        }else{
            addChild(tag);// Create the child
            if( !attribute.isEmpty())
                attr(attribute,value);
            down(); // make it the last/parent
        }
        return this;
    }
    /**
     * Select a child node for later alterations (eg. attributes etc) or create it if it doesn't exist
     * @param tag The tag of the child node to look for
     * @return The fab with the new/selected child node
     */
    public XMLfab alterChild( String tag ){
        var childOpt = XMLtools.getFirstChildByTag(parent, tag);
        last = childOpt.orElseGet(() -> XMLtools.createChildElement(xmlDoc, parent, tag).get());
        return this;
    }
    public XMLfab alterChild( String tag, String attr, String val ){
        last = getChild(tag,attr,val).orElseGet(() -> XMLtools.createChildElement(xmlDoc, parent, tag ).get());
        attr(attr,val);
        return this;
    }
    /**
     * Select a child node for later alterations (eg. attributes etc) and alter the content or create it if it doesn't exist
     * @param tag The tag of the child node to look for
     * @param content The new content for the child node
     * @return The fab after altering/selecting
     */
    public XMLfab alterChild( String tag, String content ){
        var alterOpt = XMLtools.getFirstChildByTag(parent, tag);
        if( alterOpt.isPresent()){
            alterOpt.get().setTextContent(content);
        }else{
            last = XMLtools.createChildTextElement(xmlDoc, parent, tag, content).orElse(last);
        }
        return this;
    }

    /**
     * Add a comment as a child node to the current node
     * @param comment The comment to add
     * @return The fab after adding the comment
     */
    public XMLfab comment(String comment){
        parent.appendChild( xmlDoc.createComment(" "+comment+" ") );        
        return this;
    }

    /**
     * Add a comment above the current node (meaning on top instead of inside
     * @param comment The comment to add
     * @return The fab after adding the comment
     */
    public XMLfab commentBack(String comment){
        last.getParentNode().insertBefore( xmlDoc.createComment(" "+comment+" "),last );
        return this;
    }

    /**
     * Clear the content of the current parent node
     * @return This XMLfab after removing the content
     */
    public XMLfab clearParentTextContent(){
        if(parent.getFirstChild()==null ){
            parent.setTextContent("");
        }
        return this;
    }
    /* Attributes */
    /**
     * Add an attribute with the given value
     * @param attr The attribute to add
     * @param value The value for this attribute
     * @return The fab after adding the attribute
     */
    public XMLfab attr( String attr, String value ){
        last.setAttribute(attr, value);
        return this;
    }

    /**
     * Add an attribute with the given value
     * @param attr The attribute to add
     * @param value The value for this attribute
     * @return The fab after adding the attribute
     */
    public XMLfab attr( String attr, int value ){
        last.setAttribute(attr, ""+value);
        return this;
    }
    /**
     * Add a double attribute with the given value
     * @param attr The attribute to add
     * @param value The value for this attribute
     * @return The fab after adding the attribute
     */
    public XMLfab attr( String attr, double value ){
        last.setAttribute(attr, ""+value);
        return this;
    }
    /**
     * Add an empty attribute to the current node
     * @param attr The name of the attribute
     * @return The fab after adding the attribute
     */
    public XMLfab attr( String attr ){
        last.setAttribute(attr, "");
        return this;
    }

    /**
     * Remove an attribute of the current node
     * @param attr The name of the attribute to remove
     * @return The fab after the removal attempt
     */
    public XMLfab removeAttr( String attr ){
        if( last.hasAttribute(attr))
            last.removeAttribute(attr);
        return this;
    }
    /* Content */

    /**
     * Set the content of the current node
     * @param content The new content
     * @return The fab after setting the content
     */
    public XMLfab content(String content ){
        last.setTextContent(content);
        return this;
    }
    /* Info on current node */

    /**
     * Get the content of the current node
     * @return The content of the current node
     */
    public String getContent(){
        return last.getTextContent();
    }

    /**
     * Get the name of the current node
     * @return The name of the current node
     */
    public String getName(){
        return last.getNodeName();
    }
    /* Moving in the tree */

    /**
     * Move back up the xml tree, so the parent of the current parent becomes the new parent
     * @return The fab after going up one level
     */
    public XMLfab up(){
        parent = (Element)parent.getParentNode();
        last=parent;
        return this;
    }

    /**
     * Move down the xml tree,so the current node becomes the parent node
     * @return The fab after making the current node the parent node
     */
    public XMLfab down(){
        parent=last;
        return this;
    }

    /* Building the file */

    /**
     * Build the document based on the fab
     * @return The build document or null if failed
     */
    public boolean build(){
        if( xmlPath == null ){
            XMLtools.updateXML(xmlDoc);
        }else{
            XMLtools.writeXML(xmlPath, xmlDoc);
        }        
        return xmlDoc!=null;
    }

    /**
     * Get a list of all the children with the given tag
     * @param tag The tag to look for
     * @return A list of all the child elements found or empty list if none
     */
    public List<Element> getChildren( String tag ){
        if( tag.equals("*") )
            return XMLtools.getChildElements(last);
        return XMLtools.getChildElements(last, tag);
    }

    public Element getCurrentElement(){
        return last;
    }
    public String getAttribute( String attr ){
        return last.getAttribute(attr);
    }

}