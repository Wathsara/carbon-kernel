/*
 * Copyright (c) 2005-2012, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.tomcat.internal;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardVirtualThreadExecutor;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.startup.CatalinaBaseConfigurationSource;
import org.apache.catalina.startup.Constants;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.jasper.servlet.JasperInitializer;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.file.ConfigFileLoader;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.base.CarbonBaseConstants;
import org.wso2.carbon.tomcat.CarbonTomcatException;
import org.wso2.carbon.tomcat.api.CarbonTomcatService;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * the extended {@link Tomcat} class, which configures itself using the tomcat {@link Digester}
 */
public class CarbonTomcat extends Tomcat implements CarbonTomcatService {
    private static Log log = LogFactory.getLog(CarbonTomcat.class);
    private ExtendedCatalina catalina = new ExtendedCatalina();
    private String globalWebXml;
    private String globalContextXml;

    /**
     * configuring the {@link CarbonTomcat} using the inbuilt digester mechanism in tomcat
     *
     * @param baseDir     normally we set the catalina.home as the baseDir
     * @param inputStream of catalina-server.xml
     */
    public void configure(String baseDir, InputStream inputStream) {
        this.setBaseDir(baseDir);
        String configPath = System.getProperty(CarbonBaseConstants.CARBON_CONFIG_DIR_PATH);
        if (configPath == null) {
            globalWebXml = Paths.get(System.getProperty("carbon.home"), "repository", "conf", "tomcat", "web.xml").toString();
            globalContextXml = Paths.get(System.getProperty("carbon.home"), "repository", "conf", "tomcat", "context.xml").toString();
        } else {
            globalWebXml = Paths.get(configPath, "tomcat", "web.xml").toString();
            globalContextXml = Paths.get(configPath, "tomcat", "context.xml").toString();
        }

        //creating a digester to parse our catalina-server.xml
        Digester digester = catalina.createStartDigester();
        digester.push(this);
        try {
            digester.parse(inputStream);
        } catch (IOException e) {
            log.error("error while reading xml stream", e);
        } catch (SAXException e) {
            log.error("error while parsing xml stream", e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                log.error("error while closing the inputStream", e);
            }
        }

    }

    /**
     * Start the server.
     *
     * @throws LifecycleException
     */
    @Override
    public void start() throws LifecycleException {
        getServer();
        this.server.start();
    }

    /**
     * This gets called by the tomcat configurator via reflection.
     *
     * @param server reference
     */
    @SuppressWarnings("unused")
    public void setServer(Server server) {
        this.server = server;
        initBaseDir();

        // Set configuration source
        ConfigFileLoader.setSource(new CatalinaBaseConfigurationSource(new File(basedir), null));
    }


    /**
     * the current catalina-server.xml based configuration only allows one service.
     * Even if there are multiple services, we only take first service in to account
     *
     * @return first service found
     */
    @Override
    public Service getService() {
        Server server = getServer();
        Service[] findServices = server.findServices();
        if (findServices != null && findServices.length > 0) {
            return findServices[0];
        }
        throw new IllegalStateException("Unable to locate Service.");
    }

    @Override
    public Host getHost() {
        return findHost();
    }

    @Override
    public Engine getEngine() {
        return findEngine();
    }

    private Engine findEngine() {
        Server server = getServer();
        Service[] findServices = server.findServices();
        for (Service service : findServices) {
            Container container = service.getContainer();
            if (container instanceof Engine) {
                return (Engine) container;
            }
        }
        throw new IllegalStateException("Unable to locate Engine.");
    }

    private Host findHost() {

        Engine engine = findEngine();
        String defaultHost = engine.getDefaultHost();
        Container child = engine.findChild(defaultHost);
        return (Host) child;
    }

    public void init() throws LifecycleException {
        getServer();
        this.server.init();
    }

    /**
     * End of Tomcat configuration related methods. The following methods are public (exposed via OSGi service)
     * and can be considered as the true API.
     */

    /**
     * adding web-app with default-host and default listeners
     *
     * @param contextPath    unique web-app context
     * @param webappFilePath File location of the web-app
     * @return {@link Context} object of the added web-app
     */
    public Context addWebApp(String contextPath, String webappFilePath) throws CarbonTomcatException {
        Host defaultHost = (Host) this.getEngine().findChild(this.getEngine().getDefaultHost());
        return this.addWebApp(defaultHost, contextPath, webappFilePath, null);
    }

    /**
     * adding web-app with the default life-cycle listener
     *
     * @param host           virtual host for the web-app
     * @param contextPath    unique web-app context
     * @param webappFilePath File location of the web-app
     * @return {@link Context} object of the added web-app
     */
    public Context addWebApp(Host host, String contextPath,
                             String webappFilePath) throws CarbonTomcatException {
        return this.addWebApp(host, contextPath, webappFilePath, null);
    }

    /**
     * adding web-app with default-host
     *
     * @param contextPath       unique web-app context
     * @param webappFilePath    File location of the web-app
     * @param lifecycleListener tomcat life-cycle listener
     * @return {@link Context} object of the added web-app
     */
    public Context addWebApp(String contextPath, String webappFilePath, LifecycleListener lifecycleListener)
            throws CarbonTomcatException {
        Host defaultHost = (Host) this.getEngine().findChild(this.getEngine().getDefaultHost());
        return this.addWebApp(defaultHost, contextPath, webappFilePath, lifecycleListener);
    }

    /**
     * web-app addition
     *
     * @param host              virtual host for the webapp
     * @param contextPath       unique web-app context
     * @param webappFilePath    File location of the web-app
     * @param lifecycleListener tomcat life-cycle listener
     * @return {@link Context} object of the added web-app
     */
    @Override
    public Context addWebApp(Host host, String contextPath,
                             String webappFilePath, LifecycleListener lifecycleListener)
            throws CarbonTomcatException {
        JarFile webappJarFile = null;
        JarEntry contextXmlFileEntry;
        Context ctx = null;
        boolean removeContext = false;
        try {
            Container child = host.findChild(contextPath);
            if (child != null) {
                if (ctx != null && host != null) {
                    ctx.setRealm(null);
                    try {
                        ctx.stop();
                    } catch (LifecycleException x) {
                        log.error("Cannot stop context ", x);
                    }
                    host.removeChild(ctx);
                }
            }
            ctx = new StandardContext();
            ctx.setName(contextPath);
            ctx.setPath(contextPath);
            ctx.setDocBase(webappFilePath);
            ctx.setRealm(host.getRealm());
            // We dont need to init the DefaultWebXML since we maintain a web.xml file for a carbon server.
            // hence removing ctx.addLifecycleListener(new Tomcat.DefaultWebXmlListener()); code
            if (lifecycleListener != null) {
                ctx.addLifecycleListener(lifecycleListener);
            }
            SCIRegistrarContextConfig sciRegistrarContextConfig = new SCIRegistrarContextConfig();
            ctx.addLifecycleListener(sciRegistrarContextConfig);
            ctx.addServletContainerInitializer(new JasperInitializer(), null);

            // Set global webXml to this context
            if (new File(globalWebXml).exists()) {
                sciRegistrarContextConfig.setDefaultWebXml(globalWebXml);
            } else {
                sciRegistrarContextConfig.setDefaultWebXml("org/apache/catalin/startup/NO_DEFAULT_XML");
            }

            if (new File(globalContextXml).exists()) {
                ((StandardContext) ctx).setDefaultContextXml(globalContextXml);
            }

            File f = new File(webappFilePath);
            //During dir based webapp deployment
            if (f.isDirectory()) {
                File cf = new File(webappFilePath + File.separator + Constants.ApplicationContextXml);
                if (cf.exists()) {
                    ctx.setConfigFile(cf.toURI().toURL());
                }
            } else {
                // Check for embedded contextXml file in this webapp
                webappJarFile = new JarFile(webappFilePath);
                contextXmlFileEntry = webappJarFile.getJarEntry(Constants.ApplicationContextXml);
                if (contextXmlFileEntry != null) {
                    ctx.setConfigFile(new URL("jar:file:" + URLEncoder.encode(webappFilePath, "UTF-8") + "!/" +
                            Constants.ApplicationContextXml));
                }
            }
            if (ctx instanceof StandardContext) {
                ((StandardContext) ctx).setClearReferencesStopTimerThreads(true);
            }
            if (host == null) {
                host = this.getHost();
            }
            host.addChild(ctx);
            if (ctx.getState().equals(LifecycleState.STOPPED)) {
                ctx.setRealm(null);
                ctx.destroy();
                throw new Exception("Webapp failed to deploy, Lifecycle state is STOPPED");
            }
            if (log.isDebugEnabled()) {
                log.debug("Webapp context: " + ctx);
            }
        } catch (Exception e) {
            //since any exception can be thrown from Lifecycles, "Exception" is been caught.
            removeContext = true;
            throw new CarbonTomcatException("Webapp failed to deploy", e);
        } finally {
            if (removeContext && ctx != null && host != null) {
                ctx.setRealm(null);
                try {
                    if (!ctx.getState().equals(LifecycleState.STOPPED)) {
                        ctx.stop();
                    }
                } catch (LifecycleException e) {
                    log.error("Cannot stop context ", e);
                }
                host.removeChild(ctx);
                log.error("Webapp " + ctx + " failed to deploy");
            }
            if (webappJarFile != null) {
                try {
                    webappJarFile.close();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
            }
        }
        return ctx;
    }

    @Override
    public Tomcat getTomcat() {
        return this;
    }


    /**
     * getting port value by giving the connector scheme. we only support 'http' and 'https'
     * schemes
     *
     * @param scheme this value is http or https
     * @return port value of the scheme, -1 if the matching scheme not found
     */
    @Override
    public int getPort(String scheme) {
        for (Connector connector : this.getService().findConnectors()) {
            if (connector.getScheme().equals(scheme)) {
                return connector.getPort();
            }
        }
        return -1;
    }

    /**
     * getting proxy port value by giving the connector scheme. we only support 'http' and 'https'
     * schemes
     *
     * @param scheme this value is http or https
     * @return port value of the scheme, -1 if the matching scheme not found
     */
    @Override
    public int getProxyPort(String scheme) {
        for (Connector connector : this.getService().findConnectors()) {
            if (connector.getScheme().equals(scheme)) {
                return connector.getProxyPort();
            }
        }
        return -1;
    }


    /**
     * starting the connectors. We have overridden the CatalinaService. It doesn't start the connectors during
     * Engine startup
     *
     * @deprecated use {@link #startConnectors(int)} instead.
     *
     * @param portOffset that to be set while starting connectors
     */
    @Override
    @Deprecated
    public void startConnectors(int portOffset, String keyPass, String keyStorePass, String keyStoreFile) {
        //getting the list of connectors bound to this tomcat instance

        Connector[] connectors = this.getService().findConnectors();
        for (Connector connector : connectors) {
            try {
                int currentPort = connector.getPort();
                connector.setPort(currentPort + portOffset);
                if (connector.getProtocolHandler() instanceof Http11NioProtocol) {
                    ((Http11NioProtocol) connector.getProtocolHandler()).setKeyPass(keyPass);
                    ((Http11NioProtocol) connector.getProtocolHandler()).setKeystorePass(keyStorePass);
                    ((Http11NioProtocol) connector.getProtocolHandler()).setKeystoreFile(keyStoreFile);

                }
                StandardVirtualThreadExecutor standardVirtualThreadExecutor = new StandardVirtualThreadExecutor();
                standardVirtualThreadExecutor.start();
                connector.getProtocolHandler().setExecutor(standardVirtualThreadExecutor);
                connector.start();
                if (log.isDebugEnabled()) {
                    log.debug("staring the tomcat connector : " + connector.getProtocol());
                }
            } catch (LifecycleException e) {
                log.error("LifeCycleException while starting tomcat connector", e);
            }
        }
    }

    @Override
    public void startConnectors(int portOffset) {
        //getting the list of connectors bound to this tomcat instance
        Connector[] connectors = this.getService().findConnectors();
        for (Connector connector : connectors) {
            try {
                String isRandomPort = System.getProperty(CarbonConstants.TOMCAT_RANDOM_PORT_ENABLE);
                if (isRandomPort != null && isRandomPort.equals("true")) {
                    connector.setPort(findFreePort());
                    connector.setProxyPort(connector.getProxyPort() + portOffset);
                } else {
                    String portNumber = System.getProperty("tomcat." + connector.getScheme() + ".port");
                    if (portNumber != null) {
                        connector.setPort(Integer.parseInt(portNumber));
                        connector.setProxyPort(connector.getProxyPort() + portOffset);
                    } else {
                        int currentPort = connector.getPort();
                        connector.setPort(currentPort + portOffset);
                    }
                }
                StandardVirtualThreadExecutor standardVirtualThreadExecutor = new StandardVirtualThreadExecutor();
                standardVirtualThreadExecutor.start();
                connector.getProtocolHandler().setExecutor(standardVirtualThreadExecutor);
                connector.start();
                if (log.isDebugEnabled()) {
                    log.debug("staring the tomcat connector : " + connector.getProtocol());
                }
            } catch (LifecycleException e) {
                log.error("LifeCycleException while starting tomcat connector", e);
            }
        }
    }

    /**
     * starting the connectors. We have overridden the CatalinaService. It doesn't start the connectors during
     * Engine startup
     *
     * @param scheme     e.g: http|https.
     * @param portOffset that to be set while starting connectors
     */
    @Override
    public void startConnector(String scheme, int portOffset) {
        //getting the list of connectors bound to this tomcat instance
        Connector[] connectors = this.getService().findConnectors();
        for (Connector connector : connectors) {
            if (connector.getScheme().equals(scheme)) {
                try {
                    String isRandomPort = System.getProperty(CarbonConstants.TOMCAT_RANDOM_PORT_ENABLE);
                    if (isRandomPort != null && isRandomPort.equals("true")) {
                        connector.setPort(findFreePort());
                        connector.setProxyPort(connector.getProxyPort() + portOffset);
                    } else {
                        String portNumber = System.getProperty("tomcat." + connector.getScheme() + ".port");
                        if (portNumber != null) {
                            connector.setPort(Integer.parseInt(portNumber));
                            connector.setProxyPort(connector.getProxyPort() + portOffset);
                        } else {
                            int currentPort = connector.getPort();
                            connector.setPort(currentPort + portOffset);
                        }
                    }
                    StandardVirtualThreadExecutor standardVirtualThreadExecutor = new StandardVirtualThreadExecutor();
                    standardVirtualThreadExecutor.start();
                    connector.getProtocolHandler().setExecutor(standardVirtualThreadExecutor);
                    connector.start();
                    if (log.isDebugEnabled()) {
                        log.debug("staring the tomcat connector : " + connector.getProtocol());
                    }
                } catch (LifecycleException e) {
                    log.error("LifeCycleException while starting tomcat connector", e);
                }
            }
        }
    }

    /**
     * stopping all the existing catalina connectors
     */
    @Override
    public void stopConnectors() {
        //getting the list of connectors bound to this tomcat instance
        Connector[] connectors = this.getService().findConnectors();
        for (Connector connector : connectors) {
            try {
                connector.stop();
                if (log.isDebugEnabled()) {
                    log.debug("stopping the tomcat connector : " + connector.getProtocol());
                }
            } catch (LifecycleException e) {
                log.error("LifeCycleException while starting tomcat connector", e);
            }
        }
    }

    /**
     * stopping all the existing catalina connectors
     *
     * @param scheme e.g: http|https
     */
    @Override
    public void stopConnector(String scheme) {
        //getting the list of connectors bound to this tomcat instance
        Connector[] connectors = this.getService().findConnectors();
        for (Connector connector : connectors) {
            if (connector.getScheme().equals(scheme)) {
                try {
                    connector.stop();
                    if (log.isDebugEnabled()) {
                        log.debug("stopping the tomcat connector : " + connector.getProtocol());
                    }
                } catch (LifecycleException e) {
                    log.error("LifeCycleException while stopping tomcat connector", e);
                }
            }
        }
    }

    /**
     * check if unpack wars enabled
     *
     * @return true if enabled.
     */
    public boolean isUnpackWARs() {
        StandardHost standardHost = (StandardHost) this.getHost();
        return standardHost.isUnpackWARs();
    }

    /**
     * Returns true if the specified port is available on this host.
     *
     * @param port the port to check
     * @return true if the port is available, false otherwise
     */
    private static boolean available(final int port) {
        ServerSocket serverSocket = null;
        DatagramSocket dataSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            dataSocket = new DatagramSocket(port);
            dataSocket.setReuseAddress(true);
            return true;
        } catch (final IOException e) {
            return false;
        } finally {
            if (dataSocket != null) {
                dataSocket.close();
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (final IOException e) {
                    log.warn("Error occurred while closing the " + port + " port.");
                }
            }
        }
    }

    private static int findFreePort() {
        int i = 30000 + (int) (Math.random() * ((10000) + 1));
        if (available(i)) {
            return i;
        } else {
            return findFreePort();
        }
    }

    /**
     * we want get access {@link org.apache.catalina.startup.Catalina#createStartDigester()} method,
     * to configure our <pre>Tomcat</pre> instance using catalina-server.xml
     */
    private static class ExtendedCatalina extends Catalina {

        @Override
        public Digester createStartDigester() {
            return super.createStartDigester();
        }

    }
}



