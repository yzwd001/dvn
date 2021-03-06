/*
   Copyright (C) 2005-2013, by the President and Fellows of Harvard College.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

   Dataverse Network - A web application to share, preserve and analyze research data.
   Developed at the Institute for Quantitative Social Science, Harvard University.
   Version 3.0.
*/

package edu.harvard.iq.dvn.ingest.statdataio.impl.plugins.rdata;

/*
 * @author Matt Owen
 * @date 02-20-2013
 */
import java.io.*;
import java.text.*;
import java.util.logging.*;
import java.util.*;
import java.security.NoSuchAlgorithmException;

// Rosunda's Wrappers and Methods for R-calls to Rserve
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.RList;
import org.rosuda.REngine.Rserve.RFileInputStream;
import org.rosuda.REngine.Rserve.RFileOutputStream;
import org.rosuda.REngine.Rserve.*;

// DVN-made parts
import edu.harvard.iq.dvn.ingest.org.thedata.statdataio.*;
import edu.harvard.iq.dvn.ingest.org.thedata.statdataio.spi.*;
import edu.harvard.iq.dvn.ingest.org.thedata.statdataio.metadata.*;
import edu.harvard.iq.dvn.ingest.org.thedata.statdataio.data.*;
import edu.harvard.iq.dvn.ingest.statdataio.impl.plugins.util.*;
import edu.harvard.iq.dvn.rserve.*;
import edu.harvard.iq.dvn.unf.*;
import edu.harvard.iq.dvn.unf.UNF5Util;
import edu.harvard.iq.dvn.ingest.thedata.helpers.*;
import nesstar.util.FileUtils;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.ArrayUtils;
/**
 * A DVN-Project-implementation of <code>StatDataFileReader</code> for the 
 * RData Binary Format.
 * 
 * @author Matthew Owen
 * @date 02-11-2012
 *
 * This implementation uses R-Scripts to do the bulk of the processing.
 * @note The code is primarily based on the SAV and CSV file readers.
 */
public class RDATAFileReader extends StatDataFileReader {
  
  // Formats for R
  public static final int FORMAT_INTEGER = 0;
  public static final int FORMAT_NUMERIC = 1;
  public static final int FORMAT_STRING = -1;
  public static final int FORMAT_DATE = -2;
  public static final int FORMAT_DATETIME = -3;
  
  private int [] mFormatTable = null;
  
  // Date-time things
  public static final String[] FORMATS = { "other", "date", "date-time", "date-time-timezone" };

  // R-ingest recognition files
  private static final String[] FORMAT_NAMES = { "RDATA", "Rdata", "rdata" };
  private static final String[] EXTENSIONS = { "Rdata", "rdata" };
  private static final String[] MIME_TYPE = { "application/x-rlang-transport" };
  
  // R Scripts
  static private String RSCRIPT_CREATE_WORKSPACE;
  static private String RSCRIPT_DATASET_INFO_SCRIPT = "";
  static private String RSCRIPT_GET_DATASET = "";
  static private String RSCRIPT_GET_LABELS = "";
  static private String RSCRIPT_WRITE_DVN_TABLE = "";
  
  // RServe static variables
  private static String RSERVE_HOST = System.getProperty("vdc.dsb.host");
  private static String RSERVE_USER = System.getProperty("vdc.dsb.rserve.user");
  private static String RSERVE_PASSWORD = System.getProperty("vdc.dsb.rserve.pwrd");
  private static int RSERVE_PORT;
  
  public static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
  public static final String DSB_TEMP_DIR = System.getProperty("vdc.dsb.temp.dir");
  public static final String DVN_TEMP_DIR = null;
  public static final String WEB_TEMP_DIR = null;

  // DATE FORMATS
  private static SimpleDateFormat[] DATE_FORMATS = new SimpleDateFormat[] {
    new SimpleDateFormat("yyyy-MM-dd")
  };
  
  // TIME FORMATS
  private static SimpleDateFormat[] TIME_FORMATS = new SimpleDateFormat[] {
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z"),
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"),
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z"),
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  };
  
  // Logger
  private static final Logger LOG = Logger.getLogger(RDATAFileReader.class.getPackage().getName());

  // UNF Version
  private static final String unfVersionNumber = "5";
  
  // DataTable
  private DataTable mDataTable = new DataTable();
  
  // Specify which are decimal values
  Set <Integer> mDecimalVariableSet = new HashSet <Integer>(); 
  
  private Map <String, String> mPrintFormatTable = new LinkedHashMap<String, String>(); 
  private Map <String, String> mPrintFormatNameTable = new LinkedHashMap<String, String>(); 
  private List <Integer> mPrintFormatList = new ArrayList<Integer>();
  /*
   * Object Variables
   */
  
  // Number of observations
  private int mCaseQuantity = 0;
  
  // Number of variables
  private int mVarQuantity = 0;
  
  // Array specifying column-wise data-types
  private String [] mDataTypes;
  
  // Process ID, used partially in the generation of temporary directories
  private String mPID;
  
  // Object containing all the informatin for an R-workspace (including
  // temporary directories on and off server)
  private RWorkspace mRWorkspace;

  // Names of variables
  private List <String> variableNameList = new ArrayList <String> ();

  // The meta-data object
  SDIOMetadata smd = new RDATAMetadata();
  
  // Entries from TAB Data File
  DataTable mCsvDataTable = null;
  SDIOData sdiodata = null;

  // Number formatter
  NumberFormat doubleNumberFormatter = new DecimalFormat();

  // Builds R Requests for an R-server
  private RRequestBuilder mRequestBuilder;
  /*
   * Initialize Static Variables
   * This is primarily to construct the R-Script
   */
  static {
    /*
     * Set defaults fallbacks for class properties
     */
    if (RSERVE_HOST == null)
      RSERVE_HOST = "vdc-build.hmdc.harvard.edu";

    if (RSERVE_USER == null)
      RSERVE_USER = "rserve";

    if (RSERVE_PASSWORD == null)
      RSERVE_PASSWORD = "rserve";

    if (System.getProperty("vdc.dsb.rserve.port") == null)
      RSERVE_PORT = 6311;
    else
      RSERVE_PORT = Integer.parseInt(System.getProperty("vdc.dsb.rserve.port"));

    /*
     * Create Rscript to compute UNF
     */
    
    // Load R Scripts into memory, so that we can run them via R-serve
    RSCRIPT_WRITE_DVN_TABLE = readLocalResource("scripts/write.dvn.table.R");
    RSCRIPT_GET_DATASET = readLocalResource("scripts/get.dataset.R");
    RSCRIPT_CREATE_WORKSPACE = readLocalResource("scripts/create.workspace.R");
    RSCRIPT_GET_LABELS = readLocalResource("scripts/get.labels.R");
    RSCRIPT_DATASET_INFO_SCRIPT = readLocalResource("scripts/dataset.info.script.R");
    
    
    LOG.finer("R SCRIPTS AS STRINGS --------------");
    LOG.finer(RSCRIPT_WRITE_DVN_TABLE);
    LOG.finer(RSCRIPT_GET_DATASET);
    LOG.finer(RSCRIPT_CREATE_WORKSPACE);
    LOG.finer(RSCRIPT_GET_LABELS);
    LOG.finer(RSCRIPT_DATASET_INFO_SCRIPT);
    LOG.finer("END OF R SCRIPTS AS STRINGS -------");
   }
  /**
   * Helper Object to Handle Creation and Destruction of R Workspace
   */
  private class RWorkspace {
    public String mParent, mWeb, mDvn, mDsb;
    public File mDataFile, mCsvDataFile;
    public RRequest mRRequest;
    public BufferedInputStream mInStream;
    /**
     * 
     */
    public RWorkspace () {
      mParent = mWeb = mDvn = mDsb = "";
      mDataFile = null;
      mCsvDataFile = null;
      mInStream = null;
    }
    /**
     * Create the Actual R Workspace
     */
    public void create () {
      try {
        LOG.info("RDATAFileReader: Creating R Workspace");
        
        REXP result = mRequestBuilder
                .script(RSCRIPT_CREATE_WORKSPACE)
                .build()
                .eval();
        
        RList directoryNames = result.asList();
        
        mParent = directoryNames.at("parent").asString();
        
        LOG.info(String.format("RDATAFileReader: Parent directory of R Workspace is %s", mParent));
        
        LOG.info("RDATAFileReader: Creating file handle");
        
        mDataFile = new File(mParent, "data.Rdata");
      }
      catch (Exception E) {
        LOG.warning("RDATAFileReader: Could not create R workspace");
        mParent = mWeb = mDvn = mDsb = "";
      }
    }
    /**
     * Destroy the Actual R Workspace
     */
    public void destroy () {
      String destroyerScript = new StringBuilder("")
              .append(String.format("unlink(\"%s\", TRUE, TRUE)", mParent))
              .toString();
      
      try {
        LOG.info("RDATAFileReader: Destroying R Workspace");

        mRRequest = mRequestBuilder
                .script(destroyerScript)
                .build();
        
        mRRequest.eval();
        
        LOG.info("RDATAFileReader: DESTROYED R Workspace");
      }
      catch (Exception ex) {
        LOG.warning("RDATAFileReader: R Workspace was not destroyed");
        LOG.info(ex.getMessage());
      }
    }
    /**
     * Create the Data File to Use for Analysis, etc.
     */
    public File dataFile (String target, String prefix, int size) {
      
      String fileName = String.format("DVN.dataframe.%s.Rdata", mPID);
      
      mDataFile = new File(mParent, fileName);
                
      RFileInputStream RInStream = null;
      OutputStream outStream = null;
      
      RRequest req = mRequestBuilder.build();
      
      try {
        outStream = new BufferedOutputStream(new FileOutputStream(mDataFile));
        RInStream = req.getRConnection().openFile(target);
        
        if (size < 1024*1024*500) {
          int bufferSize = size;
          byte [] outputBuffer = new byte[bufferSize];
          RInStream.read(outputBuffer);
          outStream.write(outputBuffer, 0, size);
        }
        
        RInStream.close();
        outStream.close();
        return mDataFile;
      }
      catch (FileNotFoundException exc) {
        exc.printStackTrace();
        LOG.warning("RDATAFileReader: FileNotFound exception occurred");
        return mDataFile;
      }
      catch (IOException exc) {
        exc.printStackTrace();
        LOG.warning("RDATAFileReader: IO exception occurred");
      }

      // Close R input data stream
      if (RInStream != null) {
        try {
          RInStream.close();
        }
        catch (IOException exc) {
        }
      }

      // Close output data stream
      if (outStream != null) {
        try {
          outStream.close();
        }
        catch (IOException ex) {
        }
      }
      
      return mDataFile;
    }
    /**
     * Set the stream
     * @param inStream 
     */
    public void stream (BufferedInputStream inStream) {
      mInStream = inStream;
    }
    /**
     * Save the Rdata File Temporarily
     */
    private File saveRdataFile () {
      LOG.info("RDATAFileReader: Saving Rdata File from Input Stream");
      
      if (mInStream == null) {
        LOG.info("RDATAFileReader: No input stream was specified. Not writing file and returning NULL");
        return null;
      }
      
      byte [] buffer = new byte [1024];
      int bytesRead = 0;
      RFileOutputStream outStream = null;
      RConnection rServerConnection = null;
      
      try {
        LOG.info("RDATAFileReader: Opening R connection");
        rServerConnection = new RConnection(RSERVE_HOST, RSERVE_PORT);
        
        LOG.info("RDATAFileReader: Logging into R connection");
        rServerConnection.login(RSERVE_USER, RSERVE_PASSWORD);
        
        LOG.info("RDATAFileReader: Attempting to create file");
        outStream = rServerConnection.createFile(mDataFile.getAbsolutePath());
        
        LOG.info(String.format("RDATAFileReader: File created on server at %s", mDataFile.getAbsolutePath()));
      }
      catch (IOException ex) {
        LOG.warning("RDATAFileReader: Could not create file on R Server");
      }
      catch (RserveException ex) {
        LOG.warning("RDATAFileReader: Could not connect to R Server");
      }
      
      /*
       * Read stream and write to destination file
       */
      try {
        // Read from local file and write to rserver 1kb at a time
        while (mInStream.read(buffer) != -1) {
          outStream.write(buffer);
          bytesRead++;
        }
      }
      catch (IOException ex) {
        LOG.warning("RDATAFileReader: Could not write to file");
        LOG.info(String.format("Error message: %s", ex.getMessage()));
      }
      catch (NullPointerException ex) {
        LOG.warning("RDATAFileReader: Data file has not been specified");
      }
      
      // Closing R server connection
      if (rServerConnection != null) {
        LOG.info("RDATAFileReader: Closing R server connection");
        rServerConnection.close();
      }
      
      return mDataFile;
    }
    private File saveCsvFile () {
      mCsvDataFile = new File(mRWorkspace.getRdataFile().getParent(), "data.csv");
      
      String csvScript = new StringBuilder("")
        .append("options(digits.secs=3)\n")
        .append("\n")
        .append(RSCRIPT_WRITE_DVN_TABLE)
        .append("\n")
        .append(String.format("load(\"%s\")\n", mRWorkspace.getRdataAbsolutePath()))
        .append(RSCRIPT_GET_DATASET)
        .append("\n")
        .append(String.format("write.dvn.table(data.set, file=\"%s\")", mCsvDataFile.getAbsolutePath()))
        .toString();
      
      RRequest csvRequest = mRequestBuilder.build();
      
      LOG.info(String.format("RDATAFileReader: Attempting to write table to `%s`", mCsvDataFile.getAbsolutePath()));
      csvRequest.script(csvScript).eval();

      return mCsvDataFile;
    }
    /**
     * Return Rdata File Handle on R Server
     * @return 
     */
    public File getRdataFile () {
      return mDataFile;
    }
    /**
     * Return Location of Rdata File on R Server
     * @return the file location as a string on the (potentially) remote R server
     */
    public String getRdataAbsolutePath () {
      return mDataFile.getAbsolutePath();
    }
  }
  /**
   * Constructs a <code>RDATAFileReader</code> instance from its "Spi" Class
   * @param originator a <code>StatDataFileReaderSpi</code> object.
   */
  public RDATAFileReader(StatDataFileReaderSpi originator) {

    super(originator);
    
    

    LOG.info("RDATAFileReader: INSIDE RDATAFileReader");

    init();

    // Create request builder.
    // This object is used throughout as an RRequest factory
    mRequestBuilder = new RRequestBuilder()
            .host(RSERVE_HOST)
            .port(RSERVE_PORT)
            .user(RSERVE_USER)
            .password(RSERVE_PASSWORD);
    
    // Create R Workspace
    mRWorkspace = new RWorkspace();
    
    // 
    mCsvDataTable = new DataTable();
    
    // 
    mPID = RandomStringUtils.randomNumeric(6);
  }

  private void init(){
    doubleNumberFormatter.setGroupingUsed(false);
    doubleNumberFormatter.setMaximumFractionDigits(340);
  }
  /**
   * Read the Given RData File
   * @param stream a <code>BufferedInputStream</code>.
   * @param ignored
   * @return an <code>SDIOData</code> object
   * @throws java.io.IOException if a reading error occurs.
   */
  @Override
  public SDIOData read (BufferedInputStream stream, File dataFile) throws IOException {
    
    // Create Request object
    LOG.info("RDATAFileReader: Creating RRequest object from RRequestBuilder object");
    
    // Create R Workspace
    mRWorkspace.stream(stream);
    mRWorkspace.create();
    mRWorkspace.saveRdataFile();
    mRWorkspace.saveCsvFile();
    
    // Copy CSV file to a local, temporary directory
    // Additionally, this sets the "tabDelimitedDataFile" property of the FileInformation
    File localCsvFile = copyBackCsvFile(mRWorkspace.mCsvDataFile);
    
    // Save basic information about data set
    putFileInformation();
    
    CSVFileReader csvFileReader = new CSVFileReader('\t');
    BufferedReader localBufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(localCsvFile)));
    
    // int lineCount = csvFileReader.read(localBufferedReader, smd, null);
    File tabFileDestination = File.createTempFile("data-", ".tab");
    PrintWriter tabFileWriter = new PrintWriter(tabFileDestination.getAbsolutePath());
    
    // Additionally, this outputs to the tab-delimited file
    int lineCount = csvFileReader.read(localBufferedReader, smd, tabFileWriter);
    
    // This actually *sets* type lists more than it *gets* them
    getVariableTypeList(mDataTypes);
        
    // File Data Table
    mCsvDataTable = readTabDataFile(tabFileDestination);
    
    // Set meta information about format names, I guess.
    setMetaInfo();

    // Create UNF for each column
    createUNF(mCsvDataTable);
    
    //
    LOG.info("RDATAFileReader: varQnty = " + mVarQuantity);
    LOG.info("RDATAFileReader: Leaving \"read\" function");

    // Destroy R workspace
    mRWorkspace.destroy();
    
    // Return data properly stored
    return new SDIOData(smd, mCsvDataTable);
  }
  /**
   * Copy Remote File on R-server to a Local Target
   * @param target a target on the remote r-server
   * @return 
   */
  private File copyBackCsvFile (File target) {
    File destination;
    FileOutputStream csvDestinationStream;
    
    try {
      destination = File.createTempFile("data", ".csv");
      LOG.info(String.format("RDATAFileReader: Writing local CSV File to `%s`", destination.getAbsolutePath()));
      csvDestinationStream = new FileOutputStream(destination);
    }
    catch (IOException ex) {
      LOG.warning("RDATAFileReader: Could not create temporary file!");
      return null;
    }
    
    try {
      // Open connection to R-serve
      RConnection rServeConnection = new RConnection(RSERVE_HOST, RSERVE_PORT);
      rServeConnection.login(RSERVE_USER, RSERVE_PASSWORD);
      
      // Open file for reading from R-serve
      RFileInputStream rServeInputStream = rServeConnection.openFile(target.getAbsolutePath());
      
      int b;
      
      LOG.info("RDATAFileReader: Beginning to write to local destination file");
      
      // Read from stream one character at a time
      while ((b = rServeInputStream.read()) != -1) {
        // Write to the *local* destination file
        csvDestinationStream.write(b);
      }
      
      LOG.info(String.format("RDATAFileReader: Finished writing from destination `%s`", target.getAbsolutePath()));
      LOG.info(String.format("RDATAFileReader: Finished copying to source `%s`", destination.getAbsolutePath()));
      
      smd.getFileInformation().put("tabDelimitedDataFileLocation", destination.getAbsolutePath());
      
      LOG.info("RDATAFileReader: Closing CSVFileReader R Connection");
      rServeConnection.close();
    }
    /*
     * TO DO: Make this error catching more intelligent
     */
    catch (Exception ex) {
    }
    
    return destination;
  }
  /**
   * Put File Information into the Meta-data object
   * 
   * Runs an R-script that extracts meta-data from the *original* Rdata object, then 
   * 
   * @returnthe output writer
   * @throws IOException if something bad happens?
   */
  private void putFileInformation () {
    LOG.info("RDATAFileReader: Entering `putFileInformation` function");
    
    // Store variable names
    String [] variableNames = { };
    
    String parentDirectory = mRWorkspace.getRdataFile().getParent();
    
    String fileInfoScript = new StringBuilder("")
            .append(String.format("load(\"%s\")\n", mRWorkspace.getRdataAbsolutePath()))
            .append(String.format("setwd(\"%s\")\n", parentDirectory))
            .append(RSCRIPT_GET_DATASET)
            .append("\n")
            .append(RSCRIPT_DATASET_INFO_SCRIPT)
            .toString();
    
    Map <String, String> variableLabels = new LinkedHashMap <String, String> ();
    
    try {
      RRequest request = mRequestBuilder.build();
      request.script(fileInfoScript);
      RList fileInformation = request.eval().asList();
      
      int varQnty = 0;
      variableNames = fileInformation.at("varNames").asStrings();
      
      mDataTypes = fileInformation.at("dataTypes").asStrings();
      
      for (String varName : variableNames) {
        variableLabels.put(varName, varName);
        variableNameList.add(varName);
        varQnty++;
      }
      
      mCaseQuantity = fileInformation.at("caseQnty").asInteger();
      mVarQuantity = varQnty;

      smd.getFileInformation().put("varQnty", mVarQuantity);
      smd.getFileInformation().put("caseQnty", mCaseQuantity);
    }
    catch (REXPMismatchException ex) {
      LOG.warning("RDATAFileReader: Could not put information correctly");
    }
    catch (Exception ex) {
      ex.printStackTrace();
      LOG.warning(ex.getMessage());
    }
    
    // smd.getFileInformation().put("compressedData", true);
    smd.getFileInformation().put("charset", "UTF-8");
    smd.getFileInformation().put("mimeType", MIME_TYPE[0]);
    smd.getFileInformation().put("fileFormat", FORMAT_NAMES[0]);
    smd.getFileInformation().put("varFormat_schema", "RDATA");
    smd.getFileInformation().put("fileUNF", "");
    
    // Variable names
    smd.setVariableLabel(variableLabels);
    smd.setVariableName(variableNames);
  }
  /**
   * Read a Tabular Data File and create a "DataTable" Object
   * 
   * Returns a "DataTable" object that is a representation of the tabular data file. So sick.
   * 
   * @param tabFile a File object specifying the location of tabular data
   * @return a "DataTable" object representing the 
   * @throws IOException 
   */
  private DataTable readTabDataFile (File tabFile) throws IOException {
    DataTable tabData = new DataTable();
    Object[][] dataTable = null;
    
    dataTable = new Object[mVarQuantity][mCaseQuantity];

    String tabFileName = (String) smd.getFileInformation().get("tabDelimitedDataFileLocation");
    
    BufferedReader tabFileReader = new BufferedReader(new InputStreamReader(new FileInputStream(tabFileName)));

    boolean[] isCharacterVariable = smd.isStringVariable();

    String[] valueTokens = new String[mVarQuantity];

    for (int j = 0; j < mCaseQuantity; j++) {
      String line = tabFileReader.readLine();
      
      if (line == null) {
        String msg = String.format("Failed to read %d lines from tabular data file \"%s\"", mCaseQuantity, tabFileName);
        throw new IOException(msg);
      }
      
      valueTokens = line.split("\t", mVarQuantity);
      
      for ( int i = 0; i < mVarQuantity; i++ ) {
 
        // If it's a character variable but not a date
        if (isCharacterVariable[i]) {
          
          if (valueTokens[i].length() == 0)
            // If it is a missing value
            valueTokens[i] = null;
          else {
            // Otherwise parse it out
            valueTokens[i] = valueTokens[i].replaceFirst("^\"", "");
            valueTokens[i] = valueTokens[i].replaceFirst("\"$", "");
            valueTokens[i] = valueTokens[i].replaceAll("\\\\\"", "\"");
          }
          
          dataTable[i][j] = valueTokens[i];
        }
        // Otherwise, we don't care
        else {
          dataTable[i][j] = valueTokens[i];
        }
       
      }
    }

    // Close the file reader
    tabFileReader.close();
    
    // Convert dataTable to an actual "DataTable" object
    tabData.setData(dataTable);
    
    return tabData;
  }
  /**
   * Get Variable Type List
   * 
   * Categorize the columns of a data-set according to data-type. Returns a list
   * of integers corresponding to: (-1) String (0) Integer (1) Double-precision.
   * The numbers do not directly correspond with anything used by UNF5Util,
   * however this convention is seen throughout the DVN data-file readers.
   * 
   * This function essentially matches R data-types with those understood by
   * DVN:
   * * integer => "Integer"
   * * numeric (non-integer), double => "Double"
   * * Date => "Date"
   * * Other => "String"
   * 
   * @param dataTypes an array of strings where index corresponds to data-set
   * column and string corresponds to the class of the R-object.
   * @return 
   */
  private List<Integer> getVariableTypeList (String[] dataTypes) {
    //
    mFormatTable = new int [mVarQuantity];
    // Okay.
    List <Integer>
            minimalTypeList = new ArrayList<Integer>(),
            normalTypeList = new ArrayList<Integer>();
    
    Set <Integer> decimalVariableSet = new HashSet <Integer>(); 
    
    List <Boolean> isContinuous = new ArrayList<Boolean>();
    List <String> isString = new ArrayList<String>();
    
    
    int k = 0;
    
    for (String type : dataTypes) {
      // Log
      LOG.info("type[" + k + "] = " + type);
      
      // Convention is that integer is zero, right?
      if (type.equals("integer")) {
        minimalTypeList.add(0);
        normalTypeList.add(0);
        mFormatTable[k] = FORMAT_INTEGER;
      }

      // Double-precision data-types
      else if (type.equals("numeric") || type.equals("double")) {
        minimalTypeList.add(1);
        normalTypeList.add(0);
        decimalVariableSet.add(k);
        mFormatTable[k] = FORMAT_NUMERIC;
      }
      
      // If date
      else if (type.equals("Date")) {
        // 
        minimalTypeList.add(-1);
        normalTypeList.add(1);
        mFormatTable[k] = FORMAT_DATE;
      }
      
      else if (type.equals("POSIXct") || type.equals("POSIXlt") || type.equals("POSIXt")) {
        minimalTypeList.add(-1);
        normalTypeList.add(1);
        mFormatTable[k] = FORMAT_DATETIME;
      }

      // Everything else is a string
      else {
        minimalTypeList.add(-1);
        normalTypeList.add(1);
        mFormatTable[k] = FORMAT_STRING;
      }
      
      LOG.info(String.format("formatTable[%d] = %d", k, mFormatTable[k]));
      
      k++;
    }
    
    // Decimal Variables
    
    smd.setVariableTypeMinimal(ArrayUtils.toPrimitive(normalTypeList.toArray(new Integer[normalTypeList.size()])));
    smd.setDecimalVariables(decimalVariableSet);
    smd.setVariableStorageType(null);
    
    LOG.info("minimalTypeList = " + Arrays.deepToString(minimalTypeList.toArray()));
    LOG.info("normalTypeList = " + Arrays.deepToString(normalTypeList.toArray()));
    LOG.info("decimalVariableSet = " + Arrays.deepToString(decimalVariableSet.toArray()));

    // Return the variable type list
    return minimalTypeList;
  }
 /**
   * Create UNF from Tabular File
   * This methods iterates through each column of the supplied data table and
   * invoked the 
   * @param DataTable table a rectangular data table
   * @return void
   */
  private void createUNF (DataTable table) {
    List<Integer> variableTypeList = getVariableTypeList(mDataTypes);
    String[] dateFormats = new String[mCaseQuantity];
    String[] unfValues = new String[mVarQuantity];
    String fileUNFvalue = null;
    
    // Set variable types
    // smd.setVariableTypeMinimal(ArrayUtils.toPrimitive(variableTypeList.toArray(new Integer[variableTypeList.size()])));
    
    int [] x = ArrayUtils.toPrimitive(variableTypeList.toArray(new Integer[variableTypeList.size()]));
    
    int count = 0;
    for (int y : x) {
      LOG.info("[variableType] " + count + " = " + y + "");
      count++;
    }
    
    for (int k = 0; k < mVarQuantity; k++) {
      String unfValue, name = variableNameList.get(k);
      int varType = variableTypeList.get(k);
      
      Object [] varData = table.getData()[k];
      
      LOG.info("// // // // //");
      LOG.info("varData = " + Arrays.deepToString(varData));
      LOG.info("varData = " + Arrays.deepToString(varData));
      LOG.info("varData = " + Arrays.deepToString(varData));
      try {
        switch (varType) {
          case 0:
            // Convert array of Strings to array of Longs
              Long[] integerEntries = new Long[varData.length];

              for (int i = 0; i < varData.length; i++) {
                  try {
                      integerEntries[i] = new Long((String) varData[i]);
                  } catch (Exception ex) {
                      integerEntries[i] = null;
                  }
              }

            unfValue = UNF5Util.calculateUNF(integerEntries);
            
            // UNF5Util.cal

            // Summary/category statistics
            smd.getSummaryStatisticsTable().put(k, ArrayUtils.toObject(StatHelper.calculateSummaryStatistics(integerEntries)));
            Map <String, Integer> catStat = StatHelper.calculateCategoryStatistics(integerEntries);
            smd.getCategoryStatisticsTable().put(variableNameList.get(k), catStat);

            break;

          // If double
          case 1:
            LOG.info(k + ": " + name + " is double");
            // Convert array of Strings to array of Doubles
            Double[]  doubleEntries = new Double[varData.length];
            
              for (int i = 0; i < varData.length; i++) {
                  try {
                      // Check for the special case of "NaN" - this is the R and DVN
                      // notation for the "Not A Number" value:
                      if (varData[i] != null && ((String) varData[i]).equals("NaN")) {
                          doubleEntries[i] = Double.NaN;
                      } else {
                          // Missing Values don't need to be treated separately; these 
                          // are represented as empty spaces in the TAB file; so 
                          // attempting to create a Double object from one will 
                          // throw an exception - which we are going to intercept 
                          // below. For the UNF and Summary Stats purposes, missing
                          // values are represented as NULLs. 
                          doubleEntries[i] = new Double((String) varData[i]);
                      }
                  } catch (Exception ex) {
                      doubleEntries[i] = null;
                  }
              }
            
            LOG.info("sumstat:long case=" + Arrays.deepToString(
                        ArrayUtils.toObject(StatHelper.calculateSummaryStatisticsContDistSample(doubleEntries))));
            unfValue = UNF5Util.calculateUNF(doubleEntries);
            
            // SPECIFY DECIMAL VARIABLE SET FOR SPECIAL ANALYSIS
            
            
            
            // Update summary statistics
            smd.getSummaryStatisticsTable().put(k, ArrayUtils.toObject(StatHelper.calculateSummaryStatisticsContDistSample(doubleEntries)));

            break;
       
          case -1:
            LOG.info(k + ": " + name + " is string");

            String[] stringEntries = Arrays.asList(varData).toArray(new String[varData.length]);
            
            LOG.info("string array passed to calculateUNF: " + Arrays.deepToString(stringEntries));
            
            //
            if (mFormatTable[k] == FORMAT_DATE || mFormatTable[k] == FORMAT_DATETIME) {
              DateFormatter dateFormatter = new DateFormatter();
              
              dateFormatter.setDateFormats(DATE_FORMATS);
              dateFormatter.setTimeFormats(TIME_FORMATS);
              
              for (int i = 0; i < varData.length; i++) {
                DateWithFormatter entryDateWithFormat;
                
                // Place a null entry if data is missing
                if (dateFormats[i] != null && (stringEntries[i].equals("") || stringEntries[i].equals(" "))) {
                  stringEntries[i] = dateFormats[i] = null;
                }
                else {
                  entryDateWithFormat = dateFormatter.getDateWithFormat(stringEntries[i]);
                  // Otherwise get the pattern
                  // entryDateWithFormat = dateFormatter.getDateWithFormat(stringEntries[i]);
                  dateFormats[i] = entryDateWithFormat.getFormatter().toPattern();
                }
              }
              
              // Compute UNF
              try {
                LOG.info("strdata: " + Arrays.deepToString(stringEntries));
                LOG.info("dateFormats: " + Arrays.deepToString(dateFormats));
                
                unfValue = UNF5Util.calculateUNF(stringEntries, dateFormats);
              }
              catch (Exception ex) {
                LOG.warning("UNF FOR DATE COULD NOT BE COMPUTED");
                unfValue = UNF5Util.calculateUNF(stringEntries);
                ex.printStackTrace();
              }
            }
            else
              unfValue = UNF5Util.calculateUNF(stringEntries);


            LOG.info(name + " (UNF) = " + unfValue);
            
            smd.getSummaryStatisticsTable().put(k, StatHelper.calculateSummaryStatistics(stringEntries));
            Map <String, Integer> StrCatStat = StatHelper.calculateCategoryStatistics(stringEntries);
            smd.getCategoryStatisticsTable().put(variableNameList.get(k), StrCatStat);

            break;
            
          default:
            unfValue = null;
        }
        
        LOG.info(name + " (UNF) = " + unfValue);
        unfValues[k] = unfValue;
      }
      catch (Exception ex) { }
    }
    
    try {
      fileUNFvalue = UNF5Util.calculateUNF(unfValues);
    } catch (NumberFormatException ex) {
      ex.printStackTrace();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
    
    mCsvDataTable.setUnf(unfValues);
    mCsvDataTable.setFileUnf(fileUNFvalue);

    
    // Set meta-data to make it look like a SAV file
    // smd.setVariableStorageType(null);
    // smd.setDecimalVariables(mDecimalVariableSet);
    
    boolean [] b = smd.isContinuousVariable();
    
    for (int k = 0; k < b.length; k++) {
      String s = b[k] ? "True" : "False";
      LOG.info(k + " = " + s);
    }
    
    smd.setVariableUNF(unfValues);
    smd.getFileInformation().put("fileUNF", fileUNFvalue);
  }
  /**
   * Set meta information
   * 
   */
  private void setMetaInfo () {
    smd.setVariableFormat(mPrintFormatList);
    smd.setVariableFormatName(mPrintFormatNameTable);
  }
  /**
   * Set Missing Values from CSV File
   * Sets missing value table from CSV file.
   */
  private void setMissingValueTable () {
    smd.setMissingValueTable(null);
    // smd.getFileInformation().put("caseWeightVariableName", caseWeightVariableName);
  }
  /**
   * Read a Local Resource and Return Its Contents as a String
   * <code>readLocalResource</code> searches the local path around the class
   * <code>RDATAFileReader</code> for a file and returns its contents as a
   * string.
   * @param path String specifying the name of the local file to be converted
   * into a UTF-8 string.
   * @return a UTF-8 <code>String</code>
   */
  private static String readLocalResource (String path) {
    // Debug
    LOG.info(String.format("RDATAFileReader: readLocalResource: reading local path \"%s\"", path));
    
    // Get stream
    InputStream resourceStream = RDATAFileReader.class.getResourceAsStream(path);
    String resourceAsString = "";
    
    // Try opening a buffered reader stream
    try {
      resourceAsString = FileUtils.readAll(resourceStream, "UTF-8");
      resourceStream.close();
    }
    catch (IOException ex) {
      LOG.warning(String.format("RDATAFileReader: (readLocalResource) resource stream from path \"%s\" was invalid", path));
    }
    
    // Return string
    return resourceAsString;
  }
}