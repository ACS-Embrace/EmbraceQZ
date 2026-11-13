package qz.ui;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import qz.ui.component.IconCache;
import qz.ui.component.LinkLabel;
import qz.utils.FileUtilities;
import qz.utils.SystemUtilities;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Created by Tres on 2/26/2015.
 */
public class LogDialog extends BasicDialog {

    private final int ROWS = 20;
    private final int COLS = 80;

    private JScrollPane logPane;
    private JTextArea logArea;

    private JButton clearButton;

    private WriterAppender logStream;


    public LogDialog(JMenuItem caller, IconCache iconCache) {
        super(caller, iconCache);
        initComponents();
    }

    public void initComponents() {
        LinkLabel logDirLabel = new LinkLabel(FileUtilities.USER_DIR + File.separator);
        logDirLabel.setLinkLocation(new File(FileUtilities.USER_DIR + File.separator));
        setHeader(logDirLabel);

        logArea = new JTextArea(ROWS, COLS);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        // TODO:  Fix button panel resizing issues
        clearButton = addPanelButton("Clear", IconCache.Icon.DELETE_ICON, KeyEvent.VK_L);
        clearButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                logArea.setText(null);
            }
        });

        logPane = new JScrollPane(logArea);
        setContent(logPane, true);
        setResizable(true);

        // add new appender to Log4J just for text area
        OutputStream textAreaStream = new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        logArea.append(String.valueOf((char)b));
                        logPane.getVerticalScrollBar().setValue(logPane.getVerticalScrollBar().getMaximum());
                    }
                });
            }
        };
        LoggerContext context = (LoggerContext)LogManager.getContext(false);
        logStream = WriterAppender.newBuilder()
                .setName("QZLogDialog")
                .setTarget(new OutputStreamWriter(textAreaStream, StandardCharsets.UTF_8))
                .setLayout(PatternLayout.newBuilder()
                                   .withPattern("[%p] %d{ISO8601} @ %c:%L%n	%m%n")
                                   .withConfiguration(context.getConfiguration())
                                   .build())
                .setFollow(true)
                .build();
        logStream.start();
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            logArea.setText(null);
            Configuration config = ((LoggerContext)LogManager.getContext(false)).getConfiguration();
            config.getRootLogger().addAppender(logStream, Level.TRACE, null);
            if (config.getLoggers().containsKey("qz")) {
                config.getLoggerConfig("qz").addAppender(logStream, Level.TRACE, null);
            }
            ((LoggerContext)LogManager.getContext(false)).updateLoggers();
        } else {
            LoggerContext context = (LoggerContext)LogManager.getContext(false);
            Configuration config = context.getConfiguration();
            config.getRootLogger().removeAppender(logStream.getName());
            if (config.getLoggers().containsKey("qz")) {
                config.getLoggerConfig("qz").removeAppender(logStream.getName());
            }
            context.updateLoggers();
        }

        super.setVisible(visible);
    }

}
