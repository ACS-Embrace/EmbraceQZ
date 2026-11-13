/**
 * @author Robert Casto
 *
 * Copyright (C) 2016 Tres Finocchiaro, QZ Industries, LLC
 *
 * LGPL 2.1 This is free software.  This software and source code are released under
 * the "LGPL 2.1 License".  A copy of this license should be distributed with
 * this software. http://www.gnu.org/licenses/lgpl-2.1.html
 */

package qz.ws;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.server.pathmap.ServletPathSpec;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.common.Constants;
import qz.common.TrayManager;
import qz.installer.Installer;
import qz.installer.certificate.*;
import qz.utils.ArgParser;
import qz.utils.ArgValue;
import qz.utils.FileUtilities;
import qz.utils.SystemUtilities;

import javax.swing.*;
import java.io.*;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by robert on 9/9/2014.
 */

public class PrintSocketServer {

    private static final Logger log = LoggerFactory.getLogger(PrintSocketServer.class);

    private static final int MAX_MESSAGE_SIZE = Integer.MAX_VALUE;
    public static final List<Integer> SECURE_PORTS = Collections.unmodifiableList(Arrays.asList(Constants.WSS_PORTS));
    public static final List<Integer> INSECURE_PORTS = Collections.unmodifiableList(Arrays.asList(Constants.WS_PORTS));

    private static final AtomicInteger securePortIndex = new AtomicInteger(0);
    private static final AtomicInteger insecurePortIndex = new AtomicInteger(0);

    private static TrayManager trayManager;
    private static CertificateManager certificateManager;

    private static boolean forceHeadless;


    public static void main(String[] args) {
        ArgParser parser = new ArgParser(args);
        if(parser.intercept()) {
            System.exit(parser.getExitCode());
        }
        forceHeadless = parser.hasFlag(ArgValue.HEADLESS);
        log.info(Constants.ABOUT_TITLE + " version: {}", Constants.VERSION);
        log.info(Constants.ABOUT_TITLE + " vendor: {}", Constants.ABOUT_COMPANY);
        log.info("Java version: {}", Constants.JAVA_VERSION.toString());
        log.info("Java vendor: {}", Constants.JAVA_VENDOR);
        setupFileLogging();

        try {
            // Gets and sets the SSL info, properties file
            certificateManager = Installer.getInstance().certGen(false);
            // Reoccurring (e.g. hourly) cert expiration check
            new ExpiryTask(certificateManager).schedule();
        } catch(Exception e) {
            log.error("Something went critically wrong loading HTTPS", e);
        }
        Installer.getInstance().addUserSettings();

        // Linux needs the cert installed in user-space on every launch for Chrome SSL to work
        if(!SystemUtilities.isWindows() && !SystemUtilities.isMac()) {
            NativeCertificateInstaller.getInstance().install(certificateManager.getKeyPair(KeyPairWrapper.Type.CA).getCert());
        }

        try {
            log.info("Starting {} {}", Constants.ABOUT_TITLE, Constants.VERSION);
            SwingUtilities.invokeAndWait(() -> trayManager = new TrayManager(forceHeadless));
            runServer();
        }
        catch(Exception e) {
            log.error("Could not start tray manager", e);
        }

        log.warn("The web socket server is no longer running");
    }

    /**
     * Sends QZ's own logging (the qz packages) to a rolling debug.log in the user folder, at DEBUG and above,
     * keeping 5 old files of 512KB each. Warnings and errors still go to the console appenders set up in log4j2.xml.
     */
    private static void setupFileLogging() {
        LoggerContext context = (LoggerContext)LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        String logFile = FileUtilities.USER_DIR + File.separator + Constants.LOG_FILE + ".log";

        RollingFileAppender fileAppender = RollingFileAppender.newBuilder()
                .setName("QZFile")
                .withFileName(logFile)
                .withFilePattern(logFile + ".%i")
                .withPolicy(SizeBasedTriggeringPolicy.createPolicy(String.valueOf(Constants.LOG_SIZE)))
                .withStrategy(DefaultRolloverStrategy.newBuilder()
                                      .withMin("1")
                                      .withMax(String.valueOf(Constants.LOG_ROTATIONS))
                                      .withFileIndex("min")
                                      .withConfig(config)
                                      .build())
                .setLayout(PatternLayout.newBuilder()
                                   .withPattern("%d{ISO8601} [%p] %m%n")
                                   .withCharset(StandardCharsets.UTF_8)
                                   .withConfiguration(config)
                                   .build())
                .setImmediateFlush(true)
                .setConfiguration(config)
                .build();
        fileAppender.start();
        config.addAppender(fileAppender);

        // Third-party libraries keep the root level from log4j2.xml; they reach the file at that level.
        config.getRootLogger().addAppender(fileAppender, null, null);

        // QZ's own classes log at DEBUG to the file, as they did under log4j 1, and only WARN and above to the console.
        LoggerConfig rootConfig = config.getRootLogger();
        LoggerConfig qzConfig = new LoggerConfig("qz", Level.DEBUG, false);
        qzConfig.addAppender(fileAppender, Level.DEBUG, null);
        for(Appender consoleAppender : rootConfig.getAppenders().values()) {
            if (consoleAppender != fileAppender) {
                qzConfig.addAppender(consoleAppender, Level.WARN, null);
            }
        }
        config.addLogger("qz", qzConfig);
        context.updateLoggers();
    }

    public static void runServer() {
        final AtomicBoolean running = new AtomicBoolean(false);
        while(!running.get() && securePortIndex.get() < SECURE_PORTS.size() && insecurePortIndex.get() < INSECURE_PORTS.size()) {
            Server server = new Server(getInsecurePortInUse());
            if (certificateManager != null) {
                // Bind the secure socket on the proper port number (i.e. 9341), add it as an additional connector
                SslConnectionFactory sslConnection = new SslConnectionFactory(certificateManager.configureSslContextFactory(), HttpVersion.HTTP_1_1.asString());
                HttpConnectionFactory httpConnection = new HttpConnectionFactory(new HttpConfiguration());

                ServerConnector connector = new ServerConnector(server, sslConnection, httpConnection);
                connector.setHost(certificateManager.getProperties().getProperty("wss.host"));
                connector.setPort(getSecurePortInUse());
                server.addConnector(connector);
            } else {
                log.warn("Could not start secure WebSocket");
            }

            try {
                ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

                // Handle WebSocket connections
                WebSocketUpgradeFilter filter = WebSocketUpgradeFilter.configureContext(context);
                filter.addMapping(new ServletPathSpec("/"), new WebSocketCreator() {
                    @Override
                    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
                        return new PrintSocketClient();
                    }
                });
                filter.getFactory().getPolicy().setMaxTextMessageSize(MAX_MESSAGE_SIZE);

                // Handle HTTP landing page
                ServletHolder httpServlet = new ServletHolder(new HttpAboutServlet(certificateManager));
                httpServlet.setInitParameter("resourceBase","/");
                context.addServlet(httpServlet, "/");
                context.addServlet(httpServlet, "/json");

                server.setHandler(context);
                server.setStopAtShutdown(true);
                server.start();

                running.set(true);
                trayManager.setServer(server, running, securePortIndex, insecurePortIndex);
                log.info("Server started on port(s) " + TrayManager.getPorts(server));

                server.join();
            }
            catch(BindException | MultiException e) {
                //order of getConnectors is the order we added them -> insecure first
                if (server.getConnectors()[0].isFailed()) {
                    insecurePortIndex.incrementAndGet();
                }
                if (server.getConnectors().length > 1 && server.getConnectors()[1].isFailed()) {
                    securePortIndex.incrementAndGet();
                }

                //explicitly stop the server, because if only 1 port has an exception the other will still be opened
                try { server.stop(); }catch(Exception ignore) { ignore.printStackTrace(); }
            }
            catch(Exception e) {
                e.printStackTrace();
                trayManager.displayErrorMessage(e.getLocalizedMessage());
            }
        }
    }

    /**
     * Get the TrayManager instance for this SocketServer
     *
     * @return The TrayManager instance
     */
    public static TrayManager getTrayManager() {
        return trayManager;
    }

    public static int getSecurePortInUse() {
        return SECURE_PORTS.get(securePortIndex.get());
    }

    public static int getInsecurePortInUse() {
        return INSECURE_PORTS.get(insecurePortIndex.get());
    }

    public static Properties getTrayProperties() {
        return certificateManager == null ? null : certificateManager.getProperties();
    }

}
