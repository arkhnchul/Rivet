package org.e2k;

import org.apache.commons.cli.*;

import java.util.Arrays;

public class RivetCmdOptions {
    private CommandLine cmdLine;

    public enum OptionName {
        MODE("m", "mode", "mode name, mandatory", true, true),
        INPUT_FILE("i", "infile", "WAV input filename, \"-\" for STDIN", true, true),
        FSK_SHIFT(null, "shift", "FSK modes shift (FSK, RTTY, CIS36-50)", false, true),
        FSK_BAUD(null, "baudrate", "FSK modes baudrate (FSK, RTTY)", false, true),
        FSK_STOPBITS(null, "stopbits", "FSK modes stopbits (RTTY)", false, true),
        CROWD36_HIGHSYNC(null, "highsync", "CROWD36 High sync tone number (0-33)", false, true);

        public final String shortOpt;
        public final String longOpt;
        public final String description;
        public final boolean required;
        public final boolean hasArg;

        OptionName(String shortOpt, String longOpt, String description, boolean required, boolean hasArg) {
            this.shortOpt = shortOpt;
            this.longOpt = longOpt;
            this.description = description;
            this.required = required;
            this.hasArg = hasArg;
        }
    }

    public static Options buildOptions() {
        Options options = new Options();
        for (OptionName tmpOption : OptionName.values()) {
            options.addOption(Option.builder(tmpOption.shortOpt)
                    .longOpt(tmpOption.longOpt)
                    .desc(tmpOption.description)
                    .required(tmpOption.required)
                    .hasArg(tmpOption.hasArg)
                    .build());
        }
        return options;
    }

    public static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("RivetCLI.class [ OPTIONS ]", buildOptions());
        System.out.println("Available modes:");
        System.out.println(Arrays.toString(RivetMode.values()));
        System.out.println("FSK parameters accept arbitrary values.");
    }

    public void parseOptions(String[] args) {
        Options cmdOptions = buildOptions();
        CommandLineParser parser = new DefaultParser();
        try {
            cmdLine = parser.parse(cmdOptions, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            RivetCmdOptions.printHelp();
            System.exit(0);
        }
    }

    public void checkOptionsSanity() {
        boolean errorFound = false;

        for (OptionName tmpOption : OptionName.values()) {
            if (cmdLine.hasOption(tmpOption.longOpt)) {
                try {
                    String value = getOptionValue(tmpOption);
                    switch (tmpOption) {
                        case MODE:
                            RivetMode.valueOf(value);
                            break;
                        case FSK_STOPBITS:
                        case FSK_BAUD:
                            Double.parseDouble(value);
                            break;
                        case FSK_SHIFT:
                            Integer.parseInt(value);
                            break;
                        case CROWD36_HIGHSYNC:
                            int highsync = Integer.parseInt(value);
                            if (highsync < 0 || highsync > 33)
                                throw new IllegalArgumentException();
                            break;
                    }
                } catch (Exception e) {
                    System.err.println("Wrong value for " + tmpOption.longOpt + ": " + getOptionValue(tmpOption));
                    errorFound = true;
                }
            }
        }

        if (errorFound) {
            RivetCmdOptions.printHelp();
            System.exit(0);
        }
    }

    public String getOptionValue(String option) {
        if (cmdLine == null)
            return null;
        return cmdLine.getOptionValue(option);
    }

    public String getOptionValue(OptionName option) {
        return getOptionValue(option.longOpt);
    }
}
