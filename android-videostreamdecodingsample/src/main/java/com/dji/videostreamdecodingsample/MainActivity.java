package com.dji.videostreamdecodingsample;

import android.Manifest;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.media.MediaCodec;

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;

import com.dji.videostreamdecodingsample.media.NativeHelper;
import dji.common.product.Model;
import dji.sdk.airlink.DJILBAirLink;
import dji.sdk.camera.DJICamera;
import dji.sdk.codec.DJICodecManager;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import dji.sdk.base.DJIBaseProduct;

import net.ossrs.sea.SrsHttpFlv;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.cert.X509Certificate;

public class MainActivity extends Activity implements DJIVideoStreamDecoder.IYuvDataListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    static final int MSG_WHAT_SHOW_TOAST = 0;
    static final int MSG_WHAT_UPDATE_TITLE = 1;
    static final boolean useSurface = true;

    private TextView titleTv;
    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;

    private DJIBaseProduct mProduct;
    private DJICamera mCamera;
    private DJICodecManager mCodecManager;

    private TextView savePath;
    private TextView screenShot;
    private List<String> pathList = new ArrayList<>();

    private HandlerThread backgroundHandlerThread;
    public Handler backgroundHandler;

    protected DJICamera.CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = null;
    protected DJILBAirLink.DJIOnReceivedVideoCallback mOnReceivedVideoCallback = null;

    private static boolean firstGetBuffer = true;
    private static int yuvFrameCount = 0;
    private String flv_url = "http://192.168.5.4:8936/android/dji.flv";
    // the bitrate in kbps.
    private int vbitrate_kbps = 1200;
    private final static int VFPS = 25;
    private final static int VGOP = 5;
    private final static int VWIDTH = 1280;
    private final static int VHEIGHT = 720;
    private long presentationTimeUs;
    private SrsHttpFlv muxer;
    private int vtrack;
    private int vcolor = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private MediaCodec vencoder;
    private MediaCodec.BufferInfo vebi;

    static {
        disableSslVerification();
    }

    private static void disableSslVerification() {
        try
        {
            // Create a trust manager that does not validate certificate chains
            TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {

                }

                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            }};

            // Install the all-trusting trust manager
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Create all-trusting host name verifier
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };

            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (useSurface) {
            DJIVideoStreamDecoder.getInstance().resume();
        }
        notifyStatusChange();
    }

    @Override
    protected void onPause() {
        if (mCamera != null) {
            mCamera.setDJICameraReceivedVideoDataCallback(null);
        }
        if (useSurface) {
            DJIVideoStreamDecoder.getInstance().stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (useSurface) {
            DJIVideoStreamDecoder.getInstance().destroy();
            NativeHelper.getInstance().release();
        }
        if (mCodecManager != null) {
            mCodecManager.destroyCodec();
        }

        muxer.stop();
        muxer.release();
        vencoder.stop();
        vencoder.release();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NativeHelper.getInstance().init();

        // When the compile and target version is higher than 22, please request the
        // following permissions at runtime to ensure the
        // SDK work well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.VIBRATE,
                            Manifest.permission.INTERNET, Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SYSTEM_ALERT_WINDOW,
                            Manifest.permission.READ_PHONE_STATE,
                    }
                    , 1);
        }

        setContentView(R.layout.activity_main);

        backgroundHandlerThread = new HandlerThread("background handler thread");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper());

        initUi();
        initPreviewer();

        presentationTimeUs = new Date().getTime() * 1000;

        muxer = new SrsHttpFlv(flv_url, SrsHttpFlv.OutputFormat.MUXER_OUTPUT_HTTP_FLV);
        try {
            muxer.start();
        } catch (IOException e) {
            Log.e(TAG, "start muxer failed.");
            e.printStackTrace();
            return;
        }

        try {
            vencoder = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            Log.e(TAG, "createEncoderByType failed.");
            e.printStackTrace();
        }
        vebi = new MediaCodec.BufferInfo();

        MediaFormat vformat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
        vformat.setInteger(MediaFormat.KEY_COLOR_FORMAT, vcolor);
        vformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        vformat.setInteger(MediaFormat.KEY_BIT_RATE, 1000 * vbitrate_kbps);
        vformat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
        vformat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP);
        vencoder.configure(vformat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        vtrack = muxer.addTrack(vformat);

        vencoder.start();
    }

    public Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SHOW_TOAST:
                    Toast.makeText(getApplicationContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_WHAT_UPDATE_TITLE:
                    if (titleTv != null) {
                        titleTv.setText((String) msg.obj);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void showToast(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        );
    }

    private void updateTitle(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        );
    }

    private void initUi() {
        savePath = (TextView) findViewById(R.id.activity_main_save_path);
        screenShot = (TextView) findViewById(R.id.activity_main_screen_shot);
        screenShot.setSelected(false);
        titleTv = (TextView) findViewById(R.id.title_tv);
        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
        if (useSurface) {
            videostreamPreviewSf.setVisibility(View.VISIBLE);
            videostreamPreviewTtView.setVisibility(View.GONE);
            videostreamPreviewSh.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), videostreamPreviewSh.getSurface());
                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(MainActivity.this);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    DJIVideoStreamDecoder.getInstance().changeSurface(holder.getSurface());
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {

                }
            });
        } else {
            videostreamPreviewSf.setVisibility(View.GONE);
            videostreamPreviewTtView.setVisibility(View.VISIBLE);
        }
    }

    private void notifyStatusChange() {

        mProduct = VideoDecodingApplication.getProductInstance();

        Log.d(TAG, "notifyStatusChange: " + (mProduct == null ? "Disconnect" : (mProduct.getModel() == null ? "null model" : mProduct.getModel().name())));
        if (mProduct != null && mProduct.isConnected() && mProduct.getModel() != null) {
            updateTitle(mProduct.getModel().name() + " Connected");
        } else {
            updateTitle("Disconnected");
        }

        mReceivedVideoDataCallBack = new DJICamera.CameraReceivedVideoDataCallback() {

            @Override
            public void onResult(byte[] videoBuffer, int size) {
                Log.d(TAG, "camera recv video data size: " + size);
                if (useSurface) {
                    DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                } else if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }

                for (int index = 0; firstGetBuffer && index < 10; index++){
                    Log.e(TAG, String.format("0x%20x", videoBuffer[index]));
                }
                firstGetBuffer = false;

//                File sdCard = Environment.getExternalStorageDirectory();
//                File dir = new File (sdCard.getAbsolutePath() + "/dji");
//                dir.mkdirs();
//                File file = new File(dir, "filename");
//                try {
//                    FileOutputStream f = new FileOutputStream(file, true);
//                    f.write(videoBuffer);
//                    Log.w(TAG, "write chunk finished: " + size);
//                }catch (IOException e){
//                    e.printStackTrace();
//                }
            }
        };
        mOnReceivedVideoCallback = new DJILBAirLink.DJIOnReceivedVideoCallback() {

            @Override
            public void onResult(byte[] videoBuffer, int size) {
                Log.d(TAG, "airlink recv video data size: " + size);
                if (useSurface) {
                    DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                }
            }
        };

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast("Disconnected");
        } else {
            if (!mProduct.getModel().equals(Model.UnknownAircraft)) {
                mCamera = mProduct.getCamera();
                if (mCamera != null) {
                    mCamera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);
                }
            } else {
                if (null != mProduct.getAirLink()) {
                    if (null != mProduct.getAirLink().getLBAirLink()) {
                        mProduct.getAirLink().getLBAirLink().setDJIOnReceivedVideoCallback(mOnReceivedVideoCallback);
                    }
                }
            }
        }
    }

    // when got encoded h264 es stream.
    private void onEncodedAnnexbFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
//        File sdCard = Environment.getExternalStorageDirectory();
//        File dir = new File (sdCard.getAbsolutePath() + "/dji");
//        dir.mkdirs();
//        File file = new File(dir, "livestream.h264");
//        byte[] arr = new byte[es.remaining()];
//        es.get(arr);
//        try {
//            FileOutputStream f = new FileOutputStream(file, true);
//            f.write(arr);
//            Log.w(TAG, "write chunk finished: " + arr.length);
//        }catch (IOException e){
//            e.printStackTrace();
//        }

        try {
            muxer.writeSampleData(vtrack, es, bi);
        } catch (Exception e) {
            Log.e(TAG, "muxer write video sample failed.");
            e.printStackTrace();
        }
    }

    private void onGetYuvFrame(byte[] data) {
        Log.w(TAG, String.format("got YUV image, size=%d", data.length));

        // feed the vencoder with yuv frame, got the encoded 264 es stream.
        ByteBuffer[] inBuffers = vencoder.getInputBuffers();
        ByteBuffer[] outBuffers = vencoder.getOutputBuffers();

        if (true) {
            int inBufferIndex = vencoder.dequeueInputBuffer(-1);
            Log.w(TAG, String.format("try to dequeue input vbuffer, ii=%d", inBufferIndex));
            if (inBufferIndex >= 0) {
                ByteBuffer bb = inBuffers[inBufferIndex];
                bb.clear();
                bb.put(data, 0, data.length);
                long pts = new Date().getTime() * 1000 - presentationTimeUs;
                Log.w(TAG, String.format("feed YUV to encode %dB, pts=%d", data.length, pts / 1000));
                vencoder.queueInputBuffer(inBufferIndex, 0, data.length, pts, 0);
            }
        }

        for (;;) {
            int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
            //Log.i(TAG, String.format("try to dequeue output vbuffer, ii=%d, oi=%d", inBufferIndex, outBufferIndex));
            if (outBufferIndex >= 0) {
                ByteBuffer bb = outBuffers[outBufferIndex];
                onEncodedAnnexbFrame(bb, vebi);
                vencoder.releaseOutputBuffer(outBufferIndex, false);
            }

            if (outBufferIndex < 0) {
                break;
            }
        }
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private void initPreviewer() {
        videostreamPreviewTtView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                if (mCodecManager == null) {
                    mCodecManager = new DJICodecManager(getApplicationContext(), surface, width, height);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mCodecManager != null) mCodecManager.cleanSurface();
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    @Override
    public void onYuvDataReceived(byte[] yuvFrame, int width, int height) {
        Log.d(TAG, "onGetYuvFrame runs");
        onGetYuvFrame(yuvFrame);
        if(yuvFrameCount++ < 10) {
            saveByteArray(yuvFrame, "dji", "yuvFrame" + yuvFrameCount);
        }
//        //In this demo, we test the YUV data by saving it into JPG files.
//        if (DJIVideoStreamDecoder.getInstance().frameIndex % 30 == 0) {
//            byte[] y = new byte[width * height];
//            byte[] u = new byte[width * height / 4];
//            byte[] v = new byte[width * height / 4];
//            byte[] nu = new byte[width * height / 4]; //
//            byte[] nv = new byte[width * height / 4];
//            System.arraycopy(yuvFrame, 0, y, 0, y.length);
//            for (int i = 0; i < u.length; i++) {
//                v[i] = yuvFrame[y.length + 2 * i];
//                u[i] = yuvFrame[y.length + 2 * i + 1];
//            }
//            int uvWidth = width / 2;
//            int uvHeight = height / 2;
//            for (int j = 0; j < uvWidth / 2; j++) {
//                for (int i = 0; i < uvHeight / 2; i++) {
//                    byte uSample1 = u[i * uvWidth + j];
//                    byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
//                    byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
//                    byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
//                    nu[2 * (i * uvWidth + j)] = uSample1;
//                    nu[2 * (i * uvWidth + j) + 1] = uSample1;
//                    nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
//                    nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
//                    nv[2 * (i * uvWidth + j)] = vSample1;
//                    nv[2 * (i * uvWidth + j) + 1] = vSample1;
//                    nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
//                    nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
//                }
//            }
//            //nv21test
//            byte[] bytes = new byte[yuvFrame.length];
//            System.arraycopy(y, 0, bytes, 0, y.length);
//            for (int i = 0; i < u.length; i++) {
//                bytes[y.length + (i * 2)] = nv[i];
//                bytes[y.length + (i * 2) + 1] = nu[i];
//            }
//            Log.d(TAG,
//                    "onYuvDataReceived: frame index: "
//                            + DJIVideoStreamDecoder.getInstance().frameIndex
//                            + ",array length: "
//                            + bytes.length);
//            screenShot(bytes, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot");
//        }
    }

    /**
     * Save the buffered data into a JPG image file
     */
    private void screenShot(byte[] buf, String shotDir) {
        File dir = new File(shotDir);
        if (!dir.exists() || !dir.isDirectory()) {
            dir.mkdirs();
        }
        YuvImage yuvImage = new YuvImage(buf,
                ImageFormat.NV21,
                DJIVideoStreamDecoder.getInstance().width,
                DJIVideoStreamDecoder.getInstance().height,
                null);
        OutputStream outputFile;
        final String path = dir + "/ScreenShot_" + System.currentTimeMillis() + ".jpg";
        try {
            outputFile = new FileOutputStream(new File(path));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "test screenShot: new bitmap output file error: " + e);
            return;
        }
        if (outputFile != null) {
            yuvImage.compressToJpeg(new Rect(0,
                    0,
                    DJIVideoStreamDecoder.getInstance().width,
                    DJIVideoStreamDecoder.getInstance().height), 100, outputFile);
        }
        try {
            outputFile.close();
        } catch (IOException e) {
            Log.e(TAG, "test screenShot: compress yuv image error: " + e);
            e.printStackTrace();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                displayPath(path);
            }
        });
    }

    public void onClick(View v) {
        if (screenShot.isSelected()) {
            screenShot.setText("Screen Shot");
            screenShot.setSelected(false);
            if (useSurface) {
                DJIVideoStreamDecoder.getInstance().changeSurface(videostreamPreviewSh.getSurface());
            }
            savePath.setText("");
            savePath.setVisibility(View.INVISIBLE);
        } else {
            screenShot.setText("Live Stream");
            screenShot.setSelected(true);
            if (useSurface) {
                DJIVideoStreamDecoder.getInstance().changeSurface(null);
            }
            savePath.setText("");
            savePath.setVisibility(View.VISIBLE);
            pathList.clear();
        }
    }

    private void displayPath(String path){
        path = path + "\n\n";
        if(pathList.size() < 6){
            pathList.add(path);
        }else{
            pathList.remove(0);
            pathList.add(path);
        }
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0 ;i < pathList.size();i++){
            stringBuilder.append(pathList.get(i));
        }
        savePath.setText(stringBuilder.toString());
    }

    private void saveByteArray(byte[] data, String folder, String fileName){
        File sdCard = Environment.getExternalStorageDirectory();
        File dir = new File (sdCard.getAbsolutePath() + "/" + folder);
        dir.mkdirs();
        File file = new File(dir, fileName);
        try {
            FileOutputStream f = new FileOutputStream(file, true);
            f.write(data);
            Log.d(TAG, "write chunk finished: " + data.length);
        }catch (IOException e){
            e.printStackTrace();
        }
    }
}
