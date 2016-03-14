package com.delb;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.jpeg.JpegDirectory;

/*
 * BatchImageProcessing is a program that will copy jpeg files from a directory (optionally sub-directories) to
 * another directory (optionally recreating the directory structure if processing sub-directories).
 * As the files are copied they are resized to the dimensions of your picture frame keeping the aspect ratio
 * the same.
 * A simple algorithm is used to determine if the width or the height of the image will be set to the maximum,
 * the other dimension is calculated using the existing ratio.
 * The algorithm used is:
 * 1. The aspect ratio is calculated as width / height. Ex: w:3648, h:2736, ratio: 1.3333
 * Ex: w:2736, h:3648, ratio: 0.75
 * 2. If the aspect ration is less than or equal to 1 then the height is set to the maximum height entered.
 * If the aspect ration is greater than 1 then the width is set to the maximum width entered.
 * 2a. An aspect ratio above 1.7778 (16/9) may be a panorama, WARN
 * 3. The aspect ratio is then used to calculate the other dimension.
 * 
 * ImageJ is then used internally to resize the image to the new dimensions using BiCubic Interpolation to
 * re-sample the image. Perhaps the choice of interpolation (BiCubic, BiLinear, or None) should be allowed.
 * ImageJ will then save the resized image to the requested directory.
 * 
 * Command Line:
 * o f - File List of directories (Mutually exclusive with -d)
 * o d - Directory to process (Mutually exclusive with -f)
 * o s - process sub-directories
 * o o - output directory
 * o c - create output sub-directories default off
 * o fw - frame resolution width
 * o fh - frame resolution Height
 * o i - interpolation (BiCubic, BiLinear, or None) BiCubic is default
 * 
 */

public class BatchImageProcessing
{
  private static final String BATCH_IMAGE_PROCESSING_VERSION = "v0.10.0";
  private static final String BATCH_IMAGE_PROCESSING_NAME = "BatchImageProcessing";
  private static final String SOFTWARE_TYPE = BATCH_IMAGE_PROCESSING_NAME + " " + BATCH_IMAGE_PROCESSING_VERSION;
  private static final double MAYBE_PANORAMA_RATIO = 1.8d; // 16x9 = 1.77777778
  private static final String[] EXTENSIONS_TO_PROCESS = {
      ".jpg", ".jpeg", ".jpe"
  };
  private static final String[] INTERPOLATION_VALUES = {
      "Bicubic", "Bilinear", "None"
  };
  private static final String FIJI_EXECUTE_PARMS = "%s --headless -macro \"%s\"";
  private static final String IMAGEJ_OPEN = "open(\"%s\");%n";
  private static final String IMAGEJ_RESIZE =
      "run(\"Size...\", \"width=%d height=%d constrain average interpolation=%s\");%n";
  private static final String IMAGEJ_SAVE_AS = "saveAs(\"Jpeg\", \"%s\");%n";
  private static final String IMAGEJ_CLOSE_IMAGE = "close();\n";
  private static final String IMAGEJ_FLIP_HORZ = "run(\"Flip Horizontally\");\n";
  private static final String IMAGEJ_FLIP_VERT = "run(\"Flip Vertically\");\n";
  private static final String IMAGEJ_ROTATE_180 = IMAGEJ_FLIP_VERT + IMAGEJ_FLIP_HORZ;
  private static final String IMAGEJ_ROTATE_270 = "run(\"Rotate 90 Degrees Left\");\n";
  private static final String IMAGEJ_ROTATE_90 = "run(\"Rotate 90 Degrees Right\");\n";
  private static final String IMAGEJ_QUIT = "run(\"Quit\");\n";
  private static final int ORIENTATION_NORMAL = 1;
  private static final int ORIENTATION_MIRROR_HORZ = 2;
  private static final int ORIENTATION_ROTATE_180 = 3;
  private static final int ORIENTATION_MIRROR_VERT = 4;
  private static final int ORIENTATION_MIRROR_HORZ_ROTATE_270_CW = 5;
  private static final int ORIENTATION_ROTATE_90_CW = 6;
  private static final int ORIENTATION_MIRROR_HORZ_ROTATE_90_CW = 7;
  private static final int ORIENTATION_ROTATE_270_CW = 8;

  PrintStream outPS;
  String outPSPath;
  boolean debugMode;
  boolean verboseMode;
  boolean processSubDirs;
  boolean newFilesOnly;
  File dirToProcess;
  File fileListOfDirs;
  File outputDir;
  double frameHeight;
  double frameWidth;
  String imageInterpolation;
  String fijiExecuteStr;

  /**
   * @param outPS
   * @param outPSPath
   * @param debugMode
   * @param verboseMode
   * @param processSubDirs
   * @param dirToProcess
   * @param fileListOfDirs
   * @param outputDir
   * @param frameHeight
   * @param frameWidth
   * @param imageInterpolation
   * @param fijiExecuteStr
   * @param newFilesOnly
   */
  public BatchImageProcessing (PrintStream outPS, String outPSPath, boolean debugMode, boolean verboseMode,
      boolean processSubDirs, File dirToProcess, File fileListOfDirs, File outputDir, double frameHeight,
      double frameWidth, String imageInterpolation, String fijiExecuteStr, boolean newFilesOnly) {
    this.outPS = outPS;
    this.outPSPath = outPSPath;
    this.debugMode = debugMode;
    this.verboseMode = verboseMode;
    this.processSubDirs = processSubDirs;
    this.dirToProcess = dirToProcess;
    this.fileListOfDirs = fileListOfDirs;
    this.outputDir = outputDir;
    this.frameHeight = frameHeight;
    this.frameWidth = frameWidth;
    this.imageInterpolation = imageInterpolation;
    this.fijiExecuteStr = fijiExecuteStr;
    this.newFilesOnly = newFilesOnly;
  }

  public BatchImageProcessing () {
    this.outPS = System.out;
    this.outPSPath = "Standard Out";
    this.debugMode = false;
    this.verboseMode = true;
    this.processSubDirs = false;
    this.dirToProcess = null;
    this.fileListOfDirs = null;
    this.outputDir = null;
    this.frameHeight = 0.0d;
    this.frameWidth = 0.0d;
    this.imageInterpolation = "Bicubic";
    this.fijiExecuteStr = null;
    this.newFilesOnly = false;
  }

  private void process ()
  {
    SimpleDateFormat dtFormat = new SimpleDateFormat ("yyyy-MM-dd kk:mm:ss.SSS z");
    SimpleDateFormat runDateFormat = new SimpleDateFormat ("yyyyMMddkkmmssSSS");
    Date currDate = new Date (System.currentTimeMillis ());
    TreeSet<File> directoriesToProcess = new TreeSet<File> ();
    BufferedReader br = null;
    String runDateStr = runDateFormat.format (currDate);
    HashSet<String> currOutputFileNames = null;

    dtFormat.setTimeZone (TimeZone.getDefault ());
    outPS.printf ("--------------------------------------- %s%n", dtFormat.format (currDate));
    outPS.printf ("outPS=%s%ndebugMode=%s%nverboseMode=%s%nprocessSubDirs=%s%ndirToProcess=%s"
      + "%nfileListOfDirs=%s%noutputDir=%s%nframeHeight=%s%nframeWidth=%s%nimageInterpolation=%s%n" 
      + "newFilesOnly=%s%nfijiExecuteStr=%s%n",
      outPSPath, debugMode, verboseMode, processSubDirs, dirToProcess, fileListOfDirs, outputDir, frameHeight,
      frameWidth, imageInterpolation, newFilesOnly, fijiExecuteStr);

    if (newFilesOnly) {
      // newFilesOnly set, build a list of filenames in the output directory
      File[] currOutputFilesArray = outputDir.listFiles ();
      int numCurrOutputFiles = currOutputFilesArray.length;
      currOutputFileNames = new HashSet<> (numCurrOutputFiles);
      for (int i = 0; i < numCurrOutputFiles; ++i) {
        if (currOutputFilesArray[i].isFile ()) {
          // it is a file, add it to list of the files
          currOutputFileNames.add (currOutputFilesArray[i].getName ());
        }
      }
      // no longer need the list (array) of files in the output directory
      currOutputFilesArray = null;
    }

    if (dirToProcess != null) {
      // a single directory to process was given
      directoriesToProcess.add (dirToProcess);
    }
    if (fileListOfDirs != null) {
      // a file with a list of directories to process (one per line)
      try {
        br = new BufferedReader (new FileReader (fileListOfDirs));
      } catch (FileNotFoundException e) {
        outPS.printf ("Error opening \"%s\", %s%n", fileListOfDirs, e.toString ());
        System.exit (1);
      }
      String dirName;
      try {
        while ((dirName = br.readLine ()) != null) {
          // add the directory named on line to Tree of directories to process
          directoriesToProcess.add (new File (dirName));
        }
      } catch (IOException e) {
        outPS.printf ("Error reading \"%s\", %s%n", fileListOfDirs, e.toString ());
        try {
          br.close ();
        } catch (IOException e1) {
        }
        System.exit (2);
      }
      // close the file, we have finished reading it
      try {
        br.close ();
      } catch (IOException e1) {
      }
    }
    processDirectoryStructure (directoriesToProcess, runDateStr, currOutputFileNames);
  }

  private void processDirectoryStructure (TreeSet<File> directoriesToProcess, String runDateStr,
      Set<String> currOutputFileNames)
  {
    ArrayList<File> imagesToProcess = new ArrayList<> (30000);
    int numberOfImagesToProcess = 0;

    // now process all directories
    while (directoriesToProcess.size () > 0) {
      File nextDirToProcess = directoriesToProcess.first ();
      directoriesToProcess.remove (nextDirToProcess);
      processDirectory (nextDirToProcess, directoriesToProcess, imagesToProcess, currOutputFileNames);
    }
    numberOfImagesToProcess = imagesToProcess.size ();
    if (numberOfImagesToProcess != 0) {
      outPS.printf ("%nProcessing the following %,d images:%n", numberOfImagesToProcess);
      batchProcessImages (imagesToProcess, currOutputFileNames, runDateStr);
    }
    else {
      outPS.println ("There are no images to process - Done.");
    }
  }

  private void batchProcessImages (ArrayList<File> imagesToProcess, Set<String> currOutputFileNames, String runDateStr)
  {
    File tempFile = null;
    String macroFileStr = null;
    BufferedWriter bw = null;
    boolean mirrorHorz = false;
    boolean mirrorVert = false;
    boolean rotate270 = false;
    boolean rotate180 = false;
    boolean rotate90 = false;
    boolean resizeNeeded = false;
    int imageWidth = 0;
    int imageHeight = 0;
    int imageOrgWidth = 0;
    int imageOrgHeight = 0;
    int fileNum = 0;
    double imageAspectRatio = 0.0d;
    double imageAdjustRatio = 0.0d;
    Metadata metadata = null;
    StringBuilder macroSB = new StringBuilder (1000);
    ArrayList<File> finalFilenameArray = new ArrayList<> (imagesToProcess.size ());
    ArrayList<File> tempFilenameArray = new ArrayList<> (imagesToProcess.size ());

    try {
      tempFile = File.createTempFile (SOFTWARE_TYPE, ".ijm");
    } catch (IOException e) {
      outPS.printf (">>>>>>IOException while creating temporary macro file for ImageJ:%n%s%n", e.toString ());
      return;
    }
    macroFileStr = tempFile.toPath ().toString ();
    outPS.printf ("Macro file is \"%s\"%n%n", macroFileStr);
    tempFile.deleteOnExit ();
    // tempFilePath = tempFile.toPath ().toString ();
    try {
      bw = new BufferedWriter (new FileWriter (tempFile));
    } catch (IOException e) {
      outPS.printf (">>>>>>IOException while opening temporary macro file for ImageJ:%n%s%n", e.toString ());
      return;
    }

    for (File file : imagesToProcess) {
      try {
        // 1st we need to generate the final name this file will have in the output dir
        // we have to give it a temporary save as file name since ImageJ has strange limitations on file names
        finalFilenameArray.add (new File (outputDir, file.getName ()));

        metadata = JpegMetadataReader.readMetadata (file);
      } catch (JpegProcessingException e) {
        outPS.printf (">>>>>>Error, File: \"%s\", %s%n", file.toPath ().toString (), e.toString ());
        continue;
      } catch (IOException e) {
        outPS.printf (">>>>>>Error, File: \"%s\", %s%n", file.toPath ().toString (), e.toString ());
        continue;
      }

      int orientation = ORIENTATION_NORMAL;
      for (ExifIFD0Directory exifIFD0Directory : metadata.getDirectoriesOfType (ExifIFD0Directory.class)) {
        if (exifIFD0Directory.containsTag (ExifDirectoryBase.TAG_ORIENTATION)) {
          try {
            orientation = exifIFD0Directory.getInt (ExifDirectoryBase.TAG_ORIENTATION);
          } catch (MetadataException e) {
          } // ignore it if we get MetadataException, initialized to ORIENTATION_NORMAL
        }
      }

      for (Directory jpegDirectory : metadata.getDirectoriesOfType (JpegDirectory.class)) {
        // Each Directory stores values in Tag objects
        try {
          if (jpegDirectory.containsTag (JpegDirectory.TAG_IMAGE_HEIGHT)) {
            imageOrgHeight = jpegDirectory.getInt (JpegDirectory.TAG_IMAGE_HEIGHT);
          }
          if (jpegDirectory.containsTag (JpegDirectory.TAG_IMAGE_WIDTH)) {
            imageOrgWidth = jpegDirectory.getInt (JpegDirectory.TAG_IMAGE_WIDTH);
          }
        } catch (MetadataException e) {
          outPS.printf (">>>>>>Error, File: \"%s\", %s%n", file.toPath ().toString (), e.toString ());
          continue;
        }
        // we have the original height and width of the image, however, we have to check orientation to see
        // if they need to be swapped, since they will rotate within the frame on display.
        mirrorHorz = false;
        mirrorVert = false;
        rotate270 = false;
        rotate180 = false;
        rotate90 = false;

        switch (orientation) {
          case ORIENTATION_NORMAL:
            break; // nothing to do
          case ORIENTATION_MIRROR_HORZ:
            mirrorHorz = true;
            break;
          case ORIENTATION_MIRROR_VERT:
            mirrorVert = true;
            break;
          case ORIENTATION_ROTATE_180:
            rotate180 = true;
            break;
          default:
            break; // nothing to do
          case ORIENTATION_MIRROR_HORZ_ROTATE_270_CW:
            mirrorHorz = true;
            rotate270 = true;
            break;
          case ORIENTATION_MIRROR_HORZ_ROTATE_90_CW:
            mirrorHorz = true;
            rotate90 = true;
            break;
          case ORIENTATION_ROTATE_270_CW:
            rotate270 = true;
            break;
          case ORIENTATION_ROTATE_90_CW:
            rotate90 = true;
            break;
        }
        if (rotate90 || rotate270) {
          // the image width and height need to swap
          int temp = imageOrgHeight;
          imageOrgHeight = imageOrgWidth;
          imageOrgWidth = temp;
        }
        // we now have the image's height and width, calculate the image aspect ratio
        imageAspectRatio = ((double) imageOrgWidth) / ((double) imageOrgHeight);
        if (imageAspectRatio > MAYBE_PANORAMA_RATIO) {
          outPS.printf ("~~~~This image may be a Panorama image, it's width: %,d and height: %,d gives it an "
              + "aspect ratio of %6f%n", imageOrgWidth, imageOrgHeight, imageAspectRatio);
        }
        if (imageAspectRatio > 1.0d) {
          // image is landscape mode (wider than it is tall)
          imageWidth = (int) frameWidth;
          imageAdjustRatio = frameWidth / ((double) imageOrgWidth);
          imageHeight = (int) ((((double) imageOrgHeight) * imageAdjustRatio) + 0.5d);
        }
        else {
          // image is portrait mode (taller than wide)
          imageHeight = (int) frameHeight;
          imageAdjustRatio = frameHeight / ((double) imageOrgHeight);
          imageWidth = (int) ((((double) imageOrgWidth) * imageAdjustRatio) + 0.5d);
        }
      }

      if (imageWidth != imageOrgWidth || imageHeight != imageOrgHeight) {
        resizeNeeded = true;
      }
      else {
        resizeNeeded = false;
      }

      String saveAsFilename = String.format ("%s_%s_%06d.jpg", BATCH_IMAGE_PROCESSING_NAME, runDateStr, fileNum++);
      File saveAsFile = new File (outputDir, saveAsFilename);
      // add the saveAsFile to the tempFilenameArray
      tempFilenameArray.add (saveAsFile);

      // build the needed list of macro commands
      macroSB.setLength (0);
      macroSB.append (String.format (IMAGEJ_OPEN, file.toPath ().toString ())); // open the image to be processed
      if (mirrorHorz)
        macroSB.append (IMAGEJ_FLIP_HORZ);
      if (mirrorVert)
        macroSB.append (IMAGEJ_FLIP_VERT);
      if (rotate270)
        macroSB.append (IMAGEJ_ROTATE_270);
      if (rotate180)
        macroSB.append (String.format (IMAGEJ_ROTATE_180, imageInterpolation));
      if (rotate90)
        macroSB.append (IMAGEJ_ROTATE_90);
      if (resizeNeeded) {
        macroSB.append (String.format (IMAGEJ_RESIZE, imageWidth, imageHeight, imageInterpolation));
      }
      macroSB.append (String.format (IMAGEJ_SAVE_AS, saveAsFile.toPath ().toString ()));
      macroSB.append (IMAGEJ_CLOSE_IMAGE);
      // now make all backslash characters double backslash
      int backslashOffset = 0;
      int lastBackslashOffset = 0;
      while (lastBackslashOffset < macroSB.length ()
          && ((backslashOffset = macroSB.indexOf ("\\", lastBackslashOffset)) != -1)) {
        // we have found a backslash, double it
        macroSB.insert (backslashOffset, '\\');
        lastBackslashOffset = backslashOffset + 2;
      }
      String macroLines = macroSB.toString ();

      outPS.print (macroLines); // for log
      try {
        bw.write (macroLines);
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace ();
      }
    }

    // now add the quit to end of macro
    outPS.print (IMAGEJ_QUIT);
    try {
      bw.write (IMAGEJ_QUIT);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace ();
    }

    try {
      bw.close ();
    } catch (IOException e1) {
    }

    String runFiji = String.format (FIJI_EXECUTE_PARMS, fijiExecuteStr, macroFileStr);
    executeFiji (runFiji, fijiExecuteStr, imagesToProcess.size ());
    
    // don't need the temporary macro file any more
    tempFile.delete ();

    // now rename the batch processed files to the correct name
    outPS.printf ("Renaming the %,d files to their final name%n", imagesToProcess.size ());
    for (int i = 0; i < tempFilenameArray.size (); ++i) {
      // rename each file from it temporary name that Fiji can handle to the final name
      if (!tempFilenameArray.get (i).renameTo (finalFilenameArray.get (i))) {
        outPS.printf (">>>>>Error, unable to rename \"%s\" to \"%s\"%n",
            tempFilenameArray.get (i).toPath ().toString (), finalFilenameArray.get (i).toPath ().toString ());
      }
    }
  }

  private void executeFiji (String runFiji, String fijiExecuteStr, double numberOfImagesToProcess)
  {
    Process fijiProcess = null;
    File[] filesInOutputDir = null;
    double initialNumFilesInOutputDir = 0.0d;
    double numFilesInOutputDir = 0.0d;
    int percentFinished = 0;
    boolean fijiDone = false;

    filesInOutputDir = outputDir.listFiles (); // this is really fast, appears to be cached
    initialNumFilesInOutputDir = filesInOutputDir.length;
    outPS.printf ("Initial number of files in Output Directory: %,d%n", (int) initialNumFilesInOutputDir);
    filesInOutputDir = null; // to free up some memory

    // determine the working directory from the Fiji execution string
    File fijiExecutionFile = new File (fijiExecuteStr);
    String fijiProgram = fijiExecutionFile.getName ();
    // now replace the fijiProgram string in fijiExecuteStr with "", this gives us the working directory
    String workingDirStr = fijiExecuteStr.replace (fijiProgram, "");
    File workingDir = new File (workingDirStr);
    try {
      fijiProcess = Runtime.getRuntime ().exec (runFiji, null, workingDir);
    } catch (IOException e) {
      outPS.printf (">>>>>Unable to start Fiji with this cmd: \"%s\"%n%s%n", runFiji, e.toString ());
      return;
    }
    do {
      // now wait for Fiji to finish or 10 seconds
      try {
        fijiDone = fijiProcess.waitFor (10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // re-throw the interrupt exception
        Thread.currentThread ().interrupt ();
        return; // exit
      }
      filesInOutputDir = outputDir.listFiles ();
      numFilesInOutputDir = filesInOutputDir.length;
      filesInOutputDir = null; // to free up some memory
      percentFinished = (int) (((numFilesInOutputDir - initialNumFilesInOutputDir) / numberOfImagesToProcess) * 100.0d);
      outPS.printf ("%d%% Done%n", percentFinished);
    } while (!fijiDone);

    outPS.printf ("Fiji exited with a Return Code of %d%n", fijiProcess.exitValue ());
  }

  private void processDirectory (File currentDir, TreeSet<File> directoriesToProcess, List<File> imagesToProcess,
      Set<String> currOutputFileNames)
  {
    // get list of all files and sub-directories in currentDir
    File[] currDirsFilesAndDirs = currentDir.listFiles ();
    if (currDirsFilesAndDirs == null) {
      outPS.printf (">>>>>>Error, Invalid Directory: \"%s\"%n", currentDir.toPath ().toString ());
      return;
    }

    // check each entry in currDirsFilesAndDirs to determine if it is a file or directory
    for (int i = 0; i < currDirsFilesAndDirs.length; i++) {
      if (currDirsFilesAndDirs[i].isDirectory () && processSubDirs) {
        // this is a sub-directory to process, add it to process list
        directoriesToProcess.add (currDirsFilesAndDirs[i]);
      }
      else {
        // it is a file, should it be processed?
        addFileIfProcessingNeeded (currDirsFilesAndDirs[i], imagesToProcess, currOutputFileNames);
      }
    }
  }

  private void addFileIfProcessingNeeded (File file, List<File> imagesToProcess, Set<String> currOutputFileNames)
  {
    // if this is a image file with the correct extension, add it to the process list
    String fileExtension = null;
    String fileName = file.getName ();
    
    if (newFilesOnly && currOutputFileNames.contains (fileName)) {
      // this file (or one by that name already exists in the output directory, do not process it
      return;
    }
    int dotOffset = fileName.lastIndexOf ('.');
    if (dotOffset != -1) {
      // file has an extension, is it one of the ones to process?
      fileExtension = fileName.substring (dotOffset);
      for (int i = 0; i < EXTENSIONS_TO_PROCESS.length; ++i) {
        if (EXTENSIONS_TO_PROCESS[i].equalsIgnoreCase (fileExtension)) {
          // yes, add file to list to process
          imagesToProcess.add (file);
          break; // done
        }
      }
    }
  }

  @Override
  public String toString ()
  {
    StringBuilder builder = new StringBuilder ();
    builder.append ("BatchImageProcessing [outPS=");
    builder.append (outPS);
    builder.append (", outPSPath=");
    builder.append (outPSPath);
    builder.append (", debugMode=");
    builder.append (debugMode);
    builder.append (", verboseMode=");
    builder.append (verboseMode);
    builder.append (", processSubDirs=");
    builder.append (processSubDirs);
    builder.append (", newFilesOnly=");
    builder.append (newFilesOnly);
    builder.append (", dirToProcess=");
    builder.append (dirToProcess);
    builder.append (", fileListOfDirs=");
    builder.append (fileListOfDirs);
    builder.append (", outputDir=");
    builder.append (outputDir);
    builder.append (", frameHeight=");
    builder.append (frameHeight);
    builder.append (", frameWidth=");
    builder.append (frameWidth);
    builder.append (", imageInterpolation=");
    builder.append (imageInterpolation);
    builder.append (", fijiExecuteStr=");
    builder.append (fijiExecuteStr);
    builder.append ("]");
    return builder.toString ();
  }

  public static void main (String[] args)
  {
    CommandLine cmdLineParms = null;
    PrintStream outPS = System.out;
    String outPSPath = "Standard Out";
    boolean debugMode = false;
    boolean verboseMode = true;
    boolean processSubDirs = false;
    boolean newFilesOnly = false;
    File dirToProcess = null;
    File fileListOfDirs = null;
    File outputDir = null;
    double frameHeight = 0.0d;
    double frameWidth = 0.0d;
    String imageInterpolation = "Bicubic";
    String fijiExecuteStr = null;

    // use Apache Commons CLI to parse keyword command line parameters
    String cmdArg = null;
    cmdLineParms = processKeywordParms (args);
    // did user ask for version?
    if (cmdLineParms.hasOption ('v')) {
      // print version and exit
      outPS.println (SOFTWARE_TYPE);
      System.exit (18);
    }
    if (cmdLineParms.hasOption ('?') || cmdLineParms.hasOption ("help")) {
      printHelpAndExit (outPS);
    }
    if (cmdLineParms.hasOption ("log")) {
      // a log file has been entered, open it for append
      cmdArg = cmdLineParms.getOptionValue ("log");
      outPSPath = cmdArg;
      try {
        outPS = new PrintStream (new FileOutputStream (cmdArg, true), true);
      } catch (FileNotFoundException e) {
        parmInvalid (outPS, "Optional", "-log, Log File", cmdArg, e.toString ());
      }
    }
    if (cmdLineParms.hasOption ('D')) {
      debugMode = true;
    }
    if (cmdLineParms.hasOption ("fiji")) {
      // Required Parameter, -fiji, Fiji execute string
      fijiExecuteStr = cmdLineParms.getOptionValue ("fiji");
    }
    else {
      // required Parameter missing
      requiredParmMissing (outPS, "-fiji, Fiji execute string");
    }
    if (cmdLineParms.hasOption ('d')) {
      // Optional Parameter, -d, Directory to Process
      cmdArg = cmdLineParms.getOptionValue ('d');
      dirToProcess = new File (cmdArg);
      if (!dirToProcess.exists ()) {
        parmInvalid (outPS, "Optional", "-d, Directory to Process", cmdArg, "directory doesn't exist");
      }
      if (!dirToProcess.isDirectory ()) {
        parmInvalid (outPS, "Optional", "-d, Directory to Process", cmdArg, "isn't a directory");
      }
    }
    if (cmdLineParms.hasOption ('f')) {
      // Optional Parameter, -f, File List of directories
      cmdArg = cmdLineParms.getOptionValue ('f');
      fileListOfDirs = new File (cmdArg);
      if (!fileListOfDirs.exists ()) {
        parmInvalid (outPS, "Optional", "-f, File List of directories", cmdArg, "file doesn't exist");
      }
      if (!fileListOfDirs.isFile ()) {
        parmInvalid (outPS, "Optional", "-f, File List of directories", cmdArg, "isn't a file");
      }
    }
    if ((dirToProcess == null && fileListOfDirs == null) || (dirToProcess != null && fileListOfDirs != null)) {
      // one (but only one) of the -d or -f option must be specified
      requiredParmMissing (outPS, "-d or -f option must be specified, however, only one of the two");
    }
    if (cmdLineParms.hasOption ('h')) {
      // Required Parameter, -h, frame resolution height
      cmdArg = cmdLineParms.getOptionValue ('h');
      try {
        int argVal = Integer.valueOf (cmdArg);
        frameHeight = (double) argVal;
      } catch (NumberFormatException e) {
        parmInvalid (outPS, "Required", "-h, frame resolution height", cmdArg, "is not a valid number");
      }
    }
    else {
      // required Parameter missing
      requiredParmMissing (outPS, "-h, frame resolution height");
    }
    if (cmdLineParms.hasOption ('w')) {
      // Required Parameter, -w, frame resolution width
      cmdArg = cmdLineParms.getOptionValue ('w');
      try {
        int argVal = Integer.valueOf (cmdArg);
        frameWidth = (double) argVal;
      } catch (NumberFormatException e) {
        parmInvalid (outPS, "Required", "-w, frame resolution width", cmdArg, "is not a valid number");
      }
    }
    else {
      // required Parameter missing
      requiredParmMissing (outPS, "-w, frame resolution width");
    }

    if (cmdLineParms.hasOption ('q')) {
      verboseMode = false;
    }
    if (cmdLineParms.hasOption ('s')) {
      // user has asked for us to process sub-directories on input
      processSubDirs = Boolean.TRUE;
    }
    if (cmdLineParms.hasOption ('n')) {
      // -n -new-only, process images not in output-dir (optional) Default: false
      newFilesOnly = Boolean.TRUE;
    }
    if (cmdLineParms.hasOption ('o')) {
      // Required Parameter, -o, output directory
      cmdArg = cmdLineParms.getOptionValue ('o');
      outputDir = new File (cmdArg);
      if (!outputDir.exists ()) {
        parmInvalid (outPS, "Required", "-o, output directory", cmdArg, "directory doesn't exist");
      }
      if (!outputDir.isDirectory ()) {
        parmInvalid (outPS, "Required", "-o, output directory", cmdArg, "isn't a directory");
      }
    }
    else {
      // required Parameter missing
      requiredParmMissing (outPS, "-s, image Source Directory");
    }
    if (cmdLineParms.hasOption ('i')) {
      // Optional Parameter, -i, interpolation (BiCubic, BiLinear, or None)
      boolean argValid = false;
      cmdArg = cmdLineParms.getOptionValue ('i');
      for (int i = 0; i < INTERPOLATION_VALUES.length; ++i) {
        if (INTERPOLATION_VALUES[i].equalsIgnoreCase (cmdArg)) {
          // found valid interpolation setting, set it
          imageInterpolation = INTERPOLATION_VALUES[i];
          argValid = true;
          break;
        }
      }
      if (!argValid) {
        parmInvalid (outPS, "Optional", "-i, interpolation (BiCubic, BiLinear, or None)", cmdArg, "invalid input");
      }
    }

    BatchImageProcessing batchImageProcessing =
        new BatchImageProcessing (outPS, outPSPath, debugMode, verboseMode, processSubDirs, dirToProcess, fileListOfDirs,
            outputDir, frameHeight, frameWidth, imageInterpolation, fijiExecuteStr, newFilesOnly);
    batchImageProcessing.process ();
  }

  private static void parmInvalid (PrintStream outPS, String typeParm, String cmdStr, String arg, String error)
  {
    outPS.printf ("%s parameter \"%s\", argument \"%s\" is invalid, \"%s\", can't continue.%n%n", typeParm, cmdStr, arg,
        error);
    printHelpAndExit (outPS);
  }

  private static void requiredParmMissing (PrintStream outPS, String cmdStr)
  {
    outPS.printf ("Required parameter \"%s\", not specified, can't continue.%n%n", cmdStr);
    printHelpAndExit (outPS);
  }

  private static void printHelpAndExit (PrintStream outPS)
  {
    // print help and exit
    HelpFormatter formatter = new HelpFormatter ();
    Options options = getCmdLineOptions ();
    formatter.printHelp (BATCH_IMAGE_PROCESSING_NAME, options);
    System.exit (8);
  }

  private static CommandLine processKeywordParms (String[] args)
  {
    CommandLineParser parser = new DefaultParser ();
    CommandLine cmdLine = null;
    Options options = getCmdLineOptions ();

    try {
      // parse the command line arguments
      cmdLine = parser.parse (options, args);
    } catch (ParseException e) {
      // oops, something went wrong
      System.out.printf ("Command Line Parsing failed.  Reason: %s%n", e.toString ());
      System.exit (20);
    }

    return cmdLine;
  }

  private static Options getCmdLineOptions ()
  {
    // define the option we support
    Options options = new Options ();
    options.addOption ("?", false, "print this message");
    // options.addOption ("c", "create-output-sub-dirs", false, "create output sub-directories (optional) Default: off");
    options.addOption ("D", "Debug", false, "print debugging information");
    options.addOption ("d", "directory-to-process", true, "Directory to process (Mutually exclusive with -f)");
    options.addOption ("f", "file-list-of-dirs", true,
        "File List of directories, one / line (Mutually exclusive with -d)");
    options.addOption ("fiji", true, "Fiji execute string (Required)");
    options.addOption ("h", "frame-height", true, "frame resolution height (Required)");
    options.addOption ("help", false, "print this message");
    options.addOption ("i", "interpolation", true,
        "interpolation (Bicubic, Bilinear, or None) (optional) Default: BiCubic");
    options.addOption ("log", true, "log file to append all output to (optional) Default: Standard Out");
    options.addOption ("n", "new-only", false, "process images not in output-dir (optional) Default: false");
    options.addOption ("o", "output-dir", true, "output directory (Required)");
    options.addOption ("q", "quiet", false, "quiet mode, most output not displayed");
    options.addOption ("s", "sub-dir", false, "sub-directories process (optional) Default: off");
    options.addOption ("v", "version", false, "print the version information and exit");
    options.addOption ("w", "frame-width", true, "frame resolution width (Required)");

    return options;
  }
}

class RenameFiles
{
  File tempName;
  File correctName;

  /**
   * @param tempName
   * @param correctName
   */
  public RenameFiles (File tempName, File correctName) {
    this.tempName = tempName;
    this.correctName = correctName;
  }
}