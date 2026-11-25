package com.echora.app;

import com.echora.app.IMicStateCallback;

interface IEchoraCapture {
    void open(int source, int sampleRate, int channels, int bufferBytes) = 0;
    void start() = 1;
    int read(out byte[] buffer, int sizeBytes) = 2;
    void release() = 3;
    boolean isMicrophoneDisabled() = 4;
    void setMicListener(IMicStateCallback cb) = 5;
    int actualSampleRate() = 6;
    int actualChannels() = 7;
    void enableVoipPlayback(int sampleRate) = 8;
    void destroy() = 16777114;
}
