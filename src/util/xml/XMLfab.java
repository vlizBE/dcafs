package util.xml;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            root = xmlDoc.createElement(parentTag);
            xmlDoc.appendChild(root);
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
        XMLfab fab = new XMLfab(xmlPath);
        fab.root = XMLtools.getFirstElementByTag(fab.xmlDoc, roots[0]);
        String end = roots[roots.length-1];
        if( fab.hasRoots(roots) ){
            fab.last=(Element)fab.root.getParentNode();
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
     * Add a parent node to the current root
     * @param tag The tag of the parent node
     * @return The fab after adding the parent node
     */
    public XMLfab addParent( String tag ){
        last = XMLtools.createChildElement(xmlDoc, root, tag);   
        parent = last;     
        return this;
    }

    /**
     * Add a parent node to the current root
     * @param tag The tag of the parent node
     * @param comment The comment for this parent node
     * @return The fab after adding the parent node
     */
    public XMLfab addParent( String tag, String comment ){
        root.appendChild(xmlDoc.createComment(" "+comment+" "));
        last = XMLtools.createChildElement(xmlDoc, root, tag);           
        this.parent = last;     
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
     * Remove all the children of this parent node
     * @return The fab after removing the child nodes of the current parent node
     */
    public XMLfab clearChildren(){
        XMLtools.removeAllChildren(parent);
        return this;
    }
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
     * Get the child node with the given tag and attribute
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
     * @return True if found
     */
    public boolean hasChild( String tag, String attr, String value){
        return getChildren(tag).stream().anyMatch(
            x -> x.getAttribute(attr).equalsIgnoreCase(value)
        );
    }

    /**
     * Checks the children of the active node for a specific tag,attribute,value match and make that active and parent
     * @param tag The tag of the parent
     * @param attribute The attribute to check
     * @param value The value the attribute should be
     * @return The optional parent node or empty if none found
     */
    public Optional<Element> selectParent( String tag, String attribute, String value){
        Optional<Element> found = getChildren(tag).stream()
                .filter( x -> x.getAttribute(attribute).equalsIgnoreCase(value)).findFirst();
        if( found.isPresent() ){
            last = found.get();
            parent = last;
        }
        return found;
    }

    /**
     * Checks the children of the active node for a specific tag and make that active and parent
     * @param tag The tag of the parent
     * @return The optional parent node or empty if none found
     */
    public Optional<Element> selectFirstParent( String tag ){
        Optional<Element> found = getChildren(tag).stream().findFirst();
        if( found.isPresent() ){
            last = found.get();
            parent = last;
        }
        return found;
    }
    /**
     * Checks the children of the active node for a specific tag,attribute,value match and make that active and parent
     * If not found create it.
     * @param tag The tag of the parent
     * @param attribute The attribute to check
     * @param value The value the attribute should be
     * @return This fab
     */
    public XMLfab selectOrCreateParent( String tag, String attribute, String value){
        Optional<Element> found = getChildren(tag).stream()
                .filter( x -> x.getAttribute(attribute).equalsIgnoreCase(value)||attribute.isEmpty()).findFirst();
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
     * Checks the children of the active node for a specific tag,attribute,value match and make that active and parent
     * If not found create it.
     * @param tag The tag of the parent
     * @return This fab
     */
    public XMLfab selectOrCreateParent( String tag ){
        return selectOrCreateParent(tag,"","");
    }
    /**
     * Select a child node for later alterations (eg. attributes etc) or create it if it doesn't exist
     * @param tag The tag of the child node to look for
     * @return The fab with the new/selected child node
     */
    public XMLfab alterChild( String tag ){
        alter=true;
        last = XMLtools.getFirstChildByTag(parent, tag);
        if( last!=null){           
            return this;
        }else{
            last = XMLtools.createChildElement(xmlDoc, parent, tag );           
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
    public Document build(){
        if( xmlPath == null ){
            XMLtools.updateXML(xmlDoc);
        }else{
            XMLtools.writeXML(xmlPath, xmlDoc);
        }        
        return xmlDoc;
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
}