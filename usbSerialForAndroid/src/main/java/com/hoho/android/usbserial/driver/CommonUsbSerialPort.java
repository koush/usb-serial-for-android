/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.annotation.SuppressLint;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A base class shared by several driver implementations.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
abstract class CommonUsbSerialPort implements UsbSerialPort {

    public static final int DEFAULT_READ_BUFFER_SIZE = 16 * 1024;
    public static final int DEFAULT_WRITE_BUFFER_SIZE = 16 * 1024;

    protected final UsbDevice mDevice;
    protected final int mPortNumber;

    // non-null when open()
    protected UsbDeviceConnection mConnection = null;

    protected final Object mReadBufferLock = new Object();
    protected final Object mWriteBufferLock = new Object();

    /** Internal read buffer.  Guarded by {@link #mReadBufferLock}. */
    protected byte[] mReadBuffer;

    /** Internal write buffer.  Guarded by {@link #mWriteBufferLock}. */
    protected byte[] mWriteBuffer;

    protected boolean mEnableAsyncReads;
    protected UsbEndpoint mReadEndpoint;
    protected UsbEndpoint mWriteEndpoint;

    public CommonUsbSerialPort(UsbDevice device, int portNumber) {
        mDevice = device;
        mPortNumber = portNumber;

        mReadBuffer = new byte[DEFAULT_READ_BUFFER_SIZE];
        mWriteBuffer = new byte[DEFAULT_WRITE_BUFFER_SIZE];
    }
    
    @Override
    public String toString() {
        return String.format("<%s device_name=%s device_id=%s port_number=%s>",
                getClass().getSimpleName(), mDevice.getDeviceName(),
                mDevice.getDeviceId(), mPortNumber);
    }

    /**
     * Returns the currently-bound USB device.
     *
     * @return the device
     */
    public final UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public int getPortNumber() {
        return mPortNumber;
    }
    
    /**
     * Returns the device serial number
     *  @return serial number
     */
    @Override
    public String getSerial() {
        return mConnection.getSerial();
    }

    /**
     * Sets the size of the internal buffer used to exchange data with the USB
     * stack for read operations.  Most users should not need to change this.
     *
     * @param bufferSize the size in bytes
     */
    public final void setReadBufferSize(int bufferSize) {
        synchronized (mReadBufferLock) {
            if (bufferSize == mReadBuffer.length) {
                return;
            }
            mReadBuffer = new byte[bufferSize];
        }
    }

    /**
     * Sets the size of the internal buffer used to exchange data with the USB
     * stack for write operations.  Most users should not need to change this.
     *
     * @param bufferSize the size in bytes
     */
    public final void setWriteBufferSize(int bufferSize) {
        synchronized (mWriteBufferLock) {
            if (bufferSize == mWriteBuffer.length) {
                return;
            }
            mWriteBuffer = new byte[bufferSize];
        }
    }

    @Override
    public abstract void open(UsbDeviceConnection connection) throws IOException;

    @Override
    public abstract void close() throws IOException;

    @SuppressLint("NewApi")
    @Override
    public int read(byte[] dest, int offset, int length, int timeoutMillis) throws IOException {
        if (mEnableAsyncReads) {
            final UsbRequest request = new UsbRequest();
            try {
                request.initialize(mConnection, mReadEndpoint);
                final ByteBuffer buf = ByteBuffer.wrap(dest, offset, length);
                if (!request.queue(buf)) {
                    throw new IOException("Error queueing request.");
                }

                final UsbRequest response = mConnection.requestWait();
                if (response == null) {
                    throw new IOException("Null response");
                }

                final int nread = buf.position() - offset;
                if (nread > 0) {
                    //Log.d(TAG, HexDump.dumpHexString(dest, 0, Math.min(32, dest.length)));
                    return nread;
                } else {
                    return 0;
                }
            } finally {
                request.close();
            }
        }

        final int numBytesRead;
        synchronized (mReadBufferLock) {
            numBytesRead = mConnection.bulkTransfer(mReadEndpoint, dest, offset, length,
                    timeoutMillis);
            if (numBytesRead < 0) {
                // This sucks: we get -1 on timeout, not 0 as preferred.
                // We *should* use UsbRequest, except it has a bug/api oversight
                // where there is no way to determine the number of bytes read
                // in response :\ -- http://b.android.com/28023

                // @koush: Workaround is to never block/loop on this reads, and watch writes for
                // exceptions to cancel the reads.

                if (timeoutMillis == Integer.MAX_VALUE) {
                    // Hack: Special case "~infinite timeout" as an error.
                    return -1;
                }

                return 0;
            }
        }
        return numBytesRead;
    }

    @Override
    public int write(byte[] src, int offset, int length, int timeoutMillis) throws IOException {
        final int amtWritten;

        synchronized (mWriteBufferLock) {
            amtWritten = mConnection.bulkTransfer(mWriteEndpoint, src, length,
                    timeoutMillis);
        }
        if (amtWritten <= 0) {
            throw new IOException("Error writing " + length
                    + " bytes at offset " + offset);
        }

        return amtWritten;
    }

    @Override
    public abstract void setParameters(
            int baudRate, int dataBits, int stopBits, int parity) throws IOException;

    @Override
    public abstract boolean getCD() throws IOException;

    @Override
    public abstract boolean getCTS() throws IOException;

    @Override
    public abstract boolean getDSR() throws IOException;

    @Override
    public abstract boolean getDTR() throws IOException;

    @Override
    public abstract void setDTR(boolean value) throws IOException;

    @Override
    public abstract boolean getRI() throws IOException;

    @Override
    public abstract boolean getRTS() throws IOException;

    @Override
    public abstract void setRTS(boolean value) throws IOException;

    @Override
    public boolean purgeHwBuffers(boolean flushReadBuffers, boolean flushWriteBuffers) throws IOException {
        return !flushReadBuffers && !flushWriteBuffers;
    }

}
