package com.anil.jarvis;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/** 🌱 The ESP32's USB serial chip (CP2102 / CH340 / CH9102) as {@link EspFlash}'s line, through usb-serial-for-android. */
final class UsbLine implements EspFlash.Line {
    private final UsbSerialPort port;

    UsbLine(UsbSerialPort port) { this.port = port; }

    @Override public void write(byte[] b) throws IOException { port.write(b, 3000); }

    @Override public int read(byte[] buf, int timeoutMs) throws IOException {
        return Math.max(0, port.read(buf, Math.max(1, timeoutMs))); // (0 would mean "wait for ever")
    }

    @Override public void dtr(boolean on) throws IOException { port.setDTR(on); }

    @Override public void rts(boolean on) throws IOException { port.setRTS(on); }

    @Override public void baud(int baud) throws IOException {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
    }

    @Override public void flushIn() throws IOException {
        byte[] t = new byte[4096];
        for (int i = 0; i < 50 && port.read(t, 15) > 0; i++) { /* (what came before: not needed) */ }
    }

    void close() {
        try { port.close(); } catch (Exception ignored) {}
    }
}
