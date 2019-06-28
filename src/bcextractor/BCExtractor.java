/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bcextractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import util.CommandLauncher;
import util.Utils;

/**
 *
 * @author dmcd2356
 */
public class BCExtractor {

  // relative path location to output files (all are relative to the project path)
  private static final String OUTPUT_FOLDER = "danlauncher/";
  private static final String CLASSFILE_STORAGE = OUTPUT_FOLDER + "classes/";
  private static final String JAVAPFILE_STORAGE = OUTPUT_FOLDER + "javap/";
  private static final String LOGFILE_FOLDER    = OUTPUT_FOLDER + ".bcextractor/";

  private static NetworkServer   netServer;
  private static Visitor         makeConnection;
  private static String          clientPort;
  private static String          projectName;
  private static String          projectPathName;
  
  // allow ServerThread to indicate on panel when a connection has been made for TCP
  public interface Visitor {
    void showConnection(String connection);
    void resetConnection();
  }

  /**
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    // read arg list and process
    if (args.length < 3) {
      System.err.println("Missing arguments: require 3, found " + args.length);
      System.exit(1);
    }
    String portstr = args[0];
    String fname = args[1];
    String classSelect = args[2];

    // start the server
    int port;
    try {
      port = Integer.parseUnsignedInt(portstr);
      startServer(port);
    } catch (NumberFormatException ex) {
      System.err.println("Invalid port selection " + portstr);
      System.exit(1);
    }
    
    File jarfile = new File(fname);
    if (jarfile.isFile()) {
      projectName = jarfile.getName();
      projectPathName = jarfile.getParentFile().getAbsolutePath() + "/";
    } else {
      System.err.println("jar file not found - " + fname);
      System.exit(1);
    }

    // setup the message logger file
    String logpathname = projectPathName + LOGFILE_FOLDER;
    File logpath = new File(logpathname);
    String logfile = logpathname + "commands.log";
    if (logpath.isDirectory()) {
      new File(logfile).delete();
    } else {
      logpath.mkdirs();
    }
    Utils.msgLoggerInit(logfile, "message logger started for BCExtractor");
    
    Utils.msgLogger(Utils.LogType.INFO, "processing jar file: " + projectName);
    Utils.msgLogger(Utils.LogType.INFO, "projectPathName: " + projectPathName);

    // run javap to generate the bytecode source and save output in file
    String content = generateJavapFile(jarfile, classSelect  + ".class");
    if (content == null) {
      System.exit(1);
    }
    fname = projectPathName + JAVAPFILE_STORAGE + classSelect + ".txt";
    Utils.saveTextFile(fname, content);
  }

  private static void startServer(int port) {
    makeConnection = new Visitor() {
      @Override
      public void showConnection(String connection) {
        clientPort = connection;
        Utils.msgLogger(Utils.LogType.INFO, "port " + clientPort + " - CONNECTED");
      }

      @Override
      public void resetConnection() {
        Utils.msgLogger(Utils.LogType.INFO, "port " + clientPort + " - CLOSED");
      }
    };

    // start the server
    try {
      Utils.msgLogger(Utils.LogType.INFO, "NetworkServer starting");
      netServer = new NetworkServer(port, makeConnection);
      netServer.start();
    } catch (IOException ex) {
      Utils.msgLogger(Utils.LogType.ERROR, "unable to start NetworkServer. " + ex);
    }
  }
  
  private static String generateJavapFile(File jarfile, String classSelect) {
    // add the suffix
    Utils.msgLogger(Utils.LogType.INFO, "generateJavapFile: processing Bytecode for: " + classSelect);
    
    // if we don't already have the class file, extract itfrom the jar file
    String clsname = projectPathName + CLASSFILE_STORAGE + classSelect;
    File classFile = new File(clsname);
    if (classFile.isFile()) {
      Utils.msgLogger(Utils.LogType.INFO, "generateJavapFile: " + classSelect + " already exists");
    } else {
      try {
        // extract the selected class file
        int rc = extractClassFile(jarfile, classSelect);
        if (rc != 0) {
          return null;
        }
      } catch (IOException ex) {
        Utils.printStatusError(ex.getMessage());
        return null;
      }
    }

    Utils.msgLogger(Utils.LogType.INFO, "START - CommandLauncher: Generating javap file for: " + classSelect);
    
    // decompile the selected class file
    String[] command = { "javap", "-p", "-c", "-s", "-l", clsname };
    // this creates a command launcher that runs on the current thread
    CommandLauncher commandLauncher = new CommandLauncher();
    int retcode = commandLauncher.start(command, projectPathName);
    if (retcode != 0) {
      Utils.msgLogger(Utils.LogType.ERROR, "generateJavapFile failed for: " + classSelect);
      return null;
    }

    // success - save the output as a file
    Utils.msgLogger(Utils.LogType.INFO, "generateJavapFile - COMPLETED");
    String content = commandLauncher.getResponse();
    return content;
  }

  private static int extractClassFile(File jarfile, String className) throws IOException {
    // get the path relative to the application directory
    int offset;
    String relpathname = "";
    offset = className.lastIndexOf('/');
    if (offset > 0)  {
      relpathname = className.substring(0, offset + 1);
      className = className.substring(offset + 1);
    }
    
    Utils.msgLogger(Utils.LogType.INFO, "extractClassFile: '" + className + "' from " + relpathname);
    
    // get the location of the jar file (where we will extract the class files to)
    String jarpathname = jarfile.getAbsolutePath();
    offset = jarpathname.lastIndexOf('/');
    if (offset > 0) {
      jarpathname = jarpathname.substring(0, offset + 1);
    }
    
    JarFile jar = new JarFile(jarfile.getAbsoluteFile());
    Enumeration enumEntries = jar.entries();

    // look for specified class file in the jar
    while (enumEntries.hasMoreElements()) {
      JarEntry file = (JarEntry) enumEntries.nextElement();
      String fullname = file.getName();

      if (fullname.equals(relpathname + className)) {
        String fullpath = jarpathname + CLASSFILE_STORAGE + relpathname;
        File fout = new File(fullpath + className);
        // skip if file already exists
        if (fout.isFile()) {
          Utils.msgLogger(Utils.LogType.INFO, "extractClassFile: File '" + className + "' already created");
        } else {
          // make sure to create the entire dir path needed
          File relpath = new File(fullpath);
          if (!relpath.isDirectory()) {
            relpath.mkdirs();
          }

          // extract the file to its new location
          InputStream istream = jar.getInputStream(file);
          FileOutputStream fos = new FileOutputStream(fout);
          while (istream.available() > 0) {
            // write contents of 'istream' to 'fos'
            fos.write(istream.read());
          }
        }
        Utils.msgLogger(Utils.LogType.INFO, "extractClassFile: COMPLETED");
        return 0;
      }
    }
    
    Utils.msgLogger(Utils.LogType.ERROR, "extractClassFile: '" + className + "' is not contained in jar file");
    return -1;
  }
  
}
