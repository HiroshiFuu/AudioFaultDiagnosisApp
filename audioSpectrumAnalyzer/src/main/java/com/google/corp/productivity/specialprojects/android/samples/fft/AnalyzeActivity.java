package com.google.corp.productivity.specialprojects.android.samples.fft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.google.corp.productivity.specialprojects.android.samples.fft.AnalyzeView.Ready;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

//import android.R;

/**
 * Audio "FFT" analyzer.
 */

public class AnalyzeActivity extends Activity
        implements OnLongClickListener, OnClickListener,
        OnItemClickListener, Ready {

    static final String TAG = "AnalyzeActivity";
    static float DPRatio;

    private AnalyzeView graphView;
    private AnalyzeView graphView_Reduced;
    private Looper samplingThread;

    private final static double SAMPLE_VALUE_MAX = 32767.0;   // Maximum signal value
    private final static int RECORDER_AGC_OFF = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private final static int BYTE_OF_SAMPLE = 2;

    private final int sampleRate = 11025;
    private final int fftLen = 32768;
    private final int nFFTAverage = 1;

    private static int Ref_Voltage = 100;
    private static float Ref_Current = 1.0f;
    private static float Threshold = 0.05f;
    private static boolean isSavedWav = false;
    private static String wndFuncName;

    private static boolean bSaveWav;
    private static int audioSourceId = RECORDER_AGC_OFF;
    private boolean bWarnOverrun = true;
    private double timeDurationPref = 4.0;
    private double wavSec, wavSecRemain;

    float listItemTextSize = 20;        // see R.dimen.button_text_fontsize
    float listItemTitleTextSize = 12;   // see R.dimen.button_text_small_fontsize

    double dtRMS = 0;
    double dtRMSFromFT = 0;
    double maxAmpDB;
    double maxAmpFreq;
    double secondMaxAmpDB;
    double secondMaxAmpFreq;

    StringBuilder textRMS = new StringBuilder("");
    StringBuilder textPeak = new StringBuilder("");
    StringBuilder textRec = new StringBuilder("");  // for textCurChar
    char[] textRMSChar;   // for text in R.id.textview_RMS
    char[] textPeakChar;  // for text in R.id.textview_peak
    char[] textRecChar;   // for text in R.id.textview_rec

    PopupWindow popupMenuRef_Voltage;
    PopupWindow popupMenuRef_Current;
    PopupWindow popupMenuThreshold;
    PopupWindow popupMenuPreset;

    private File file;
    private FileOutputStream fos;

    double[] minimumSpectrum;

    @Override
    public void onCreate(Bundle savedInstanceState) {
//  Debug.startMethodTracing("calc");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        DPRatio = getResources().getDisplayMetrics().density;

        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        Log.i(TAG, " max mem = " + maxMemory + "k");

        File folder = new File(Environment.getExternalStorageDirectory(), "/AudioFaultDiagnosisApp/");
        if (!folder.exists())
            folder.mkdir();
        file = new File(folder.getAbsoluteFile() + File.separator + "SavedWav.csv");

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        // set and get preferences in PreferenceActivity
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        updatePreferenceSaved();

        textRMSChar = new char[getString(R.string.textview_RMS_text).length()];
        textRecChar = new char[getString(R.string.textview_rec_text).length()];
        textPeakChar = new char[getString(R.string.textview_peak_text).length()];

        graphView = (AnalyzeView) findViewById(R.id.plot);
        graphView_Reduced = (AnalyzeView) findViewById(R.id.plotReduced);

        // travel Views, and attach ClickListener to the views that contain android:tag="select"
        visit((ViewGroup) graphView.getRootView(), new Visit() {
            @Override
            public void exec(View view) {
                view.setOnLongClickListener(AnalyzeActivity.this);
                view.setOnClickListener(AnalyzeActivity.this);
                ((TextView) view).setFreezesText(true);
            }
        }, "select");

        Resources res = getResources();
        getAudioSourceNameFromIdPrepare(res);

        listItemTextSize = res.getDimension(R.dimen.button_text_fontsize);
        listItemTitleTextSize = res.getDimension(R.dimen.button_text_small_fontsize);

        /// initialize pop up window items list
        // http://www.codeofaninja.com/2013/04/show-listview-as-drop-down-android.html
        popupMenuRef_Voltage = popupMenuCreate(validateAudioRates(
                res.getStringArray(R.array.voltages)), R.id.button_Ref_Voltage);
        popupMenuRef_Current = popupMenuCreate(
                res.getStringArray(R.array.currents), R.id.button_Ref_Current);
        popupMenuThreshold = popupMenuCreate(
                res.getStringArray(R.array.thresholds), R.id.button_Threshold);
        popupMenuPreset = popupMenuCreate(
                res.getStringArray(R.array.presets), R.id.button_Preset);

//        mDetector = new GestureDetectorCompat(this, new AnalyzerGestureListener());

        setTextViewFontSize();

        minimumSpectrum = new double[1000];
        for (int i = 0; i < 1000; i++)
            minimumSpectrum[i] = -128.0;
    }

    // Set text font size of textview_cur and textview_peak
    // according to space left
    @SuppressWarnings("deprecation")
    private void setTextViewFontSize() {
        TextView tv = (TextView) findViewById(R.id.textview_peak);
        // At this point tv.getWidth(), tv.getLineCount() will return 0

        Paint mTestPaint = new Paint();
        mTestPaint.setTextSize(tv.getTextSize());
        mTestPaint.setTypeface(Typeface.MONOSPACE);

        final String text = getString(R.string.textview_peak_text);
        Display display = getWindowManager().getDefaultDisplay();

        // pixels left
        float px = display.getWidth() - getResources().getDimension(R.dimen.textview_RMS_layout_width) - 5;

        float fs = tv.getTextSize();  // size in pixel
        while (mTestPaint.measureText(text) > px && fs > 5) {
            fs -= 0.5;
            mTestPaint.setTextSize(fs);
        }
        ((TextView) findViewById(R.id.textview_peak)).setTextSize(fs / DPRatio);

//        graphView.setXShiftScale(-100, 10);
    }

    /**
     * Run processClick() for views, transferring the state in the textView to our
     * internal state, then begin sampling and processing audio data
     */

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        boolean keepScreenOn = sharedPref.getBoolean("keepScreenOn", true);
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        audioSourceId = Integer.parseInt(sharedPref.getString("audioSource", Integer.toString(RECORDER_AGC_OFF)));
        wndFuncName = sharedPref.getString("windowFunction", "Blackman Harris");

        // spectrum
        graphView.setShowLines(sharedPref.getBoolean("showLines", false));
        // set spectrum show range
        float b = graphView.getBounds().bottom;
        b = Float.parseFloat(sharedPref.getString("spectrumRange", Double.toString(b)));
        graphView.setBoundsBottom(b);
        graphView_Reduced.setBoundsBottom(b);

        // spectrogram
        // set spectrogram shifting mode
        graphView.setSpectrogramModeShifting(sharedPref.getBoolean("spectrogramShifting", false));
        graphView.setShowTimeAxis(sharedPref.getBoolean("spectrogramTimeAxis", true));
        graphView.setShowFreqAlongX(sharedPref.getBoolean("spectrogramShowFreqAlongX", true));
        graphView.setSmoothRender(sharedPref.getBoolean("spectrogramSmoothRender", false));
        // set spectrogram show range
        double d = graphView.getLowerBound();
        d = Double.parseDouble(sharedPref.getString("spectrogramRange", Double.toString(d)));
        graphView.setLowerBound(d);
        graphView_Reduced.setLowerBound(d);
        timeDurationPref = Double.parseDouble(sharedPref.getString("spectrogramDuration",
                Double.toString(4.0)));

        bWarnOverrun = sharedPref.getBoolean("warnOverrun", false);

        if (bSaveWav) {
            ((TextView) findViewById(R.id.textview_rec)).setHeight((int) (19 * DPRatio));
        } else {
            ((TextView) findViewById(R.id.textview_rec)).setHeight((int) (0 * DPRatio));
        }

        // travel the views with android:tag="select" to get default setting values
        visit((ViewGroup) graphView.getRootView(), new Visit() {
            @Override
            public void exec(View view) {
                processClick(view);
            }
        }, "select");
        graphView.setReady(this);
        graphView_Reduced.setReady(this);

        samplingThread = new Looper();
        samplingThread.start();
        samplingThread.setPause(true);

        if (graphView.getShowMode() == 1) {
            // data is synchronized here
            graphView_Reduced.saveRowSpectrumAsColor(spectrumDBsaved);
        }
        AnalyzeActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (graphView.getShowMode() == 0) {
                    graphView_Reduced.saveSpectrum(spectrumDBsaved);
                }
                // data will get out of synchronize here
                AnalyzeActivity.this.invalidateGraphView();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        samplingThread.finish();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
//    Debug.stopMethodTracing();
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putDouble("dtRMS", dtRMS);
        savedInstanceState.putDouble("dtRMSFromFT", dtRMSFromFT);
        savedInstanceState.putDouble("maxAmpDB", maxAmpDB);
        savedInstanceState.putDouble("maxAmpFreq", maxAmpFreq);

        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        // will be calls after the onStart()
        super.onRestoreInstanceState(savedInstanceState);

        dtRMS = savedInstanceState.getDouble("dtRMS");
        dtRMSFromFT = savedInstanceState.getDouble("dtRMSFromFT");
        maxAmpDB = savedInstanceState.getDouble("maxAmpDB");
        maxAmpFreq = savedInstanceState.getDouble("maxAmpFreq");
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.info, menu);
        return true;
    }

    // for pass audioSourceIDs and audioSourceNames to MyPreferences
    public final static String MYPREFERENCES_MSG_SOURCE_ID = "AnalyzeActivity.SOURCE_ID";
    public final static String MYPREFERENCES_MSG_SOURCE_NAME = "AnalyzeActivity.SOURCE_NAME";

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, item.toString());
        switch (item.getItemId()) {
            case R.id.info:
                showInstructions();
                return true;
            case R.id.settings:
                Intent settings = new Intent(getBaseContext(), MyPreferences.class);
                settings.putExtra(MYPREFERENCES_MSG_SOURCE_ID, audioSourceIDs);
                settings.putExtra(MYPREFERENCES_MSG_SOURCE_NAME, audioSourceNames);
                startActivity(settings);
                return true;
            case R.id.info_recoder:
                Intent int_info_rec = new Intent(this, InfoRecActivity.class);
                startActivity(int_info_rec);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showInstructions() {
        TextView tv = new TextView(this);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setText(Html.fromHtml(getString(R.string.instructions_text)));
        new AlertDialog.Builder(this)
                .setTitle(R.string.instructions_title)
                .setView(tv)
                .setNegativeButton(R.string.dismiss, null)
                .create().show();
    }

    @SuppressWarnings("deprecation")
    public void showPopupMenu(View view) {
        // popup menu position
        // In API 19, we can use showAsDropDown(View anchor, int xoff, int yoff, int gravity)
        // The problem in showAsDropDown (View anchor, int xoff, int yoff) is
        // it may show the window in wrong direction (so that we can't see it)
        int[] wl = new int[2];
        view.getLocationInWindow(wl);
        int x_left = wl[0];
        int y_bottom = getWindowManager().getDefaultDisplay().getHeight() - wl[1];
        int gravity = android.view.Gravity.LEFT | android.view.Gravity.BOTTOM;

        switch (view.getId()) {
            case R.id.button_Ref_Voltage:
                popupMenuRef_Voltage.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuRef_Voltage.showAsDropDown(view, 0, 0);
                break;
            case R.id.button_Ref_Current:
                popupMenuRef_Current.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuRef_Current.showAsDropDown(view, 0, 0);
                break;
            case R.id.button_Threshold:
                popupMenuThreshold.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuThreshold.showAsDropDown(view, 0, 0);
                break;
            case R.id.button_Preset:
                popupMenuPreset.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuThreshold.showAsDropDown(view, 0, 0);
                break;
        }
    }

    // Maybe put this PopupWindow into a class
    public PopupWindow popupMenuCreate(String[] popUpContents, int resId) {

        // initialize a pop up window type
        PopupWindow popupWindow = new PopupWindow(this);

        // the drop down list is a list view
        ListView listView = new ListView(this);

        // set our adapter and pass our pop up window contents
        ArrayAdapter<String> aa = popupMenuAdapter(popUpContents);
        listView.setAdapter(aa);

        // set the item click listener
        listView.setOnItemClickListener(this);

        listView.setTag(resId);  // button res ID, so we can trace back which button is pressed

        // get max text width
        Paint mTestPaint = new Paint();
        mTestPaint.setTextSize(listItemTextSize);
        float w = 0;
        float wi;      // max text width in pixel
        for (String popUpContent : popUpContents) {
            String sts[] = popUpContent.split("::");
            String st = sts[0];
            if (sts.length == 2 && sts[1].equals("0")) {
                mTestPaint.setTextSize(listItemTitleTextSize);
                wi = mTestPaint.measureText(st);
                mTestPaint.setTextSize(listItemTextSize);
            } else {
                wi = mTestPaint.measureText(st);
            }
            if (w < wi) {
                w = wi;
            }
        }

        // left and right padding, at least +7, or the whole app will stop respond, don't know why
        w = w + 20 * DPRatio;
        if (w < 60) {
            w = 60;
        }

        // some other visual settings
        popupWindow.setFocusable(true);
        popupWindow.setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
        // Set window width according to max text width
        popupWindow.setWidth((int) w);
        // also set button width
        ((Button) findViewById(resId)).setWidth((int) (w + 4 * DPRatio));
        // Set the text on button in updatePreferenceSaved()

        // set the list view as pop up window content
        popupWindow.setContentView(listView);

        return popupWindow;
    }

    /*
     * adapter where the list values will be set
     */
    private ArrayAdapter<String> popupMenuAdapter(String itemTagArray[]) {
        return new ArrayAdapter<String>(AnalyzeActivity.this, android.R.layout.simple_list_item_1, itemTagArray) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // setting the ID and text for every items in the list
                String item = getItem(position);
                String[] itemArr = item.split("::");
                String text = itemArr[0];
                String id = itemArr[1];

                // visual settings for the list item
                TextView listItem = new TextView(AnalyzeActivity.this);

                if (id.equals("0")) {
                    listItem.setText(text);
                    listItem.setTag(id);
                    listItem.setTextSize(listItemTitleTextSize / DPRatio);
                    listItem.setPadding(5, 5, 5, 5);
                    listItem.setTextColor(Color.GREEN);
                    listItem.setGravity(android.view.Gravity.CENTER);
                } else {
                    listItem.setText(text);
                    listItem.setTag(id);
                    listItem.setTextSize(listItemTextSize / DPRatio);
                    listItem.setPadding(5, 5, 5, 5);
                    listItem.setTextColor(Color.WHITE);
                    listItem.setGravity(android.view.Gravity.CENTER);
                }

                return listItem;
            }
        };
    }

    // popup menu click listener
    // read chosen preference, save the preference, set the state.
    @Override
    public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
        // get the tag, which is the value we are going to use
        String selectedItemTag = v.getTag().toString();
        // if tag() is "0" then do not update anything (it is a title)
        if (selectedItemTag.equals("0")) {
            return;
        }

        // get the text and set it as the button text
        String selectedItemText = ((TextView) v).getText().toString();

        int buttonId = Integer.parseInt((parent.getTag().toString()));
        Button buttonView = (Button) findViewById(buttonId);
        buttonView.setText(selectedItemText);

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPref.edit();

        String index = sharedPref.getString("button_Preset", "1");

        // dismiss the pop up
        switch (buttonId) {
            case R.id.button_Ref_Voltage:
                popupMenuRef_Voltage.dismiss();
                Ref_Voltage = Integer.parseInt(selectedItemTag);
                editor.putInt("button_Ref_Voltage-" + index, Ref_Voltage);
                break;
            case R.id.button_Ref_Current:
                popupMenuRef_Current.dismiss();
                Ref_Current = Float.parseFloat(selectedItemTag);
                editor.putFloat("button_Ref_Current-" + index, Ref_Current);
                break;
            case R.id.button_Threshold:
                popupMenuThreshold.dismiss();
                Threshold = Float.parseFloat(selectedItemTag);
                editor.putFloat("button_Threshold-" + index, Threshold);
                break;
            case R.id.button_Preset:
                popupMenuPreset.dismiss();
                index = selectedItemTag;
                updateButtonTexts(index);
                editor.putString("button_Preset", index);
                break;
            default:
                Log.w(TAG, "onItemClick(): no this button");
        }

        editor.commit();
    }

    double[] spectrumDBsaved;   // XXX, transfers data from Looper to AnalyzeView

    // Load preferences for Views
    // When this function is called, the Looper must not running in the meanwhile.
    void updatePreferenceSaved() {
        // load preferences for buttons
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        ((Button) findViewById(R.id.button_Preset)).setText(String.format("%s", "Preset1"));
        updateButtonTexts("1");

        isSavedWav = sharedPref.getBoolean("isSavedWav", false);

        updateSpectrumDBSaved();
    }
    
    void updateButtonTexts(String index) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        Ref_Voltage = sharedPref.getInt("button_Ref_Voltage-" + index, 100);
        Ref_Current = sharedPref.getFloat("button_Ref_Current-" + index, 1.0f);
        Threshold = sharedPref.getFloat("button_Threshold-" + index, 0.05f);

        ((Button) findViewById(R.id.button_Ref_Voltage)).setText(String.format("%d", Ref_Voltage) + "V");
        ((Button) findViewById(R.id.button_Ref_Current)).setText(String.format("%.2f", Ref_Current) + "A");
        ((Button) findViewById(R.id.button_Threshold)).setText(String.format("%.0f", Threshold * 100) + "%");
    }

    void updateSpectrumDBSaved() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        if (isSavedWav) {
            try {
                FileInputStream fis = new FileInputStream(file);
                byte[] bs = new byte[fis.available()];
                fis.read(bs);

                int Nreq = sharedPref.getInt("Nreq", 891);
                spectrumDBsaved = new double[Nreq];
                String values[] = new String(bs).split(",");
                for (int i = 0; i < Nreq; i++) {
                    spectrumDBsaved[i] = Double.parseDouble(values[i]);
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static String[] audioSourceNames;
    static int[] audioSourceIDs;

    private void getAudioSourceNameFromIdPrepare(Resources res) {
        audioSourceNames = res.getStringArray(R.array.audio_source);
        String[] sasid = res.getStringArray(R.array.audio_source_id);
        audioSourceIDs = new int[audioSourceNames.length];
        for (int i = 0; i < audioSourceNames.length; i++) {
            audioSourceIDs[i] = Integer.parseInt(sasid[i]);
        }
    }

    // Get audio source name from its ID
    // Tell me if there is better way to do it.
    private static String getAudioSourceNameFromId(int id) {
        for (int i = 0; i < audioSourceNames.length; i++) {
            if (audioSourceIDs[i] == id) {
                return audioSourceNames[i];
            }
        }
        Log.e(TAG, "getAudioSourceName(): no this entry.");
        return "";
    }

    /**
     * Gesture Listener for graphView (and possibly other views)
     * How to attach these events to the graphView?
     *
     * @author xyy
     */
//    class AnalyzerGestureListener extends GestureDetector.SimpleOnGestureListener {
//        @Override
//        public boolean onDown(MotionEvent event) {  // enter here when down action happen
//            flyingMoveHandler.removeCallbacks(flyingMoveRunnable);
//            return true;
//        }
//
//        @Override
//        public void onLongPress(MotionEvent event) {
//            if (isInGraphView(event.getX(0), event.getY(0))) {
//                if (!isMeasure) {  // go from "scale" mode to "cursor" mode
//                    switchMeasureAndScaleMode();
//                }
//            }
//            measureEvent(event);  // force insert this event
//        }
//
//        @Override
//        public boolean onDoubleTap(MotionEvent event) {
//            if (!isMeasure) {
//                scaleEvent(event);            // ends scale mode
//                graphView.resetViewScale();
//            }
//            return true;
//        }
//
//        @Override
//        public boolean onFling(MotionEvent event1, MotionEvent event2,
//                               float velocityX, float velocityY) {
//            if (isMeasure) {
//                // seems never reach here...
//                return true;
//            }
////      Log.d(TAG, "  AnalyzerGestureListener::onFling: " + event1.toString()+event2.toString());
//            // Fly the canvas in graphView when in scale mode
//            shiftingVelocity = (float) Math.sqrt(velocityX * velocityX + velocityY * velocityY);
//            shiftingComponentX = velocityX / shiftingVelocity;
//            shiftingComponentY = velocityY / shiftingVelocity;
//            flyAcceleration = 1200f * DPRatio;
//            timeFlingStart = SystemClock.uptimeMillis();
//            flyingMoveHandler.postDelayed(flyingMoveRunnable, 0);
//            return true;
//        }
//
//        Handler flyingMoveHandler = new Handler();
//        long timeFlingStart;                     // Prevent from running forever
//        float flyDt = 1 / 20f;                     // delta t of refresh
//        float shiftingVelocity;                  // fling velocity
//        float shiftingComponentX;                // fling direction x
//        float shiftingComponentY;                // fling direction y
//        float flyAcceleration = 1200f;           // damping acceleration of fling, pixels/second^2
//
//        Runnable flyingMoveRunnable = new Runnable() {
//            @Override
//            public void run() {
//                float shiftingVelocityNew = shiftingVelocity - flyAcceleration * flyDt;
//                if (shiftingVelocityNew < 0) shiftingVelocityNew = 0;
//                // Number of pixels that should move in this time step
//                float shiftingPixel = (shiftingVelocityNew + shiftingVelocity) / 2 * flyDt;
//                shiftingVelocity = shiftingVelocityNew;
//                if (shiftingVelocity > 0f
//                        && SystemClock.uptimeMillis() - timeFlingStart < 10000) {
////          Log.i(TAG, "  fly pixels x=" + shiftingPixelX + " y=" + shiftingPixelY);
//                    graphView.setXShift(graphView.getXShift() - shiftingComponentX * shiftingPixel / graphView.getCanvasWidth() / graphView.getXZoom());
//                    graphView.setYShift(graphView.getYShift() - shiftingComponentY * shiftingPixel / graphView.getCanvasHeight() / graphView.getYZoom());
//                    // Am I need to use runOnUiThread() ?
//                    AnalyzeActivity.this.invalidateGraphView();
//                    flyingMoveHandler.postDelayed(flyingMoveRunnable, (int) (1000 * flyDt));
//                }
//            }
//        };
//    }

//    @Override
//    public boolean onTouchEvent(MotionEvent event) {
//        if (isInGraphView(event.getX(0), event.getY(0))) {
//            this.mDetector.onTouchEvent(event);
//            if (isMeasure) {
//                measureEvent(event);
//            } else {
//                scaleEvent(event);
//            }
//            scaleEvent(event);
//            invalidateGraphView();
//            // Go to scaling mode when user release finger in measure mode.
//            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
//                if (isMeasure) {
//                    switchMeasureAndScaleMode();
//                }
//            }
            // When finger is outside the plot, hide the cursor and go to scaling mode.
//            if (isMeasure) {
//                graphView.hideCursor();
//                switchMeasureAndScaleMode();
//            }
//        }
//        return super.onTouchEvent(event);
//    }


    /**
     * Manage scroll and zoom
     */
//    final private static float INIT = Float.MIN_VALUE;
//    private boolean isPinching = false;
//    private float xShift0 = INIT, yShift0 = INIT;
//    float x0, y0;
//    int[] windowLocation = new int[2];
//
//    private void scaleEvent(MotionEvent event) {
//        if (event.getAction() != MotionEvent.ACTION_MOVE) {
//            xShift0 = INIT;
//            yShift0 = INIT;
//            isPinching = false;
////      Log.i(TAG, "scaleEvent(): Skip event " + event.getAction());
//            return;
//        }
////    Log.i(TAG, "scaleEvent(): switch " + event.getAction());
//        switch (event.getPointerCount()) {
//            case 2:
//                if (isPinching) {
//                    graphView.setShiftScale(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
//                } else {
//                    graphView.setShiftScaleBegin(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
//                }
//                isPinching = true;
//                break;
//            case 1:
//                float x = event.getX(0);
//                float y = event.getY(0);
//                graphView.getLocationInWindow(windowLocation);
////        Log.i(TAG, "scaleEvent(): xy=" + x + " " + y + "  wc = " + wc[0] + " " + wc[1]);
//                if (isPinching || xShift0 == INIT) {
//                    xShift0 = graphView.getXShift();
//                    x0 = x;
//                    yShift0 = graphView.getYShift();
//                    y0 = y;
//                } else {
//                    // when close to the axis, scroll that axis only
//                    if (x0 < windowLocation[0] + 50) {
//                        graphView.setYShift(yShift0 + (y0 - y) / graphView.getCanvasHeight() / graphView.getYZoom());
//                    } else if (y0 < windowLocation[1] + 50) {
//                        graphView.setXShift(xShift0 + (x0 - x) / graphView.getCanvasWidth() / graphView.getXZoom());
//                    } else {
//                        graphView.setXShift(xShift0 + (x0 - x) / graphView.getCanvasWidth() / graphView.getXZoom());
//                        graphView.setYShift(yShift0 + (y0 - y) / graphView.getCanvasHeight() / graphView.getYZoom());
//                    }
//                }
//                isPinching = false;
//                break;
//            default:
//                Log.v(TAG, "Invalid touch count");
//                break;
//        }
//    }

    @Override
    public boolean onLongClick(View view) {
        vibrate(300);
        Log.i(TAG, "long click: " + view.toString());
        return true;
    }

    // Responds to layout with android:tag="select"
    // Called from SelectorText.super.performClick()
    @Override
    public void onClick(View v) {
        if (processClick(v)) {
            reRecur();
        }
        invalidateGraphView();
    }

    private void reRecur() {
        samplingThread.finish();
        try {
            samplingThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        samplingThread = new Looper();
        samplingThread.start();
    }

    /**
     * Process a click on one of our selectors.
     *
     * @param v The view that was clicked
     * @return true if we need to update the graph
     */
    public boolean processClick(View v) {
        String value = ((TextView) v).getText().toString();
        switch (v.getId()) {
            case R.id.button_recording:
                bSaveWav = value.equals("Rec");
                if (bSaveWav) {
                    if (samplingThread != null) {
                    samplingThread.setPause(true);
                    }
                    wavSec = 0;
                    ((TextView) findViewById(R.id.textview_rec)).setHeight((int) (19 * DPRatio));
                    isSavedWav = false;
                } else {
                    ((TextView) findViewById(R.id.textview_rec)).setHeight((int) (0 * DPRatio));
                }
                return true;
            case R.id.run:
                boolean pause = value.equals("stop");
                if (samplingThread != null && samplingThread.getPause() != pause) {
                    samplingThread.setPause(pause);
                    if (pause) {
                        if (graphView.getShowMode() == 1) {
                            // data is synchronized here
                            graphView.saveRowSpectrumAsColor(minimumSpectrum);
                        }
                        AnalyzeActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (graphView.getShowMode() == 0) {
                                    graphView.saveSpectrum(minimumSpectrum);
                                }
                                // data will get out of synchronize here
                                AnalyzeActivity.this.invalidateGraphView();
                            }
                        });
                    }
                }
                return false;
            default:
                return true;
        }
    }

    private void refreshRMSLabel() {
        textRMS.setLength(0);
        textRMS.append("RMS:dB");
        SBNumFormat.fillInNumFixedWidth(textRMS, 20 * Math.log10(dtRMSFromFT), 3, 1);
        textRMS.getChars(0, Math.min(textRMS.length(), textRMSChar.length), textRMSChar, 0);

        TextView tv = (TextView) findViewById(R.id.textview_RMS);
        tv.setText(textRMSChar, 0, textRMSChar.length);
        tv.invalidate();
    }

    private void refreshPeakLabel() {
        textPeak.setLength(0);
        textPeak.append("Peak:");
        SBNumFormat.fillInNumFixedWidthPositive(textPeak, maxAmpFreq, 5, 1);
        textPeak.append("Hz(");
        textPeak.append(") ");
//    SBNumFormat.fillInNumFixedWidth(textPeak, maxAmpDB, 3, 1);
        SBNumFormat.fillInNumFixedWidth(textPeak, secondMaxAmpFreq, 3, 1);
        textPeak.append("dB");
        textPeak.getChars(0, Math.min(textPeak.length(), textPeakChar.length), textPeakChar, 0);

        TextView tv = (TextView) findViewById(R.id.textview_peak);
        tv.setText(textPeakChar, 0, textPeakChar.length);
        tv.invalidate();
    }

    private void refreshRecTimeLable() {
        // consist with @string/textview_rec_text
        textRec.setLength(0);
        textRec.append("Rec: ");
        SBNumFormat.fillTime(textRec, wavSec, 1);
        textRec.append(", Remain: ");
        SBNumFormat.fillTime(textRec, wavSecRemain, 0);
        textRec.getChars(0, Math.min(textRec.length(), textRecChar.length), textRecChar, 0);
        ((TextView) findViewById(R.id.textview_rec))
                .setText(textRecChar, 0, Math.min(textRec.length(), textRecChar.length));
    }

    long timeToUpdate = SystemClock.uptimeMillis();
    volatile boolean isInvalidating = false;

    // Invalidate graphView in a limited frame rate
    public void invalidateGraphView() {
        invalidateGraphView(-1);
    }

    static final int VIEW_MASK_graphView = 1;
    static final int VIEW_MASK_textview_RMS = 1 << 1;
    static final int VIEW_MASK_textview_peak = 1 << 2;
    static final int VIEW_MASK_RecTimeLable = 1 << 4;

    public void invalidateGraphView(int viewMask) {
        if (isInvalidating) {
            return;
        }
        isInvalidating = true;
        long frameTime;                      // time delay for next frame
        if (graphView.getShowMode() != 0) {
            frameTime = 1000 / 8;  // use a much lower frame rate for spectrogram
        } else {
            frameTime = 1000 / 60;
        }
        long t = SystemClock.uptimeMillis();
        //  && !graphView.isBusy()
        if (t >= timeToUpdate) {    // limit frame rate
            timeToUpdate += frameTime;
            if (timeToUpdate < t) {            // catch up current time
                timeToUpdate = t + frameTime;
            }
            idPaddingInvalidate = false;
            // Take care of synchronization of graphView.spectrogramColors and spectrogramColorsPt,
            // and then just do invalidate() here.
            if ((viewMask & VIEW_MASK_graphView) != 0) {
                graphView.invalidate();
                graphView_Reduced.invalidate();
            }
            // RMS
            if ((viewMask & VIEW_MASK_textview_RMS) != 0)
                refreshRMSLabel();
            // peak frequency
            if ((viewMask & VIEW_MASK_textview_peak) != 0)
                refreshPeakLabel();
            if ((viewMask & VIEW_MASK_RecTimeLable) != 0)
                refreshRecTimeLable();
        } else {
            if (!idPaddingInvalidate) {
                idPaddingInvalidate = true;
                paddingViewMask = viewMask;
                paddingInvalidateHandler.postDelayed(paddingInvalidateRunnable, timeToUpdate - t + 1);
            } else {
                paddingViewMask |= viewMask;
            }
        }
        isInvalidating = false;
    }

    volatile boolean idPaddingInvalidate = false;
    volatile int paddingViewMask = -1;
    Handler paddingInvalidateHandler = new Handler();

    // Am I need to use runOnUiThread() ?
    Runnable paddingInvalidateRunnable = new Runnable() {
        @Override
        public void run() {
            if (idPaddingInvalidate) {
                // It is possible that t-timeToUpdate <= 0 here, don't know why
                AnalyzeActivity.this.invalidateGraphView(paddingViewMask);
            }
        }
    };

    /**
     * Return a array of verified audio sampling rates.
     *
     * @param requested: the sampling rates to be verified
     */
    private static String[] validateAudioRates(String[] requested) {
        ArrayList<String> validated = new ArrayList<>();
        for (String s : requested) {
            int rate;
            String[] sv = s.split("::");
            if (sv.length == 1) {
                rate = Integer.parseInt(sv[0]);
            } else {
                rate = Integer.parseInt(sv[1]);
            }
            if (rate != 0) {
                if (AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT) != AudioRecord.ERROR_BAD_VALUE) {
                    validated.add(s);
                }
            } else {
                validated.add(s);
            }
        }
        return validated.toArray(new String[validated.size()]);
    }

    /**
     * Read a snapshot of audio data at a regular interval, and compute the FFT
     *
     */
    double[] spectrumDBcopy;   // XXX, transfers data from Looper to AnalyzeView

    public class Looper extends Thread {
        AudioRecord record;
        volatile boolean isRunning = true;
        volatile boolean isPaused1 = false;
        double wavSecOld = 0;      // used to reduce frame rate
        public STFT stft;   // use with care

        DoubleSineGen sineGen1;
        DoubleSineGen sineGen2;
        double[] mdata;

        public Looper() {
//            isPaused1 = ((SelectorText) findViewById(R.id.run)).getText().toString().equals("stop");
            // Signal sources for testing
            double fq0 = Double.parseDouble(getString(R.string.test_signal_1_freq1));
            double amp0 = Math.pow(10, 1 / 20.0 * Double.parseDouble(getString(R.string.test_signal_1_db1)));
            double fq1 = Double.parseDouble(getString(R.string.test_signal_2_freq1));
            double fq2 = Double.parseDouble(getString(R.string.test_signal_2_freq2));
            double amp1 = Math.pow(10, 1 / 20.0 * Double.parseDouble(getString(R.string.test_signal_2_db1)));
            double amp2 = Math.pow(10, 1 / 20.0 * Double.parseDouble(getString(R.string.test_signal_2_db2)));
            if (audioSourceId == 1000) {
                sineGen1 = new DoubleSineGen(fq0, sampleRate, SAMPLE_VALUE_MAX * amp0);
            } else {
                sineGen1 = new DoubleSineGen(fq1, sampleRate, SAMPLE_VALUE_MAX * amp1);
            }
            sineGen2 = new DoubleSineGen(fq2, sampleRate, SAMPLE_VALUE_MAX * amp2);
        }

        private void SleepWithoutInterrupt(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        private double baseTimeMs = SystemClock.uptimeMillis();

        private void LimitFrameRate(double updateMs) {
            // Limit the frame rate by wait `delay' ms.
            baseTimeMs += updateMs;
            long delay = (int) (baseTimeMs - SystemClock.uptimeMillis());
//      Log.i(TAG, "delay = " + delay);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Log.i(TAG, "Sleep interrupted");  // seems never reached
                }
            } else {
                baseTimeMs -= delay;  // get current time
                // Log.i(TAG, "time: cmp t="+Long.toString(SystemClock.uptimeMillis())
                //            + " v.s. t'=" + Long.toString(baseTimeMs));
            }
        }

        // generate test data
        private int readTestData(short[] a, int offsetInShorts, int sizeInShorts, int id) {
            if (mdata == null || mdata.length != sizeInShorts) {
                mdata = new double[sizeInShorts];
            }
            Arrays.fill(mdata, 0.0);
            switch (id - 1000) {
                case 1:
                    sineGen2.getSamples(mdata);
                case 0:
                    sineGen1.addSamples(mdata);
                    for (int i = 0; i < sizeInShorts; i++) {
                        a[offsetInShorts + i] = (short) Math.round(mdata[i]);
                    }
                    break;
                case 2:
                    for (int i = 0; i < sizeInShorts; i++) {
                        a[i] = (short) (SAMPLE_VALUE_MAX * (2.0 * Math.random() - 1));
                    }
                    break;
                default:
                    Log.w(TAG, "readTestData(): No this source id = " + audioSourceId);
            }
            LimitFrameRate(1000.0 * sizeInShorts / sampleRate);
            return sizeInShorts;
        }

        // The reduced frequency
        final int frequency_reduced = 300;
        // Calculate the frequency resolution
        double df = (sampleRate * 1.0) / fftLen;
        // Get number of points required for first frequency Hz.
        int Nreq = (int) (frequency_reduced / df);
        // Flag to show Reduced graph
        boolean showReduced = true;

        @Override
        public void run() {
            if (showReduced) {
                if (!isSavedWav)
                    setupView_Reduced();
                else
                    setupView_ReducedRun();
            }
            else
                setupView();
            // Wait until previous instance of AudioRecord fully released.
            SleepWithoutInterrupt(500);

            int minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Looper::run(): Invalid AudioRecord parameter.\n");
                return;
            }

            /**
             * Develop -> Reference -> AudioRecord
             *    Data should be read from the audio hardware in chunks of sizes
             *    inferior to the total recording buffer size.
             */
            // Determine size of buffers for AudioRecord and AudioRecord::read()
            int readChunkSize = fftLen / 2;  // /2 due to overlapped analyze window
            readChunkSize = Math.min(readChunkSize, 2048);  // read in a smaller chunk, hopefully smaller delay
            int bufferSampleSize = Math.max(minBytes / BYTE_OF_SAMPLE, fftLen / 2) * 2;
            // tolerate up to about 1 sec.
            bufferSampleSize = (int) Math.ceil(1.0 * sampleRate / bufferSampleSize) * bufferSampleSize;

            // Use the mic with AGC turned off. e.g. VOICE_RECOGNITION
            // The buffer size here seems not relate to the delay.
            // So choose a larger size (~1sec) so that overrun is unlikely.
            if (audioSourceId < 1000) {
                record = new AudioRecord(audioSourceId, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, BYTE_OF_SAMPLE * bufferSampleSize);
            } else {
                record = new AudioRecord(RECORDER_AGC_OFF, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, BYTE_OF_SAMPLE * bufferSampleSize);
            }
            Log.i(TAG, "Looper::Run(): Starting recorder... \n" +
                    "  source          : " + (audioSourceId < 1000 ? getAudioSourceNameFromId(audioSourceId) : audioSourceId) + "\n" +
                    String.format("  sample rate     : %d Hz (request %d Hz)\n", record.getSampleRate(), sampleRate) +
                    String.format("  min buffer size : %d samples, %d Bytes\n", minBytes / BYTE_OF_SAMPLE, minBytes) +
                    String.format("  buffer size     : %d samples, %d Bytes\n", bufferSampleSize, BYTE_OF_SAMPLE * bufferSampleSize) +
                    String.format("  read chunk size : %d samples, %d Bytes\n", readChunkSize, BYTE_OF_SAMPLE * readChunkSize) +
                    String.format("  FFT length      : %d\n", fftLen) +
                    String.format("  nFFTAverage     : %d\n", nFFTAverage));

            if (sampleRate != record.getSampleRate()) {
                Log.e(TAG, "Looper::run(): Fail to set correct sample rate");
                return;
            }

            if (record.getState() == AudioRecord.STATE_UNINITIALIZED) {
                Log.e(TAG, "Looper::run(): Fail to initialize AudioRecord()");
                // If failed somehow, leave user a chance to change preference.
                return;
            }

            short[] audioSamples = new short[readChunkSize];
            int numOfReadShort;
            stft = new STFT(fftLen, sampleRate, wndFuncName);
//            stft.setAWeighting(isAWeighting);
            stft.setAWeighting(false);
            if (spectrumDBcopy == null || spectrumDBcopy.length != fftLen / 2 + 1) {
                spectrumDBcopy = new double[fftLen / 2 + 1];
            }

            RecorderMonitor recorderMonitor = new RecorderMonitor(sampleRate, bufferSampleSize, "Looper::run()");
            recorderMonitor.start();

//      FramesPerSecondCounter fpsCounter = new FramesPerSecondCounter("Looper::run()");

            WavWriter wavWriter = new WavWriter(sampleRate);
            boolean bSaveWavLoop = bSaveWav;  // change of bSaveWav during loop will only affect next enter.
            if (bSaveWavLoop) {
                wavWriter.start();
                wavSecRemain = wavWriter.secondsLeft();
                wavSec = 0;
                wavSecOld = 0;
                Log.i(TAG, "PCM write to file " + wavWriter.getPath());
            }

            // Start recording
            record.startRecording();

            // Main loop
            // When running in this loop (including when paused), you can not change properties
            // related to recorder: e.g. audioSourceId, sampleRate, bufferSampleSize
            while (isRunning) {
                // Read data
                if (audioSourceId >= 1000) {
                    numOfReadShort = readTestData(audioSamples, 0, readChunkSize, audioSourceId);
                    Log.d("Record", "Source from Test Data");
                } else {
                    numOfReadShort = record.read(audioSamples, 0, readChunkSize);   // pulling
                }
                if (recorderMonitor.updateState(numOfReadShort)) {  // performed a check
                    if (recorderMonitor.getLastCheckOverrun())
                        notifyOverrun();
                    if (bSaveWavLoop)
                        wavSecRemain = wavWriter.secondsLeft();
                }
                if (bSaveWavLoop) {
                    wavWriter.pushAudioShort(audioSamples, numOfReadShort);  // Maybe move this to another thread?
                    wavSec = wavWriter.secondsWritten();
                    updateRec();
                }
                if (isPaused1) {
//          fpsCounter.inc();
                    // keep reading data, for overrun checker and for write wav data
                    continue;
                }
//                Log.d("Record ", String.valueOf(audioSamples));

                stft.feedData(audioSamples, numOfReadShort);

                // If there is new spectrum data, do plot
                if (stft.nElemSpectrumAmp() >= nFFTAverage) {
                    // Update spectrum or spectrogram
                    final double[] spectrumDB = stft.getSpectrumAmpDB();
                    System.arraycopy(spectrumDB, 0, spectrumDBcopy, 0, spectrumDB.length);
                    if (showReduced) {
                        if (!isSavedWav)
                            update_Reduced(spectrumDBcopy);
                        else
                            update_ReducedRun(spectrumDBcopy);
                    }
                    else
                        update(spectrumDBcopy);
//                    System.out.println("spectrumDB: " + Arrays.toString(spectrumDB));
//          fpsCounter.inc();

                    stft.calculatePeak();
                    maxAmpFreq = stft.maxAmpFreq;
                    maxAmpDB = stft.maxAmpDB;
                    secondMaxAmpFreq = stft.secondMaxAmpFreq;
                    secondMaxAmpDB = stft.secondMaxAmpDB;

                    // get RMS
                    dtRMS = stft.getRMS();
                    dtRMSFromFT = stft.getRMSFromFT();

                    if (bSaveWavLoop) {
                        if (isSavedWav)
                            continue;
                        try {
                            fos = new FileOutputStream(file, false);
                            String output = "";
                            for (int i = 0; i < Nreq; i++) {
                                output += spectrumDBcopy[i] + ",";
                            }
                            fos.write(output.getBytes());
                            fos.flush();
                            fos.close();
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(AnalyzeActivity.this);
                        SharedPreferences.Editor editor = sharedPref.edit();
                        editor.putBoolean("isSavedWav", true);
                        editor.putInt("Nreq", Nreq);
                        editor.commit();
                        isSavedWav = true;

                        updateSpectrumDBSaved();

                        AnalyzeActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ((SelectorText) findViewById(R.id.button_recording)).performClick();
                            }
                        });
                    }
                }
            }
            Log.i(TAG, "Looper::Run(): Actual sample rate: " + recorderMonitor.getSampleRate());
            Log.i(TAG, "Looper::Run(): Stopping and releasing recorder.");
            record.stop();
            record.release();
            record = null;
            if (bSaveWavLoop) {
                Log.i(TAG, "Looper::Run(): Ending saved wav.");
                wavWriter.stop();
                notifyWAVSaved(wavWriter.relativeDir);
            }
        }

        long lastTimeNotifyOverrun = 0;

        private void notifyOverrun() {
            if (!bWarnOverrun) {
                return;
            }
            long t = SystemClock.uptimeMillis();
            if (t - lastTimeNotifyOverrun > 6000) {
                lastTimeNotifyOverrun = t;
                AnalyzeActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Context context = getApplicationContext();
                        String text = "Recorder buffer overrun!\nYour cell phone is too slow.\nTry lower sampling rate or higher average number.";
                        Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
                        toast.show();
                    }
                });
            }
        }

        private void notifyWAVSaved(final String path) {
            AnalyzeActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Context context = getApplicationContext();
                    String text = "WAV saved to " + path;
                    Toast toast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
                    toast.show();
                }
            });
        }

        private void update(final double[] data) {
            final double[] reducedData = new double[data.length];
            System.arraycopy(data, 0, reducedData, 0, reducedData.length);
            for (int i = Nreq; i < data.length; i++)
                reducedData[i] = -128;
            if (graphView.getShowMode() == 1) {
                // data is synchronized here
                graphView.saveRowSpectrumAsColor(data);
                graphView_Reduced.saveRowSpectrumAsColor(reducedData);
            }
            AnalyzeActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (graphView.getShowMode() == 0) {
                        graphView.saveSpectrum(data);
                        graphView_Reduced.saveSpectrum(reducedData);
                    }
                    // data will get out of synchronize here
                    AnalyzeActivity.this.invalidateGraphView();
                }
            });
        }

        private void update_Reduced(final double[] data) {
            final double[] reducedData = new double[Nreq];
            if (isSavedWav)
                System.arraycopy(spectrumDBsaved, 0, reducedData, 0, reducedData.length);
            else
                System.arraycopy(data, 0, reducedData, 0, reducedData.length);
            if (graphView.getShowMode() == 1) {
                // data is synchronized here
                graphView.saveRowSpectrumAsColor(data);
                graphView_Reduced.saveRowSpectrumAsColor(reducedData);
            }
            AnalyzeActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (graphView.getShowMode() == 0) {
                        graphView.saveSpectrum(data);
                        graphView_Reduced.saveSpectrum(reducedData);
                    }
                    // data will get out of synchronize here
                    AnalyzeActivity.this.invalidateGraphView();
                }
            });
        }

        private void update_ReducedRun(final double[] data) {
            final double[] reducedData = new double[Nreq];
            System.arraycopy(data, 0, reducedData, 0, reducedData.length);
            if (graphView.getShowMode() == 1) {
                // data is synchronized here
                graphView.saveRowSpectrumAsColor(reducedData);
                graphView_Reduced.saveRowSpectrumAsColor(spectrumDBsaved);
            }
            AnalyzeActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (graphView.getShowMode() == 0) {
                        graphView.saveSpectrum(reducedData);
                        graphView_Reduced.saveSpectrum(spectrumDBsaved);
                    }
                    // data will get out of synchronize here
                    AnalyzeActivity.this.invalidateGraphView();
                }
            });
        }

        private void updateRec() {
            if (wavSec - wavSecOld < 0.1) {
                return;
            }
            wavSecOld = wavSec;
            AnalyzeActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // data will get out of synchronize here
                    AnalyzeActivity.this.invalidateGraphView(VIEW_MASK_RecTimeLable);
                }
            });
        }

        private void setupView() {
            // Maybe move these out of this class
            RectF bounds = graphView.getBounds();
            bounds.right = sampleRate / 2;
            graphView.setBounds(bounds);
            graphView.setupSpectrogram(sampleRate, fftLen, timeDurationPref);
            graphView.setTimeMultiplier(nFFTAverage);
            graphView_Reduced.setBounds(bounds);
            graphView_Reduced.setupSpectrogram(sampleRate, fftLen, timeDurationPref);
            graphView_Reduced.setTimeMultiplier(nFFTAverage);
        }

        private void setupView_Reduced() {
            // Maybe move these out of this class
            RectF bounds = graphView.getBounds();
            bounds.right = sampleRate / 2;
            graphView.setBounds(bounds);
            graphView.setupSpectrogram(sampleRate, fftLen, timeDurationPref);
            graphView.setTimeMultiplier(nFFTAverage);
            bounds = graphView_Reduced.getBounds();
            bounds.right = frequency_reduced;
            graphView_Reduced.setBounds(bounds);
            graphView_Reduced.setupSpectrogram(sampleRate, fftLen, timeDurationPref);
            graphView_Reduced.setTimeMultiplier(nFFTAverage);
        }

        private void setupView_ReducedRun() {
            // Maybe move these out of this class
            RectF bounds = graphView.getBounds();
            bounds.right = frequency_reduced;
            graphView.setBounds(bounds);
            graphView.setupSpectrogram(sampleRate, fftLen, timeDurationPref);
            graphView.setTimeMultiplier(nFFTAverage);
            graphView_Reduced.setBounds(bounds);
            graphView_Reduced.setupSpectrogram(sampleRate, fftLen, timeDurationPref);
            graphView_Reduced.setTimeMultiplier(nFFTAverage);
        }


        public void setPause(boolean pause) {
            this.isPaused1 = pause;
        }

        public boolean getPause() {
            return this.isPaused1;
        }

        public void finish() {
            isRunning = false;
            interrupt();
        }
    }

    private void vibrate(int ms) {
        //((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(ms);
    }

    /**
     * Visit all subviews of this view group and run command
     *
     * @param group  The parent view group
     * @param cmd    The command to run for each view
     * @param select The tag value that must match. Null implies all views
     */

    private void visit(ViewGroup group, Visit cmd, String select) {
        exec(group, cmd, select);
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c instanceof ViewGroup) {
                visit((ViewGroup) c, cmd, select);
            } else {
                exec(c, cmd, select);
            }
        }
    }

    private void exec(View v, Visit cmd, String select) {
        if (select == null || select.equals(v.getTag())) {
            cmd.exec(v);
        }
    }

    /**
     * Interface for view hierarchy visitor
     */
    interface Visit {
        void exec(View view);
    }

    /**
     * The graph view size has been determined - update the labels accordingly.
     */
    @Override
    public void ready() {
        // put code here for the moment that graph size just changed
        Log.v(TAG, "ready()");
        invalidateGraphView();
    }

}