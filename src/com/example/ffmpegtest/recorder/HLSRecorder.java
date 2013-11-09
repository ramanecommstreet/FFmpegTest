/*
 * Copyright (c) 2013, David Brodsky. All rights reserved.
 *
 *	This program is free software: you can redistribute it and/or modify
 *	it under the terms of the GNU General Public License as published by
 *	the Free Software Foundation, either version 3 of the License, or
 *	(at your option) any later version.
 *	
 *	This program is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU General Public License for more details.
 *	
 *	You should have received a copy of the GNU General Public License
 *	along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * This file was derived from work authored by the Android Open Source Project
 * Specifically http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
 * Below is the original license, but note this adaptation is 
 * licensed under GPLv3
 * 
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Enormous thanks to Andrew McFadden for his MediaCodec work!

package com.example.ffmpegtest.recorder;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.*;
import android.opengl.*;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.UUID;

import com.example.ffmpegtest.FFmpegWrapper;
import com.example.ffmpegtest.FileUtils;

/**
 * Records video in gapless chunks of fixed duration.
 *
 * This was derived from Andrew McFadden's MediaCodec examples:
 * http://bigflake.com/mediacodec
 */
public class HLSRecorder {
	// Debugging
    private static final String TAG = "HLSRecorder";
    private static final boolean VERBOSE = false;           			// Lots of logging
    private static final boolean TRACE = false; 						// Enable systrace markers
    int totalFrameCount = 0;											// Used to calculate realized FPS
    long startTime;
    
    // Output
    private static String mRootStorageDirName = "HLSRecorder";				// The root storage directory
    private MediaFormat mVideoFormat;
    private String mUUID;
    private File mOutputDir;											// Folder containing recording files. /path/to/externalStorage/mOutputDir/<mUUID>/
    
    // Video Encoder
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private CodecInputSurface mInputSurface;							// Encoder input Surface
    private TrackInfo mVideoTrackInfo;									// Track meta data for Muxer
    private static final String VIDEO_MIME_TYPE = "video/avc";    		// H.264 Advanced Video Coding
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";    // AAC Low Overhead Audio Transport Multiplex
    private static final int VIDEO_WIDTH 		= 640;
    private static final int VIDEO_HEIGHT 		= 480;
    private static final int FRAME_RATE 		= 30;
    private static final int IFRAME_INTERVAL 	= 3;           			// Seconds between I-frames
    
    // Audio Encoder and Configuration
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mAudioBufferInfo;
    private TrackInfo mAudioTrackInfo;
    private MediaFormat mAudioFormat;									// Configured with the options below
    public static final int SAMPLE_RATE 		= 44100;
    public static final int SAMPLES_PER_FRAME 	= 1024; 				// AAC frame size. Audio encoder input size is a multiple of this
    public static final int CHANNEL_CONFIG 		= AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT 		= AudioFormat.ENCODING_PCM_16BIT;
    
    // Audio sampling interface
    private AudioRecord audioRecord;
    
    // Recycled storage for Audio packets as we write ADTS header
    byte[] audioPacket = new byte[2048]; 
    int ADTS_LENGTH = 7;	// ADTS Header length
    
    // Camera
    private Camera mCamera;
    private SurfaceTextureManager mStManager;

    // Recording state
    private int leadingChunk = 1;
    long startWhen;
    boolean fullStopReceived = false;
    boolean videoEncoderStopped = false;			// these variables keep track of global recording state. They are not toggled during chunking
    boolean audioEncoderStopped = false;

    // Synchronization
    public Object sync = new Object();				// Synchronize access to muxer across Audio and Video encoding threads

    // FFmpegWrapper
    FFmpegWrapper ffmpeg = new FFmpegWrapper();		// Used to Mux encoded audio and video output from MediaCodec
    
    Context c;										// For accessing external storage

    // Manage Track meta data to pass to Muxer
    class TrackInfo {
        int index = 0;
    }

    boolean firstFrameReady = false;
    boolean eosReceived = false;
    
    public HLSRecorder(Context c){
    	this.c = c;
    }
    
    public String getUUID(){
    	return mUUID;
    }
    
    public String getOutputPath(){
    	if(mOutputDir == null){
    		Log.w(TAG, "getOutputPath called in invalid state");
    		return "";
    	}
    	return mOutputDir.getAbsolutePath();
    }
    
    /* TODO: Display surface
    public void setDisplayEGLContext(EGLContext context){
        mInputSurface.mEGLDisplayContext = context;
    }
    */

    /**
     * Start recording within the given root directory
     * The recording files will be placed in:
     * outputDir/<UUID>/
     * @param outputDir
     */
    public void startRecording(final String outputDir){
        if(outputDir != null)
            mRootStorageDirName = outputDir;
        mUUID = UUID.randomUUID().toString();
        mOutputDir = FileUtils.getStorageDirectory(FileUtils.getRootStorageDirectory(c, mRootStorageDirName), mUUID);
        
        // TODO: Create Base HWRecorder class and subclass to provide output format, codecs etc
        ffmpeg.prepareAVFormatContext(mOutputDir.getAbsolutePath() + File.separator + "ffmpeg_" + System.currentTimeMillis() + ".m3u8");
        new Thread(new Runnable(){

            @Override
            public void run() {
                _startRecording(outputDir);
            }
        }, TAG).start();
    }

    /**
     * This method prepares and starts the ChunkedHWRecorder
     * The loop
     * @param outputDir
     */
    private void _startRecording(String outputDir){
        int encBitRate = 1000000;      // bps
        //int framesPerChunk = (int) CHUNK_DURATION_SEC * FRAME_RATE;
        Log.d(TAG, VIDEO_MIME_TYPE + " output " + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + " @" + encBitRate);

        try {
            if (TRACE) Trace.beginSection("prepare");
            prepareCamera(VIDEO_WIDTH, VIDEO_HEIGHT, Camera.CameraInfo.CAMERA_FACING_BACK);
            prepareEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, encBitRate);
            mInputSurface.makeEncodeContextCurrent();
            prepareSurfaceTexture();
            setupAudioRecord();
            if (TRACE) Trace.endSection();


            /*
            File outputHq = FileUtils.createTempFileInRootAppStorage(c, "hq.mp4");
            if (TRACE) Trace.beginSection("startMediaRecorder");
            if (useMediaRecorder) mMediaRecorderWrapper = new MediaRecorderWrapper(c, outputHq.getAbsolutePath(), mCamera);
            */
            startAudioRecord();
            /*
            if (useMediaRecorder) mMediaRecorderWrapper.startRecording();
            if (TRACE) Trace.endSection();
            */
            startWhen = System.nanoTime();

            mCamera.startPreview();
            SurfaceTexture st = mStManager.getSurfaceTexture();
            eosReceived = false;

            // The video encoding loop:
            while (!fullStopReceived) {
                //audioEosRequested = eosReceived;  //XXX Remove. Artifact from segmenting at this layer
                synchronized (sync){
                    if (TRACE) Trace.beginSection("drainVideo");
                    drainEncoder(mVideoEncoder, mVideoBufferInfo, mVideoTrackInfo, false);
                    if (TRACE) Trace.endSection();
                }

                //videoFrameCount++;
                totalFrameCount++;

                // Acquire a new frame of input, and render it to the Surface.  If we had a
                // GLSurfaceView we could switch EGL contexts and call drawImage() a second
                // time to render it on screen.  The texture can be shared between contexts by
                // passing the GLSurfaceView's EGLContext as eglCreateContext()'s share_context
                // argument.
                if (TRACE) Trace.beginSection("awaitImage");
                mStManager.awaitNewImage();
                if (TRACE) Trace.endSection();
                if (TRACE) Trace.beginSection("drawImage");
                mStManager.drawImage();
                if (TRACE) Trace.endSection();


                // Set the presentation time stamp from the SurfaceTexture's time stamp.  This
                // will be used by MediaMuxer to set the PTS in the video.
                mInputSurface.setPresentationTime(st.getTimestamp() - startWhen);

                // Submit it to the encoder.  The eglSwapBuffers call will block if the input
                // is full, which would be bad if it stayed full until we dequeued an output
                // buffer (which we can't do, since we're stuck here).  So long as we fully drain
                // the encoder before supplying additional input, the system guarantees that we
                // can supply another frame without blocking.
                if (VERBOSE) Log.d(TAG, "sending frame to encoder");
                if (TRACE) Trace.beginSection("swapBuffers");
                mInputSurface.swapBuffers();
                if (TRACE) Trace.endSection();
                if (!firstFrameReady) startTime = System.nanoTime();
                firstFrameReady = true;

                /*
                if (TRACE) Trace.beginSection("sendAudio");
                sendAudioToEncoder(false);
                if (TRACE) Trace.endSection();
                */
            }
            Log.i(TAG, "Exited video encode loop. Draining video encoder");
            synchronized(sync){
            	drainEncoder(mVideoEncoder, mVideoBufferInfo, mVideoTrackInfo, true);
            }

        } catch (Exception e){
            Log.e(TAG, "Encoding loop exception!");
            e.printStackTrace();
        } finally {
        }
    }

    public void stopRecording(){
        Log.i(TAG, "stopRecording");
        fullStopReceived = true;
        double recordingDurationSec = (System.nanoTime() - startTime) / 1000000000.0;
        Log.i(TAG, "Recorded " + recordingDurationSec + " s. Expected " + (FRAME_RATE * recordingDurationSec) + " frames. Got " + totalFrameCount + " for " + (totalFrameCount / recordingDurationSec) + " fps");
    }

    /**
     * Called internally to finalize HQ and last chunk
     */
    public void _stopRecording(){
        releaseCamera();
        releaseEncodersAndMuxer();
        releaseSurfaceTexture();
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
    }

    private void setupAudioRecord(){
        int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int buffer_size = SAMPLES_PER_FRAME * 10;
        if (buffer_size < min_buffer_size)
            buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,       // source
                SAMPLE_RATE,                         // sample rate, hz
                CHANNEL_CONFIG,                      // channels
                AUDIO_FORMAT,                        // audio format
                buffer_size);                        // buffer size (bytes)
    }

    private void startAudioRecord(){
        if(audioRecord != null){

            new Thread(new Runnable(){

                @Override
                public void run() {
                    audioRecord.startRecording();
                    while(!fullStopReceived){
                        if(!firstFrameReady)
                            continue;

                        synchronized (sync){
                            if (TRACE) Trace.beginSection("drainAudio");
                            drainEncoder(mAudioEncoder, mAudioBufferInfo, mAudioTrackInfo, false);
                            if (TRACE) Trace.endSection();
                        }

                        if (TRACE) Trace.beginSection("sendAudio");
                        sendAudioToEncoder(false);
                        if (TRACE) Trace.endSection();

                    }
                    
                    Log.i(TAG, "Exiting audio encode loop. Draining Audio Encoder");
                    if (TRACE) Trace.beginSection("sendAudio");
                    sendAudioToEncoder(true);
                    if (TRACE) Trace.endSection();
                    audioRecord.stop();
                    synchronized(sync){
                    	drainEncoder(mAudioEncoder, mAudioBufferInfo, mAudioTrackInfo, true);
                    }
                }
            }).start();

        }

    }

    public void sendAudioToEncoder(boolean endOfStream) {
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mAudioEncoder.getInputBuffers();
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                int inputLength =  audioRecord.read(inputBuffer, SAMPLES_PER_FRAME );
                long presentationTimeNs = System.nanoTime();
                presentationTimeNs -= (inputLength / SAMPLE_RATE ) / 1000000000;
                if(inputLength == AudioRecord.ERROR_INVALID_OPERATION)
                    Log.e(TAG, "Audio read error");
                long presentationTimeUs = (presentationTimeNs - startWhen) / 1000;
                if (VERBOSE) Log.i(TAG, "queueing " + inputLength + " audio bytes with pts " + presentationTimeUs);
                if (endOfStream) {
                    Log.i(TAG, "EOS received in sendAudioToEncoder");
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, inputLength, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, inputLength, presentationTimeUs, 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception");
            t.printStackTrace();
        }
    }


    /**
     * Attempts to find a preview size that matches the provided width and height (which
     * specify the dimensions of the encoded video).  If it fails to find a match it just
     * uses the default preview size.
     * <p/>
     * TODO: should do a best-fit match.
     */
    private static void choosePreviewSize(Camera.Parameters parms, int width, int height) {
        // We should make sure that the requested MPEG size is less than the preferred
        // size, and has the same aspect ratio.
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        if (VERBOSE && ppsfv != null) {
            Log.d(TAG, "Camera preferred preview size for video is " +
                    ppsfv.width + "x" + ppsfv.height);
        }

        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                parms.setPreviewSize(width, height);
                return;
            }
        }

        Log.w(TAG, "Unable to set preview size to " + width + "x" + height);
        if (ppsfv != null) {
            parms.setPreviewSize(ppsfv.width, ppsfv.height);
        }
    }

    /**
     * Configures Camera for video capture.  Sets mCamera.
     * <p/>
     * Opens a Camera and sets parameters.  Does not start preview.
     */
    private void prepareCamera(int encWidth, int encHeight, int cameraType) {
        if (cameraType != Camera.CameraInfo.CAMERA_FACING_FRONT && cameraType != Camera.CameraInfo.CAMERA_FACING_BACK) {
            Log.w(TAG, "waiting for camera frame");
        	//throw new RuntimeException("Invalid cameraType"); // TODO
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == cameraType) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null && cameraType == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();
        List<int[]> fpsRanges = parms.getSupportedPreviewFpsRange();
        int[] maxFpsRange = fpsRanges.get(fpsRanges.size() - 1);
        parms.setPreviewFpsRange(maxFpsRange[0], maxFpsRange[1]);

        choosePreviewSize(parms, encWidth, encHeight);
        // leave the frame rate set to default
        mCamera.setParameters(parms);

        Camera.Size size = parms.getPreviewSize();
        Log.d(TAG, "Camera preview size is " + size.width + "x" + size.height);
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (VERBOSE) Log.d(TAG, "releasing camera");
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    /**
     * Configures SurfaceTexture for camera preview.  Initializes mStManager, and sets the
     * associated SurfaceTexture as the Camera's "preview texture".
     * <p/>
     * Configure the EGL surface that will be used for output before calling here.
     */
    private void prepareSurfaceTexture() {
        mStManager = new SurfaceTextureManager();
        SurfaceTexture st = mStManager.getSurfaceTexture();
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException("setPreviewTexture failed", ioe);
        }
    }

    /**
     * Releases the SurfaceTexture.
     */
    private void releaseSurfaceTexture() {
        if (mStManager != null) {
            mStManager.release();
            mStManager = null;
        }
    }

    /**
     * Configures encoder and muxer state, and prepares the input Surface.  Initializes
     * mVideoEncoder, mMuxerWrapper, mInputSurface, mVideoBufferInfo, mVideoTrackInfo, and mMuxerStarted.
     */
    private void prepareEncoder(int width, int height, int bitRate) {
        fullStopReceived = false;
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        mVideoTrackInfo = new TrackInfo();

        mVideoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        mVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        mVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + mVideoFormat);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        //
        // If you want to have two EGL contexts -- one for display, one for recording --
        // you will likely want to defer instantiation of CodecInputSurface until after the
        // "display" EGL context is created, then modify the eglCreateContext call to
        // take eglGetCurrentContext() as the share_context argument.
        mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        mVideoEncoder.configure(mVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = new CodecInputSurface(mVideoEncoder.createInputSurface());
        mVideoEncoder.start();
        videoEncoderStopped = false;

        mAudioBufferInfo = new MediaCodec.BufferInfo();
        mAudioTrackInfo = new TrackInfo();

        mAudioFormat = new MediaFormat();
        mAudioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        mAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
        audioEncoderStopped = false;
    }

    private void stopAndReleaseVideoEncoder(){
        videoEncoderStopped = true;
        //videoFrameCount = 0; // avoid setting this zero before all frames submitted to ffmpeg
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
    }


    private void stopAndReleaseAudioEncoder(){
        //audioFrameCount = 0;	// avoid setting this zero before all frames submitted to ffmpeg
        audioEncoderStopped = true;
        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
    }

    private void stopAndReleaseEncoders(){
        stopAndReleaseVideoEncoder();
        stopAndReleaseAudioEncoder();
    }


    /**
     * Releases encoder resources.
     */
    private void releaseEncodersAndMuxer() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        stopAndReleaseEncoders();
        // TODO: Finalize ffmpeg
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p/>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    private void drainEncoder(MediaCodec encoder, MediaCodec.BufferInfo bufferInfo, TrackInfo trackInfo, boolean endOfStream) {
        final int TIMEOUT_USEC = 100;

        if (VERBOSE) Log.d(TAG, "drain" + ((encoder == mVideoEncoder) ? "Video" : "Audio") + "Encoder(" + endOfStream + ")");
        if (endOfStream && encoder == mVideoEncoder) {
            if (VERBOSE) Log.d(TAG, "sending EOS to " + ((encoder == mVideoEncoder) ? "video" : "audio") + " encoder");
            encoder.signalEndOfInputStream();
        }
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    if (VERBOSE) Log.d(TAG, "no output available. aborting drain");
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once

                // Previously, we fed newFormat to Android's MediaMuxer
                // Perhaps this is where we should adapt Android's MediaFormat
                // to FFmpeg's AVCodecContext
                
                /* Old code for Android's MediaMuxer:
                 
                MediaFormat newFormat = encoder.getOutputFormat();
                trackInfo.index = muxerWrapper.addTrack(newFormat);
                if(!muxerWrapper.allTracksAdded())
                    break;
                */

            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Previously we ignored this state due to having fed Android's
                	// MediaFormat to MediaMuxer. See above condition for:
                	// else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
                	
                	// Perhaps we should concentrate on setting FFmpeg's
                	// AVCodecContext from Android's MediaFormat
                	
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    //Log.i(TAG, String.format("Writing codec_config for %s, pts %d", (encoder == mVideoEncoder) ? "video" : "audio", bufferInfo.presentationTimeUs));
                    ffmpeg.writeAVPacketFromEncodedData(encodedData, (encoder == mVideoEncoder) ? 1 : 0, bufferInfo.offset, bufferInfo.size, bufferInfo.flags, bufferInfo.presentationTimeUs);
                    bufferInfo.size = 0;	// prevent writing as normal packet
                }
                	
                

                if (bufferInfo.size != 0) {
                	
                	if(encoder == mAudioEncoder){
	                	int outBitsSize = bufferInfo.size;
	                	int outPacketSize = outBitsSize + ADTS_LENGTH;	                	
	                	addADTStoPacket(audioPacket, outPacketSize);
	                    encodedData.get(audioPacket, ADTS_LENGTH, outBitsSize);
	                    encodedData.position(bufferInfo.offset);
	                    encodedData.put(audioPacket, 0, outPacketSize);
	                    bufferInfo.size = outPacketSize;
                	}
                	
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    /*
                    if(bufferInfo.presentationTimeUs < 0){
                        bufferInfo.presentationTimeUs = 0;
                    }
                    */
                    
                    // Previously, we'd write to Android's MediaMuxer here:
                    //muxerWrapper.writeSampleData(trackInfo.index, encodedData, bufferInfo);
                    ffmpeg.writeAVPacketFromEncodedData(encodedData, (encoder == mVideoEncoder) ? 1 : 0, bufferInfo.offset, bufferInfo.size, bufferInfo.flags, /* (encoder == mVideoEncoder) ? videoFrameCount++ : audioFrameCount++*/ bufferInfo.presentationTimeUs); 
                    if (VERBOSE)
                        Log.d(TAG, "sent " + bufferInfo.size + ((encoder == mVideoEncoder) ? " video" : " audio") + " bytes to muxer with pts " + bufferInfo.presentationTimeUs);
                }

                encoder.releaseOutputBuffer(encoderStatus, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of " + ((encoder == mVideoEncoder) ? "video" : "audio") + " stream reached. ");
                        Log.d(TAG, "end of " + ((encoder == mVideoEncoder) ? "video" : "audio") + " stream reached. ");
                        if(encoder == mVideoEncoder){
                            stopAndReleaseVideoEncoder();
                        } else if(encoder == mAudioEncoder){
                            stopAndReleaseAudioEncoder();
                        }
                        if(videoEncoderStopped && audioEncoderStopped)
                        	ffmpeg.finalizeAVFormatContext();
                    }
                    break;      // break out of while
                }
            }
        }
    }
    
    /**
     *  Add ADTS header at the beginning of each and every AAC packet.
     *  This is needed as MediaCodec encoder generates a packet of raw
     *  AAC data.
     *
     *  Note the packetLen must count in the ADTS header itself.
     *  See: http://wiki.multimedia.cx/index.php?title=ADTS
     *  Also: http://wiki.multimedia.cx/index.php?title=MPEG-4_Audio#Channel_Configurations
     **/
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2;  //AAC LC
                          //39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
        int freqIdx = 4;  //44.1KHz
        int chanCfg = 1;  //MPEG-4 Audio Channel Configuration. 1 Channel front-center

        // fill in ADTS data
        packet[0] = (byte)0xFF;	// 11111111  	= syncword
        packet[1] = (byte)0xF9;	// 1111 1 00 1  = syncword MPEG-2 Layer CRC
        packet[2] = (byte)(((profile-1)<<6) + (freqIdx<<2) +(chanCfg>>2));
        packet[3] = (byte)(((chanCfg&3)<<6) + (packetLen>>11));
        packet[4] = (byte)((packetLen&0x7FF) >> 3);
        packet[5] = (byte)(((packetLen&7)<<5) + 0x1F);
        packet[6] = (byte)0xFC;
    }


    /**
     * Holds state associated with a Surface used for MediaCodec encoder input.
     * <p/>
     * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
     * that to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to
     * be sent to the video encoder.
     * <p/>
     * This object owns the Surface -- releasing this will release the Surface too.
     */
    private static class CodecInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLEncodeContext = EGL14.EGL_NO_CONTEXT;
        // public static EGLContext mEGLDisplayContext = EGL14.EGL_NO_CONTEXT; // TODO: Display surface
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
        private Surface mSurface;

        EGLConfig[] configs;
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface) {
            if (surface == null) {
                throw new NullPointerException();
            }
            mSurface = surface;

            eglSetup();
        }

        public void updateSurface(Surface newSurface){
            // Destroy old EglSurface
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mSurface = newSurface;
            // create new EglSurface
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
            // eglMakeCurrent called in chunkRecording() after mVideoEncoder.start()
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private void eglSetup() {
            if(VERBOSE) Log.i(TAG, "Creating EGL14 Surface");
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                    numConfigs, 0);
            checkEglError("eglCreateContext RGB888+recordable ES2");

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            //if(mEGLDisplayContext == EGL14.EGL_NO_CONTEXT) Log.e(TAG, "mEGLDisplayContext not set properly");
            mEGLEncodeContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.eglGetCurrentContext(),
                    attrib_list, 0);
            checkEglError("eglCreateContext");

            // Create a window surface, and attach it to the Surface we received.
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLEncodeContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mSurface.release();

            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLEncodeContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;

            mSurface = null;
         }

        /* TODO: Display surface
        public void makeDisplayContextCurrent(){
            makeCurrent(mEGLDisplayContext);
        }
        */
        public void makeEncodeContextCurrent(){
            makeCurrent(mEGLEncodeContext);
        }

         /**
         * Makes our EGL context and surface current.
         */
        private void makeCurrent(EGLContext context) {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, context);
            checkEglError("eglMakeCurrent");
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        public boolean swapBuffers() {
            boolean result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
            checkEglError("eglSwapBuffers");
            return result;
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
            checkEglError("eglPresentationTimeANDROID");
        }

        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }
    }

    /**
     * Manages a SurfaceTexture.  Creates SurfaceTexture and TextureRender objects, and provides
     * functions that wait for frames and render them to the current EGL surface.
     * <p/>
     * The SurfaceTexture can be passed to Camera.setPreviewTexture() to receive camera output.
     */
    private static class SurfaceTextureManager
            implements SurfaceTexture.OnFrameAvailableListener {
        private SurfaceTexture mSurfaceTexture;
        private HLSRecorder.STextureRender mTextureRender;
        private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
        private boolean mFrameAvailable;

        /**
         * Creates instances of TextureRender and SurfaceTexture.
         */
        public SurfaceTextureManager() {
            mTextureRender = new HLSRecorder.STextureRender();
            mTextureRender.surfaceCreated();

            if (VERBOSE) Log.d(TAG, String.format("textureID=%d", mTextureRender.getTextureId()) );
            mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());

            // This doesn't work if this object is created on the thread that CTS started for
            // these test cases.
            //
            // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
            // create a Handler that uses it.  The "frame available" message is delivered
            // there, but since we're not a Looper-based thread we'll never see it.  For
            // this to do anything useful, OutputSurface must be created on a thread without
            // a Looper, so that SurfaceTexture uses the main application Looper instead.
            //
            // Java language note: passing "this" out of a constructor is generally unwise,
            // but we should be able to get away with it here.
            mSurfaceTexture.setOnFrameAvailableListener(this);
        }

        public void release() {
            // this causes a bunch of warnings that appear harmless but might confuse someone:
            //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
            //mSurfaceTexture.release();

            mTextureRender = null;
            mSurfaceTexture = null;
        }

        /**
         * Returns the SurfaceTexture.
         */
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        /**
         * Replaces the fragment shader.
         */
        public void changeFragmentShader(String fragmentShader) {
            mTextureRender.changeFragmentShader(fragmentShader);
        }

        /**
         * Latches the next buffer into the texture.  Must be called from the thread that created
         * the OutputSurface object.
         */
        public void awaitNewImage() {
            final int TIMEOUT_MS = 4500;
            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                        // stalling the test if it doesn't arrive.
                        if(VERBOSE) Log.i(TAG, "Waiting for Frame in Thread");
                        mFrameSyncObject.wait(TIMEOUT_MS);
                        if (!mFrameAvailable) {
                            // TODO: if "spurious wakeup", continue while loop
                            throw new RuntimeException("Camera frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mFrameAvailable = false;
            }

            // Latch the data.
            mTextureRender.checkGlError("before updateTexImage");
            mSurfaceTexture.updateTexImage();

        }

        /**
         * Draws the data from SurfaceTexture onto the current EGL surface.
         */
        public void drawImage() {
            mTextureRender.drawFrame(mSurfaceTexture);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            if (VERBOSE) Log.d(TAG, "new frame available");
            synchronized (mFrameSyncObject) {
                if (mFrameAvailable) {
                    throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
                }
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
        }
    }

    /**
     * Code for rendering a texture onto a surface using OpenGL ES 2.0.
     */
    private static class STextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                        "uniform mat4 uSTMatrix;\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec4 aTextureCoord;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = uMVPMatrix * aPosition;\n" +
                        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                        "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +      // highp here doesn't seem to matter
                        "varying vec2 vTextureCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                        "}\n";
        private final float[] mTriangleVerticesData = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0, 0.f, 0.f,
                1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f, 1.0f, 0, 0.f, 1.f,
                1.0f, 1.0f, 0, 1.f, 1.f,
        };
        private FloatBuffer mTriangleVertices;
        private float[] mMVPMatrix = new float[16];
        private float[] mSTMatrix = new float[16];
        private int mProgram;
        private int mTextureID = -12345;
        private int muMVPMatrixHandle;
        private int muSTMatrixHandle;
        private int maPositionHandle;
        private int maTextureHandle;

        public STextureRender() {
            mTriangleVertices = ByteBuffer.allocateDirect(
                    mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);

            Matrix.setIdentityM(mSTMatrix, 0);
        }

        public int getTextureId() {
            return mTextureID;
        }

        public void drawFrame(SurfaceTexture st) {
            checkGlError("onDrawFrame start");
            st.getTransformMatrix(mSTMatrix);

            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maPosition");
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            checkGlError("glEnableVertexAttribArray maPositionHandle");

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maTextureHandle");
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            checkGlError("glEnableVertexAttribArray maTextureHandle");

            Matrix.setIdentityM(mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");
            GLES20.glFinish();
        }

        /**
         * Initializes GL state.  Call this after the EGL surface has been created and made current.
         */
        public void surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            checkGlError("glGetAttribLocation aPosition");
            if (maPositionHandle == -1) {
                throw new RuntimeException("Could not get attrib location for aPosition");
            }
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            checkGlError("glGetAttribLocation aTextureCoord");
            if (maTextureHandle == -1) {
                throw new RuntimeException("Could not get attrib location for aTextureCoord");
            }

            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            checkGlError("glGetUniformLocation uMVPMatrix");
            if (muMVPMatrixHandle == -1) {
                throw new RuntimeException("Could not get attrib location for uMVPMatrix");
            }

            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            checkGlError("glGetUniformLocation uSTMatrix");
            if (muSTMatrixHandle == -1) {
                throw new RuntimeException("Could not get attrib location for uSTMatrix");
            }

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);

            mTextureID = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            checkGlError("glBindTexture mTextureID");

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            checkGlError("glTexParameter");
        }

        /**
         * Replaces the fragment shader.  Pass in null to resetWithChunk to default.
         */
        public void changeFragmentShader(String fragmentShader) {
            if (fragmentShader == null) {
                fragmentShader = FRAGMENT_SHADER;
            }
            GLES20.glDeleteProgram(mProgram);
            mProgram = createProgram(VERTEX_SHADER, fragmentShader);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            checkGlError("glCreateShader type=" + shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            checkGlError("glCreateProgram");
            if (program == 0) {
                Log.e(TAG, "Could not create program");
            }
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            return program;
        }

        public void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }
    }
}