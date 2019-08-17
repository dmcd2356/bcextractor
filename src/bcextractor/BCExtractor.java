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

  private static NetworkServer  netServer;
  private static Visitor        makeConnection;
  private static File           jarFile;
  private static String         clientPort;
  private static String         projectName;
  private static String         projectPathName;
  private static String         className;
  private static String         methName;
  private static String         classFolder;        // relative path to store class files
  private static String         javapFolder;        // relative path to store javaps files
  private static String         logfileFolder;      // relative path to store log file
  
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
    jarFile = null;
    int port = 6000;
    if (args.length > 0) {
      try {
        port = Integer.parseUnsignedInt(args[0]);
      } catch (NumberFormatException ex) {
        System.err.println("Invalid port selection " + args[0] + " - using " + port);
      }
    }

    // start the server
    startServer(port);
    
    // start the extractor parser
    ParserThread parser = new ParserThread();
    parser.start();
  }
  
  private static class ParserThread extends Thread {
    
    /**
     * the run process for solving the constraints in the database
     */
    @Override
    public void run() {
      while (true) {
        String message = netServer.getNextMessage();
        parseInput(message);
      }
    }

  }

  private static int parseInput(String message) {
    // parse input
    message = message.trim();
    
    // check for execute command
    if (message.equals("GET_BYTECODE")) {
      if (jarFile == null || className == null) {
        Utils.msgLogger(Utils.LogType.ERROR, "parseInput: incomplete or invalid settings");
        return 0;
      }

      // run javap to generate the bytecode source and save output in file
      String content = generateJavapFile(className  + ".class");
      if (content != null) {
        String fname = projectPathName + javapFolder + className + ".txt";
        Utils.saveTextFile(fname, content);
      }
      return 1;
    }
    
    // else parse the command
    int offset = message.indexOf(":");
    if (offset <= 0) {
      Utils.msgLogger(Utils.LogType.ERROR, "parseInput: Invalid entry received: " + message);
    } else {
      String key   = message.substring(0, offset).trim();
      String value = message.substring(offset + 1).trim();
      switch (key) {
        case "PATHLOG":
          logfileFolder = value;
          break;
        case "PATHCLASS":
          classFolder = value;
          break;
        case "PATHJAVAP":
          javapFolder = value;
          break;
        case "CLASS":
          className = value;
          break;
        case "METHOD":
          methName = value;
          break;
        case "JAR":
          // check for valid jar file
          jarFile = new File(value);
          Utils.msgLogger(Utils.LogType.INFO, "parser: <jarFile> = " + value);
          if (jarFile.isFile()) {
            projectName = jarFile.getName();
            projectPathName = jarFile.getParentFile().getAbsolutePath() + "/";
          } else {
            System.err.println("jar file not found - " + value);
            jarFile = null;
          }

          // setup the message logger file
          String logpathname = projectPathName + logfileFolder;
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
          break;
        default:
          Utils.msgLogger(Utils.LogType.ERROR, "parseInput: Invalid key received: " + key);
          break;
      }
    }
    return 0;
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
  
  private static String generateJavapFile(String classSelect) {
    if (!jarFile.isFile()) {
      System.err.println("jar file not found - " + jarFile.getAbsolutePath());
      return null;
    }
    
    // add the suffix
    Utils.msgLogger(Utils.LogType.INFO, "generateJavapFile: processing Bytecode for: " + classSelect);
    
    // if we don't already have the class file, extract it from the jar file
    String clsname = projectPathName + classFolder + classSelect;
    File classFile = new File(clsname);
    if (classFile.isFile()) {
      Utils.msgLogger(Utils.LogType.INFO, "generateJavapFile: " + classSelect + " already exists");
    } else {
      try {
        // extract the selected class file
        int rc = extractClassFile(classSelect);
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

  private static int extractClassFile(String classSelect) throws IOException {
    if (!jarFile.isFile()) {
      System.err.println("jar file not found - " + jarFile.getAbsolutePath());
      return -1;
    }
    
    // get the path relative to the application directory
    int offset;
    String relpathname = "";
    offset = classSelect.lastIndexOf('/');
    if (offset > 0)  {
      relpathname = classSelect.substring(0, offset + 1);
      classSelect = classSelect.substring(offset + 1);
    }
    
    Utils.msgLogger(Utils.LogType.INFO, "extractClassFile: '" + classSelect + "' from " + relpathname);
    
    JarFile jar = new JarFile(jarFile.getAbsoluteFile());
    Enumeration enumEntries = jar.entries();

    // look for specified class file in the jar
    while (enumEntries.hasMoreElements()) {
      JarEntry file = (JarEntry) enumEntries.nextElement();
      String fullname = file.getName();

      if (fullname.equals(relpathname + classSelect)) {
        String fullpath = projectPathName + classFolder + relpathname;
        File fout = new File(fullpath + classSelect);
        // skip if file already exists
        if (fout.isFile()) {
          Utils.msgLogger(Utils.LogType.INFO, "extractClassFile: File '" + classSelect + "' already created");
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
    
    Utils.msgLogger(Utils.LogType.ERROR, "extractClassFile: '" + classSelect + "' is not contained in jar file");
    return -1;
  }

}
