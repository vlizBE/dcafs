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
import java.util.stream.Stream;

public class XMLfab {
    Element root;           // The highest element to which parents are added
    Element last;           // The last created/added element
    Element parent;         // The element to which child nodes are added
    Document xmlDoc;        // The xml document
    Path xmlPath;           // The path to the xml document
    boolean alter=false;    // Set the flag that the next operations are altering

    /**
     * Create a fab based on the given doc and start from the given root
     * @param xml The xml document
     * @param rootTag The tag to look for as a starting point
     */
    private XMLfab( Document xml, String rootTag ){
       this(xml,rootTag,true);
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
            xmlDoc = XMLtools.reloadXML(doc); // make sure we use latest version
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
            xmlDoc = XMLtools.readXML(path);
        }else{
            Logger.warn("No such XML "+path+", so creating it.");
            xmlDoc = XMLtools.createXML(path, false);
        }
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

    /**
     * Get the path of the xml document
     * @return The path of the document
     */
    public Path getXMLPath(){
        return xmlPath;
    }

    private void getRoot(String parentTag){
        this.root = XMLtools.getFirstElementByTag(xmlDoc, parentTag );
        if( root == null ){
            Logger.warn("No such root "+parentTag+ " in "+xmlPath.getFileName()+", so creating it.");
            root = xmlDoc.createElement(parentTag);
            try {
                xmlDoc.appendChild(root);
            }catch( DOMException e ){
                Logger.error( "Issue while trying to add "+parentTag+" to "+xmlDoc.toString()+":"+e.getMessage());
            }
        }
        this.last=root;
    }

    /**
     * Check if a document contains the requested roots
     * @param xmlDoc The document to check
     * @param roots The roots to find
     * @return True if found
     */
    public static boolean hasRoot( Document xmlDoc, String... roots){
        return new XMLfab(xmlDoc,false).hasRoots(roots);
    }
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
        XMLfab fab = new XMLfab(xmlDoc,roots[0]);
        for( int a=1; a<roots.length;a++)
            fab.digRoot(roots[a]);          
        fab.last=fab.root; 
        fab.parent=fab.root;
        return fab;
    }

    /**
     * Start a Mathfab based on the xml found at the path and after traversing the given roots/branches
     * @param xmlPath The path on which to find the xml file
     * @param roots The roots to look for
     * @return The fab found
     */
    public static XMLfab withRoot( Path xmlPath, String... roots){        
        XMLfab fab = new XMLfab(xmlPath,roots[0]);
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
        Element ele = XMLtools.getFirstChildByTag(root, tag);
        if( ele == null ){
            root = (Element)root.appendChild( xmlDoc.createElement(tag) );    
            Logger.debug("Creating element with tag: "+tag);
        }else{
            Logger.debug("Using found element with tag: "+tag);
            root = ele;            
        }
        last = root;
        parent = root;
        return this;
    }
    /**
     * Get a Element stream with all the elements that match the last item of the given root.
     * fe. trunk,branch,twig will return all the twig elements
     * Note that twig can be * because this is considered a special tag that acts as a wildcard
     * @param xmlPath The path to the document
     * @param roots The roots to look for
     * @return The elements found at the end of the root
     */
    public static Stream<Element> getRootChildren( Path xmlPath, String... roots){
        if( Files.notExists(xmlPath) ){
            Logger.error("No such xml file: "+xmlPath);
            return new ArrayList<Element>().stream();
        }

        XMLfab fab = new XMLfab(xmlPath);
        fab.root = XMLtools.getFirstElementByTag(fab.xmlDoc, roots[0]);

        if( fab.hasRoots(roots) ){
            if( fab.root.getParentNode()!=null && fab.root.getParentNode() instanceof Element) {
                fab.last = (Element) fab.root.getParentNode();
            }else{
                fab.last=fab.root;
            }
            return fab.getChildren(roots[roots.length-1]).stream();
        }        
        return new ArrayList<Element>().stream();
    }

    /**
     * Get a Element stream with all the elements that match the last item of the given root.
     * fe. trunk,branch,twig will return all the twig elements.
     * Note that twig can be * because this is considered a special tag that acts as a wildcard
     * @param xml The source document
     * @param roots The roots to look for
     * @return The elements found at the end of the root
     */
    public static Stream<Element> getRootChildren( Document xml, String... roots){
        XMLfab fab = new XMLfab(xml,false);
        fab.root = XMLtools.getFirstElementByTag(fab.xmlDoc, roots[0]);

        if( fab.hasRoots(roots) ){
            fab.last=(Element)fab.root.getParentNode();            
            return fab.getChildren(roots[roots.length-1]).stream();
        }        
        return new ArrayList<Element>().stream();
    }

    /**
     * Check if the current document has the given roots
     * @param roots The roots to look for (each step goes down a level)
     * @return True if found
     */
    private boolean hasRoots( String... roots ){
        root = XMLtools.getFirstElementByTag(xmlDoc, roots[0]);
        for( int a=1; a<roots.length;a++){
            Element ele = XMLtools.getFirstChildByTag(root, roots[a]);

            if( ele == null ){
                return false;
            }else{               
                root = ele;
            }
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
        last = XMLtools.createChildElement(xmlDoc, root, tag);   
        parent = last;     
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
        last = XMLtools.createChildElement(xmlDoc, root, tag);           
        this.parent = last;     
        return this;
    }

    /**
     * Add a node after the the current parent
     * @param tag
     * @param content
     * @return
     */
    public XMLfab addParentHere( String tag, String content){
        var newNode = xmlDoc.createElement(tag);
        newNode.setTextContent(content);

        parent = (Element) parent.insertBefore(newNode,parent.getNextSibling());
        return this;
    }
    public XMLfab addParentAtEnd( String tag, String content){
        var newNode = xmlDoc.createElement(tag);
        newNode.setTextContent(content);

        parent = (Element) parent.insertBefore(xmlDoc.createElement(tag),null);
        return this;
    }
    /**
     * Add a child node to the current parent
     * @param tag The tag of the childnode to add
     * @return The fab after adding the child node
     */
    public XMLfab addChild( String tag ){
        alter=false;
        last = XMLtools.createChildElement(xmlDoc, parent, tag);
        return this;
    }

    /**
     * Add a child node to the current parent node with the given content
     * @param tag The tag of the child node to add
     * @param content The content of the child node to add
     * @return The fab after adding the child node
     */
    public XMLfab addChild( String tag, String content ){
        alter=false;
        last = XMLtools.createChildTextElement(xmlDoc, parent, tag, content);
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
        var child = getChild(tag);
        if( child.isPresent() ) {
            parent.removeChild(child.get());
        }else{
            Logger.warn("Tried to remove a none-existing childnode "+tag);
        }
        return this;
    }
    /**
     * Remove a single child node from the current parent node
     * @param tag The tag of the childnode to remove
     * @return This fab
     */
    public boolean removeChild( String tag, String attr, String value ){
        var child = getChild(tag,attr,value);
        if( child.isPresent() ) {
            parent.removeChild(child.get());
            build();
            return true;
        }else{
            Logger.warn("Tried to remove a none-existing childnode "+tag);
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
        alter=true;
        last = XMLtools.getFirstChildByTag(parent, tag);
        if( last==null){
            last = XMLtools.createChildElement(xmlDoc, parent, tag );           
        }
        return this;
    }
    public XMLfab alterChild( String tag, String attr, String val ){
        alter=true;
        var ch = getChild(tag,attr,val);
        if( ch.isPresent() ){
            last = ch.get();
        }else{
            last = XMLtools.createChildElement(xmlDoc, parent, tag );
            attr(attr,val);
        }
        return this;
    }
    /**
     * Select a child node for later alterations (eg. attributes etc) and alter the content or create it if it doesn't exist
     * @param tag The tag of the child node to look for
     * @param content The new content for the child node
     * @return The fab after altering/selecting
     */
    public XMLfab alterChild( String tag, String content ){
        alter=true;
        last = XMLtools.getFirstChildByTag(parent, tag);
        if( last!=null){
            last.setTextContent(content);            
        }else{
            last = XMLtools.createChildTextElement(xmlDoc, parent, tag, content);
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
    /**
     * Get a list of all the children with the given tag
     * @param tag The tag to look for
     * @return A list of all the child elements found or empty list if none
     */
    public List<Element> getDistinctChildren( String tag ){
        if( tag.equals("*") )
            return XMLtools.getChildElements(last);
        var l= XMLtools.getChildElements(last, tag);
        return l.stream().distinct().collect( Collectors.toList());
    }
    /**
     * Get a list of all the children with the given tag and attribute value combination
     * @param tag The tag to look for
     * @param attr The attribute to compare
     * @param value The value the attribute should be
     * @return The list of found child nodes or an empty list if none
     */
    public List<Element> getChildren( String tag, String attr, String value ){
        if( tag.equals("*") )
            return XMLtools.getChildElements(last).stream()
                    .filter( e->e.getAttribute(attr).equalsIgnoreCase(value))
                    .collect(Collectors.toList());
        return XMLtools.getChildElements(last, tag).stream()
                            .filter( e->e.getAttribute(attr).equalsIgnoreCase(value))
                            .collect(Collectors.toList());
    }
    public Element getCurrentElement(){
        return last;
    }
    public String getAttribute( String attr ){
        return last.getAttribute(attr);
    }
    public ArrayList<String[]> getAttributes(){
        ArrayList<String[]> hash = new ArrayList<>();

        var map = last.getAttributes();
        for( int a=0;a<map.getLength();a++){
            String val = map.item(a).getNodeValue();
            String att = map.item(a).getNodeName();
            hash.add(new String[]{att,val});
        }
        return hash;
    }
    public static ArrayList<String[]> getAttributes(Element ele){
        ArrayList<String[]> hash = new ArrayList<>();

        var map = ele.getAttributes();
        for( int a=0;a<map.getLength();a++){
            String val = map.item(a).getNodeValue();
            String att = map.item(a).getNodeName();
            hash.add(new String[]{att,val});
        }
        return hash;
    }
}