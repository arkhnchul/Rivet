package org.e2k;

import java.awt.*;
import java.util.List;

public interface RivetApp {
    void setStatusLabel (String st);
    void setInvertSignal(boolean invertSignal);
    void writeLine(String line, Color col, Font font);
    void writeChar (String ct,Color col,Font font);
    String getTimeStamp();
    void newLineWrite();
    boolean isInvertSignal();

    Font getItalicFont();
    Font getBoldFont();
    Font getPlainFont();
    Font getBoldMonospaceFont();

    boolean isDebug();
    boolean isBitStreamOut();
    boolean bitStreamWrite(String fline);
    void setSystem(int system);
    void setModeLabel (String st);
    void setF06aASCII(boolean ascii);
    boolean isDisplayBadPackets();
    void clearLoggedShipsList();
    void logShip (String mmsi);
    boolean isViewGWChannelMarkers();
    int getActiveTriggerCount();
    List<Trigger> getListTriggers();
    void setSoundCardInputOnly(boolean s);
}
