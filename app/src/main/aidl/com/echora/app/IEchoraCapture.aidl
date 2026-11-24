package com.echora.app;

import com.echora.app.IMicStateCallback;

interface IEchoraCapture {
    void open(int source, int sampleRate, int channels, int bufferBytes);
    void start();
    int read(in byte[] buffer, int sizeBytes);
    void release();
    boolean isMicrophoneDisabled();
    void setMicListener(IMicStateCallback cb);
    int actualSampleRate();
    int actualChannels();
}