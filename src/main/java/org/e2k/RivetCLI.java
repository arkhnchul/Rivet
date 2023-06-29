// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// Rivet Copyright (C) 2011 Ian Wraith
// This program comes with ABSOLUTELY NO WARRANTY

package org.e2k;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.*;
import java.util.List;

public class RivetCLI implements RivetApp {

    private static boolean RUNNING = true;
    public final String program_version = "Rivet (Build 91) Beta 3";
    public XPA xpaHandler;
    public XPA2 xpa2Handler;
    public CROWD36 crowd36Handler;
    public FSK200500 fsk200500Handler;
    public FSK2001000 fsk2001000Handler;
    public F06a f06aHandler;
    public CIS3650 cis3650Handler;
    public CCIR493 ccir493Handler;
    public RTTY rttyHandler;
    public GW gwHandler;
    public FSKraw fskHandler;
    //public RDFT rdftHandler=new RDFT(this);
    //public AT3x04 at3x04Handler=new AT3x04(this);
    public InputThread inputThread;
    private DataInputStream inPipeData;
    private PipedInputStream inPipe;
    private final CircularDataBuffer circBuffer = new CircularDataBuffer();
    private WaveData waveData = new WaveData();
    private boolean logging = false;
    public FileWriter file;
    public FileWriter bitStreamFile;
    private boolean debug = false;
    private boolean soundCardInput = false;
    private boolean wavFileLoadOngoing = false;
    private boolean invertSignal = false;
    private boolean f06aASCII = false;
    private int soundCardInputLevel = 0;
    private boolean soundCardInputTemp;
    private boolean bitStreamOut = false;
    private boolean viewGWChannelMarkers = true;
    private int bitStreamOutCount = 0;
    private List<Trigger> listTriggers = new ArrayList<Trigger>();
    private int activeTriggerCount = 0;
    private boolean displayBadPackets = false;
    private boolean logInUTC = false;
    private final List<Ship> listLoggedShips = new ArrayList<Ship>();
    private RivetMode currentMode;

    public RivetCLI() {
        xpaHandler = new XPA(this, 10);
        xpa2Handler = new XPA2(this);
        crowd36Handler = new CROWD36(this, 40);
        fsk200500Handler = new FSK200500(this, 200);
        fsk2001000Handler = new FSK2001000(this, 200);
        f06aHandler = new F06a(this, 200);
        cis3650Handler = new CIS3650(this);
        ccir493Handler = new CCIR493(this);
        rttyHandler = new RTTY(this);
        gwHandler = new GW(this);
        fskHandler = new FSKraw(this);
//        //public RDFT rdftHandler=new RDFT(this);
//        //public AT3x04 at3x04Handler=new AT3x04(this);
        inputThread = new InputThread(this);
    }

    public static void main(String[] args) {
        RivetCmdOptions cmdOptions = new RivetCmdOptions();
        cmdOptions.parseOptions(args);
        cmdOptions.checkOptionsSanity();

        RivetCLI theApp = new RivetCLI();

        String modeName = cmdOptions.getOptionValue(RivetCmdOptions.OptionName.MODE);
        theApp.setCurrentMode(RivetMode.valueOf(modeName));
        // remnants of the old decoder selection "system"
        theApp.setSystem(Arrays.asList(RivetMode.values())
                .indexOf(theApp.getCurrentMode()));

        String wavFileName = cmdOptions.getOptionValue(RivetCmdOptions.OptionName.INPUT_FILE);

        // Get data from the sound card thread
        try {
            // Connected a piped input stream to the piped output stream in the thread
            theApp.inPipe = new PipedInputStream(theApp.inputThread.getPipedWriter());
            // Now connect a data input stream to the piped input stream
            theApp.inPipeData = new DataInputStream(theApp.inPipe);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        RUNNING = true;

        theApp.loadWAVfile(wavFileName);
        // The main loop
        while (RUNNING) {
            if (theApp.wavFileLoadOngoing) theApp.getWavData();
//            else if ((theApp.inputThread.getAudioReady()) && (theApp.pReady)) theApp.getAudioData();
            else {
                // Add the following so the thread doesn't eat all of the CPU time
//                try {
//                    Thread.sleep(1);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
                RUNNING = false;
            }
        }
        if (theApp.getLogging()) {
            try {
                theApp.file.close();
                theApp.file.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        System.exit(0);
    }

    public RivetMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(RivetMode currentMode) {
        this.currentMode = currentMode;
        switch (currentMode) {
            case XPA10:
                xpaHandler.setBaudRate(10);
                break;
            case XPA20:
                xpaHandler.setBaudRate(20);
                break;
        }
    }

    public boolean isFSK() {
        return currentMode.equals(RivetMode.FSK);
    }

    // Tell the input thread to start to load a .WAV file
    public void loadWAVfile(String fileName) {
        String disp;
        disp = getTimeStamp() + " Loading file " + fileName;
        writeLine(disp, Color.BLACK, null);
        BufferedInputStream stream;
        if (fileName.equals("-")) {
            stream = new BufferedInputStream(System.in);
        } else {
            File wavFile = new File(fileName);
            try {
                stream = new BufferedInputStream(Files.newInputStream(wavFile.toPath()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        // Clear the data buffer
        circBuffer.setBufferCounter(0);
        // Make sure the program knows this data is coming from a file
        waveData.setFromFile(true);
        // Ensure the program knows we have a WAV file load ongoing
        wavFileLoadOngoing = true;
        // Reset the system objects
        resetDecoderState();

        waveData = inputThread.startStreamLoad(stream);
    }

    // This is called when the input thread is busy getting data from a WAV file
    private void getWavData() {
        // Get the sample from the input thread
        try {
            // Add the data from the thread pipe to the circular buffer
            circBuffer.addToCircBuffer(inPipeData.readInt());
            // Process this data
            processData();
            // Check if the file has now all been read but we need to process all the data in the buffer
            if (!inputThread.getLoadingFileState()) {
                int a;
                for (a = 0; a < circBuffer.retMax(); a++) {
                    processData();
                    // Keep adding null data to the circular buffer to move it along
                    circBuffer.addToCircBuffer(0);
                }
                // Check if there is anything left to display
                switch (getCurrentMode()) {
                    case CROWD36:
                        //if (crowd36Handler.getLineCount()>0) writeLine(crowd36Handler.getLineBuffer(),Color.BLACK,plainFont);
                        writeLine(crowd36Handler.lowHighFreqs(), Color.BLACK, null);
                        crowd36Handler.toneResults();
                        break;
                    case CIS36_50:
                        //writeLine(cis3650Handler.lineBuffer.toString(),Color.BLACK,plainFont);
                        break;
                    case F01:
                        writeLine(fsk200500Handler.getQuailty(), Color.BLACK, null);
                        break;
                    case F06:
                        writeLine(fsk2001000Handler.getQuailty(), Color.BLACK, null);
                        break;
                    case F06a:
                        writeLine(f06aHandler.getQuailty(), Color.BLACK, null);
                        break;
                }

                // Once the buffer data has been read we are done
                if (wavFileLoadOngoing) {
                    String disp = getTimeStamp() + " WAV file loaded and analysis complete (" + Long.toString(inputThread.getSampleCounter()) + " samples read)";
                    System.err.println(disp);
//                    writeLine(disp, Color.BLACK, null);
                    wavFileLoadOngoing = false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // This is called when the input thread is busy getting data from the sound card
    private void getAudioData() {
        // Get the sample from the input thread
        try {
            // Add the data from the thread pipe to the circular buffer
            circBuffer.addToCircBuffer(inPipeData.readInt());
            // Process this data
            processData();
            // Update the volume bar every 50 samples
//            if (inputThread.getSampleCounter() % 50 == 0) updateVolumeBar();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // A central data processing class
    private void processData() {
        try {
            boolean res = false;
            // CROWD36
            switch (getCurrentMode()) {
                case CROWD36:
                    res = crowd36Handler.decode(circBuffer, waveData);
                    break;
                case XPA10:
                case XPA20:
                    res = xpaHandler.decode(circBuffer, waveData);
                    break;
                case XPA2:
                    res = xpa2Handler.decode(circBuffer, waveData);
                    break;
                case CIS36_50:
                    res = cis3650Handler.decode(circBuffer, waveData);
                    break;
                case F01:
                    res = fsk200500Handler.decode(circBuffer, waveData);
                    break;
                case CCIR493_4:
                    res = ccir493Handler.decode(circBuffer, waveData);
                    break;
                case F06:
                    res = fsk2001000Handler.decode(circBuffer, waveData);
                    break;
                case GWFSK:
                    res = gwHandler.decode(circBuffer, waveData);
                    break;
                case RTTY:
                    res = rttyHandler.decode(circBuffer, waveData);
                    break;
                case FSK:
                    res = fskHandler.decode(circBuffer, waveData);
                    break;
                case F06a:
                    res = f06aHandler.decode(circBuffer, waveData);
                    break;
            }
            // Tell the user there has been an error and stop the WAV file from loading
            if (!res) {
                if (!soundCardInput) {
                    inputThread.stopReadingFile();
                    wavFileLoadOngoing = false;
                    writeLine("Error Loading WAV File", Color.RED, null);
                }

            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Write a line to the debug file
    public void debugDump(String line) {
        try {
            FileWriter dfile = new FileWriter("debug.csv", true);
            dfile.write(line);
            dfile.write("\r\n");
            dfile.flush();
            dfile.close();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // Return a time stamp
    public String getTimeStamp() {
        Date now = new Date();
        DateFormat df = DateFormat.getTimeInstance();
        // If we are logging in UTC time then set the time zone to that
        // Other wise logs will be in local time
        if (logInUTC) df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(now);
    }

    public void setStatusLabel(String st) {
        System.err.println("Status update: " + st);
    }

    public void setModeLabel(String st) {
        System.err.println("Selected mode: " + st);
    }

    public void setLogging(boolean logging) {
        this.logging = logging;
    }

    public boolean getLogging() {
        return logging;
    }

    // Write to a string to the logging file
    public boolean fileWriteLine(String fline) {
        try {
            file.write(fline);
            file.write("\r\n");
            file.flush();
        } catch (Exception e) {
            // Stop logging as we have a problem
            logging = false;
            System.err.println(e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Write a line char to the logging file
    public boolean fileWriteChar(String ch) {
        try {
            file.write(ch);
        } catch (Exception e) {
            // Stop logging as we have a problem
            logging = false;
            System.err.println(e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Write a newline to the logging file
    public boolean fileWriteNewline() {
        try {
            file.write("\r\n");
        } catch (Exception e) {
            // Stop logging as we have a problem
            logging = false;
            System.err.println(e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Write a string to the bit stream file
    public boolean bitStreamWrite(String fline) {
        try {
            // Have only 80 bits per line
            bitStreamOutCount++;
            if (bitStreamOutCount == 80) {
                fline = fline + "\n";
                bitStreamOutCount = 0;
            }
            bitStreamFile.write(fline);
        } catch (Exception e) {
            // We have a problem
            bitStreamOut = false;
            System.err.println(e);
            e.printStackTrace();
            return false;
        }
        return true;
    }


    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public boolean isSoundCardInput() {
        return soundCardInput;
    }

    // Change the sound source
    public void setSoundCardInput(boolean s) {
        // Try to close the audio device if it is already in operation
        if (this.soundCardInput) inputThread.closeAudio();
        // If the soundcard is already running we need to close it
        if (!s) {
            this.soundCardInput = false;
        } else {
            // CROWD36 , XPA , XPA2 , CIS36-50 , FSK200/500 , FSK200/1000 , CCIR493-4 , GW , RTTY , RDFT , Experimental, F06a
            // why there was this check?
//            if ((system == 0) || (system == 1) || (system == 2) || (system == 3) || (system == 4) || (system == 5) || (system == 6) || (system == 8) || (system == 7) || (system == 9) || (system == 10) || (system == 11) || (system == 12)) {
            WaveData waveSetting = new WaveData();
            waveSetting.setChannels(1);
            waveSetting.setEndian(true);
            waveSetting.setSampleSizeInBits(16);
            waveSetting.setFromFile(false);
            waveSetting.setSampleRate(8000.0);
            waveSetting.setBytesPerFrame(2);
            inputThread.setupAudio(waveSetting);
            waveData = waveSetting;
            this.soundCardInput = true;
//            }
        }
    }

    public void setSoundCardInputOnly(boolean s) {
        this.soundCardInput = s;
    }

    // Reset the decoder state
    public void resetDecoderState() {
        switch (getCurrentMode()) {
            case CROWD36:
                crowd36Handler.setState(0);
                break;
            case XPA10:
            case XPA20:
                xpaHandler.setState(0);
                break;
            case XPA2:
                xpa2Handler.setState(0);
                break;
            case CIS36_50:
                cis3650Handler.setState(0);
                break;
            case F01:
                fsk200500Handler.setState(0);
                break;
            case CCIR493_4:
                ccir493Handler.setState(0);
                break;
            case F06:
                fsk2001000Handler.setState(0);
                break;
            case GWFSK:
                gwHandler.setState(0);
                break;
            case RTTY:
                rttyHandler.setState(0);
                break;
            case FSK:
                fskHandler.setState(0);
                break;
            case F06a:
                f06aHandler.setState(0);
                break;
        }
        // RDFT
        //else if (system==12) rdftHandler.setState(0);
    }

    // Allows the user to set the CROWD36 high sync tone number
    public void setCROWD36SyncHighTone(String sval) {
//        // Create a panel that will contain the sync number
//        JPanel panel = new JPanel();
//        // Set JPanel layout using GridLayout
//        panel.setLayout(new GridLayout(2, 1));
//        // Create a label with text (Username)
//        JLabel label = new JLabel("High Sync Tone Number (0 to 33)");
//        // Create text field that will use to enter the high sync tone
//        JTextField toneField = new JTextField(2);
//        toneField.setText(Integer.toString(crowd36Handler.getSyncHighTone()));
//        panel.add(label);
//        panel.add(toneField);
//        panel.setVisible(true);
//        // Show JOptionPane that will ask user for this information
//        int resp = JOptionPane.showConfirmDialog(window, panel, "Enter the CROWD36 High Sync Tone Number", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
//        if (resp == JOptionPane.OK_OPTION) {
//            String sval = new String(toneField.getText());
        crowd36Handler.setSyncHighTone(Integer.parseInt(sval));
//        }
    }

    public void setBEEOptions(int shift) {
        cis3650Handler.setShift(shift);
    }

    public void setRTTYOptions(double baudrate, int shift, double stopbits) {
        rttyHandler.setBaudRate(baudrate);
        rttyHandler.setShift(shift);
        rttyHandler.setStopBits(stopbits);

        fskHandler.setBaudRate(baudrate);
        fskHandler.setShift(shift);
    }

    public boolean isInvertSignal() {
        return invertSignal;
    }

    @Override
    public Font getItalicFont() {
        return null;
    }

    @Override
    public Font getBoldFont() {
        return null;
    }

    @Override
    public Font getPlainFont() {
        return null;
    }

    @Override
    public Font getBoldMonospaceFont() {
        return null;
    }

    public void setInvertSignal(boolean invertSignal) {
        this.invertSignal = invertSignal;
    }

    public boolean isF06aASCII() {
        return f06aASCII;
    }

    public void setF06aASCII(boolean ascii) {
        this.f06aASCII = ascii;
        if (ascii)
            f06aHandler.setEncoding(1);
        else
            f06aHandler.setEncoding(0);
    }

    // Save the programs settings in the rivet_settings.xml file
    public void saveSettings() {
        FileWriter xmlfile;
        String line;
        // Open the default file settings //
        try {
            xmlfile = new FileWriter("rivet_settings.xml");
            // Start the XML file //
            line = "<?xml version='1.0' encoding='utf-8' standalone='yes'?>\n<settings>\n";
            xmlfile.write(line);
            // Invert
            line = "<invert val='";
            if (invertSignal == true) line = line + "TRUE";
            else line = line + "FALSE";
            line = line + "'/>\n";
            xmlfile.write(line);
            // Debug mode
            line = "<debug val='";
            if (debug == true) line = line + "TRUE";
            else line = line + "FALSE";
            line = line + "'/>\n";
            xmlfile.write(line);
            // Mode
            line = "<mode val='" + getCurrentMode() + "'/>\n";
            xmlfile.write(line);
            // CROWD36 sync tone
            line = "<c36tone val='" + crowd36Handler.getSyncHighTone() + "'/>\n";
            xmlfile.write(line);
            // Soundcard Input Level
            line = "<soundcard_level val='" + soundCardInputLevel + "'/>\n";
            xmlfile.write(line);
            // Soundcard Input
            if (soundCardInput) line = "<soundcard_input val='1'/>\n";
            else line = "<soundcard_input val='0'/>\n";
            xmlfile.write(line);
            // View GW Free Channel Markers
            if (viewGWChannelMarkers) line = "<view_gw_markers val='1'/>\n";
            else line = "<view_gw_markers val='0'/>\n";
            xmlfile.write(line);
            // RTTY & FSK
            // Baud
            line = "<rttybaud val='" + rttyHandler.getBaudRate() + "'/>\n";
            xmlfile.write(line);
            // Shift
            line = "<rttyshift val='" + rttyHandler.getShift() + "'/>\n";
            xmlfile.write(line);
            // Stop bits
            line = "<rttystop val='" + rttyHandler.getStopBits() + "'/>\n";
            xmlfile.write(line);
            // Save the current audio source
            line = "<audioDevice val='" + inputThread.getMixerName() + "'/>\n";
            xmlfile.write(line);
            // Display bad packets
            if (displayBadPackets) line = "<display_bad_packets val='1'/>\n";
            else line = "<display_bad_packets val='0'/>\n";
            xmlfile.write(line);
            // Show UTC time
            if (logInUTC) line = "<UTC val='1'/>\n";
            else line = "<UTC val='0'/>\n";
            xmlfile.write(line);
            // CIS36-50 shift
            line = "<cis3650shift val='" + Integer.toString(cis3650Handler.getShift()) + "'/>\n";
            xmlfile.write(line);
            // All done so close the root item //
            line = "</settings>";
            xmlfile.write(line);
            // Flush and close the file //
            xmlfile.flush();
            xmlfile.close();
        } catch (Exception e) {
            System.err.println("Unable to create the file rivet_settings.xml");
            e.printStackTrace();
        }
        return;
    }

    // Read in the rivet_settings.xml file //
    public void readDefaultSettings() throws SAXException, IOException, ParserConfigurationException {
        // Create a parser factory and use it to create a parser
        SAXParserFactory parserFactory = SAXParserFactory.newInstance();
        SAXParser parser = parserFactory.newSAXParser();
        // This is the name of the file you're parsing
        String filename = "rivet_settings.xml";
        // Instantiate a DefaultHandler subclass to handle events
        DefaultXMLFileHandler handler = new DefaultXMLFileHandler();
        // Start the parser. It reads the file and calls methods of the handler.
        parser.parse(new File(filename), handler);
    }


    // This class handles the rivet_settings.xml SAX events
    public class DefaultXMLFileHandler extends DefaultHandler {
        String value;

        public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
        }

        public void characters(char[] ch, int start, int length) throws SAXException {
            // Extract the element value as a string //
            String tval = new String(ch);
            value = tval.substring(start, (start + length));
        }

        // Handle an XML start element //
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            // Check an element has a value //
            if (attributes.getLength() > 0) {
                // Get the elements value //
                String aval = attributes.getValue(0);
                // Debug mode //
                switch (qName) {
                    case "debug":
                        setDebug(aval.equals("TRUE"));
                        break;
                    // Invert
                    case "invert":
                        setInvertSignal(aval.equals("TRUE"));
                        break;
                    // Mode
                    case "mode":
                        setCurrentMode(RivetMode.valueOf(aval));
                        break;
                    // Crowd36 sync tone
                    case "c36tone":
                        crowd36Handler.setSyncHighTone(Integer.parseInt(aval));
                        break;
                    // Soundcard input level
                    case "soundcard_level":
                        soundCardInputLevel = Integer.parseInt(aval);
                        // Check if this is to high or to low
                        if (soundCardInputLevel < -10) soundCardInputLevel = -10;
                        else if (soundCardInputLevel > 10) soundCardInputLevel = 10;
                        break;
                    // Soundcard input
                    case "soundcard_input":
                        soundCardInputTemp = Integer.parseInt(aval) == 1;
                        break;
                    // View GW Free Channel Markers
                    case "view_gw_markers":
                        viewGWChannelMarkers = Integer.parseInt(aval) == 1;
                        break;
                    // RTTY & FSK Options
                    // Baud rate
                    case "rttybaud":
                        rttyHandler.setBaudRate(Double.parseDouble(aval));
                        fskHandler.setBaudRate(Double.parseDouble(aval));
                        break;
                    // Shift
                    case "rttyshift":
                        rttyHandler.setShift(Integer.parseInt(aval));
                        fskHandler.setShift(Integer.parseInt(aval));
                        break;
                    // Stop bits
                    case "rttystop":
                        rttyHandler.setStopBits(Double.parseDouble(aval));
                        break;
                    // The audio input source
                    case "audioDevice":
                        if (!inputThread.changeMixer(aval)) {
                            System.err.println("Error changing mixer");
                            System.err.println(inputThread.getMixerErrorMessage());
                            System.err.println(aval);
                        }
                        break;
                    // Display bad packets
                    case "display_bad_packets":
                        displayBadPackets = Integer.parseInt(aval) == 1;
                        break;
                    // Show UTC time
                    case "UTC":
                        logInUTC = Integer.parseInt(aval) == 1;
                        break;
                    // CIS36-50 Shift
                    case "cis3650shift":
                        cis3650Handler.setShift(Integer.parseInt(aval));
                        break;
                }
            }
        }
    }


    // Change the invert setting
    public void changeInvertSetting() {
        invertSignal = !invertSignal;
    }

    // Set the soundcard input level in the input thread
    public void setSoundCardLevel(int sli) {
        soundCardInputLevel = sli;
        // Pass this to the input thread
        inputThread.setInputLevel(sli);
    }

    // Returns the current sound card input level
    public int getSoundCardLevel() {
        return soundCardInputLevel;
    }

    public boolean issoundCardInputTemp() {
        return soundCardInputTemp;
    }

    public boolean isBitStreamOut() {
        return bitStreamOut;
    }

    public void setBitStreamOut(boolean bitStreamOut) {
        this.bitStreamOut = bitStreamOut;
    }

    public boolean isViewGWChannelMarkers() {
        return viewGWChannelMarkers;
    }

    public void setViewGWChannelMarkers(boolean viewGWChannelMarkers) {
        this.viewGWChannelMarkers = viewGWChannelMarkers;
    }

    public void clearBitStreamCountOut() {
        bitStreamOutCount = 0;
    }

    // Adds a line to the display
    public void writeLine(String line, Color col, Font font) {
        if (line != null) {
            if (logging) fileWriteLine(line);
            System.out.println(line);
        }
    }

    // Adds a single char to the current line on the display
    public void writeChar(String ct, Color col, Font font) {
        if (ct != null) {
            if (logging) fileWriteChar(ct);
            System.out.print(ct);
        }
    }

    // Writes a new line to the screen
    public void newLineWrite() {
        System.out.println();
        if (logging) fileWriteNewline();
    }

    public List<Trigger> getListTriggers() {
        return listTriggers;
    }

    public void setListTriggers(List<Trigger> listTriggers) {
        this.listTriggers = listTriggers;
        // Count the number of active triggers
        activeTriggerCount = 0;
        int a;
        for (a = 0; a < listTriggers.size(); a++) {
            if (listTriggers.get(a).isActive()) activeTriggerCount++;
        }
    }

    // Read in the trigger.xml file //
    public void readTriggerSettings() throws SAXException, IOException, ParserConfigurationException {
        // Create a parser factory and use it to create a parser
        SAXParserFactory parserFactory = SAXParserFactory.newInstance();
        SAXParser parser = parserFactory.newSAXParser();
        // This is the name of the file you're parsing
        String filename = "trigger.xml";
        // Instantiate a DefaultHandler subclass to handle events
        TriggerXMLFileHandler handler = new TriggerXMLFileHandler();
        // Start the parser. It reads the file and calls methods of the handler.
        parser.parse(new File(filename), handler);
    }


    public int getActiveTriggerCount() {
        return activeTriggerCount;
    }

    // This class handles the rivet_settings.xml SAX events
    public class TriggerXMLFileHandler extends DefaultHandler {
        String value, description, sequence;
        int type, backward, forward;

        // Handle an XML start element
        public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
            // Look for a <trigger> end tag
            if (qName.equals("trigger")) {
                // Put the values in a Trigger object
                Trigger trigger = new Trigger();
                trigger.setTriggerDescription(description);
                trigger.setTriggerSequence(sequence);
                trigger.setTriggerType(type);
                // If type 3 (GRAB) load the forward and backward values
                if (type == 3) {
                    trigger.setForwardGrab(forward);
                    trigger.setBackwardGrab(backward);
                }
                // Add this to the Trigger list
                listTriggers.add(trigger);
            }
        }

        public void characters(char[] ch, int start, int length) throws SAXException {
            // Extract the element value as a string //
            String tval = new String(ch);
            value = tval.substring(start, (start + length));
        }

        // Handle an XML start element //
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            // Check an element has a value //
            if (attributes.getLength() > 0) {
                // Get the elements value //
                String aval = attributes.getValue(0);
                // Trigger Description //
                if (qName.equals("description")) {
                    description = aval;
                }
                // Trigger Sequence
                if (qName.equals("sequence")) {
                    sequence = aval;
                }
                // Trigger Type
                if (qName.equals("type")) {
                    type = Integer.parseInt(aval);
                }
                // Forward grab value
                if (qName.equals("forward")) {
                    forward = Integer.parseInt(aval);
                }
                // Backward grab value
                if (qName.equals("backward")) {
                    backward = Integer.parseInt(aval);
                }
            }

        }
    }

    // Save the current Trigger list to the file trigger.xml
    public boolean saveTriggerXMLFile() {
        try {
            FileWriter xmlfile;
            String line;
            xmlfile = new FileWriter("trigger.xml");
            // Start the XML file //
            line = "<?xml version='1.0' encoding='utf-8' standalone='yes'?>";
            xmlfile.write(line);
            line = "\n<settings>";
            xmlfile.write(line);
            // Run through each Trigger object in the list
            int a;
            for (a = 0; a < listTriggers.size(); a++) {
                line = "\n <trigger>";
                xmlfile.write(line);
                // Description
                line = "\n  <description val='" + listTriggers.get(a).getTriggerDescription() + "'/>";
                xmlfile.write(line);
                // Sequence
                line = "\n  <sequence val='" + listTriggers.get(a).getTriggerSequence() + "'/>";
                xmlfile.write(line);
                // Type
                line = "\n  <type val='" + Integer.toString(listTriggers.get(a).getTriggerType()) + "'/>";
                xmlfile.write(line);
                // If a GRAB (type 3) then save the forward and backward values
                if (listTriggers.get(a).getTriggerType() == 3) {
                    // Forward bits
                    line = "\n  <forward val='" + Integer.toString(listTriggers.get(a).getForwardGrab()) + "'/>";
                    xmlfile.write(line);
                    // Backward bits
                    line = "\n  <backward val='" + Integer.toString(listTriggers.get(a).getBackwardGrab()) + "'/>";
                    xmlfile.write(line);
                }
                line = "\n </trigger>";
                xmlfile.write(line);
            }
            // All done so close the root item //
            line = "\n</settings>";
            xmlfile.write(line);
            // Flush and close the file //
            xmlfile.flush();
            xmlfile.close();

        } catch (Exception e) {
            debugDump("Error writing Triger.xml :" + e.toString());
            return false;
        }
        return true;
    }

    // Change the audio mixer
    public boolean changeMixer(String mixerName) {
        // Tell the audio in thread to change its mixer
        return inputThread.changeMixer(mixerName);
    }

    // Write system information for diagnostic purposes to the screen
    public void displaySystemInfo() {
        // Version
        writeLine(program_version, Color.BLACK, null);
        // Cores
        String cores = "Available processors (cores): " + Runtime.getRuntime().availableProcessors();
        writeLine(cores, Color.BLACK, null);
        // Memory available to the JVM
        String jmem = "JVM Free memory (bytes): " + Runtime.getRuntime().freeMemory();
        writeLine(jmem, Color.BLACK, null);
        // OS
        String os = "OS : " + System.getProperty("os.name") + " (" + System.getProperty("os.version") + ")";
        writeLine(os, Color.BLACK, null);
        // Java version
        String jver = "Java : " + System.getProperty("java.vendor") + " (" + System.getProperty("java.version") + ")";
        writeLine(jver, Color.BLACK, null);
        // Folder
        String folder = "Working directory : " + System.getProperty("user.dir");
        writeLine(folder, Color.BLACK, null);
        // Current Time
        String time = "Current Time : " + getTimeStamp();
        writeLine(time, Color.BLACK, null);
    }

    public boolean isDisplayBadPackets() {
        return displayBadPackets;
    }

    public void setDisplayBadPackets(boolean displayBadPackets) {
        this.displayBadPackets = displayBadPackets;
    }

    public boolean isLogInUTC() {
        return logInUTC;
    }

    public void setLogInUTC(boolean logInUTC) {
        this.logInUTC = logInUTC;
    }

    // Clear the list of logged ships
    public void clearLoggedShipsList() {
        listLoggedShips.clear();
    }

    // Given a ships MMSI check if it is in ships.xml or not and log it
    public void logShip(String mmsi) {
        UserIdentifier uid = new UserIdentifier();
        // First check if a ship has already been logged and is in the list
        int a;
        for (a = 0; a < listLoggedShips.size(); a++) {
            if (listLoggedShips.get(a).getMmsi().equals(mmsi)) {
                // Increment the log count and return
                listLoggedShips.get(a).incrementLogCount();
                return;
            }
        }
        // Now check if the ship is in ships.xml
        Ship loggedShip = uid.getShipDetails(mmsi);
        // If null then we need to create a ship object
        if (loggedShip == null) {
            Ship newShip = new Ship();
            newShip.setMmsi(mmsi);
            newShip.incrementLogCount();
            listLoggedShips.add(newShip);
        } else {
            // Increment the ship objects log counter and add it to the list
            loggedShip.incrementLogCount();
            listLoggedShips.add(loggedShip);
        }
    }

    // Return a list of all logged ships
    public String getShipList() {
        StringBuilder sb = new StringBuilder();
        // No ships logged
        if (listLoggedShips.isEmpty()) return "\r\n\r\nNo ships were logged.";
        // Show the number of ships logged
        if (listLoggedShips.size() == 1) sb.append("\r\n\r\nYou logged one ship.");
        else sb.append("\r\n\r\nYou logged " + Integer.toString(listLoggedShips.size()) + " ships.");
        // Display the ships
        int a;
        for (a = 0; a < listLoggedShips.size(); a++) {
            // MMSI
            sb.append("\r\nMMSI " + listLoggedShips.get(a).getMmsi());
            // Name and flag (if we have them)
            if (listLoggedShips.get(a).getName() != null) {
                sb.append(" " + listLoggedShips.get(a).getName() + " " + listLoggedShips.get(a).getFlag());
            }
            // Number of times logged
            sb.append(" (" + Integer.toString(listLoggedShips.get(a).getLogCount()) + ")");
        }
        return sb.toString();
    }

    public void setSystem(int system) {
    }
}
