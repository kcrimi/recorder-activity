package com.example.kevin.recordedactivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;

/**
 * Created by kevin on 12/24/16.
 */

public abstract class RecordedActivity extends Activity {

    private MediaProjectionManager mMediaProjectionManager;
    private static final int REQUEST_CODE_CAPTURE_PERM = 1234;

    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final String MIME_TYPE_AUDIO = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    public static final int CHANNEL_COUNT = 1;
    public static final int BIT_RATE_AUDIO = 128000;
    public static final int SAMPLES_PER_FRAME = 1024;
    public static final int FRAMES_PER_BUFFER = 30;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;

    private boolean mMuxerStarted = false;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private AudioRecord mAudioRecorder;
    private MediaMuxer mMuxer;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private boolean mRequestSent = false;
    private boolean mReceivedApproval;

    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;

    private final Object mMuxerLock = new Object();
    private final Object mAudioEncoderLock = new Object();
    private final Object mWriteVideoLock = new Object();
    private final Object mWriteAudioLock = new Object();

    private volatile boolean mVideoEncoding;
    private volatile boolean mAudioEncoding;
    private volatile boolean mAudioRecording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(
                android.content.Context.MEDIA_PROJECTION_SERVICE);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (REQUEST_CODE_CAPTURE_PERM == requestCode) {
            if (resultCode == RESULT_OK) {
                mReceivedApproval = true;
                mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, intent);
                startRecording(); // defined below
            } else {
                Toast.makeText(this, "Permission needed to start digital transport", Toast.LENGTH_SHORT).show();
                finish();
                // user did not grant permissions
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mRequestSent) {
            mRequestSent = true;
            Intent permissionIntent = mMediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(permissionIntent, REQUEST_CODE_CAPTURE_PERM);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mReceivedApproval) {
            mReceivedApproval = false;
            mRequestSent = false;
        }
    }

    private void startRecording() {
        DisplayManager dm = (DisplayManager)getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (defaultDisplay == null) {
            throw new RuntimeException("No display found.");
        }
        prepareVideoEncoder();
        prepareAudioEncoder();
        prepareAudioRecorder();

        try {
            Date now = new Date();
            String time = now.getHours()+"-"+now.getMinutes();
            mMuxer = new MediaMuxer("/sdcard/test-video"+time+".mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        // Get the display size and density.
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int screenDensity = metrics.densityDpi;

        // Start the video input.
        mMediaProjection.createVirtualDisplay("Recording Display", screenWidth,
                screenHeight, screenDensity, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);

        // Start the encoders
        new Thread(new VideoEncoderTask(), "VideoEncoderTask").start();
        new Thread(new AudioEncoderTask(), "AudioEncoderTask").start();
    }

    public void prepareAudioRecorder() {
        int iMinBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

        int bufferSize = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;

        // Ensure buffer is adequately sized for the AudioRecord
        // object to initialize
        if (bufferSize < iMinBufferSize)
            bufferSize = ((iMinBufferSize / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

        mAudioRecorder = new AudioRecord(
            AUDIO_SOURCE, // source
            SAMPLE_RATE, // sample rate, hz
            CHANNEL_CONFIG, // channels
            AUDIO_FORMAT, // audio format
            bufferSize); // buffer size (bytes)

        mAudioRecorder.startRecording();
        mAudioRecording = true;

        new Thread(new AudioRecorderTask(), "AudioRecorderTask").start();
    }

    private void prepareVideoEncoder() {
        DisplayMetrics displaymetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        int height = displaymetrics.heightPixels;
        int width = displaymetrics.widthPixels;
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
        int frameRate = 30; // 30 fps

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (IOException e) {
            releaseEncoders();
        }
    }

    public void prepareAudioEncoder() {
        // prepare audio format
        MediaFormat mAudioFormat = MediaFormat.createAudioFormat(MIME_TYPE_AUDIO, SAMPLE_RATE, CHANNEL_COUNT);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_AUDIO);

        try {
            mAudioEncoder = MediaCodec.createEncoderByType(MIME_TYPE_AUDIO);
            mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();
        } catch (IOException e) {
            releaseEncoders();
        }
    }

    private void releaseEncoders() {
        mAudioRecording = false;
        mAudioEncoding = false;
        mVideoEncoding = false;
    }

    @Override
    public void onBackPressed() {
        releaseEncoders();
        super.onBackPressed();
    }


    private class AudioRecorderTask implements Runnable {
        ByteBuffer inputBuffer;
        int readResult;

        @Override
        public void run() {
            long audioPresentationTimeNs;

            byte[] mTempBuffer = new byte[SAMPLES_PER_FRAME];

            while (mAudioRecording) {
                audioPresentationTimeNs = System.nanoTime();

                readResult = mAudioRecorder.read(mTempBuffer, 0, SAMPLES_PER_FRAME);
                if(readResult == AudioRecord.ERROR_BAD_VALUE || readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                    continue;
                }

                // send current frame data to encoder
                try {
                    synchronized (mAudioEncoderLock) {
                        if (mAudioEncoding) {
                            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
                            if (inputBufferIndex >= 0) {
                                inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                                inputBuffer.clear();
                                inputBuffer.put(mTempBuffer);

                                mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, mTempBuffer.length, audioPresentationTimeNs / 1000, 0);
                            }
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            // finished recording -> send it to the encoder
            audioPresentationTimeNs = System.nanoTime();

            readResult = mAudioRecorder.read(mTempBuffer, 0, SAMPLES_PER_FRAME);
            if (readResult == AudioRecord.ERROR_BAD_VALUE
                || readResult == AudioRecord.ERROR_INVALID_OPERATION)

            // send current frame data to encoder
            try {
                synchronized (mAudioEncoderLock) {
                    if (mAudioEncoding) {
                        int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
                        if (inputBufferIndex >= 0) {
                            inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                            inputBuffer.clear();
                            inputBuffer.put(mTempBuffer);

                            mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, mTempBuffer.length, audioPresentationTimeNs / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }


            mAudioRecorder.stop();
            mAudioRecorder.release();
            mAudioRecorder = null;
        }
    }

    private class VideoEncoderTask implements Runnable {
        private MediaCodec.BufferInfo videoBufferInfo;

        @Override
        public void run(){
            mVideoEncoding = true;

            videoBufferInfo = new MediaCodec.BufferInfo();

            while(mVideoEncoding){
                int bufferIndex = mVideoEncoder.dequeueOutputBuffer(videoBufferInfo, 10);

                if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // nothing available yet
                } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                    if (videoTrackIndex >= 0) {
                        throw new RuntimeException("format changed twice");
                    }

                    synchronized (mMuxerLock) {
                        videoTrackIndex = mMuxer.addTrack(mVideoEncoder.getOutputFormat());

                        if (!mMuxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            mMuxer.start();
                            mMuxerStarted = true;
                        }
                    }
                } else if (bufferIndex < 0) {
                    // not sure what's going on, ignore it
                } else {
                    ByteBuffer videoData = mVideoEncoder.getOutputBuffer(bufferIndex);

                    if (videoData == null) {
                        throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                    }

                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        videoBufferInfo.size = 0;
                    }


                    if (videoBufferInfo.size != 0) {
                        if (mMuxerStarted) {
                            videoData.position(videoBufferInfo.offset);
                            videoData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                            synchronized (mWriteVideoLock) {
                                if (mMuxerStarted) {
                                    mMuxer.writeSampleData(videoTrackIndex, videoData, videoBufferInfo);
                                }
                            }
                        } else {
                            // muxer not started
                        }
                    }

                    mVideoEncoder.releaseOutputBuffer(bufferIndex, false);

                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        mVideoEncoding = false;
                        break;
                    }
                }
            }


            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;

            if (mInputSurface != null) {
                mInputSurface.release();
                mInputSurface = null;
            }
            if (mMediaProjection != null) {
                mMediaProjection.stop();
                mMediaProjection = null;
            }

            synchronized (mWriteAudioLock) {
                synchronized (mMuxerLock) {
                    if (mMuxer != null) {
                        if (mMuxerStarted) {
                            mMuxer.stop();
                        }
                        mMuxer.release();
                        mMuxer = null;
                        mMuxerStarted = false;
                    }
                }
            }
        }


    }

    private class AudioEncoderTask implements Runnable {
        private MediaCodec.BufferInfo audioBufferInfo;

        @Override
        public void run(){
            mAudioEncoding = true;

            audioBufferInfo = new MediaCodec.BufferInfo();

            while(mAudioEncoding){
                int bufferIndex = mAudioEncoder.dequeueOutputBuffer(audioBufferInfo, 10);

                if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                    if (audioTrackIndex >= 0) {
                        throw new RuntimeException("format changed twice");
                    }

                    synchronized (mMuxerLock) {
                        audioTrackIndex = mMuxer.addTrack(mAudioEncoder.getOutputFormat());

                        if (!mMuxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            mMuxer.start();
                            mMuxerStarted = true;
                        }
                    }
                } else if (bufferIndex < 0) {
                    // let's ignore it
                } else {
                    if (mMuxerStarted && audioTrackIndex >= 0) {
                        ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(bufferIndex);
                        if (encodedData == null) {
                            throw new RuntimeException("encoderOutputBuffer " + bufferIndex + " was null");
                        }

                        if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // The codec config data was pulled out and fed to the muxer when we got
                            // the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                            audioBufferInfo.size = 0;
                        }

                        if (audioBufferInfo.size != 0) {
                            if (mMuxerStarted) {
                                // adjust the ByteBuffer values to match BufferInfo (not needed?)
                                encodedData.position(audioBufferInfo.offset);
                                encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);
                                synchronized (mWriteAudioLock) {
                                    if (mMuxerStarted) {
                                        mMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                                    }
                                }
                            }
                        }

                        mAudioEncoder.releaseOutputBuffer(bufferIndex, false);

                        if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            // reached EOS
                            mAudioEncoding = false;
                            break;
                        }
                    }
                }
            }


            synchronized (mAudioEncoderLock) {
                mAudioEncoder.stop();
                mAudioEncoder.release();
                mAudioEncoder = null;
            }

            synchronized (mWriteVideoLock) {
                synchronized (mMuxerLock) {
                    if (mMuxer != null) {
                        if (mMuxerStarted) {
                            mMuxer.stop();
                        }
                        mMuxer.release();
                        mMuxer = null;
                        mMuxerStarted = false;
                    }
                }
            }
        }

    }
}
