package com.echora.spike;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Looper;

import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Misura quali sorgenti AudioRecord producono PCM non silenzioso su questo device.
 * Va eseguito come root con app_process, fuori da un'app installata.
 */
public final class Spike {
    private static final int[] SOURCES = {1999, 4, 8, 1};
    private static final int[][] FORMATS = {{16000, 1}, {48000, 2}};
    private static final int BLOCK_BYTES = 3200;
    private static final long DURATION_MS = 3000;

    public static void main(String[] args) throws Exception {
        System.out.println("sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE);
        for (Constructor<?> c : AudioRecord.class.getDeclaredConstructors()) {
            System.out.println("ctor: " + c);
        }

        Context context = systemContext();
        System.out.println("context=" + context + " package=" + context.getPackageName()
                + " attribution=" + context.getAttributionSource());

        Method setSource = AudioAttributes.Builder.class
                .getDeclaredMethod("setInternalCapturePreset", int.class);
        setSource.setAccessible(true);
        Constructor<AudioRecord> recordCtor = findRecordConstructor();
        recordCtor.setAccessible(true);
        System.out.println("using ctor: " + recordCtor);

        for (int source : SOURCES) {
            for (int[] format : FORMATS) {
                measure(source, format[0], format[1], context, setSource, recordCtor);
            }
        }
    }

    private static Context systemContext() throws Exception {
        Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    /** Costruttore con prefisso (AudioAttributes, AudioFormat, int, int, Context) e coda di int. */
    private static Constructor<AudioRecord> findRecordConstructor() {
        Constructor<?> best = null;
        for (Constructor<?> c : AudioRecord.class.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length < 5 || p[0] != AudioAttributes.class || p[1] != AudioFormat.class
                    || p[2] != int.class || p[3] != int.class || p[4] != Context.class) {
                continue;
            }
            boolean tailIsInt = true;
            for (int i = 5; i < p.length; i++) {
                tailIsInt &= p[i] == int.class;
            }
            if (tailIsInt && (best == null || p.length > best.getParameterTypes().length)) {
                best = c;
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    "nessun AudioRecord(AudioAttributes, AudioFormat, int, int, Context, int...)");
        }
        @SuppressWarnings("unchecked")
        Constructor<AudioRecord> found = (Constructor<AudioRecord>) best;
        return found;
    }

    private static void measure(int source, int rate, int channels, Context context,
            Method setSource, Constructor<AudioRecord> recordCtor) {
        String label = "source=" + source + " rate=" + rate + " ch=" + channels;
        int mask = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        AudioRecord record = null;
        FileOutputStream out = null;
        try {
            AudioAttributes.Builder attributes = new AudioAttributes.Builder();
            setSource.invoke(attributes, source);
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build();
            int bufferSize = Math.max(
                    AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT),
                    BLOCK_BYTES * 4);

            record = recordCtor.newInstance(
                    ctorArgs(recordCtor.getParameterTypes(), attributes.build(), format,
                            bufferSize, context));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                System.out.println(label + " state=" + record.getState() + " non inizializzato");
                return;
            }
            record.startRecording();

            String path = "/data/local/tmp/spike-" + source + "-" + rate + ".pcm";
            out = new FileOutputStream(path);
            byte[] buffer = new byte[BLOCK_BYTES];
            long deadline = System.nanoTime() + DURATION_MS * 1_000_000L;
            long sumSquares = 0;
            long samples = 0;
            long readBytes = 0;
            while (System.nanoTime() < deadline) {
                int n = record.read(buffer, 0, buffer.length);
                if (n < 0) {
                    System.out.println(label + " readBytes=" + readBytes + " readError=" + n);
                    return;
                }
                if (n == 0) {
                    continue;
                }
                readBytes += n;
                out.write(buffer, 0, n);
                for (int i = 0; i + 1 < n; i += 2) {
                    int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                    sumSquares += (long) sample * sample;
                    samples++;
                }
            }
            double rms = samples == 0 ? 0 : Math.sqrt((double) sumSquares / samples);
            System.out.println(label + " readBytes=" + readBytes + " rms=" + (long) rms
                    + " file=" + path);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println(label + " FAIL " + cause.getClass().getName() + ": "
                    + cause.getMessage());
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                    // mai avviato
                }
                record.release();
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // niente da salvare
                }
            }
        }
    }

    private static Object[] ctorArgs(Class<?>[] types, AudioAttributes attributes,
            AudioFormat format, int bufferSize, Context context) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == AudioAttributes.class) {
                args[i] = attributes;
            } else if (type == AudioFormat.class) {
                args[i] = format;
            } else if (type == Context.class) {
                args[i] = context;
            } else if (type == int.class) {
                args[i] = i == 2 ? bufferSize : 0;
            } else {
                throw new IllegalArgumentException("parametro costruttore inatteso: " + type);
            }
        }
        return args;
    }
}
