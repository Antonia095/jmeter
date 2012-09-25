/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.protocol.http.proxy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.gui.AssertionGui;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.gui.LogicControllerGui;
import org.apache.jmeter.control.gui.TransactionControllerGui;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.gui.HeaderPanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerFactory;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.WorkBench;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.apache.oro.text.MalformedCachePatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Compiler;

//For unit tests, @see TestProxyControl

/**
 * Class handles storing of generated samples, etc
 */
public class ProxyControl extends GenericController {

    private static final Logger log = LoggingManager.getLoggerForClass();

    private static final long serialVersionUID = 240L;

    private static final String ASSERTION_GUI = AssertionGui.class.getName();


    private static final String TRANSACTION_CONTROLLER_GUI = TransactionControllerGui.class.getName();

    private static final String LOGIC_CONTROLLER_GUI = LogicControllerGui.class.getName();

    private static final String HEADER_PANEL = HeaderPanel.class.getName();

    private transient Daemon server;

    public static final int DEFAULT_PORT = 8080;

    // and as a string
    public static final String DEFAULT_PORT_S =
        Integer.toString(DEFAULT_PORT);// Used by GUI

    //+ JMX file attributes
    private static final String PORT = "ProxyControlGui.port"; // $NON-NLS-1$

    private static final String EXCLUDE_LIST = "ProxyControlGui.exclude_list"; // $NON-NLS-1$

    private static final String INCLUDE_LIST = "ProxyControlGui.include_list"; // $NON-NLS-1$

    private static final String CAPTURE_HTTP_HEADERS = "ProxyControlGui.capture_http_headers"; // $NON-NLS-1$

    private static final String ADD_ASSERTIONS = "ProxyControlGui.add_assertion"; // $NON-NLS-1$

    private static final String GROUPING_MODE = "ProxyControlGui.grouping_mode"; // $NON-NLS-1$

    private static final String SAMPLER_TYPE_NAME = "ProxyControlGui.sampler_type_name"; // $NON-NLS-1$

    private static final String SAMPLER_REDIRECT_AUTOMATICALLY = "ProxyControlGui.sampler_redirect_automatically"; // $NON-NLS-1$

    private static final String SAMPLER_FOLLOW_REDIRECTS = "ProxyControlGui.sampler_follow_redirects"; // $NON-NLS-1$

    private static final String USE_KEEPALIVE = "ProxyControlGui.use_keepalive"; // $NON-NLS-1$

    private static final String SAMPLER_DOWNLOAD_IMAGES = "ProxyControlGui.sampler_download_images"; // $NON-NLS-1$

    private static final String REGEX_MATCH = "ProxyControlGui.regex_match"; // $NON-NLS-1$

    private static final String HTTPS_SPOOF = "ProxyControlGui.https_spoof"; // $NON-NLS-1$

    private static final String HTTPS_SPOOF_MATCH = "ProxyControlGui.https_spoof_match"; // $NON-NLS-1$

    private static final String CONTENT_TYPE_EXCLUDE = "ProxyControlGui.content_type_exclude"; // $NON-NLS-1$

    private static final String CONTENT_TYPE_INCLUDE = "ProxyControlGui.content_type_include"; // $NON-NLS-1$
    //- JMX file attributes

    // Must agree with the order of entries in the drop-down
    // created in ProxyControlGui.createGroupingPanel()
    //private static final int GROUPING_NO_GROUPS = 0;
    private static final int GROUPING_ADD_SEPARATORS = 1;
    private static final int GROUPING_IN_SIMPLE_CONTROLLERS = 2;
    private static final int GROUPING_STORE_FIRST_ONLY = 3;
    private static final int GROUPING_IN_TRANSACTION_CONTROLLERS = 4;

    // Original numeric order (we now use strings)
    private static final String SAMPLER_TYPE_HTTP_SAMPLER_JAVA = "0";
    private static final String SAMPLER_TYPE_HTTP_SAMPLER_HC3_1 = "1";
    private static final String SAMPLER_TYPE_HTTP_SAMPLER_HC4 = "2";

    private long lastTime = 0;// When was the last sample seen?

    private static final long sampleGap =
        JMeterUtils.getPropDefault("proxy.pause", 1000); // $NON-NLS-1$
    // Detect if user has pressed a new link

    private AtomicBoolean addAssertions = new AtomicBoolean(false);

    private AtomicInteger groupingMode = new AtomicInteger(0);

    private AtomicBoolean samplerRedirectAutomatically = new AtomicBoolean(false);

    private AtomicBoolean samplerFollowRedirects = new AtomicBoolean(false);

    private AtomicBoolean useKeepAlive = new AtomicBoolean(false);

    private AtomicBoolean samplerDownloadImages = new AtomicBoolean(false);

    private AtomicBoolean regexMatch = new AtomicBoolean(false);// Should we match using regexes?

    /**
     * Tree node where the samples should be stored.
     * <p>
     * This property is not persistent.
     */
    private JMeterTreeNode target;

    public ProxyControl() {
        setPort(DEFAULT_PORT);
        setExcludeList(new HashSet<String>());
        setIncludeList(new HashSet<String>());
        setCaptureHttpHeaders(true); // maintain original behaviour
    }

    public void setPort(int port) {
        this.setProperty(new IntegerProperty(PORT, port));
    }

    public void setPort(String port) {
        setProperty(PORT, port);
    }

    public void setCaptureHttpHeaders(boolean capture) {
        setProperty(new BooleanProperty(CAPTURE_HTTP_HEADERS, capture));
    }

    public void setGroupingMode(int grouping) {
        this.groupingMode.set(grouping);
        setProperty(new IntegerProperty(GROUPING_MODE, grouping));
    }

    public void setAssertions(boolean b) {
        addAssertions.set(b);
        setProperty(new BooleanProperty(ADD_ASSERTIONS, b));
    }

    @Deprecated
    public void setSamplerTypeName(int samplerTypeName) {
        setProperty(new IntegerProperty(SAMPLER_TYPE_NAME, samplerTypeName));
    }

    public void setSamplerTypeName(String samplerTypeName) {
        setProperty(new StringProperty(SAMPLER_TYPE_NAME, samplerTypeName));
    }
    public void setSamplerRedirectAutomatically(boolean b) {
        samplerRedirectAutomatically.set(b);
        setProperty(new BooleanProperty(SAMPLER_REDIRECT_AUTOMATICALLY, b));
    }

    public void setSamplerFollowRedirects(boolean b) {
        samplerFollowRedirects.set(b);
        setProperty(new BooleanProperty(SAMPLER_FOLLOW_REDIRECTS, b));
    }

    /**
     * @param b
     */
    public void setUseKeepAlive(boolean b) {
        useKeepAlive.set(b);
        setProperty(new BooleanProperty(USE_KEEPALIVE, b));
    }

    public void setSamplerDownloadImages(boolean b) {
        samplerDownloadImages.set(b);
        setProperty(new BooleanProperty(SAMPLER_DOWNLOAD_IMAGES, b));
    }

    public void setIncludeList(Collection<String> list) {
        setProperty(new CollectionProperty(INCLUDE_LIST, new HashSet<String>(list)));
    }

    public void setExcludeList(Collection<String> list) {
        setProperty(new CollectionProperty(EXCLUDE_LIST, new HashSet<String>(list)));
    }

    /**
     * @param b
     */
    public void setRegexMatch(boolean b) {
        regexMatch.set(b);
        setProperty(new BooleanProperty(REGEX_MATCH, b));
    }

    public void setHttpsSpoof(boolean b) {
        setProperty(new BooleanProperty(HTTPS_SPOOF, b));
    }

    public void setHttpsSpoofMatch(String s) {
        setProperty(new StringProperty(HTTPS_SPOOF_MATCH, s));
    }

    public void setContentTypeExclude(String contentTypeExclude) {
        setProperty(new StringProperty(CONTENT_TYPE_EXCLUDE, contentTypeExclude));
    }

    public void setContentTypeInclude(String contentTypeInclude) {
        setProperty(new StringProperty(CONTENT_TYPE_INCLUDE, contentTypeInclude));
    }

    public boolean getAssertions() {
        return getPropertyAsBoolean(ADD_ASSERTIONS);
    }

    public int getGroupingMode() {
        return getPropertyAsInt(GROUPING_MODE);
    }

    public int getPort() {
        return getPropertyAsInt(PORT);
    }

    public String getPortString() {
        return getPropertyAsString(PORT);
    }

    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    public boolean getCaptureHttpHeaders() {
        return getPropertyAsBoolean(CAPTURE_HTTP_HEADERS);
    }

    public String getSamplerTypeName() {
        // Convert the old numeric types - just in case someone wants to reload the workbench
        String type = getPropertyAsString(SAMPLER_TYPE_NAME);
        if (SAMPLER_TYPE_HTTP_SAMPLER_JAVA.equals(type)){
            type = HTTPSamplerFactory.IMPL_JAVA;
        } else if (SAMPLER_TYPE_HTTP_SAMPLER_HC3_1.equals(type)){
            type = HTTPSamplerFactory.IMPL_HTTP_CLIENT3_1;            
        } else if (SAMPLER_TYPE_HTTP_SAMPLER_HC4.equals(type)){
            type = HTTPSamplerFactory.IMPL_HTTP_CLIENT4;       
        }
        return type;
    }

    public boolean getSamplerRedirectAutomatically() {
        return getPropertyAsBoolean(SAMPLER_REDIRECT_AUTOMATICALLY, false);
    }

    public boolean getSamplerFollowRedirects() {
        return getPropertyAsBoolean(SAMPLER_FOLLOW_REDIRECTS, true);
    }

    public boolean getUseKeepalive() {
        return getPropertyAsBoolean(USE_KEEPALIVE, true);
    }

    public boolean getSamplerDownloadImages() {
        return getPropertyAsBoolean(SAMPLER_DOWNLOAD_IMAGES, false);
    }

    public boolean getRegexMatch() {
        return getPropertyAsBoolean(REGEX_MATCH, false);
    }

    public boolean getHttpsSpoof() {
        return getPropertyAsBoolean(HTTPS_SPOOF, false);
    }

    public String getHttpsSpoofMatch() {
        return getPropertyAsString(HTTPS_SPOOF_MATCH, "");
    }

    public String getContentTypeExclude() {
        return getPropertyAsString(CONTENT_TYPE_EXCLUDE);
    }

    public String getContentTypeInclude() {
        return getPropertyAsString(CONTENT_TYPE_INCLUDE);
    }

    public void addConfigElement(ConfigElement config) {
    	// NOOP
    }

    public void startProxy() throws IOException {
        notifyTestListenersOfStart();
        try {
            server = new Daemon(getPort(), this);
            server.start();
            GuiPackage.getInstance().register(server);
        } catch (IOException e) {
            log.error("Could not create Proxy daemon", e);
            throw e;
        }
    }

    public void addExcludedPattern(String pattern) {
        getExcludePatterns().addItem(pattern);
    }

    public CollectionProperty getExcludePatterns() {
        return (CollectionProperty) getProperty(EXCLUDE_LIST);
    }

    public void addIncludedPattern(String pattern) {
        getIncludePatterns().addItem(pattern);
    }

    public CollectionProperty getIncludePatterns() {
        return (CollectionProperty) getProperty(INCLUDE_LIST);
    }

    public void clearExcludedPatterns() {
        getExcludePatterns().clear();
    }

    public void clearIncludedPatterns() {
        getIncludePatterns().clear();
    }

    /**
     * @return the target controller node
     */
    public JMeterTreeNode getTarget() {
        return target;
    }

    /**
     * Sets the target node where the samples generated by the proxy have to be
     * stored.
     */
    public void setTarget(JMeterTreeNode target) {
        this.target = target;
    }

    /**
     * Receives the recorded sampler from the proxy server for placing in the
     * test tree. param serverResponse to be added to allow saving of the
     * server's response while recording. A future consideration.
     */
    public synchronized void deliverSampler(final HTTPSamplerBase sampler, final TestElement[] subConfigs, final SampleResult result) {
        if (filterContentType(result) && filterUrl(sampler)) {
            JMeterTreeNode myTarget = findTargetControllerNode();
            @SuppressWarnings("unchecked") // OK, because find only returns correct element types
            Collection<ConfigTestElement> defaultConfigurations = (Collection<ConfigTestElement>) findApplicableElements(myTarget, ConfigTestElement.class, false);
            @SuppressWarnings("unchecked") // OK, because find only returns correct element types
            Collection<Arguments> userDefinedVariables = (Collection<Arguments>) findApplicableElements(myTarget, Arguments.class, true);

            removeValuesFromSampler(sampler, defaultConfigurations);
            replaceValues(sampler, subConfigs, userDefinedVariables);
            sampler.setAutoRedirects(samplerRedirectAutomatically.get());
            sampler.setFollowRedirects(samplerFollowRedirects.get());
            sampler.setUseKeepAlive(useKeepAlive.get());
            sampler.setImageParser(samplerDownloadImages.get());

            placeSampler(sampler, subConfigs, myTarget);
        }
        else {
            if(log.isDebugEnabled()) {
                log.debug("Sample excluded based on url or content-type: " + result.getUrlAsString() + " - " + result.getContentType());
            }
            result.setSampleLabel("["+result.getSampleLabel()+"]");
        }
        // SampleEvent is not passed JMeterVariables, because they don't make sense for Proxy Recording
        notifySampleListeners(new SampleEvent(result, "WorkBench")); // TODO - is this the correct threadgroup name?
    }

    public void stopProxy() {
        if (server != null) {
            server.stopServer();
            GuiPackage.getInstance().unregister(server);
            try {
                server.join(1000); // wait for server to stop
            } catch (InterruptedException e) {
            	//NOOP
            }
            notifyTestListenersOfEnd();
            server = null;
        }
    }

    // Package protected to allow test case access
    boolean filterUrl(HTTPSamplerBase sampler) {
        String domain = sampler.getDomain();
        if (domain == null || domain.length() == 0) {
            return false;
        }

        String url = generateMatchUrl(sampler);
        CollectionProperty includePatterns = getIncludePatterns();
        if (includePatterns.size() > 0) {
            if (!matchesPatterns(url, includePatterns)) {
                return false;
            }
        }

        CollectionProperty excludePatterns = getExcludePatterns();
        if (excludePatterns.size() > 0) {
            if (matchesPatterns(url, excludePatterns)) {
                return false;
            }
        }

        return true;
    }

    // Package protected to allow test case access
    /**
     * Filter the response based on the content type.
     * If no include nor exclude filter is specified, the result will be included
     *
     * @param result the sample result to check, true means result will be kept
     */
    boolean filterContentType(SampleResult result) {
        String includeExp = getContentTypeInclude();
        String excludeExp = getContentTypeExclude();
        // If no expressions are specified, we let the sample pass
        if((includeExp == null || includeExp.length() == 0) &&
                (excludeExp == null || excludeExp.length() == 0)
                )
        {
            return true;
        }

        // Check that we have a content type
        String sampleContentType = result.getContentType();
        if(sampleContentType == null || sampleContentType.length() == 0) {
            if(log.isDebugEnabled()) {
                log.debug("No Content-type found for : " + result.getUrlAsString());
            }

            return true;
        }

        if(log.isDebugEnabled()) {
            log.debug("Content-type to filter : " + sampleContentType);
        }
        
        // Check if the include pattern is matched
        boolean matched = testPattern(includeExp, sampleContentType, true);
        if(!matched) {
            return false;
        }
        
        // Check if the exclude pattern is matched
        matched = testPattern(excludeExp, sampleContentType, false);
        if(!matched) {
            return false;
        }

        return true;
    }

    /**
     * Returns true if matching pattern was different from expectedToMatch
     * @param expression Expression to match
     * @param sampleContentType
     * @return boolean true if Matching expression 
     */
    private final boolean testPattern(String expression, String sampleContentType, boolean expectedToMatch) {
        if(expression != null && expression.length() > 0) {
            if(log.isDebugEnabled()) {
                log.debug("Testing Expression : " + expression + " on sampleContentType:"+sampleContentType+", expected to match:"+expectedToMatch);
            }

            Pattern pattern = null;
            try {
                pattern = JMeterUtils.getPatternCache().getPattern(expression, Perl5Compiler.READ_ONLY_MASK | Perl5Compiler.SINGLELINE_MASK);
                if(JMeterUtils.getMatcher().contains(sampleContentType, pattern) != expectedToMatch) {
                    return false;
                }
            } catch (MalformedCachePatternException e) {
                log.warn("Skipped invalid content pattern: " + expression, e);
            }
        }
        return true;
    }
    /**
     * Helper method to add a Response Assertion
     * Called from AWT Event thread
     */
    private void addAssertion(JMeterTreeModel model, JMeterTreeNode node) throws IllegalUserActionException {
    	ResponseAssertion ra = new ResponseAssertion();
        ra.setProperty(TestElement.GUI_CLASS, ASSERTION_GUI);
        ra.setName(JMeterUtils.getResString("assertion_title")); // $NON-NLS-1$
        ra.setTestFieldResponseData();
        model.addComponent(ra, node);
    }

    /**
     * Helper method to add a Divider
     * Called from Application Thread that needs to update GUI (JMeterTreeModel)
     */
    private void addDivider(final JMeterTreeModel model, final JMeterTreeNode node) {
        final GenericController sc = new GenericController();
        sc.setProperty(TestElement.GUI_CLASS, LOGIC_CONTROLLER_GUI);
        sc.setName("-------------------"); // $NON-NLS-1$
        JMeterUtils.runSafe(new Runnable() {
			public void run() {
				try {
					model.addComponent(sc, node);
				} catch (IllegalUserActionException e) {
					log.error("Program error", e);
			        throw new Error(e);
				}		
			}
		});
    }

    /**
     * Helper method to add a Simple Controller to contain the samplers.
     * Called from Application Thread that needs to update GUI (JMeterTreeModel)
     * @param model
     *            Test component tree model
     * @param node
     *            Node in the tree where we will add the Controller
     * @param name
     *            A name for the Controller
     * @throws InvocationTargetException 
     * @throws InterruptedException 
     */
    private void addSimpleController(final JMeterTreeModel model, final JMeterTreeNode node, String name)
            throws InterruptedException, InvocationTargetException {
    	final GenericController sc = new GenericController();
        sc.setProperty(TestElement.GUI_CLASS, LOGIC_CONTROLLER_GUI);
        sc.setName(name);
        JMeterUtils.runSafe(new Runnable() {
			public void run() {
		        try {
					model.addComponent(sc, node);
				} catch (IllegalUserActionException e) {
					 log.error("Program error", e);
					 throw new Error(e);
				}				
			}
		});
    }

    /**
     * Helper method to add a Transaction Controller to contain the samplers.
     * Called from Application Thread that needs to update GUI (JMeterTreeModel)
     * @param model
     *            Test component tree model
     * @param node
     *            Node in the tree where we will add the Controller
     * @param name
     *            A name for the Controller
     * @throws InvocationTargetException 
     * @throws InterruptedException 
     */
    private void addTransactionController(final JMeterTreeModel model, final JMeterTreeNode node, String name)
            throws InterruptedException, InvocationTargetException {
        final TransactionController sc = new TransactionController();
        sc.setProperty(TestElement.GUI_CLASS, TRANSACTION_CONTROLLER_GUI);
        sc.setName(name);
        JMeterUtils.runSafe(new Runnable() {
			public void run() {
				 try {
					model.addComponent(sc, node);
				} catch (IllegalUserActionException e) {
					log.error("Program error", e);
                    throw new Error(e);
				}
			}
		});
    }
    /**
     * Helpler method to replicate any timers found within the Proxy Controller
     * into the provided sampler, while replacing any occurences of string _T_
     * in the timer's configuration with the provided deltaT.
     * Called from AWT Event thread
     * @param model
     *            Test component tree model
     * @param node
     *            Sampler node in where we will add the timers
     * @param deltaT
     *            Time interval from the previous request
     */
    private void addTimers(JMeterTreeModel model, JMeterTreeNode node, long deltaT) {
        TestPlan variables = new TestPlan();
        variables.addParameter("T", Long.toString(deltaT)); // $NON-NLS-1$
        ValueReplacer replacer = new ValueReplacer(variables);
        JMeterTreeNode mySelf = model.getNodeOf(this);
        Enumeration<JMeterTreeNode> children = mySelf.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode templateNode = children.nextElement();
            if (templateNode.isEnabled()) {
                TestElement template = templateNode.getTestElement();
                if (template instanceof Timer) {
                    TestElement timer = (TestElement) template.clone();
                    try {
                        replacer.undoReverseReplace(timer);
                        model.addComponent(timer, node);
                    } catch (InvalidVariableException e) {
                        // Not 100% sure, but I believe this can't happen, so
                        // I'll log and throw an error:
                        log.error("Program error", e);
                        throw new Error(e);
                    } catch (IllegalUserActionException e) {
                        // Not 100% sure, but I believe this can't happen, so
                        // I'll log and throw an error:
                        log.error("Program error", e);
                        throw new Error(e);
                    }
                }
            }
        }
    }

    /**
     * Finds the first enabled node of a given type in the tree.
     *
     * @param type
     *            class of the node to be found
     *
     * @return the first node of the given type in the test component tree, or
     *         <code>null</code> if none was found.
     */
    private JMeterTreeNode findFirstNodeOfType(Class<?> type) {
        JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();
        List<JMeterTreeNode> nodes = treeModel.getNodesOfType(type);
        for (JMeterTreeNode node : nodes) {
            if (node.isEnabled()) {
                return node;
            }
        }
        return null;
    }

    /**
     * Finds the controller where samplers have to be stored, that is:
     * <ul>
     * <li>The controller specified by the <code>target</code> property.
     * <li>If none was specified, the first RecordingController in the tree.
     * <li>If none is found, the first AbstractThreadGroup in the tree.
     * <li>If none is found, the Workspace.
     * </ul>
     *
     * @return the tree node for the controller where the proxy must store the
     *         generated samplers.
     */
    private JMeterTreeNode findTargetControllerNode() {
        JMeterTreeNode myTarget = getTarget();
        if (myTarget != null) {
            return myTarget;
        }
        myTarget = findFirstNodeOfType(RecordingController.class);
        if (myTarget != null) {
            return myTarget;
        }
        myTarget = findFirstNodeOfType(AbstractThreadGroup.class);
        if (myTarget != null) {
            return myTarget;
        }
        myTarget = findFirstNodeOfType(WorkBench.class);
        if (myTarget != null) {
            return myTarget;
        }
        log.error("Program error: proxy recording target not found.");
        return null;
    }

    /**
     * Finds all configuration objects of the given class applicable to the
     * recorded samplers, that is:
     * <ul>
     * <li>All such elements directly within the HTTP Proxy Server (these have
     * the highest priority).
     * <li>All such elements directly within the target controller (higher
     * priority) or directly within any containing controller (lower priority),
     * including the Test Plan itself (lowest priority).
     * </ul>
     *
     * @param myTarget
     *            tree node for the recording target controller.
     * @param myClass
     *            Class of the elements to be found.
     * @param ascending
     *            true if returned elements should be ordered in ascending
     *            priority, false if they should be in descending priority.
     *
     * @return a collection of applicable objects of the given class.
     */
    // TODO - could be converted to generic class?
    private Collection<?> findApplicableElements(JMeterTreeNode myTarget, Class<? extends TestElement> myClass, boolean ascending) {
        JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();
        LinkedList<TestElement> elements = new LinkedList<TestElement>();

        // Look for elements directly within the HTTP proxy:
        Enumeration<?> kids = treeModel.getNodeOf(this).children();
        while (kids.hasMoreElements()) {
            JMeterTreeNode subNode = (JMeterTreeNode) kids.nextElement();
            if (subNode.isEnabled()) {
                TestElement element = (TestElement) subNode.getUserObject();
                if (myClass.isInstance(element)) {
                    if (ascending) {
                        elements.addFirst(element);
                    } else {
                        elements.add(element);
                    }
                }
            }
        }

        // Look for arguments elements in the target controller or higher up:
        for (JMeterTreeNode controller = myTarget; controller != null; controller = (JMeterTreeNode) controller
                .getParent()) {
            kids = controller.children();
            while (kids.hasMoreElements()) {
                JMeterTreeNode subNode = (JMeterTreeNode) kids.nextElement();
                if (subNode.isEnabled()) {
                    TestElement element = (TestElement) subNode.getUserObject();
                    if (myClass.isInstance(element)) {
                        log.debug("Applicable: " + element.getName());
                        if (ascending) {
                            elements.addFirst(element);
                        } else {
                            elements.add(element);
                        }
                    }

                    // Special case for the TestPlan's Arguments sub-element:
                    if (element instanceof TestPlan) {
                        TestPlan tp = (TestPlan) element;
                        Arguments args = tp.getArguments();
                        if (myClass.isInstance(args)) {
                            if (ascending) {
                                elements.addFirst(args);
                            } else {
                                elements.add(args);
                            }
                        }
                    }
                }
            }
        }

        return elements;
    }

    private void placeSampler(final HTTPSamplerBase sampler, final TestElement[] subConfigs, 
    		JMeterTreeNode myTarget) {
        try {
            final JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();

            boolean firstInBatch = false;
            long now = System.currentTimeMillis();
            long deltaT = now - lastTime;
            int cachedGroupingMode = groupingMode.get();
            if (deltaT > sampleGap) {
                if (!myTarget.isLeaf() && cachedGroupingMode == GROUPING_ADD_SEPARATORS) {
                    addDivider(treeModel, myTarget);
                }
                if (cachedGroupingMode == GROUPING_IN_SIMPLE_CONTROLLERS) {
                    addSimpleController(treeModel, myTarget, sampler.getName());
                }
                if (cachedGroupingMode == GROUPING_IN_TRANSACTION_CONTROLLERS) {
                    addTransactionController(treeModel, myTarget, sampler.getName());
                }
                firstInBatch = true;// Remember this was first in its batch
            }
            if (lastTime == 0) {
                deltaT = 0; // Decent value for timers
            }
            lastTime = now;

            if (cachedGroupingMode == GROUPING_STORE_FIRST_ONLY) {
                if (!firstInBatch) {
                    return; // Huh! don't store this one!
                }

                // If we're not storing subsequent samplers, we'll need the
                // first sampler to do all the work...:
                sampler.setFollowRedirects(true);
                sampler.setImageParser(true);
            }

            if (cachedGroupingMode == GROUPING_IN_SIMPLE_CONTROLLERS ||
                    cachedGroupingMode == GROUPING_IN_TRANSACTION_CONTROLLERS) {
                // Find the last controller in the target to store the
                // sampler there:
                for (int i = myTarget.getChildCount() - 1; i >= 0; i--) {
                    JMeterTreeNode c = (JMeterTreeNode) myTarget.getChildAt(i);
                    if (c.getTestElement() instanceof GenericController) {
                        myTarget = c;
                        break;
                    }
                }
            }
            final long deltaTFinal = deltaT;
            final boolean firstInBatchFinal = firstInBatch;
            final JMeterTreeNode myTargetFinal = myTarget;
            JMeterUtils.runSafe(new Runnable() {
				public void run() {
					try {
			            final JMeterTreeNode newNode = treeModel.addComponent(sampler, myTargetFinal);
			            if (firstInBatchFinal) {
			                if (addAssertions.get()) {
			                    addAssertion(treeModel, newNode);
			                }
			                addTimers(treeModel, newNode, deltaTFinal);
			            }
	
			            for (int i = 0; subConfigs != null && i < subConfigs.length; i++) {
			                if (subConfigs[i] instanceof HeaderManager) {
			                	final TestElement headerManager = subConfigs[i];
			                	headerManager.setProperty(TestElement.GUI_CLASS, HEADER_PANEL);
			                    treeModel.addComponent(headerManager, newNode);
			                }
			            }
					} catch (IllegalUserActionException e) {
			            JMeterUtils.reportErrorToUser(e.getMessage());
			        }
				}
			});
        } catch (Exception e) {
            JMeterUtils.reportErrorToUser(e.getMessage());
        } 
    }

    /**
     * Remove from the sampler all values which match the one provided by the
     * first configuration in the given collection which provides a value for
     * that property.
     *
     * @param sampler
     *            Sampler to remove values from.
     * @param configurations
     *            ConfigTestElements in descending priority.
     */
    private void removeValuesFromSampler(HTTPSamplerBase sampler, Collection<ConfigTestElement> configurations) {
        for (PropertyIterator props = sampler.propertyIterator(); props.hasNext();) {
            JMeterProperty prop = props.next();
            String name = prop.getName();
            String value = prop.getStringValue();

            // There's a few properties which are excluded from this processing:
            if (name.equals(TestElement.ENABLED) || name.equals(TestElement.GUI_CLASS) || name.equals(TestElement.NAME)
                    || name.equals(TestElement.TEST_CLASS)) {
                continue; // go on with next property.
            }

            for (Iterator<ConfigTestElement> configs = configurations.iterator(); configs.hasNext();) {
                ConfigTestElement config = configs.next();

                String configValue = config.getPropertyAsString(name);

                if (configValue != null && configValue.length() > 0) {
                    if (configValue.equals(value)) {
                        sampler.setProperty(name, ""); // $NON-NLS-1$
                    }
                    // Property was found in a config element. Whether or not
                    // it matched the value in the sampler, we're done with
                    // this property -- don't look at lower-priority configs:
                    break;
                }
            }
        }
    }

    private String generateMatchUrl(HTTPSamplerBase sampler) {
        StringBuilder buf = new StringBuilder(sampler.getDomain());
        buf.append(':'); // $NON-NLS-1$
        buf.append(sampler.getPort());
        buf.append(sampler.getPath());
        if (sampler.getQueryString().length() > 0) {
            buf.append('?'); // $NON-NLS-1$
            buf.append(sampler.getQueryString());
        }
        return buf.toString();
    }

    private boolean matchesPatterns(String url, CollectionProperty patterns) {
        PropertyIterator iter = patterns.iterator();
        while (iter.hasNext()) {
            String item = iter.next().getStringValue();
            Pattern pattern = null;
            try {
                pattern = JMeterUtils.getPatternCache().getPattern(item, Perl5Compiler.READ_ONLY_MASK | Perl5Compiler.SINGLELINE_MASK);
                if (JMeterUtils.getMatcher().matches(url, pattern)) {
                    return true;
                }
            } catch (MalformedCachePatternException e) {
                log.warn("Skipped invalid pattern: " + item, e);
            }
        }
        return false;
    }

    /**
     * Scan all test elements passed in for values matching the value of any of
     * the variables in any of the variable-holding elements in the collection.
     *
     * @param sampler
     *            A TestElement to replace values on
     * @param configs
     *            More TestElements to replace values on
     * @param variables
     *            Collection of Arguments to use to do the replacement, ordered
     *            by ascending priority.
     */
    private void replaceValues(TestElement sampler, TestElement[] configs, Collection<Arguments> variables) {
        // Build the replacer from all the variables in the collection:
        ValueReplacer replacer = new ValueReplacer();
        for (Iterator<Arguments> vars = variables.iterator(); vars.hasNext();) {
            final Map<String, String> map = vars.next().getArgumentsAsMap();
            for (Iterator<String> vals = map.values().iterator(); vals.hasNext();){
               final Object next = vals.next();
               if ("".equals(next)) {// Drop any empty values (Bug 45199)
                   vals.remove();
               }
            }
            replacer.addVariables(map);
        }

        try {
            boolean cachedRegexpMatch = regexMatch.get();
            replacer.reverseReplace(sampler, cachedRegexpMatch);
            for (int i = 0; i < configs.length; i++) {
                if (configs[i] != null) {
                    replacer.reverseReplace(configs[i], cachedRegexpMatch);
                }
            }
        } catch (InvalidVariableException e) {
            log.warn("Invalid variables included for replacement into recorded " + "sample", e);
        }
    }

    /**
     * This will notify sample listeners directly within the Proxy of the
     * sampling that just occured -- so that we have a means to record the
     * server's responses as we go.
     *
     * @param event
     *            sampling event to be delivered
     */
    private void notifySampleListeners(SampleEvent event) {
        JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();
        JMeterTreeNode myNode = treeModel.getNodeOf(this);
        Enumeration<JMeterTreeNode> kids = myNode.children();
        while (kids.hasMoreElements()) {
            JMeterTreeNode subNode = kids.nextElement();
            if (subNode.isEnabled()) {
                TestElement testElement = subNode.getTestElement();
                if (testElement instanceof SampleListener) {
                    ((SampleListener) testElement).sampleOccurred(event);
                }
            }
        }
    }

    /**
     * This will notify test listeners directly within the Proxy that the 'test'
     * (here meaning the proxy recording) has started.
     */
    private void notifyTestListenersOfStart() {
        JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();
        JMeterTreeNode myNode = treeModel.getNodeOf(this);
        Enumeration<JMeterTreeNode> kids = myNode.children();
        while (kids.hasMoreElements()) {
            JMeterTreeNode subNode = kids.nextElement();
            if (subNode.isEnabled()) {
                TestElement testElement = subNode.getTestElement();
                if (testElement instanceof TestStateListener) {
                    ((TestStateListener) testElement).testStarted();
                }
            }
        }
    }

    /**
     * This will notify test listeners directly within the Proxy that the 'test'
     * (here meaning the proxy recording) has ended.
     */
    private void notifyTestListenersOfEnd() {
        JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();
        JMeterTreeNode myNode = treeModel.getNodeOf(this);
        Enumeration<JMeterTreeNode> kids = myNode.children();
        while (kids.hasMoreElements()) {
            JMeterTreeNode subNode = kids.nextElement();
            if (subNode.isEnabled()) {
                TestElement testElement = subNode.getTestElement();
                if (testElement instanceof TestStateListener) { // TL - TE
                    ((TestStateListener) testElement).testEnded();
                }
            }
        }
    }

    @Override
    public boolean canRemove() {
        return null == server;
    }
}
