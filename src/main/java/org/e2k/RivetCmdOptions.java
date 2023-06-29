package org.e2k;

import org.apache.commons.cli.*;

import java.util.Arrays;

public class RivetCmdOptions {
    private CommandLine cmdLine;

    public enum OptionName {
        MODE("m", "mode", "mode name, mandatory", true, true),
        INPUT_FILE("i", "infile", "WAV input filename, \"-\" for STDIN", true, true),
        FSK_SHIFT(null, "shift", "FSK modes shift", false, true),
        FSK_BAUD(null, "baudrate", "FSK modes baudrate", false, true);

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
        try {
            RivetMode.valueOf(getOptionValue(OptionName.MODE));
        } catch (Exception e) {
            System.err.println("Unknown mode");
            errorFound = true;
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
